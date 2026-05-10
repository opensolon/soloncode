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
package org.noear.solon.codecli.portal.dingtalk;

import org.noear.snack4.ONode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 钉钉 API 客户端
 *
 * <p>提供钉钉开放平台 API 的 HTTP 封装，包括获取 access_token、
 * 发送单聊/群聊消息等接口。纯 HTTP/JSON 实现，无第三方 SDK 依赖。</p>
 *
 * @author noear 2026/5/9 created
 */
public class DingTalkClient {
    private static final Logger LOG = LoggerFactory.getLogger(DingTalkClient.class);

    /**
     * 旧版 API 基地址（获取 token 等）
     */
    private static final String API_BASE = "https://oapi.dingtalk.com";
    /**
     * 新版 API 基地址（发送消息等）
     */
    private static final String NEW_API_BASE = "https://api.dingtalk.com";

    /**
     * 缓存的 access_token
     */
    private static String cachedToken;
    /**
     * token 过期时间戳（毫秒）
     */
    private static long tokenExpireAt;

    /**
     * 获取 access_token（自动缓存和刷新）
     *
     * @param appKey    钉钉应用 AppKey（或 appKey）
     * @param appSecret 钉钉应用 AppSecret
     * @return access_token 或 null
     */
    public static synchronized String getAccessToken(String appKey, String appSecret) {
        long now = System.currentTimeMillis();
        if (cachedToken != null && tokenExpireAt > now + 300_000) {
            return cachedToken;
        }

        try {
            ONode body = new ONode();
            body.set("appKey", appKey);
            body.set("appSecret", appSecret);

            String resp = httpPost(NEW_API_BASE + "/v1.0/oauth2/accessToken", body.toJson(), null);
            if (resp == null) return null;

            ONode root = ONode.ofJson(resp);
            cachedToken = root.get("accessToken").getString();
            int expire = root.get("expireIn").getInt();
            tokenExpireAt = now + expire * 1000L;

            LOG.debug("[DingTalk] Token refreshed, expires in {}s", expire);
            return cachedToken;
        } catch (Exception e) {
            LOG.error("[DingTalk] getAccessToken error: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 发送单聊机器人消息（批量接口，但这里每次只发一个用户）
     *
     * @param accessToken access_token
     * @param robotCode   机器人编码
     * @param userId      接收者 userId（staffId）
     * @param text        消息文本
     * @return true 发送成功
     */
    public static boolean sendSingleMessage(String accessToken, String robotCode, String userId, String text) {
        try {
            ONode body = new ONode();
            body.set("robotCode", robotCode);

            ONode userIds = body.getOrNew("userIds").asArray();
            userIds.add(userId);

            body.set("msgKey", "sampleText");

            ONode msgParam = new ONode();
            msgParam.set("content", text);
            body.set("msgParam", msgParam.toJson());

            String resp = httpPost(NEW_API_BASE + "/v1.0/robot/oToMessages/batchSend", body.toJson(), accessToken);
            if (resp == null) return false;

            // 该接口返回空 body 表示成功，或返回 JSON 包含错误信息
            if (resp.isEmpty()) {
                return true;
            }

            ONode root = ONode.ofJson(resp);
            // 检查是否有错误码
            if (root.hasKey("code")) {
                String code = root.get("code").getString();
                if (code != null && !"0".equals(code) && !"SUCCESS".equalsIgnoreCase(code)) {
                    LOG.warn("[DingTalk] sendSingleMessage failed: {}", resp);
                    return false;
                }
            }

            return true;
        } catch (Exception e) {
            LOG.error("[DingTalk] sendSingleMessage error: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 发送群聊机器人消息
     *
     * @param accessToken    access_token
     * @param robotCode      机器人编码
     * @param conversationId 群会话 ID
     * @param text           消息文本
     * @return true 发送成功
     */
    public static boolean sendGroupMessage(String accessToken, String robotCode, String conversationId, String text) {
        try {
            ONode body = new ONode();
            body.set("robotCode", robotCode);
            body.set("conversationId", conversationId);

            ONode userIds = body.getOrNew("userIds").asArray();

            body.set("msgKey", "sampleText");

            ONode msgParam = new ONode();
            msgParam.set("content", text);
            body.set("msgParam", msgParam.toJson());

            String resp = httpPost(NEW_API_BASE + "/v1.0/robot/oToMessages/batchSend", body.toJson(), accessToken);
            if (resp == null) return false;

            if (resp.isEmpty()) {
                return true;
            }

            ONode root = ONode.ofJson(resp);
            if (root.hasKey("code")) {
                String code = root.get("code").getString();
                if (code != null && !"0".equals(code) && !"SUCCESS".equalsIgnoreCase(code)) {
                    LOG.warn("[DingTalk] sendGroupMessage failed: {}", resp);
                    return false;
                }
            }

            return true;
        } catch (Exception e) {
            LOG.error("[DingTalk] sendGroupMessage error: {}", e.getMessage());
            return false;
        }
    }

    // ==================== HTTP 工具方法 ====================

    private static String httpPost(String urlStr, String jsonBody, String accessToken) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        if (accessToken != null && !accessToken.isEmpty()) {
            conn.setRequestProperty("x-acs-dingtalk-access-token", accessToken);
        }
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);

        if (jsonBody != null && !jsonBody.isEmpty()) {
            conn.getOutputStream().write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        if (code >= 200 && code < 300) {
            return readResponse(conn);
        } else {
            // 尝试读取错误响应
            String errorBody = "";
            try {
                errorBody = readErrorResponse(conn);
            } catch (Exception ignored) {
            }
            LOG.warn("[DingTalk] HTTP POST {} returned {}: {}", urlStr, code, errorBody);
            return errorBody; // 某些错误场景 body 包含有用信息
        }
    }

    private static String readResponse(HttpURLConnection conn) throws Exception {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        return sb.toString();
    }

    private static String readErrorResponse(HttpURLConnection conn) throws Exception {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        return sb.toString();
    }
}
