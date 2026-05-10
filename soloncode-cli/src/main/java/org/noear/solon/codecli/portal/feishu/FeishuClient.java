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
package org.noear.solon.codecli.portal.feishu;

import org.noear.snack4.ONode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 飞书 API 客户端
 *
 * <p>提供飞书开放平台 API 的 HTTP 封装，包括获取 tenant_access_token、
 * 发送消息等接口。纯 HTTP/JSON 实现，无第三方 SDK 依赖。</p>
 *
 * @author noear 2026/5/9 created
 */
public class FeishuClient {
    private static final Logger LOG = LoggerFactory.getLogger(FeishuClient.class);

    private static final String BASE_URL = "https://open.feishu.cn/open-apis";

    /**
     * 缓存的 tenant_access_token
     */
    private static String cachedToken;
    /**
     * token 过期时间戳（毫秒）
     */
    private static long tokenExpireAt;

    /**
     * 获取 tenant_access_token（自动缓存和刷新）
     *
     * @param appId     飞书应用 app_id
     * @param appSecret 飞书应用 app_secret
     * @return tenant_access_token 或 null
     */
    public static synchronized String getTenantAccessToken(String appId, String appSecret) {
        long now = System.currentTimeMillis();
        // 提前 5 分钟刷新
        if (cachedToken != null && tokenExpireAt > now + 300_000) {
            return cachedToken;
        }

        try {
            ONode body = new ONode();
            body.set("app_id", appId);
            body.set("app_secret", appSecret);

            String resp = httpPost(BASE_URL + "/auth/v3/tenant_access_token/internal", body.toJson(), null);
            if (resp == null) return null;

            ONode root = ONode.ofJson(resp);
            int code = root.get("code").getInt();
            if (code != 0) {
                LOG.warn("[Feishu] getTenantAccessToken failed: code={}, msg={}", code, root.get("msg").getString());
                return null;
            }

            cachedToken = root.get("tenant_access_token").getString();
            int expire = root.get("expire").getInt();
            tokenExpireAt = now + expire * 1000L;

            LOG.debug("[Feishu] Token refreshed, expires in {}s", expire);
            return cachedToken;
        } catch (Exception e) {
            LOG.error("[Feishu] getTenantAccessToken error: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 发送文本消息
     *
     * @param accessToken tenant_access_token
     * @param receiveIdType 接收者类型：open_id / user_id / chat_id
     * @param receiveId   接收者 ID
     * @param text        消息文本
     * @return message_id 或 null
     */
    public static String sendMessage(String accessToken, String receiveIdType, String receiveId, String text) {
        try {
            ONode body = new ONode();
            body.set("receive_id_type", receiveIdType);
            body.set("receive_id", receiveId);
            body.set("msg_type", "text");

            ONode content = new ONode();
            content.set("text", text);
            body.set("content", content.toJson());

            String resp = httpPost(BASE_URL + "/im/v1/messages?receive_id_type=" + receiveIdType, body.toJson(), accessToken);
            if (resp == null) return null;

            ONode root = ONode.ofJson(resp);
            int code = root.get("code").getInt();
            if (code != 0) {
                LOG.warn("[Feishu] sendMessage failed: code={}, msg={}", code, root.get("msg").getString());
                return null;
            }

            return root.get("data").get("message_id").getString();
        } catch (Exception e) {
            LOG.error("[Feishu] sendMessage error: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 发送富文本消息（post 格式，支持多段落）
     *
     * @param accessToken tenant_access_token
     * @param receiveIdType 接收者类型
     * @param receiveId   接收者 ID
     * @param title       消息标题
     * @param content     消息内容（纯文本）
     * @return message_id 或 null
     */
    public static String sendPostMessage(String accessToken, String receiveIdType, String receiveId, String title, String content) {
        try {
            ONode body = new ONode();
            body.set("receive_id_type", receiveIdType);
            body.set("receive_id", receiveId);
            body.set("msg_type", "post");

            ONode postContent = new ONode();
            ONode postBody = postContent.getOrNew("zh_cn");
            postBody.set("title", title != null ? title : "");
            ONode contentArray = postBody.getOrNew("content").asArray();
            ONode lineArray = new ONode().asArray();
            ONode textNode = new ONode();
            textNode.set("tag", "text");
            textNode.set("text", content);
            lineArray.add(textNode);
            contentArray.add(lineArray);

            body.set("content", postContent.toJson());

            String resp = httpPost(BASE_URL + "/im/v1/messages?receive_id_type=" + receiveIdType, body.toJson(), accessToken);
            if (resp == null) return null;

            ONode root = ONode.ofJson(resp);
            int code = root.get("code").getInt();
            if (code != 0) {
                LOG.warn("[Feishu] sendPostMessage failed: code={}, msg={}", code, root.get("msg").getString());
                return null;
            }

            return root.get("data").get("message_id").getString();
        } catch (Exception e) {
            LOG.error("[Feishu] sendPostMessage error: {}", e.getMessage());
            return null;
        }
    }

    // ==================== HTTP 工具方法 ====================

    private static String httpGet(String urlStr, String accessToken) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        if (accessToken != null && !accessToken.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
        }
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);

        int code = conn.getResponseCode();
        if (code != 200) {
            LOG.warn("[Feishu] HTTP GET {} returned {}", urlStr, code);
            return null;
        }

        return readResponse(conn);
    }

    private static String httpPost(String urlStr, String jsonBody, String accessToken) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        if (accessToken != null && !accessToken.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
        }
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);

        if (jsonBody != null && !jsonBody.isEmpty()) {
            conn.getOutputStream().write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        if (code != 200) {
            LOG.warn("[Feishu] HTTP POST {} returned {}", urlStr, code);
            return null;
        }

        return readResponse(conn);
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
}
