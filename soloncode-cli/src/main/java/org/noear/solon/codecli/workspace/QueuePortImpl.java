/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.codecli.workspace;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.command.builtin.QueuePort;
import org.noear.solon.ai.harness.session.queue.SessionQueue;

/**
 * 排队端口适配器：把 harness 的 {@link QueuePort} 委托到本地 SessionQueue。
 *
 * <p>存储定位遵循同源约束：只从 {@link HarnessEngine#getWorkspace()} 解析会话
 * 数据目录，与 CliShell、WorkspaceManager 等入口写入同一份 queue-tasks.json。</p>
 */
public class QueuePortImpl implements QueuePort {
    @Override
    public void bindStorage(HarnessEngine engine, AgentSession session) {
        SessionQueue.bindStorage(session, WorkspaceDataUtil.sessionsPath(engine.getWorkspace()).resolve(session.getSessionId()));
    }

    @Override
    public int enqueue(AgentSession session, String text, String source,
                       String sourceUserId, String replyTarget, String messageId) {
        return SessionQueue.enqueue(session, text, source, sourceUserId, replyTarget, messageId);
    }

    @Override
    public int maxQueueSize() {
        return SessionQueue.MAX_QUEUE_SIZE;
    }

    @Override
    public int maxTextLength() {
        return SessionQueue.MAX_TEXT_LENGTH;
    }

    @Override
    public boolean cancelPending(AgentSession session) {
        return SessionQueue.cancelPending(session);
    }
}
