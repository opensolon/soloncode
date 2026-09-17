/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.codecli.channel.wechat;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 微信 iLink 协议响应解析单元测试（纯离线，不发起任何网络请求）
 *
 * @author soloncode 2026/9/6 created
 */
class WeChatClientParseTest {

    // ===== getupdates 解析 =====

    @Test
    void parseUpdates_shouldExtractCursorAndUserMessage() {
        String json = "{\"ret\":0,\"get_updates_buf\":\"CURSOR_1\",\"msgs\":["
                + "{\"message_type\":1,\"message_state\":2,\"from_user_id\":\"u1@im.bot\","
                + "\"context_token\":\"ctx-1\",\"item_list\":[{\"type\":1,\"text_item\":{\"text\":\"你好\"}}]}"
                + "]}";

        Map<String, Object> result = WeChatClient.parseUpdates(json);

        assertNotNull(result);
        assertEquals("CURSOR_1", result.get("cursor"));

        List<Map<String, String>> messages = castMessages(result);
        assertEquals(1, messages.size());
        assertEquals("你好", messages.get(0).get("text"));
        assertEquals("u1@im.bot", messages.get(0).get("from_user_id"));
        assertEquals("ctx-1", messages.get(0).get("context_token"));
    }

    @Test
    void parseUpdates_shouldConcatAllTextItems() {
        // 原实现只取 item_list[0]，多片段文本会被截断
        String json = "{\"ret\":0,\"get_updates_buf\":\"C\",\"msgs\":["
                + "{\"message_type\":1,\"from_user_id\":\"u1\",\"context_token\":\"c\",\"item_list\":["
                + "{\"type\":1,\"text_item\":{\"text\":\"第一段\"}},"
                + "{\"type\":1,\"text_item\":{\"text\":\"第二段\"}}]}"
                + "]}";

        List<Map<String, String>> messages = castMessages(WeChatClient.parseUpdates(json));

        assertEquals(1, messages.size());
        assertEquals("第一段\n第二段", messages.get(0).get("text"));
    }

    @Test
    void parseUpdates_shouldSkipBotMessages() {
        String json = "{\"ret\":0,\"get_updates_buf\":\"C\",\"msgs\":["
                + "{\"message_type\":2,\"from_user_id\":\"bot\",\"context_token\":\"c\","
                + "\"item_list\":[{\"type\":1,\"text_item\":{\"text\":\"机器人自己的消息\"}}]}"
                + "]}";

        assertTrue(castMessages(WeChatClient.parseUpdates(json)).isEmpty());
    }

    @Test
    void parseUpdates_shouldSkipGeneratingMessageOnly() {
        // message_state == 1 (GENERATING) 跳过
        String generating = "{\"ret\":0,\"get_updates_buf\":\"C\",\"msgs\":["
                + "{\"message_type\":1,\"message_state\":1,\"from_user_id\":\"u\",\"context_token\":\"c\","
                + "\"item_list\":[{\"type\":1,\"text_item\":{\"text\":\"半成品\"}}]}]}";
        assertTrue(castMessages(WeChatClient.parseUpdates(generating)).isEmpty());

        // message_state 缺失时不能误杀：缺字段会被解析成 0，按 "!=2 即跳过" 会丢掉全部入站消息
        String missingState = "{\"ret\":0,\"get_updates_buf\":\"C\",\"msgs\":["
                + "{\"message_type\":1,\"from_user_id\":\"u\",\"context_token\":\"c\","
                + "\"item_list\":[{\"type\":1,\"text_item\":{\"text\":\"正常消息\"}}]}]}";
        assertEquals(1, castMessages(WeChatClient.parseUpdates(missingState)).size());
    }

    @Test
    void parseUpdates_shouldDetectExpiredFromRetAndErrcode() {
        Map<String, Object> byRet = WeChatClient.parseUpdates("{\"ret\":-14}");
        assertNotNull(byRet);
        assertEquals(Boolean.TRUE, byRet.get("expired"));

        // 部分实现把过期码放在 errcode 上
        Map<String, Object> byErrcode = WeChatClient.parseUpdates("{\"ret\":0,\"errcode\":-14}");
        assertNotNull(byErrcode);
        assertEquals(Boolean.TRUE, byErrcode.get("expired"));
    }

    @Test
    void parseUpdates_shouldReturnNullOnOtherFailure() {
        assertNull(WeChatClient.parseUpdates("{\"ret\":-1,\"errmsg\":\"bad request\"}"));
        assertNull(WeChatClient.parseUpdates("{\"ret\":0,\"errcode\":40001,\"errmsg\":\"invalid token\"}"));
    }

    @Test
    void parseUpdates_shouldToleratePayloadWithoutMsgs() {
        Map<String, Object> result = WeChatClient.parseUpdates("{\"ret\":0,\"get_updates_buf\":\"C\"}");

        assertNotNull(result);
        assertEquals("C", result.get("cursor"));
        assertTrue(castMessages(result).isEmpty());
    }

    // ===== get_qrcode_status 解析 =====

    @Test
    void parseQrStatus_confirmedShouldCarryCredentials() {
        String json = "{\"ret\":0,\"status\":\"confirmed\",\"bot_token\":\"tk\","
                + "\"ilink_bot_id\":\"bot1\",\"ilink_user_id\":\"user1\","
                + "\"baseurl\":\"https://node7.ilinkai.weixin.qq.com\"}";

        Map<String, String> result = WeChatClient.parseQrStatus(json);

        assertEquals("confirmed", result.get("status"));
        assertEquals("tk", result.get("bot_token"));
        assertEquals("bot1", result.get("ilink_bot_id"));
        assertEquals("user1", result.get("ilink_user_id"));
        assertEquals("https://node7.ilinkai.weixin.qq.com", result.get("baseurl"));
    }

    @Test
    void parseQrStatus_redirectStateWithTokenShouldNormalizeToConfirmed() {
        // 2.x 的 binded_redirect 同样带回凭据，若不归一化会卡在"未知状态"
        String json = "{\"ret\":0,\"status\":\"binded_redirect\",\"bot_token\":\"tk\","
                + "\"ilink_bot_id\":\"b\",\"ilink_user_id\":\"u\",\"redirect_host\":\"node9.weixin.qq.com\"}";

        Map<String, String> result = WeChatClient.parseQrStatus(json);

        assertEquals("confirmed", result.get("status"));
        assertEquals("https://node9.weixin.qq.com", result.get("baseurl"));
    }

    @Test
    void parseQrStatus_waitingStatesShouldPassThrough() {
        assertEquals("wait", WeChatClient.parseQrStatus("{\"ret\":0,\"status\":\"wait\"}").get("status"));
        assertEquals("scaned", WeChatClient.parseQrStatus("{\"ret\":0,\"status\":\"scaned\"}").get("status"));
        // ret != 0 但状态是已知过渡态时应保留，不能当成 unknown 打断流程
        assertEquals("need_verifycode",
                WeChatClient.parseQrStatus("{\"ret\":-5,\"status\":\"need_verifycode\"}").get("status"));
        assertEquals("unknown",
                WeChatClient.parseQrStatus("{\"ret\":-5,\"status\":\"whatever\"}").get("status"));
    }

    // ===== baseurl 规范化与白名单 =====

    @Test
    void normalizeBaseUrl_shouldCompleteSchemeAndTrimSlash() {
        assertEquals("https://a.weixin.qq.com", WeChatClient.normalizeBaseUrl("a.weixin.qq.com"));
        assertEquals("https://a.weixin.qq.com", WeChatClient.normalizeBaseUrl("https://a.weixin.qq.com/"));
        assertEquals("https://a.qq.com:8443", WeChatClient.normalizeBaseUrl(" https://a.qq.com:8443 "));
    }

    @Test
    void normalizeBaseUrl_shouldRejectUntrustedOrBrokenHost() {
        assertNull(WeChatClient.normalizeBaseUrl(null));
        assertNull(WeChatClient.normalizeBaseUrl("  "));
        // 接入点来自服务端响应，一旦被替换就会把 bot_token 送往该地址，故做后缀收敛
        assertNull(WeChatClient.normalizeBaseUrl("https://evil.example.com"));
        assertNull(WeChatClient.normalizeBaseUrl("http://not a host/"));
    }

    // ===== markdown 清理 =====

    @Test
    void cleanMarkdown_shouldKeepCodeBodyAndDropFence() {
        String reply = "看这段：\n```java\nint a = 1;\n```\n结束";

        String cleaned = WeChatLink.cleanMarkdown(reply);

        // 原实现整段删除代码块，代码类回复在微信端会残缺
        assertTrue(cleaned.contains("int a = 1;"), cleaned);
        assertFalse(cleaned.contains("```"), cleaned);
        assertTrue(cleaned.startsWith("看这段："), cleaned);
        assertTrue(cleaned.endsWith("结束"), cleaned);
    }

    @Test
    void cleanMarkdown_shouldStripInlineMarks() {
        assertEquals("加粗 斜体 代码", WeChatLink.cleanMarkdown("**加粗** *斜体* `代码`"));
        assertEquals("", WeChatLink.cleanMarkdown(null));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> castMessages(Map<String, Object> result) {
        assertNotNull(result);
        return (List<Map<String, String>>) result.get("messages");
    }
}
