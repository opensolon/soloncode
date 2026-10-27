/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.codecli.api.web;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.session.queue.SessionQueue;
import org.noear.snack4.ONode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 旧版队列文件（queue-tasks.json / queue.json）的一次性迁移工具。
 *
 * <p>队列已改为「会话上下文 + 快照即持久化」，不再写独立文件。本类只在进程启动恢复
 * 队列时发现旧文件的情况下读取导入：解析后经 {@link SessionQueue#importItems} 入队、
 * updateSnapshot 落盘，随后删除旧文件，避免下次重复导入。迁移失败时保留文件，
 * 仅告警不阻断恢复流程。两个大版本后可整体删除。</p>
 *
 * @deprecated 遗留数据迁移用途，随旧文件淘汰后移除
 */
@Deprecated
public final class LegacyQueueMigrator {
    private static final Logger LOG = LoggerFactory.getLogger(LegacyQueueMigrator.class);

    private LegacyQueueMigrator() { }

    /** 读取旧文件导入队列并删除；无旧文件时为空操作。 */
    public static void migrate(AgentSession session, Path sessionDir) {
        if (session == null || sessionDir == null) return;
        try {
            List<Map<String, Object>> rows = readRows(sessionDir.resolve("queue-tasks.json"));
            if (rows == null) return; // 无旧文件
            rows.addAll(readRowsOrEmpty(sessionDir.resolve("queue.json")));
            int imported = SessionQueue.importItems(session, rows);
            Files.deleteIfExists(sessionDir.resolve("queue-tasks.json"));
            Files.deleteIfExists(sessionDir.resolve("queue.json"));
            if (imported > 0) {
                LOG.info("[WebGate] migrated {} legacy queue item(s) into session snapshot for {}",
                        imported, session.getSessionId());
            }
        } catch (Throwable e) {
            LOG.warn("[WebGate] legacy queue migration failed for session {}: {}",
                    session.getSessionId(), e.toString());
        }
    }

    /** 解析 {items:[...], inFlight:{...}}；文件不存在返回 null。 */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> readRows(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return null;
        ONode root = ONode.ofJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        List<Map<String, Object>> rows = new ArrayList<>();
        ONode nodes = root == null ? null : root.get("items");
        if (nodes != null && nodes.isArray()) {
            for (ONode node : nodes.getArray()) {
                if (node != null && node.isObject()) rows.add((Map<String, Object>) node.toBean(Map.class));
                else if (node != null && node.isValue()) {
                    // 旧版 Web 队列的纯文本项
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("text", node.getString());
                    rows.add(row);
                }
            }
        }
        ONode inFlight = root == null ? null : root.get("inFlight");
        if (inFlight != null && inFlight.isObject()) {
            Map<String, Object> row = (Map<String, Object>) inFlight.toBean(Map.class);
            row.put("status", "RUNNING"); // importItems 会重置为 PENDING 并置于队首
            rows.add(0, row);
        }
        return rows;
    }

    private static List<Map<String, Object>> readRowsOrEmpty(Path file) throws IOException {
        List<Map<String, Object>> rows = readRows(file);
        return rows == null ? new ArrayList<>() : rows;
    }
}
