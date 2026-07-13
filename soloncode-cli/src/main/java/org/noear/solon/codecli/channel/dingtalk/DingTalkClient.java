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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
     * 按 appKey 隔离缓存的 access_token
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
     * 获取 access_token（自动缓存和刷新，按 appKey 隔离）
     *
     * @param appKey    钉钉应用 AppKey（或 appKey）
     * @param appSecret 钉钉应用 AppSecret
     * @return access_token 或 null
     */
    public static String getAccessToken(String appKey, String appSecret) {
        long now = System.currentTimeMillis();
        TokenEntry entry = tokenCache.get(appKey);
        if (entry != null && entry.expireAt > now + 300_000) {
            return entry.token;
        }

        synchronized (DingTalkClient.class) {
            // double-check
            entry = tokenCache.get(appKey);
            if (entry != null && entry.expireAt > now + 300_000) {
                return entry.token;
            }

            try {
                ONode body = new ONode();
                body.set("appKey", appKey);
                body.set("appSecret", appSecret);

                String resp = httpPost(NEW_API_BASE + "/v1.0/oauth2/accessToken", body.toJson(), null);
                if (resp == null) return null;

                ONode root = ONode.ofJson(resp);
                String token = root.get("accessToken").getString();
                int expire = root.get("expireIn").getInt();
                tokenCache.put(appKey, new TokenEntry(token, now + expire * 1000L));

                LOG.debug("[DingTalk] Token refreshed for app '{}', expires in {}s", appKey, expire);
                return token;
            } catch (Exception e) {
                LOG.error("[DingTalk] getAccessToken error: {}", e.getMessage());
                return null;
            }
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
            // 检查是否有错误码（钉钉可能在 "code" 或 "errcode" 字段中返回错误）
            String errorCode = root.hasKey("code") ? root.get("code").getString() : null;
            if (errorCode == null && root.hasKey("errcode")) {
                errorCode = root.get("errcode").getString();
            }
            if (errorCode != null && !"0".equals(errorCode) && !"SUCCESS".equalsIgnoreCase(errorCode)) {
                LOG.warn("[DingTalk] sendSingleMessage failed: {}", resp);
                return false;
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
            // 检查是否有错误码（钉钉可能在 "code" 或 "errcode" 字段中返回错误）
            String errorCode = root.hasKey("code") ? root.get("code").getString() : null;
            if (errorCode == null && root.hasKey("errcode")) {
                errorCode = root.get("errcode").getString();
            }
            if (errorCode != null && !"0".equals(errorCode) && !"SUCCESS".equalsIgnoreCase(errorCode)) {
                LOG.warn("[DingTalk] sendGroupMessage failed: {}", resp);
                return false;
            }

            return true;
        } catch (Exception e) {
            LOG.error("[DingTalk] sendGroupMessage error: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 从 Markdown 文本中提取通知标题
     * <ul>
     *     <li>取第一行，trim</li>
     *     <li>去除 markdown 标题标记（# 开头）</li>
     *     <li>最多 30 字符</li>
     * </ul>
     */
    public static String extractTitle(String text) {
        if (text == null || text.isEmpty()) {
            return "SolonCode";
        }
        String firstLine = text.split("\\n", 2)[0].trim();
        firstLine = firstLine.replaceAll("^#+\\s*", "");
        if (firstLine.length() > 30) {
            return firstLine.substring(0, 30) + "…";
        }
        return firstLine;
    }

    /**
     * 发送单聊 Markdown 机器人消息（批量接口，但这里每次只发一个用户）
     *
     * <p>使用 {@code msgKey: "sampleMarkdown"}，支持标题、加粗、斜体、列表、
     * 引用、代码块等 Markdown 语法。</p>
     *
     * @param accessToken access_token
     * @param robotCode   机器人编码
     * @param userId      接收者 userId（staffId）
     * @param title       消息标题（显示在消息头部，可选）
     * @param mdText      Markdown 文本内容
     * @return true 发送成功
     */
    public static boolean sendSingleMarkdownMessage(String accessToken, String robotCode, String userId,
                                                     String title, String mdText) {
        try {
            ONode body = new ONode();
            body.set("robotCode", robotCode);

            ONode userIds = body.getOrNew("userIds").asArray();
            userIds.add(userId);

            body.set("msgKey", "sampleMarkdown");

            ONode msgParam = new ONode();
            msgParam.set("title", title != null ? title : "");
            msgParam.set("text", mdText);
            body.set("msgParam", msgParam.toJson());

            String resp = httpPost(NEW_API_BASE + "/v1.0/robot/oToMessages/batchSend", body.toJson(), accessToken);
            if (resp == null) return false;

            if (resp.isEmpty()) {
                return true;
            }

            ONode root = ONode.ofJson(resp);
            String errorCode = root.hasKey("code") ? root.get("code").getString() : null;
            if (errorCode == null && root.hasKey("errcode")) {
                errorCode = root.get("errcode").getString();
            }
            if (errorCode != null && !"0".equals(errorCode) && !"SUCCESS".equalsIgnoreCase(errorCode)) {
                LOG.warn("[DingTalk] sendSingleMarkdownMessage failed: {}", resp);
                return false;
            }

            return true;
        } catch (Exception e) {
            LOG.error("[DingTalk] sendSingleMarkdownMessage error: {}", e.getMessage());
            return false;
        }
    }

    // ==================== HTTP 工具方法 ====================

    public static String httpPost(String urlStr, String jsonBody, String accessToken) throws Exception {
        HttpUtils http = HttpUtils.http(urlStr).timeout(10, 10, 15);

        if (accessToken != null && !accessToken.isEmpty()) {
            http.header("x-acs-dingtalk-access-token", accessToken);
        }

        if (jsonBody != null && !jsonBody.isEmpty()) {
            http.bodyOfJson(jsonBody);
        }

        try (HttpResponse resp = http.exec("POST")) {
            int code = resp.code();
            if (code >= 200 && code < 300) {
                return resp.bodyAsString();
            } else {
                String errorBody = "";
                try {
                    errorBody = resp.bodyAsString();
                } catch (Exception ignored) {
                }
                LOG.warn("[DingTalk] HTTP POST {} returned {}: {}", urlStr, code, errorBody);
                return errorBody;
            }
        }
    }
}
