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
import org.noear.solon.codecli.config.ProxyConfig;
import org.noear.solon.net.http.HttpResponse;
import org.noear.solon.net.http.HttpUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 微信 iLink Bot API 客户端
 *
 * <p>提供获取二维码、轮询扫码状态、收发消息等 HTTP 接口封装。
 * 协议为纯 HTTP/JSON，无第三方 SDK 依赖。</p>
 *
 * <p><b>接入点（baseUrl）</b>：扫码确认时服务端可能通过 {@code baseurl} /
 * {@code redirect_host} 指派专属接入点，后续业务请求应打到该地址。
 * 各业务方法均接受 {@code baseUrl} 参数，传 null 时回落到 {@link #DEFAULT_BASE_URL}。</p>
 *
 * @author noear 2026/5/5 created
 */
public class WeChatClient {
    private static final Logger LOG = LoggerFactory.getLogger(WeChatClient.class);

    static final String DEFAULT_BASE_URL = "https://ilinkai.weixin.qq.com";

    /**
     * iLink 协议版本号。所有业务 POST 的 base_info 必须携带，
     * 官方 SDK 与公开参考实现当前使用 1.0.2（1.0.0 在部分接口上会被拒绝）。
     */
    private static final String CHANNEL_VERSION = "1.0.2";

    /**
     * token 过期错误码。不同接口/版本可能放在 {@code ret} 或 {@code errcode} 上，两者都要判。
     */
    static final int RET_TOKEN_EXPIRED = -14;

    /**
     * 允许作为接入点的域名后缀白名单。
     *
     * <p>{@code baseurl} 来自服务端响应，一旦被替换为攻击者控制的地址，
     * 后续请求会把 bot_token 发往该地址。虽然响应本身经 TLS 校验，
     * 但按最小信任原则仍做后缀收敛，超出白名单则回落默认接入点。</p>
     */
    private static final String[] ALLOWED_HOST_SUFFIX = {".weixin.qq.com", ".qq.com"};

    private static final String HEADER_CONTENT_TYPE = "Content-Type";
    private static final String HEADER_AUTH_TYPE = "AuthorizationType";
    private static final String HEADER_AUTH = "Authorization";
    private static final String HEADER_UIN = "X-WECHAT-UIN";

    /**
     * getupdates 为长轮询，服务端 hold 约 35s，读超时需留足余量
     */
    private static final int TIMEOUT_LONG_POLL = 45;
    /**
     * sendmessage 读超时
     */
    private static final int TIMEOUT_SEND = 15;
    /**
     * getconfig / sendtyping 读超时
     */
    private static final int TIMEOUT_QUICK = 10;

    /**
     * 失败结果的原因键。{@link #fetchQRCode()} 失败时以该键返回具体原因，
     * 供上层直接展示，避免笼统的"请检查网络"掩盖真实错误（代理、证书、风控等）。
     */
    public static final String KEY_ERROR = "error";

    /**
     * 获取 Bot 登录二维码
     *
     * @return 成功返回 {qrcode, qrcode_img_content}；失败返回仅含 {@link #KEY_ERROR} 的 Map
     */
    public static Map<String, String> fetchQRCode() {
        try {
            String url = DEFAULT_BASE_URL + "/ilink/bot/get_bot_qrcode?bot_type=3";
            String resp = httpGet(url);

            ONode root = ONode.ofJson(resp);
            int ret = root.get("ret").getInt();
            if (ret != 0) {
                String msg = root.get("msg").getString();
                LOG.warn("fetchQRCode failed: ret={}, msg={}", ret, msg);
                return errorOf("接口返回 ret=" + ret
                        + (msg == null || msg.isEmpty() ? "" : ", msg=" + msg));
            }

            Map<String, String> result = new LinkedHashMap<>();
            result.put("qrcode", root.get("qrcode").getString());
            result.put("qrcode_img_content", root.get("qrcode_img_content").getString());
            return result;
        } catch (Exception e) {
            LOG.error("fetchQRCode error", e);
            return errorOf(describe(e));
        }
    }

    /**
     * 构造仅含失败原因的结果
     */
    private static Map<String, String> errorOf(String reason) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put(KEY_ERROR, reason);
        return result;
    }

    /**
     * 生成异常摘要。
     *
     * <p>超时、TLS 握手失败等异常的 {@code getMessage()} 常为空或无指向性，
     * 只打印 message 会丢掉最关键的异常类型信息，因此这里带上类名。</p>
     */
    private static String describe(Throwable e) {
        String type = e.getClass().getSimpleName();
        String msg = e.getMessage();
        return (msg == null || msg.isEmpty()) ? type : type + ": " + msg;
    }

    /**
     * 轮询二维码扫码状态
     *
     * @param qrcode 二维码 token
     * @return {status, bot_token, ilink_bot_id, ilink_user_id, baseurl} 或 null
     *         status: wait | scaned | confirmed | expired | need_verifycode | ...
     */
    public static Map<String, String> pollQRStatus(String qrcode) {
        try {
            String url = DEFAULT_BASE_URL + "/ilink/bot/get_qrcode_status?qrcode=" + encodeURIComponent(qrcode);
            String resp = httpGet(url);
            if (resp == null) return null;

            return parseQrStatus(resp);
        } catch (Exception e) {
            LOG.error("pollQRStatus error: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 已知的扫码状态。除基础四态外，2.x 起还有节点跳转与验证码相关状态，
     * 若不认领会被归为 unknown，让前端把正常的过渡态当成异常。
     */
    private static final Set<String> KNOWN_QR_STATUS = new HashSet<>(Arrays.asList(
            "wait", "scaned", "confirmed", "expired",
            "scaned_but_redirect", "binded_redirect",
            "need_verifycode", "verify_code_blocked"));

    /**
     * 解析扫码状态响应。
     *
     * <p>凭据的提取不再绑死 {@code status == "confirmed"}：{@code binded_redirect} 等
     * 跳转态同样会带回 bot_token，此时按 confirmed 归一化，让上层绑定流程统一走一条路径。</p>
     */
    static Map<String, String> parseQrStatus(String resp) {
        ONode root = ONode.ofJson(resp);
        int ret = root.get("ret").getInt();
        String status = root.get("status").getString();

        Map<String, String> result = new LinkedHashMap<>();
        // 即使 ret != 0 也尝试提取 status（某些过渡状态可能 ret 非零但 status 存在）
        result.put("status", status != null ? status : (ret == 0 ? "wait" : "unknown"));

        if (ret == 0) {
            String botToken = root.get("bot_token").getString();
            if (botToken != null && !botToken.isEmpty()) {
                result.put("bot_token", botToken);
                result.put("ilink_bot_id", root.get("ilink_bot_id").getString());
                result.put("ilink_user_id", root.get("ilink_user_id").getString());

                // 服务端指派的专属接入点：baseurl 优先，其次 redirect_host
                String baseUrl = normalizeBaseUrl(root.get("baseurl").getString());
                if (baseUrl == null) {
                    baseUrl = normalizeBaseUrl(root.get("redirect_host").getString());
                }
                if (baseUrl != null) {
                    result.put("baseurl", baseUrl);
                }

                // 拿到凭据即视为绑定完成（confirmed / binded_redirect 等）
                result.put("status", "confirmed");
            }
        } else if (!KNOWN_QR_STATUS.contains(result.get("status"))) {
            // 完全不认识的响应，标记为 unknown
            result.put("status", "unknown");
        }
        return result;
    }

    /**
     * 规范化接入点地址：补全协议头、去掉尾部斜杠，并做域名白名单校验。
     *
     * @return 合法则返回规范化后的地址；为空或不可信返回 null（由调用方回落默认接入点）
     */
    static String normalizeBaseUrl(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;

        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            // baseurl 可能只给主机名
            s = "https://" + s;
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }

        String host;
        try {
            host = new java.net.URI(s).getHost();
        } catch (Exception e) {
            LOG.warn("Ignored malformed baseurl from server: {}", raw);
            return null;
        }
        if (host == null || host.isEmpty()) {
            LOG.warn("Ignored malformed baseurl from server: {}", raw);
            return null;
        }

        String lower = host.toLowerCase(Locale.ROOT);
        for (String suffix : ALLOWED_HOST_SUFFIX) {
            if (lower.endsWith(suffix)) {
                return s;
            }
        }
        LOG.warn("Ignored untrusted baseurl host from server: {}", host);
        return null;
    }

    /**
     * 长轮询获取消息更新（服务端 hold 约 35s）
     *
     * @param baseUrl       接入点，null 表示默认
     * @param botToken      Bot 鉴权 Token
     * @param getUpdatesBuf 上次游标，首次传空
     * @return {messages: [{text, from_user_id, context_token}], cursor}、{expired: true} 或 null
     */
    public static Map<String, Object> getUpdates(String baseUrl, String botToken, String getUpdatesBuf) {
        try {
            ONode body = new ONode();
            body.set("get_updates_buf", getUpdatesBuf != null ? getUpdatesBuf : "");
            // iLink 协议要求所有 POST 请求携带 base_info
            applyBaseInfo(body);

            String resp = httpPost(baseOf(baseUrl) + "/ilink/bot/getupdates", body.toJson(), botToken, TIMEOUT_LONG_POLL);
            if (resp == null) return null;

            return parseUpdates(resp);
        } catch (Exception e) {
            LOG.error("getUpdates error: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析 getupdates 响应。
     *
     * <p>与协议对齐的三点：过期码同时可能出现在 {@code ret} 与 {@code errcode}；
     * 处于生成中（message_state == 1）的消息应跳过；文本要拼接 item_list 中的所有文本片段
     * 而不是只取第一个。</p>
     */
    static Map<String, Object> parseUpdates(String resp) {
        ONode root = ONode.ofJson(resp);
        int ret = root.get("ret").getInt();
        int errcode = root.get("errcode").getInt();

        if (ret == RET_TOKEN_EXPIRED || errcode == RET_TOKEN_EXPIRED) {
            Map<String, Object> errResult = new LinkedHashMap<>();
            errResult.put("expired", true);
            return errResult;
        }

        if (ret != 0 || errcode != 0) {
            // 静默返回 null 会让长轮询无声空转，必须留下可定位的日志
            LOG.warn("getUpdates failed: ret={}, errcode={}, msg={}",
                    ret, errcode, firstNonEmpty(root.get("errmsg").getString(), root.get("msg").getString()));
            return null;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cursor", root.get("get_updates_buf").getString());

        List<Map<String, String>> messages = new ArrayList<>();
        // API 返回 msgs 而非 updates
        ONode msgs = root.get("msgs");
        if (msgs != null && msgs.isArray()) {
            for (ONode msgNode : msgs.getArray()) {
                // 只处理用户消息 (message_type == 1)
                if (msgNode.get("message_type").getInt() != 1) continue;

                // message_state: 0=NEW, 1=GENERATING, 2=FINISH。
                // 这里只排除明确"生成中"的消息：字段缺失时 getInt() 返回 0，
                // 若按 "!= 2 即跳过" 会把全部入站消息误杀。
                if (msgNode.get("message_state").getInt() == 1) continue;

                Map<String, String> msg = new LinkedHashMap<>();
                msg.put("text", extractText(msgNode));
                msg.put("from_user_id", msgNode.get("from_user_id").getString());
                msg.put("context_token", msgNode.get("context_token").getString());
                // 消息里没有 ticket 字段，typing_ticket 需从 getconfig 获取
                messages.add(msg);
            }
        }
        result.put("messages", messages);
        return result;
    }

    /**
     * 拼接 item_list 中所有文本片段。
     *
     * <p>不按 {@code type} 过滤：该字段缺失时 getInt() 会返回 0，一旦按 {@code type == 1}
     * 严格过滤就可能把正常文本整条丢掉。以"存在 text_item.text"为准更稳。</p>
     */
    private static String extractText(ONode msgNode) {
        ONode itemList = msgNode.get("item_list");
        if (itemList == null || !itemList.isArray()) {
            return "";
        }

        StringBuilder buf = new StringBuilder();
        for (ONode item : itemList.getArray()) {
            if (item == null) continue;
            String text = item.get("text_item").get("text").getString();
            if (text == null || text.isEmpty()) continue;
            if (buf.length() > 0) {
                buf.append('\n');
            }
            buf.append(text);
        }
        return buf.toString();
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && !a.isEmpty()) return a;
        return b == null ? "" : b;
    }

    /**
     * 发送文本消息
     *
     * <p>注意：iLink 协议要求严格按照以下格式构造消息体，
     * 包含 msg 包装、client_id、message_type、message_state、item_list 等字段，
     * 缺少任何一个都可能被服务端静默丢弃。</p>
     *
     * @param baseUrl      接入点，null 表示默认
     * @param botToken     Bot 鉴权 Token
     * @param toUserId     目标用户 ID
     * @param contextToken 上下文 Token（必须从入站消息获取）
     * @param text         消息文本
     * @return true 发送成功
     */
    public static boolean sendMessage(String baseUrl, String botToken, String toUserId, String contextToken, String text) {
        return sendMessage(baseUrl, botToken, toUserId, contextToken, text,
                UUID.randomUUID().toString().replace("-", ""));
    }

    /**
     * 使用调用方提供的 client_id 发送文本。
     * 同一逻辑消息发生网络重试时必须复用 client_id，避免服务端已接收而本地超时后
     * 再生成新 ID，最终在微信侧形成两个相同气泡。
     */
    static boolean sendMessage(String baseUrl, String botToken, String toUserId, String contextToken,
                               String text, String clientId) {
        try {
            ONode body = new ONode();
            ONode msg = body.getOrNew("msg");
            msg.set("from_user_id", "");
            msg.set("to_user_id", toUserId);
            msg.set("client_id", clientId);
            msg.set("message_type", 2);  // BOT
            msg.set("message_state", 2); // FINISH

            ONode itemList = msg.getOrNew("item_list").asArray();
            ONode item = new ONode();
            item.set("type", 1); // TEXT
            item.getOrNew("text_item").set("text", text);
            itemList.add(item);

            msg.set("context_token", contextToken);

            // 所有 POST 请求需要 base_info
            applyBaseInfo(body);

            String resp = httpPost(baseOf(baseUrl) + "/ilink/bot/sendmessage", body.toJson(), botToken, TIMEOUT_SEND);
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
     * @param baseUrl      接入点，null 表示默认
     * @param botToken     Bot 鉴权 Token
     * @param ilinkUserId  用户 ID
     * @param contextToken 上下文 Token
     * @return typing_ticket 或 null
     */
    public static String getConfig(String baseUrl, String botToken, String ilinkUserId, String contextToken) {
        try {
            ONode body = new ONode();
            body.set("ilink_user_id", ilinkUserId);
            body.set("context_token", contextToken);
            applyBaseInfo(body);

            String resp = httpPost(baseOf(baseUrl) + "/ilink/bot/getconfig", body.toJson(), botToken, TIMEOUT_QUICK);
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
     * @param baseUrl      接入点，null 表示默认
     * @param botToken     Bot 鉴权 Token
     * @param ilinkUserId  用户 ID
     * @param typingTicket 从 getconfig 获取的 ticket
     * @param status       1 开始输入, 2 停止输入
     * @return true 发送成功
     */
    public static boolean sendTyping(String baseUrl, String botToken, String ilinkUserId, String typingTicket, int status) {
        try {
            ONode body = new ONode();
            body.set("ilink_user_id", ilinkUserId);
            body.set("typing_ticket", typingTicket);
            body.set("status", status);  // 1=开始, 2=停止
            applyBaseInfo(body);

            String resp = httpPost(baseOf(baseUrl) + "/ilink/bot/sendtyping", body.toJson(), botToken, TIMEOUT_QUICK);
            if (resp == null) return false;

            ONode root = ONode.ofJson(resp);
            return root.get("ret").getInt() == 0;
        } catch (Exception e) {
            LOG.error("sendTyping error: {}", e.getMessage());
            return false;
        }
    }

    // ==================== HTTP 工具方法 ====================

    private static void applyBaseInfo(ONode body) {
        body.getOrNew("base_info").set("channel_version", CHANNEL_VERSION);
    }

    /**
     * 接入点回落：null / 空 / 不可信一律用默认地址
     */
    private static String baseOf(String baseUrl) {
        String normalized = normalizeBaseUrl(baseUrl);
        return normalized != null ? normalized : DEFAULT_BASE_URL;
    }

    private static String httpGet(String urlStr) throws Exception {
        HttpUtils http = HttpUtils.http(urlStr)
                .timeout(10, 10, 15)
                .header(HEADER_CONTENT_TYPE, "application/json")
                .header(HEADER_UIN, generateUin())
                .header("iLink-App-ClientVersion", "1");
        ProxyConfig.applyIfNeeded(http);

        try (HttpResponse resp = http.exec("GET")) {
            int code = resp.code();
            if (code != 200) {
                LOG.warn("HTTP GET {} returned {}", endpointOf(urlStr), code);
                // 抛出而非返回 null：让调用方能拿到状态码，区分网关拦截与业务失败。
                // 只带 endpoint 不带 query，避免 qrcode 令牌进入日志与前端提示
                throw new IOException("HTTP " + code + " from " + endpointOf(urlStr));
            }
            return resp.bodyAsString();
        }
    }

    private static String httpPost(String urlStr, String jsonBody, String botToken, int readTimeoutSec) throws Exception {
        HttpUtils http = HttpUtils.http(urlStr)
                .timeout(10, 10, readTimeoutSec)
                .header(HEADER_CONTENT_TYPE, "application/json")
                .header(HEADER_AUTH_TYPE, "ilink_bot_token")
                .header(HEADER_AUTH, "Bearer " + botToken)
                .header(HEADER_UIN, generateUin());
        ProxyConfig.applyIfNeeded(http);

        if (jsonBody != null && !jsonBody.isEmpty()) {
            http.bodyOfJson(jsonBody);
        }

        try (HttpResponse resp = http.exec("POST")) {
            int code = resp.code();
            if (code != 200) {
                LOG.warn("HTTP POST {} returned {}", endpointOf(urlStr), code);
                return null;
            }
            return resp.bodyAsString();
        }
    }

    /**
     * X-WECHAT-UIN：随机 uint32 的十进制字符串再做 base64，每次请求重新生成
     */
    private static String generateUin() {
        long uin = ThreadLocalRandom.current().nextLong(1L, 0xFFFFFFFFL);
        return Base64.getEncoder().encodeToString(
                String.valueOf(uin).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 截去 query 部分，只保留接口地址（避免令牌等敏感参数外泄）
     */
    private static String endpointOf(String urlStr) {
        int idx = urlStr.indexOf('?');
        return idx > 0 ? urlStr.substring(0, idx) : urlStr;
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
