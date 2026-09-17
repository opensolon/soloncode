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
package org.noear.solon.codecli.portal.printmode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * StreamInputMessage 单测：JSONL 输入行解析。
 *
 * <p>解析是纯函数，全部用例离线可重复。核心约束：单行出错只影响该行
 * （返回 MALFORMED），不允许抛异常终止整个常驻会话。</p>
 *
 * @author noear
 */
public class StreamInputMessageTest {

    // ========== 用户消息 ==========

    @Test
    @DisplayName("user 消息：content 为字符串")
    public void testUserStringContent() {
        StreamInputMessage msg = StreamInputMessage.parse(
                "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"你好\"}}");

        assertEquals(StreamInputMessage.Kind.USER, msg.getKind());
        assertEquals("你好", msg.getText());
        assertNotNull(msg.getRaw(), "raw 必须保留，--replay-user-messages 要原样回显");
    }

    @Test
    @DisplayName("user 消息：content 为 block 数组，多个 text 块换行拼接")
    public void testUserBlockArrayContent() {
        StreamInputMessage msg = StreamInputMessage.parse(
                "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":"
                        + "[{\"type\":\"text\",\"text\":\"第一行\"},{\"type\":\"text\",\"text\":\"第二行\"}]}}");

        assertEquals(StreamInputMessage.Kind.USER, msg.getKind());
        assertEquals("第一行\n第二行", msg.getText());
    }

    @Test
    @DisplayName("user 消息：非 text 块被跳过，且 detail 里报告跳过数量")
    public void testUserUnsupportedBlocksReported() {
        StreamInputMessage msg = StreamInputMessage.parse(
                "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":"
                        + "[{\"type\":\"image\",\"source\":{}}]}}");

        assertEquals(StreamInputMessage.Kind.MALFORMED, msg.getKind());
        assertTrue(msg.getDetail().contains("1 unsupported block"),
                "跳过的块数量要可见，不能静默丢弃：" + msg.getDetail());
    }

    @Test
    @DisplayName("user 消息：text 块与不支持的块混合时，取到文本即算成功")
    public void testUserMixedBlocks() {
        StreamInputMessage msg = StreamInputMessage.parse(
                "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":"
                        + "[{\"type\":\"image\",\"source\":{}},{\"type\":\"text\",\"text\":\"看图\"}]}}");

        assertEquals(StreamInputMessage.Kind.USER, msg.getKind());
        assertEquals("看图", msg.getText());
    }

    @Test
    @DisplayName("user 消息：缺 message / 缺 content / 空文本都是 MALFORMED")
    public void testUserMalformedShapes() {
        assertEquals(StreamInputMessage.Kind.MALFORMED,
                StreamInputMessage.parse("{\"type\":\"user\"}").getKind());
        assertEquals(StreamInputMessage.Kind.MALFORMED,
                StreamInputMessage.parse("{\"type\":\"user\",\"message\":{\"role\":\"user\"}}").getKind());
        assertEquals(StreamInputMessage.Kind.MALFORMED,
                StreamInputMessage.parse("{\"type\":\"user\",\"message\":{\"content\":\"   \"}}").getKind());
        assertEquals(StreamInputMessage.Kind.MALFORMED,
                StreamInputMessage.parse("{\"type\":\"user\",\"message\":{\"content\":123.5}}").getKind(),
                "content 为数字时不应被 ONode 宽松转成字符串");
    }

    // ========== 控制帧 ==========

    @Test
    @DisplayName("control_request：嵌套 request.subtype=interrupt")
    public void testControlRequestNested() {
        StreamInputMessage msg = StreamInputMessage.parse(
                "{\"type\":\"control_request\",\"request_id\":\"req-1\",\"request\":{\"subtype\":\"interrupt\"}}");

        assertEquals(StreamInputMessage.Kind.INTERRUPT, msg.getKind());
        assertEquals("req-1", msg.getRequestId());
    }

    @Test
    @DisplayName("control：扁平 subtype=interrupt（SDK async 侧的历史形态）")
    public void testControlFlat() {
        StreamInputMessage msg = StreamInputMessage.parse(
                "{\"type\":\"control\",\"request_id\":\"req-2\",\"subtype\":\"interrupt\"}");

        assertEquals(StreamInputMessage.Kind.INTERRUPT, msg.getKind());
        assertEquals("req-2", msg.getRequestId());
    }

    @Test
    @DisplayName("control_request：request_id 也可嵌在 request 内")
    public void testControlRequestIdInsideRequest() {
        StreamInputMessage msg = StreamInputMessage.parse(
                "{\"type\":\"control_request\",\"request\":{\"subtype\":\"interrupt\",\"request_id\":\"req-3\"}}");

        assertEquals(StreamInputMessage.Kind.INTERRUPT, msg.getKind());
        assertEquals("req-3", msg.getRequestId());
    }

    @Test
    @DisplayName("control_request：未知 subtype 归为 UNSUPPORTED_CONTROL 并保留 request_id")
    public void testControlUnsupportedSubtype() {
        StreamInputMessage msg = StreamInputMessage.parse(
                "{\"type\":\"control_request\",\"request_id\":\"req-9\",\"request\":{\"subtype\":\"set_model\"}}");

        assertEquals(StreamInputMessage.Kind.UNSUPPORTED_CONTROL, msg.getKind());
        assertEquals("req-9", msg.getRequestId());
        assertTrue(msg.getDetail().contains("set_model"));
    }

    @Test
    @DisplayName("control_request：缺 subtype 是 MALFORMED")
    public void testControlMissingSubtype() {
        assertEquals(StreamInputMessage.Kind.MALFORMED,
                StreamInputMessage.parse("{\"type\":\"control_request\",\"request_id\":\"r\"}").getKind());
    }

    // ========== 忽略与坏行 ==========

    @Test
    @DisplayName("空行 / 空白行被忽略")
    public void testBlankIgnored() {
        assertEquals(StreamInputMessage.Kind.IGNORED, StreamInputMessage.parse(null).getKind());
        assertEquals(StreamInputMessage.Kind.IGNORED, StreamInputMessage.parse("").getKind());
        assertEquals(StreamInputMessage.Kind.IGNORED, StreamInputMessage.parse("   \t ").getKind());
    }

    @Test
    @DisplayName("出向类型出现在入向流里（回显）被忽略，不报错")
    public void testOutboundTypesIgnored() {
        assertEquals(StreamInputMessage.Kind.IGNORED,
                StreamInputMessage.parse("{\"type\":\"assistant\",\"message\":{}}").getKind());
        assertEquals(StreamInputMessage.Kind.IGNORED,
                StreamInputMessage.parse("{\"type\":\"result\",\"result\":\"ok\"}").getKind());
        assertEquals(StreamInputMessage.Kind.IGNORED,
                StreamInputMessage.parse("{\"type\":\"system\",\"subtype\":\"init\"}").getKind());
    }

    @Test
    @DisplayName("非法 JSON / 非对象 / 缺 type 都是 MALFORMED 而不抛异常")
    public void testMalformedLines() {
        assertEquals(StreamInputMessage.Kind.MALFORMED,
                StreamInputMessage.parse("not json at all").getKind());
        assertEquals(StreamInputMessage.Kind.MALFORMED,
                StreamInputMessage.parse("[1,2,3]").getKind());
        assertEquals(StreamInputMessage.Kind.MALFORMED,
                StreamInputMessage.parse("{\"message\":{\"content\":\"x\"}}").getKind());
        assertEquals(StreamInputMessage.Kind.MALFORMED,
                StreamInputMessage.parse("{\"type\":1}").getKind(),
                "type 为数字时不应被当成合法类型名");
    }

    @Test
    @DisplayName("parse 永不返回 EOF —— EOF 只能由输入泵产生")
    public void testParseNeverReturnsEof() {
        String[] lines = {
                null, "", "  ", "garbage", "{}", "{\"type\":\"user\"}",
                "{\"type\":\"user\",\"message\":{\"content\":\"hi\"}}",
                "{\"type\":\"control_request\",\"request\":{\"subtype\":\"interrupt\"}}"
        };
        for (String line : lines) {
            assertNotEquals(StreamInputMessage.Kind.EOF, StreamInputMessage.parse(line).getKind(),
                    "line=" + line);
        }
    }
}
