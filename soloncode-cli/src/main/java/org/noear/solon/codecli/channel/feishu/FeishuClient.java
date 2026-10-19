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
import org.noear.solon.codecli.config.ProxyConfig;
import org.noear.solon.net.http.HttpResponse;
import org.noear.solon.net.http.HttpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
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
        try {
            return requireTenantAccessToken(appId, appSecret, BASE_URL);
        } catch (Exception e) {
            LOG.error("[Feishu] getTenantAccessToken error: {}", e.getMessage());
            return null;
        }
    }

    private static String requireTenantAccessToken(String appId, String appSecret, String endpoint) {
        requireText(appId, "appId");
        requireText(appSecret, "appSecret");
        // 测试端点与正式端点的缓存互不污染，正式端点保留原 appId 缓存键。
        String cacheKey = BASE_URL.equals(endpoint) ? appId : endpoint + "|" + appId;
        long now = System.currentTimeMillis();
        // 提前 5 分钟刷新
        TokenEntry entry = tokenCache.get(cacheKey);
        if (entry != null && entry.expireAt > now + 300_000) {
            return entry.token;
        }

        synchronized (FeishuClient.class) {
            // double-check
            entry = tokenCache.get(cacheKey);
            if (entry != null && entry.expireAt > now + 300_000) {
                return entry.token;
            }

            ONode body = new ONode();
            body.set("app_id", appId);
            body.set("app_secret", appSecret);
            ONode root = executeApi(newRequest(endpoint + "/auth/v3/tenant_access_token/internal", null)
                    .bodyOfJson(body.toJson()), "getTenantAccessToken");
            String token = requireResult(root.get("tenant_access_token"), "tenant_access_token");
            int expire = root.get("expire").getInt();
            if (expire <= 0) {
                throw new IllegalStateException("[Feishu] getTenantAccessToken invalid expire");
            }
            tokenCache.put(cacheKey, new TokenEntry(token, System.currentTimeMillis() + expire * 1000L));
            LOG.debug("[Feishu] Token refreshed for app '{}', expires in {}s", appId, expire);
            return token;
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

    /**
     * 发送 Markdown 富文本消息（post + md tag）
     *
     * <p>飞书 post 格式支持 {@code tag: "md"}，可渲染完整 GFM Markdown（标题、加粗、
     * 斜体、代码块、引用、列表、表格、任务列表等）。</p>
     *
     * <p>图片使用 {@code ![alt](image_key)}，其中 image_key 由 {@link #uploadImage} 返回。</p>
     * <p>注意：{@code md} 标签独占一个段落，不能与其他标签（如 text、a）混排。</p>
     *
     * @param accessToken    tenant_access_token
     * @param receiveIdType  接收者类型：open_id / user_id / chat_id
     * @param receiveId      接收者 ID
     * @param title          消息标题（显示在消息顶部，可选，传空字符串则无标题）
     * @param mdBody         Markdown 文本内容
     * @return message_id 或 null
     */
    public static String sendMdPostMessage(String accessToken, String receiveIdType,
                                            String receiveId, String title, String mdBody) {
        return sendMdPostMessage(accessToken, receiveIdType, receiveId, title, mdBody, BASE_URL);
    }

    // 显式端点参数供本地 HTTP 测试使用，保持公开入口行为不变。
    static String sendMdPostMessage(String accessToken, String receiveIdType,
                                    String receiveId, String title, String mdBody, String endpoint) {
        try {
            ONode body = new ONode();
            body.set("receive_id_type", receiveIdType);
            body.set("receive_id", receiveId);
            body.set("msg_type", "post");

            // 构建 post 富文本，使用 md tag 渲染全量 Markdown
            ONode postContent = new ONode();
            ONode postBody = postContent.getOrNew("zh_cn");
            postBody.set("title", title != null ? title : "");
            ONode contentArray = postBody.getOrNew("content").asArray();
            ONode lineArray = new ONode().asArray();
            ONode mdNode = new ONode();
            mdNode.set("tag", "md");
            mdNode.set("text", mdBody);
            lineArray.add(mdNode);
            contentArray.add(lineArray);

            body.set("content", postContent.toJson());

            String resp = httpPost(endpoint + "/im/v1/messages?receive_id_type=" + receiveIdType,
                    body.toJson(), accessToken);
            if (resp == null) return null;

            ONode root = ONode.ofJson(resp);
            int code = root.get("code").getInt();
            if (code != 0) {
                LOG.warn("[Feishu] sendMdPostMessage failed: code={}, msg={}", code, root.get("msg").getString());
                return null;
            }

            return root.get("data").get("message_id").getString();
        } catch (Exception e) {
            LOG.error("[Feishu] sendMdPostMessage error: {}", e.getMessage());
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

    /**
     * 上传本地 PNG/JPEG 图片，返回可用于消息的 image_key。
     *
     * @throws IllegalArgumentException 参数为空、文件名不安全或图片格式不支持
     * @throws IllegalStateException HTTP、业务或响应格式错误（包含 token 获取错误）
     */
    public static String uploadImage(String appId, String appSecret, byte[] image, String filename) {
        return uploadImage(appId, appSecret, image, filename, BASE_URL);
    }

    // 显式端点参数供本地 HTTP 测试使用，不修改全局配置。
    static String uploadImage(String appId, String appSecret, byte[] image, String filename, String endpoint) {
        requireText(filename, "filename");
        if (filename.indexOf('\r') >= 0 || filename.indexOf('\n') >= 0 || filename.indexOf('"') >= 0
                || filename.indexOf('/') >= 0 || filename.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("filename must be a safe basename");
        }
        String mimeType;
        if (image != null && image.length >= 8 && image[0] == (byte) 0x89
                && image[1] == 'P' && image[2] == 'N' && image[3] == 'G'
                && image[4] == 13 && image[5] == 10 && image[6] == 26 && image[7] == 10) {
            mimeType = "image/png";
        } else if (image != null && image.length >= 3 && image[0] == (byte) 0xff
                && image[1] == (byte) 0xd8 && image[2] == (byte) 0xff) {
            mimeType = "image/jpeg";
        } else {
            throw new IllegalArgumentException("image must contain PNG or JPEG bytes");
        }
        String token = requireTenantAccessToken(appId, appSecret, endpoint);
        HttpUtils http = newRequest(endpoint + "/im/v1/images", token).multipart(true)
                .data("image_type", "message")
                .data("image", filename, new ByteArrayInputStream(image), mimeType);
        return requireResult(executeApi(http, "uploadImage").get("data").get("image_key"), "image_key");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static String requireResult(ONode node, String name) {
        String value = node.getString();
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException("[Feishu] invalid API response: missing " + name);
        }
        return value;
    }

    private static HttpUtils newRequest(String url, String token) {
        HttpUtils http = HttpUtils.http(url).timeout(10, 10, 15);
        ProxyConfig.applyIfNeeded(http);
        if (token != null) {
            http.header("Authorization", "Bearer " + token);
        }
        return http;
    }

    private static ONode executeApi(HttpUtils http, String operation) {
        try (HttpResponse response = http.exec("POST")) {
            int status = response.code();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("[Feishu] " + operation + " HTTP error: status=" + status);
            }
            ONode root;
            try {
                root = ONode.ofJson(response.bodyAsString());
                if (root.get("code").isNull()) {
                    throw new IllegalArgumentException("missing code");
                }
            } catch (Exception e) {
                throw new IllegalStateException("[Feishu] " + operation + " invalid JSON response", e);
            }
            int code = root.get("code").getInt();
            if (code != 0) {
                throw new IllegalStateException("[Feishu] " + operation + " business error: code=" + code
                        + ", msg=" + root.get("msg").getString());
            }
            return root;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("[Feishu] " + operation + " HTTP transport/response error", e);
        }
    }

    // ==================== HTTP 工具方法 ====================

    private static String httpGet(String urlStr, String accessToken) throws Exception {
        HttpUtils http = HttpUtils.http(urlStr).timeout(10, 10, 15);
        ProxyConfig.applyIfNeeded(http);

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
        ProxyConfig.applyIfNeeded(http);

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
