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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
     * 按 appId 隔离缓存的 tenant_access_token
     */
    private static final Map<String, TokenEntry> tokenCache = new ConcurrentHashMap<>();

    private static class TokenEntry {
        final String token;
        final long expireAt;

        TokenEntry(String token, long expireAt) {
            this.token = token;
            this.expireAt = expireAt;
        }
    }

    /**
     * 获取 tenant_access_token（自动缓存和刷新，按 appId 隔离）
     *
     * @param appId     飞书应用 app_id
     * @param appSecret 飞书应用 app_secret
     * @return tenant_access_token 或 null
     */
    public static String getTenantAccessToken(String appId, String appSecret) {
        long now = System.currentTimeMillis();
        // 提前 5 分钟刷新
        TokenEntry entry = tokenCache.get(appId);
        if (entry != null && entry.expireAt > now + 300_000) {
            return entry.token;
        }

        synchronized (FeishuClient.class) {
            // double-check
            entry = tokenCache.get(appId);
            if (entry != null && entry.expireAt > now + 300_000) {
                return entry.token;
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

                String token = root.get("tenant_access_token").getString();
                int expire = root.get("expire").getInt();
                tokenCache.put(appId, new TokenEntry(token, now + expire * 1000L));

                LOG.debug("[Feishu] Token refreshed for app '{}', expires in {}s", appId, expire);
                return token;
            } catch (Exception e) {
                LOG.error("[Feishu] getTenantAccessToken error: {}", e.getMessage());
                return null;
            }
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
            body.set("content", content.toJson()); //说明：这是规范要求，不能动

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

            body.set("content", postContent);

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

    /**
     * 获取飞书 WebSocket 长连接端点
     *
     * @param appId     飞书应用 App ID
     * @param appSecret 飞书应用 App Secret
     * @return 包含 url 和 pingInterval 的 ONode，或 null
     */
    public static ONode getWsEndpoint(String appId, String appSecret) {
        try {
            ONode body = new ONode();
            body.set("AppID", appId);
            body.set("AppSecret", appSecret);

            String resp = httpPost("https://open.feishu.cn/callback/ws/endpoint", body.toJson(), null);
            if (resp == null) return null;

            ONode root = ONode.ofJson(resp);
            int code = root.get("code").getInt();
            if (code != 0) {
                LOG.warn("[Feishu] getWsEndpoint failed: code={}, msg={}", code, root.get("msg").getString());
                return null;
            }

            return root.get("data");
        } catch (Exception e) {
            LOG.error("[Feishu] getWsEndpoint error: {}", e.getMessage());
            return null;
        }
    }

    // ==================== HTTP 工具方法 ====================

    private static String httpGet(String urlStr, String accessToken) throws Exception {
        HttpUtils http = HttpUtils.http(urlStr).timeout(10, 10, 15);

        if (accessToken != null && !accessToken.isEmpty()) {
            http.header("Authorization", "Bearer " + accessToken);
        }

        try (HttpResponse resp = http.exec("GET")) {
            int code = resp.code();
            if (code != 200) {
                LOG.warn("[Feishu] HTTP GET {} returned {}", urlStr, code);
                return null;
            }
            return resp.bodyAsString();
        }
    }

    public static String httpPost(String urlStr, String jsonBody, String accessToken) throws Exception {
        HttpUtils http = HttpUtils.http(urlStr).timeout(10, 10, 15);

        if (accessToken != null && !accessToken.isEmpty()) {
            http.header("Authorization", "Bearer " + accessToken);
        }

        if (jsonBody != null && !jsonBody.isEmpty()) {
            http.bodyOfJson(jsonBody);
        }

        try (HttpResponse resp = http.exec("POST")) {
            int code = resp.code();
            if (code != 200) {
                LOG.warn("[Feishu] HTTP POST {} returned {}", urlStr, code);
                return null;
            }
            return resp.bodyAsString();
        }
    }
}
