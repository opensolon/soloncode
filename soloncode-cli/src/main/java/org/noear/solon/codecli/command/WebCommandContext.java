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
package org.noear.solon.codecli.command;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.input.BufferedCommandContext;

import java.util.List;

/**
 * Web/IM 命令执行上下文
 * <p>
 * 回执缓冲、任务意图记录等通用能力已由 {@link BufferedCommandContext} 提供，
 * 这里只补充渠道元数据（source / sourceUserId / replyTarget / messageId），
 * 供 /steer、/queue 等命令透传到排队与回执链路。
 *
 * @author noear
 * @since 2026.4.28
 */
public class WebCommandContext extends BufferedCommandContext {
    private final String source;
    private final String sourceUserId;
    private final String replyTarget;
    private final String messageId;

    public WebCommandContext(AgentSession session,
                             String rawInput, String commandName, List<String> args) {
        this(session, null, null, null, null, rawInput, commandName, args);
    }

    public WebCommandContext(AgentSession session, String source,
                             String rawInput, String commandName, List<String> args) {
        this(session, source, null, null, null, rawInput, commandName, args);
    }

    public WebCommandContext(AgentSession session, String source, String sourceUserId,
                             String replyTarget, String messageId,
                             String rawInput, String commandName, List<String> args) {
        super(session, rawInput, commandName, args);
        this.source = source;
        this.sourceUserId = sourceUserId;
        this.replyTarget = replyTarget;
        this.messageId = messageId;
    }

    @Override
    public String getSource() {
        return source;
    }

    @Override
    public String getSourceUserId() {
        return sourceUserId;
    }

    @Override
    public String getReplyTarget() {
        return replyTarget;
    }

    @Override
    public String getMessageId() {
        return messageId;
    }
}
