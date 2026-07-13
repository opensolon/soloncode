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
package org.noear.solon.codecli.channel.wechat;

import org.noear.snack4.ONode;
import org.noear.solon.net.http.HttpResponse;
import org.noear.solon.net.http.HttpUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 微信 iLink Bot API 客户端
 *
 * <p>提供获取二维码、轮询扫码状态、收发消息等 HTTP 接口封装。
 * 协议为纯 HTTP/JSON，无第三方 SDK 依赖。</p>
 *
 * @author noear 2026/5/5 created
 */
public class WeChatClient {
    private static final Logger LOG = LoggerFactory.getLogger(WeChatClient.class);

    private static final String BASE_URL = "https://ilinkai.weixin.qq.com";

    private static final String HEADER_CONTENT_TYPE = "Content-Type";
    private static final String HEADER_AUTH_TYPE = "AuthorizationType";
    private static final String HEADER_AUTH = "Authorization";
    private static final String HEADER_UIN = "X-WECHAT-UIN";

    /**
     * 获取 Bot 登录二维码
     *
     * @return {qrcode, qrcode_img_url} 或 null
     */
    public static Map<String, String> fetchQRCode() {
        try {
            String url = BASE_URL + "/ilink/bot/get_bot_qrcode?bot_type=3";
            String resp = httpGet(url);
            if (resp == null) return null;

            ONode root = ONode.ofJson(resp);
            int ret = root.get("ret").getInt();
            if (ret != 0) {
                LOG.warn("fetchQRCode failed: ret={}, msg={}", ret, root.get("msg").getString());
                return null;
            }

            Map<String, String> result = new LinkedHashMap<>();
            result.put("qrcode", root.get("qrcode").getString());
            result.put("qrcode_img_content", root.get("qrcode_img_content").getString());
            return result;
        } catch (Exception e) {
            LOG.error("fetchQRCode error: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 轮询二维码扫码状态
     *
     * @param qrcode 二维码 token
     * @return {status, bot_token, ilink_bot_id, ilink_user_id, baseurl} 或 null
     *         status: wait | scaned | confirmed | expired
     */
    public static Map<String, String> pollQRStatus(String qrcode) {
        try {
            String url = BASE_URL + "/ilink/bot/get_qrcode_status?qrcode=" + encodeURIComponent(qrcode);
            String resp = httpGet(url);
            if (resp == null) return null;

            ONode root = ONode.ofJson(resp);
            int ret = root.get("ret").getInt();
            String status = root.get("status").getString();

            Map<String, String> result = new LinkedHashMap<>();
            // 即使 ret != 0 也尝试提取 status（某些过渡状态可能 ret 非零但 status 存在）
            result.put("status", status != null ? status : (ret == 0 ? "wait" : "unknown"));

            if (ret == 0) {
                if ("confirmed".equals(status)) {
                    result.put("bot_token", root.get("bot_token").getString());
                    result.put("ilink_bot_id", root.get("ilink_bot_id").getString());
                    result.put("ilink_user_id", root.get("ilink_user_id").getString());
                    // BUG9: 返回 baseurl（可能覆盖默认值）
                    String baseurl = root.get("baseurl").getString();
                    if (baseurl != null) {
                        result.put("baseurl", baseurl);
                    }
                }
                // scaned/wait 直接返回 status，前端继续轮询
            } else {
                // ret != 0 但 status 存在且为已知状态时仍返回
                if ("wait".equals(status) || "scaned".equals(status) || "confirmed".equals(status) || "expired".equals(status)) {
                    // 保留有效状态
                } else {
                    // 完全不认识的响应，标记为 unknown
                    result.put("status", "unknown");
                }
            }
            return result;
        } catch (Exception e) {
            LOG.error("pollQRStatus error: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 长轮询获取消息更新（约 35s 超时）
     *
     * @param botToken      Bot 鉴权 Token
     * @param getUpdatesBuf 上次游标，首次传空
     * @return {messages: [{text, from_user_id, context_token}], cursor} 或 null
     */
    public static Map<String, Object> getUpdates(String botToken, String getUpdatesBuf) {
        try {
            ONode body = new ONode();
            body.set("get_updates_buf", getUpdatesBuf != null ? getUpdatesBuf : "");
            // iLink 协议要求所有 POST 请求携带 base_info
            ONode baseInfo = body.getOrNew("base_info");
            baseInfo.set("channel_version", "1.0.0");

            String resp = httpPost(BASE_URL + "/ilink/bot/getupdates", body.toJson(), botToken);
            if (resp == null) return null;

            ONode root = ONode.ofJson(resp);
            int ret = root.get("ret").getInt();
            if (ret != 0) {
                // -14 表示 token 过期
                if (ret == -14) {
                    Map<String, Object> errResult = new LinkedHashMap<>();
                    errResult.put("expired", true);
                    return errResult;
                }
                return null;
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("cursor", root.get("get_updates_buf").getString());

            List<Map<String, String>> messages = new ArrayList<>();
            // BUG1: API 返回 msgs 而非 updates
            ONode msgs = root.get("msgs");
            if (msgs != null && msgs.isArray()) {
                for (ONode msgNode : msgs.getArray()) {
                    // BUG8: 只处理用户消息 (message_type == 1)
                    int msgType = msgNode.get("message_type").getInt();
                    if (msgType != 1) continue;

                    Map<String, String> msg = new LinkedHashMap<>();
                    // BUG2: 文本在 item_list[0].text_item.text
                    ONode itemList = msgNode.get("item_list");
                    if (itemList != null && itemList.isArray()) {
                        ONode firstItem = itemList.get(0);
                        if (firstItem != null) {
                            msg.put("text", firstItem.get("text_item").get("text").getString());
                        }
                    }
                    msg.put("from_user_id", msgNode.get("from_user_id").getString());
                    msg.put("context_token", msgNode.get("context_token").getString());
                    // BUG3: 消息里没有 ticket 字段，typing_ticket 需从 getconfig 获取
                    messages.add(msg);
                }
            }
            result.put("messages", messages);
            return result;
        } catch (Exception e) {
            LOG.error("getUpdates error: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 发送文本消息
     *
     * <p>注意：iLink 协议要求严格按照以下格式构造消息体，
     * 包含 msg 包装、client_id、message_type、message_state、item_list 等字段，
     * 缺少任何一个都可能被服务端静默丢弃。</p>
     *
     * @param botToken     Bot 鉴权 Token
     * @param toUserId     目标用户 ID
     * @param contextToken 上下文 Token（必须从入站消息获取）
     * @param text         消息文本
     * @return true 发送成功
     */
    public static boolean sendMessage(String botToken, String toUserId, String contextToken, String text) {
        try {
            // BUG4: 正确构造 sendMessage 请求体
            ONode body = new ONode();
            ONode msg = body.getOrNew("msg");
            msg.set("from_user_id", "");
            msg.set("to_user_id", toUserId);
            msg.set("client_id", UUID.randomUUID().toString().replace("-", ""));
            msg.set("message_type", 2);  // BOT
            msg.set("message_state", 2); // FINISH

            ONode itemList = msg.getOrNew("item_list").asArray();
            ONode item = new ONode();
            item.set("type", 1); // TEXT
            item.getOrNew("text_item").set("text", text);
            itemList.add(item);

            msg.set("context_token", contextToken);

            // 所有 POST 请求需要 base_info
            ONode baseInfo = body.getOrNew("base_info");
            baseInfo.set("channel_version", "1.0.0");

            String resp = httpPost(BASE_URL + "/ilink/bot/sendmessage", body.toJson(), botToken);
            if (resp == null) return false;

            ONode root = ONode.ofJson(resp);
            int ret = root.get("ret").getInt();
            if (ret != 0) {
                LOG.warn("sendMessage failed: {}", resp);
            }
            return ret == 0;
        } catch (Throwable e) {
            LOG.error("sendMessage error: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 获取用户的 typing_ticket
     *
     * <p>发送"正在输入"状态前，需要先通过此接口获取 typing_ticket。
     * 建议按用户缓存，有效期约 24 小时。</p>
     *
     * @param botToken     Bot 鉴权 Token
     * @param ilinkUserId  用户 ID
     * @param contextToken 上下文 Token
     * @return typing_ticket 或 null
     */
    public static String getConfig(String botToken, String ilinkUserId, String contextToken) {
        try {
            ONode body = new ONode();
            body.set("ilink_user_id", ilinkUserId);
            body.set("context_token", contextToken);
            ONode baseInfo = body.getOrNew("base_info");
            baseInfo.set("channel_version", "1.0.0");

            String resp = httpPost(BASE_URL + "/ilink/bot/getconfig", body.toJson(), botToken);
            if (resp == null) return null;

            ONode root = ONode.ofJson(resp);
            if (root.get("ret").getInt() != 0) return null;
            return root.get("typing_ticket").getString();
        } catch (Exception e) {
            LOG.error("getConfig error: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 发送"正在输入"状态
     *
     * @param botToken     Bot 鉴权 Token
     * @param ilinkUserId  用户 ID
     * @param typingTicket 从 getconfig 获取的 ticket
     * @param status       1 开始输入, 2 停止输入
     * @return true 发送成功
     */
    public static boolean sendTyping(String botToken, String ilinkUserId, String typingTicket, int status) {
        try {
            // BUG5: 正确字段名和值
            ONode body = new ONode();
            body.set("ilink_user_id", ilinkUserId);
            body.set("typing_ticket", typingTicket);
            body.set("status", status);  // 1=开始, 2=停止
            ONode baseInfo = body.getOrNew("base_info");
            baseInfo.set("channel_version", "1.0.0");

            String resp = httpPost(BASE_URL + "/ilink/bot/sendtyping", body.toJson(), botToken);
            if (resp == null) return false;

            ONode root = ONode.ofJson(resp);
            return root.get("ret").getInt() == 0;
        } catch (Exception e) {
            LOG.error("sendTyping error: {}", e.getMessage());
            return false;
        }
    }

    // ==================== HTTP 工具方法 ====================

    private static String httpGet(String urlStr) throws Exception {
        HttpUtils http = HttpUtils.http(urlStr)
                .timeout(10, 10, 15)
                .header(HEADER_CONTENT_TYPE, "application/json")
                .header(HEADER_UIN, generateUin())
                .header("iLink-App-ClientVersion", "1");

        try (HttpResponse resp = http.exec("GET")) {
            int code = resp.code();
            if (code != 200) {
                LOG.warn("HTTP GET {} returned {}", urlStr, code);
                return null;
            }
            return resp.bodyAsString();
        }
    }

    private static String httpPost(String urlStr, String jsonBody, String botToken) throws Exception {
        HttpUtils http = HttpUtils.http(urlStr)
                .timeout(10, 10, 45)  // getupdates 长轮询需要较长超时
                .header(HEADER_CONTENT_TYPE, "application/json")
                .header(HEADER_AUTH_TYPE, "ilink_bot_token")
                .header(HEADER_AUTH, "Bearer " + botToken)
                .header(HEADER_UIN, generateUin());

        if (jsonBody != null && !jsonBody.isEmpty()) {
            http.bodyOfJson(jsonBody);
        }

        try (HttpResponse resp = http.exec("POST")) {
            int code = resp.code();
            if (code != 200) {
                LOG.warn("HTTP POST {} returned {}", urlStr, code);
                return null;
            }
            return resp.bodyAsString();
        }
    }

    private static String generateUin() {
        return Base64.getEncoder().encodeToString(
                String.valueOf(new Random().nextInt(Integer.MAX_VALUE)).getBytes());
    }

    private static String encodeURIComponent(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8")
                    .replace("+", "%20")
                    .replace("%21", "!")
                    .replace("%27", "'")
                    .replace("%28", "(")
                    .replace("%29", ")")
                    .replace("%7E", "~");
        } catch (Exception e) {
            return s;
        }
    }
}
