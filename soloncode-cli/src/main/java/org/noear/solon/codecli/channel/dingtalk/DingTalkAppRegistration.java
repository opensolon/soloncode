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
package org.noear.solon.codecli.channel.dingtalk;

import org.noear.snack4.ONode;
import org.noear.solon.net.http.HttpResponse;
import org.noear.solon.net.http.HttpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 钉钉一键创建应用 —— Device Authorization Grant (RFC 8628) 实现。
 *
 * <p>直接 HTTP 调用钉钉开放平台 API，无需引入任何 SDK。
 * 流程：
 * <ol>
 *   <li>{@link #begin()} → POST /app/registration/init 获取 nonce → POST /app/registration/begin 获取 device_code + 二维码 URL</li>
 *   <li>前端展示 QR 码，用户用钉钉扫码确认</li>
 *   <li>{@link #poll(String, int, int)} → POST /app/registration/poll 轮询直到用户确认，返回 AppKey + AppSecret</li>
 * </ol>
 *
 * <p>参考实现：hermes-agent dingtalk_auth.py (已验证对接真实 oapi.dingtalk.com 端点)</p>
 *
 * @author soloncode 2026/6/25 created
 */
public class DingTalkAppRegistration {
    private static final Logger LOG = LoggerFactory.getLogger(DingTalkAppRegistration.class);

    protected static final String BASE_URL = "https://oapi.dingtalk.com";

    /** 注册来源标识，钉钉开放平台用于区分不同接入方 */
    protected static final String REGISTRATION_SOURCE = "openClaw";

    /** init 路径：获取 nonce */
    protected static final String INIT_PATH = "/app/registration/init";
    /** begin 路径：发起设备授权，获取二维码 */
    protected static final String BEGIN_PATH = "/app/registration/begin";
    /** poll 路径：轮询扫码结果 */
    protected static final String POLL_PATH = "/app/registration/poll";

    public DingTalkAppRegistration() {
    }

    /**
     * 开始设备授权流程，返回二维码信息。
     *
     * <p>钉钉需要两步：先 init 获取 nonce，再用 nonce 执行 begin 获取二维码。
     * 这里封装为一步，对外使用统一的 begin() 接口。</p>
     *
     * @return BeginResult 包含二维码 URL、device_code 等
     * @throws Exception 如果请求失败
     */
    public BeginResult begin() throws Exception {
        // Step 1: init —— 获取 nonce（JSON body）
        String initBody = "{\"source\":\"" + REGISTRATION_SOURCE + "\"}";
        String initResp = httpJsonPost(BASE_URL + INIT_PATH, initBody);
        if (initResp == null) {
            throw new RuntimeException("钉钉注册 init 请求失败（无响应）");
        }

        ONode initRoot = ONode.ofJson(initResp);
        if (initRoot == null || initRoot.isNull()) {
            throw new RuntimeException("钉钉注册 init 响应解析失败");
        }

        int errcode = initRoot.get("errcode").getInt();
        if (errcode != 0) {
            String errmsg = initRoot.get("errmsg").getString();
            throw new RuntimeException("钉钉注册 init 错误: errcode=" + errcode + ", errmsg=" + (errmsg != null ? errmsg : ""));
        }

        String nonce = initRoot.get("nonce").getString();
        if (nonce == null || nonce.isEmpty()) {
            throw new RuntimeException("钉钉注册 init 响应缺少 nonce");
        }

        // Step 2: begin —— 用 nonce 获取 device_code + 二维码（JSON body，仅 nonce 字段）
        String beginBody = "{\"nonce\":\"" + nonce + "\"}";
        String beginResp = httpJsonPost(BASE_URL + BEGIN_PATH, beginBody);
        if (beginResp == null) {
            throw new RuntimeException("钉钉注册 begin 请求失败（无响应）");
        }

        ONode beginRoot = ONode.ofJson(beginResp);
        if (beginRoot == null || beginRoot.isNull()) {
            throw new RuntimeException("钉钉注册 begin 响应解析失败");
        }

        errcode = beginRoot.get("errcode").getInt();
        if (errcode != 0) {
            String errmsg = beginRoot.get("errmsg").getString();
            throw new RuntimeException("钉钉注册 begin 错误: errcode=" + errcode + ", errmsg=" + (errmsg != null ? errmsg : ""));
        }

        String deviceCode = beginRoot.get("device_code").getString();
        String userCode = beginRoot.get("user_code").getString();
        String verificationUriComplete = beginRoot.get("verification_uri_complete").getString();
        int expiresIn = beginRoot.get("expires_in").getInt();
        int interval = beginRoot.get("interval").getInt();

        if (deviceCode == null || deviceCode.isEmpty()) {
            throw new RuntimeException("钉钉注册 begin 响应缺少 device_code");
        }

        // 构造二维码 URL：优先使用 API 返回的完整 URL，否则手动拼接
        String qrUrl = verificationUriComplete;
        if (qrUrl == null || qrUrl.isEmpty()) {
            qrUrl = "https://open-dev.dingtalk.com/openapp/registration/openClaw?user_code="
                    + encode(userCode != null ? userCode : "");
        }

        // 默认值保护
        if (expiresIn <= 0) expiresIn = 7200;
        if (interval <= 0) interval = 2;

        BeginResult result = new BeginResult();
        result.deviceCode = deviceCode;
        result.userCode = userCode;
        result.qrUrl = qrUrl;
        result.expiresIn = expiresIn;
        result.interval = interval;

        LOG.info("[DingTalkReg] Begin: deviceCode={}, expiresIn={}s, interval={}s",
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
        String body = "{\"device_code\":\"" + deviceCode + "\"}";

        String resp = httpJsonPost(BASE_URL + POLL_PATH, body);
        if (resp == null) {
            return PollResult.waiting("请求失败");
        }

        ONode root = ONode.ofJson(resp);
        if (root == null || root.isNull()) {
            return PollResult.waiting("响应解析失败");
        }

        int errcode = root.get("errcode").getInt();
        if (errcode != 0) {
            String errmsg = root.get("errmsg").getString();
            return PollResult.failed("errcode=" + errcode + ": " + (errmsg != null ? errmsg : ""));
        }

        // 检查状态
        String status = root.get("status").getString();
        if (status == null) {
            return PollResult.waiting("等待中");
        }

        switch (status) {
            case "WAITING":
                return PollResult.waiting("等待用户扫码");
            case "SUCCESS":
                String clientId = root.get("client_id").getString();
                String clientSecret = root.get("client_secret").getString();

                if (clientId == null || clientId.isEmpty()) {
                    return PollResult.failed("扫码成功但 client_id 为空");
                }

                LOG.info("[DingTalkReg] App registered: clientId={}",
                        clientId.substring(0, Math.min(8, clientId.length())) + "...");

                return PollResult.success(clientId, clientSecret);
            case "FAIL":
                String failReason = root.get("fail_reason").getString();
                return PollResult.failed(failReason != null ? failReason : "用户拒绝授权");
            case "EXPIRED":
                return PollResult.failed("二维码已过期");
            default:
                return PollResult.waiting("状态: " + status);
        }
    }

    // ==================== HTTP 工具方法 ====================

    /**
     * 发送 JSON POST 请求（钉钉 Device Registration API 要求 JSON body）
     */
    protected String httpJsonPost(String url, String jsonBody) throws Exception {
        try (HttpResponse resp = HttpUtils.http(url)
                .timeout(10, 10, 15)
                .header("Content-Type", "application/json;charset=utf-8")
                .body(jsonBody.getBytes(StandardCharsets.UTF_8), "application/json")
                .exec("POST")) {
            int statusCode = resp.code();
            if (statusCode != 200) {
                String errorBody = "";
                try { errorBody = resp.bodyAsString(); } catch (Exception ignored) {}
                LOG.warn("[DingTalkReg] HTTP POST {} returned {}: {}", url, statusCode, errorBody);
                return errorBody; // 返回非200体，让调用方解析错误
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
        public final String status;   // "success" | "waiting" | "failed"
        public final String message;
        public final String clientId;
        public final String clientSecret;

        private PollResult(String status, String message, String clientId, String clientSecret) {
            this.status = status;
            this.message = message;
            this.clientId = clientId;
            this.clientSecret = clientSecret;
        }

        public boolean isSuccess() {
            return "success".equals(status);
        }

        public boolean isWaiting() {
            return "waiting".equals(status);
        }

        public boolean isFailed() {
            return "failed".equals(status);
        }

        static PollResult success(String clientId, String clientSecret) {
            return new PollResult("success", null, clientId, clientSecret);
        }

        static PollResult waiting(String message) {
            return new PollResult("waiting", message, null, null);
        }

        static PollResult failed(String message) {
            return new PollResult("failed", message, null, null);
        }
    }
}
