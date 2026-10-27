/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.codecli.session;

import org.noear.solon.ai.harness.session.queue.SessionQueue;
import org.noear.solon.flow.FlowContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话快照只读探测工具。
 *
 * <p>队列等会话级状态已存于 {@link FlowContext} 快照（由 {@code AgentSession} 实现负责持久化，
 * FileAgentSession 落 *.snapshot.json）。宿主在「会话尚未实例化」场景下做只读判定
 * （如启动恢复、僵尸目录清理）时，应经本工具解析快照后交给领域类判定，
 * <b>禁止绕过 AgentSession 直接读独立文件或对文件全文做字符串匹配</b>——
 * 存储介质是会话实现细节（文件/Redis/JDBC），宿主不得耦合。</p>
 *
 * <p>目录布局（FileAgentSession 约定）：sessions/&lt;sessionId&gt;/&lt;sessionId&gt;.snapshot.json。
 * 解析失败按无数据处理（快照损坏不应阻断宿主流程）。探测不实例化会话：
 * getSession 是 computeIfAbsent 语义，会把会话（含全量历史消息）常驻内存。</p>
 */
public final class SessionSnapshots {
    private static final Logger LOG = LoggerFactory.getLogger(SessionSnapshots.class);

    private SessionSnapshots() {
    }

    /**
     * 会话目录下的快照是否携带排队任务。
     *
     * <p>只读探测专用：解析快照 JSON 为 {@link FlowContext} 后经
     * {@link SessionQueue#hasPersistedItems(FlowContext)} 判定。
     * 会话已在内存时应直接用 {@code getSessionIfPresent} + {@link SessionQueue#hasItems}。</p>
     */
    public static boolean hasQueue(Path sessionDir, String sessionId) {
        FlowContext context = readSnapshot(sessionDir, sessionId);
        return context != null && SessionQueue.hasPersistedItems(context);
    }

    /** 只读解析会话目录下的快照为 FlowContext；无快照或解析失败返回 null。 */
    private static FlowContext readSnapshot(Path sessionDir, String sessionId) {
        if (sessionDir == null || sessionId == null || sessionId.isEmpty()) return null;
        Path file = sessionDir.resolve(sessionId + ".snapshot.json");
        if (!Files.isRegularFile(file)) return null;
        try {
            return FlowContext.fromJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        } catch (Throwable e) {
            LOG.debug("[SessionSnapshots] read snapshot failed for {}: {}", sessionId, e.toString());
            return null;
        }
    }
}
