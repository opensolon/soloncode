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

import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.core.util.Assert;

/**
 * 历史消息的正文读取工具（覆盖两种形态：{@code *.messages.ndjson} 单行 JSON、内存中的 {@link ChatMessage}）。
 *
 * <p>历史消息是按<b>字段</b>序列化的（{@code ONode.ofBean}），所以读侧要跟着消息类的字段布局走。
 * {@code AssistantMessage} 自 solon-ai 4.1 起把正文与想法拆成了 {@code text} / {@code thinking}
 * 两个字段，原来的 {@code content} 字段降级为「旧数据反序列化兼容」—— 新写入的助手消息行里
 * <b>根本没有 content 键</b>。读侧若只认 content，助手消息会被整条判为无内容而跳过，
 * 表现就是 Web 历史里最后那条 AI 回答凭空消失（用户消息不受影响：
 * {@code UserMessage} 仍以 content 为正式字段）。</p>
 *
 * @author noear
 */
public class MessageLineUtil {
    private MessageLineUtil() {
    }

    /**
     * 读取一行消息的正文。
     *
     * <p>取值顺序 content → text → thinking，与 {@code AssistantMessage.getContent()} 的语义一致：
     * 末帧停在推理通道时正文可能为空（模型把答案写进了 reasoning 通道），此时退回想法，
     * 至少不让整条消息在历史里消失。</p>
     *
     * @param node 单行消息的 JSON
     * @return 正文；三者皆空时返回 {@code null}
     */
    public static String readContent(ONode node) {
        if (node == null) {
            return null;
        }

        String content = node.get("content").getString();
        if (Assert.isNotEmpty(content)) {
            return content;
        }

        String text = node.get("text").getString();
        if (Assert.isNotEmpty(text)) {
            return text;
        }

        return node.get("thinking").getString();
    }

    /**
     * 读取内存消息对象的正文。
     *
     * <p>与 {@link #readContent(ONode)} 语义对齐：{@code AssistantMessage.getContent()} 在非 thinking 帧
     * 只回 {@code text}，<b>不会</b>回退到 {@code thinking}。末帧停在推理通道（模型把答案写进 reasoning）时
     * 正文为空，此处补回想法，避免该条消息在历史里整条消失 —— 与读 ndjson 时的 content → text → thinking
     * 取值顺序保持一致。</p>
     *
     * @param msg 消息对象
     * @return 正文；无正文时返回 {@code null}
     */
    public static String readContent(ChatMessage msg) {
        if (msg == null) {
            return null;
        }

        String content = msg.getContent();
        if (Assert.isNotEmpty(content)) {
            return content;
        }

        if (msg instanceof AssistantMessage) {
            return ((AssistantMessage) msg).getThinkingRaw();
        }

        return content;
    }
}
