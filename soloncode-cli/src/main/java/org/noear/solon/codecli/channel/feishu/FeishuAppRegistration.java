/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.codecli.channel.feishu;

import org.noear.snack4.ONode;
import org.noear.solon.net.http.HttpResponse;
import org.noear.solon.net.http.HttpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/**
 * 飞书一键创建应用 —— Device Authorization Grant (RFC 8628) 实现。
 *
 * <p>直接 HTTP 调用飞书账号服务，无需引入任何 SDK。
 * 流程：
 * <ol>
 *   <li>{@link #begin()} → 获取 device_code + 二维码 URL</li>
 *   <li>前端展示 QR 码，用户用飞书扫码确认</li>
 *   <li>{@link #poll(String, int, int)} → 轮询直到用户确认，返回 App ID + App Secret</li>
 * </ol>
 *
 * @author noear 2026/6/23 created
 */
public class FeishuAppRegistration {
    private static final Logger LOG = LoggerFactory.getLogger(FeishuAppRegistration.class);

    private static final String ACCOUNTS_URL = "https://accounts.feishu.cn";
    private static final String LARK_ACCOUNTS_URL = "https://accounts.larksuite.com";
    private static final String REGISTRATION_PATH = "/oauth/v1/app/registration";

    private final String accountsUrl;

    public FeishuAppRegistration() {
        this(false);
    }

    /**
     * @param useLark true 使用 Lark 国际版域名，false 使用飞书国内版
     */
    public FeishuAppRegistration(boolean useLark) {
        this.accountsUrl = useLark ? LARK_ACCOUNTS_URL : ACCOUNTS_URL;
    }

    /**
     * 开始设备授权流程，返回二维码信息。
     *
     * @return BeginResult 包含二维码 URL、device_code 等
     * @throws Exception 如果请求失败
     */
    public BeginResult begin() throws Exception {
        String body = "action=begin"
                + "&archetype=" + encode("PersonalAgent")
                + "&auth_method=" + encode("client_secret")
                + "&request_user_info=" + encode("open_id");

        String resp = httpFormPost(accountsUrl + REGISTRATION_PATH, body);
        if (resp == null) {
            throw new RuntimeException("飞书注册请求失败（无响应）");
        }

        ONode root = ONode.ofJson(resp);
        if (root == null || root.isNull()) {
            throw new RuntimeException("飞书注册响应解析失败");
        }

        // 检查是否有错误
        String error = root.get("error").getString();
        if (error != null && !error.isEmpty()) {
            String desc = root.get("error_description").getString();
            throw new RuntimeException("飞书注册错误: " + error + " - " + (desc != null ? desc : ""));
        }

        String deviceCode = root.get("device_code").getString();
        String userCode = root.get("user_code").getString();
        String verificationUriComplete = root.get("verification_uri_complete").getString();
        int expiresIn = root.get("expires_in").getInt();
        int interval = root.get("interval").getInt();

        if (deviceCode == null || deviceCode.isEmpty()) {
            throw new RuntimeException("飞书注册响应缺少 device_code");
        }

        // 直接使用飞书 API 返回的 verification_uri_complete（含 user_code，无需手动拼接）
        String qrUrl = verificationUriComplete;
        if (qrUrl == null || qrUrl.isEmpty()) {
            // 容错：极低概率，API 未返回完整 URL
            qrUrl = accountsUrl + "/oauth/v1/device/verify?user_code=" + encode(userCode);
        }

        // 默认值保护
        if (expiresIn <= 0) expiresIn = 600;
        if (interval <= 0) interval = 5;

        BeginResult result = new BeginResult();
        result.deviceCode = deviceCode;
        result.userCode = userCode;
        result.qrUrl = qrUrl;
        result.expiresIn = expiresIn;
        result.interval = interval;

        LOG.info("[FeishuReg] Begin: deviceCode={}, expiresIn={}s, interval={}s",
                deviceCode.substring(0, Math.min(8, deviceCode.length())) + "...",
                expiresIn, interval);

        return result;
    }

    /**
     * 轮询设备授权结果。
     *
     * @param deviceCode 设备码
     * @param interval   轮询间隔（秒）
     * @param expiresIn  过期时间（秒）
     * @return PollResult，包含状态和（如果成功）凭据
     */
    public PollResult poll(String deviceCode, int interval, int expiresIn) throws Exception {
        String body = "action=poll"
                + "&device_code=" + encode(deviceCode);

        String resp = httpFormPost(accountsUrl + REGISTRATION_PATH, body);
        if (resp == null) {
            return PollResult.waiting("请求失败");
        }

        ONode root = ONode.ofJson(resp);
        if (root == null || root.isNull()) {
            return PollResult.waiting("响应解析失败");
        }

        // 检查是否有错误
        String error = root.get("error").getString();
        if (error != null && !error.isEmpty()) {
            switch (error) {
                case "authorization_pending":
                    return PollResult.waiting("等待用户扫码");
                case "slow_down":
                    return PollResult.slowDown("请减慢轮询频率");
                case "access_denied":
                    return PollResult.failed("用户拒绝了授权");
                case "expired_token":
                    return PollResult.failed("二维码已过期");
                default:
                    String desc = root.get("error_description").getString();
                    return PollResult.failed(error + ": " + (desc != null ? desc : ""));
            }
        }

        // 成功: client_id 存在
        String clientId = root.get("client_id").getString();
        if (clientId != null && !clientId.isEmpty()) {
            String clientSecret = root.get("client_secret").getString();

            // 提取用户信息
            String openId = null;
            ONode userInfo = root.get("user_info");
            if (userInfo != null && !userInfo.isNull()) {
                openId = userInfo.get("open_id").getString();
            }

            LOG.info("[FeishuReg] App registered: clientId={}, openId={}",
                    clientId.substring(0, Math.min(8, clientId.length())) + "...", openId);

            return PollResult.success(clientId, clientSecret, openId);
        }

        return PollResult.waiting("等待中");
    }

    // ==================== HTTP 工具方法 ====================

    /**
     * 发送 form-urlencoded POST 请求
     */
    private String httpFormPost(String url, String formBody) throws Exception {
        try (HttpResponse resp = HttpUtils.http(url)
                .timeout(10, 10, 15)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .body(formBody.getBytes("UTF-8"), "application/x-www-form-urlencoded")
                .exec("POST")) {
            int statusCode = resp.code();
            if (statusCode != 200) {
                LOG.warn("[FeishuReg] HTTP POST {} returned {}", url, statusCode);
                return null;
            }
            return resp.bodyAsString();
        }
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            // UTF-8 一定存在，不会发生
            throw new RuntimeException(e);
        }
    }

    // ==================== 内部数据结构 ====================

    /**
     * begin() 的返回结果
     */
    public static class BeginResult {
        public String deviceCode;
        public String userCode;
        public String qrUrl;
        public int expiresIn;
        public int interval;

        @Override
        public String toString() {
            return "BeginResult{deviceCode=" + (deviceCode != null ? deviceCode.substring(0, Math.min(8, deviceCode.length())) + "..." : "null")
                    + ", expiresIn=" + expiresIn + ", interval=" + interval + "}";
        }
    }

    /**
     * poll() 的返回结果
     */
    public static class PollResult {
        public final String status;   // "success" | "waiting" | "slow_down" | "failed"
        public final String message;
        public final String clientId;
        public final String clientSecret;
        public final String openId;

        private PollResult(String status, String message, String clientId, String clientSecret, String openId) {
            this.status = status;
            this.message = message;
            this.clientId = clientId;
            this.clientSecret = clientSecret;
            this.openId = openId;
        }

        public boolean isSuccess() {
            return "success".equals(status);
        }

        public boolean isWaiting() {
            return "waiting".equals(status);
        }

        public boolean isSlowDown() {
            return "slow_down".equals(status);
        }

        public boolean isFailed() {
            return "failed".equals(status);
        }

        static PollResult success(String clientId, String clientSecret, String openId) {
            return new PollResult("success", null, clientId, clientSecret, openId);
        }

        static PollResult waiting(String message) {
            return new PollResult("waiting", message, null, null, null);
        }

        static PollResult slowDown(String message) {
            return new PollResult("slow_down", message, null, null, null);
        }

        static PollResult failed(String message) {
            return new PollResult("failed", message, null, null, null);
        }
    }
}
