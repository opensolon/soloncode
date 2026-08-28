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
package org.noear.solon.codecli.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.UserMessage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * ndjson 行正文读取的兼容性测试。
 *
 * <p>核心风险是「写侧字段布局变了、读侧没跟上」：助手消息自 solon-ai 4.1 起落 text/thinking，
 * 不再落 content，只认 content 的读侧会把整条消息判为无内容跳过（Web 历史里最后一条 AI 回答消失）。</p>
 */
public class MessageLineUtilTest {
    /* 真实的写入路径，避免测试用手写 JSON 假装写侧形态。 */
    private ONode toNode(ChatMessage msg) {
        return ONode.ofJson(ChatMessage.toJson(msg));
    }

    @Test
    @DisplayName("新版助手消息：content 键缺失，正文取 text")
    public void assistant_newFormat() {
        ONode node = toNode(new AssistantMessage("最终回答", "想一下", false));

        // 定点确认写侧形态：真的没有 content 键（本用例的前提）
        assertEquals(true, node.get("content").isNull() || node.get("content").getString() == null);
        assertEquals("最终回答", MessageLineUtil.readContent(node));
    }

    @Test
    @DisplayName("末帧停在推理通道且正文为空：退回想法，不让整条消息消失")
    public void assistant_thinkingOnly() {
        ONode node = toNode(new AssistantMessage("", "只有想法", true));
        assertEquals("只有想法", MessageLineUtil.readContent(node));
    }

    @Test
    @DisplayName("旧数据：content 键仍在，优先取它")
    public void assistant_legacyContent() {
        ONode node = ONode.ofJson("{\"role\":\"ASSISTANT\",\"content\":\"旧的正文\"}");
        assertEquals("旧的正文", MessageLineUtil.readContent(node));
    }

    @Test
    @DisplayName("用户消息：content 仍为正式字段")
    public void user_content() {
        ONode node = toNode(ChatMessage.ofUser("你好"));
        assertEquals("你好", MessageLineUtil.readContent(node));
    }

    @Test
    @DisplayName("工具消息：content 仍为正式字段")
    public void tool_content() {
        ONode node = toNode(ChatMessage.ofTool("ok", "read", "call_1"));
        assertEquals("ok", MessageLineUtil.readContent(node));
    }

    @Test
    @DisplayName("三者皆空：返回 null（调用方据此跳过该行）")
    public void allEmpty() {
        ONode node = ONode.ofJson("{\"role\":\"ASSISTANT\"}");
        assertEquals(null, MessageLineUtil.readContent(node));
        assertNotNull(node);
    }

    // ===== 内存消息对象路径（会话已打开时历史接口改从 session 取数）=====

    @Test
    @DisplayName("内存助手消息：正常取 text")
    public void memory_assistantText() {
        assertEquals("最终回答", MessageLineUtil.readContent(new AssistantMessage("最终回答", "想一下", false)));
    }

    @Test
    @DisplayName("内存助手消息：末帧停在推理通道且非 thinking 帧，getContent() 不回退，须补回想法")
    public void memory_assistantThinkingFallback() {
        AssistantMessage msg = new AssistantMessage("", "只有想法", false);

        // 定点确认本用例的前提：直接取 getContent() 拿不到正文
        assertEquals("", msg.getContent());
        assertEquals("只有想法", MessageLineUtil.readContent(msg));
    }

    @Test
    @DisplayName("内存用户消息：取 content")
    public void memory_userContent() {
        assertEquals("你好", MessageLineUtil.readContent(ChatMessage.ofUser("你好")));
    }

    @Test
    @DisplayName("内存消息为空对象：null 入参不抛异常")
    public void memory_nullMessage() {
        assertEquals(null, MessageLineUtil.readContent((ChatMessage) null));
    }

    @Test
    @DisplayName("两条读取路径同构：同一条消息走内存与走 ndjson 得到相同正文")
    public void memory_fileParity() {
        ChatMessage[] samples = new ChatMessage[]{
                new AssistantMessage("最终回答", "想一下", false),
                new AssistantMessage("", "只有想法", false),
                new AssistantMessage("", "只有想法", true),
                ChatMessage.ofUser("你好")
        };

        for (ChatMessage msg : samples) {
            assertEquals(MessageLineUtil.readContent(toNode(msg)), MessageLineUtil.readContent(msg),
                    "读法不一致：" + ChatMessage.toJson(msg));
        }
    }

    @Test
    @DisplayName("UserMessage 仍以 content 为正式字段（内存侧）")
    public void memory_userMessageType() {
        UserMessage msg = (UserMessage) ChatMessage.ofUser("带附件的提问");
        assertEquals("带附件的提问", MessageLineUtil.readContent(msg));
    }
}
