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
package org.noear.solon.codecli.command.builtin;

import org.noear.snack4.Feature;
import org.noear.snack4.ONode;
import org.noear.snack4.Options;
import org.noear.solon.codecli.config.AgentFlags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;

/**
 * Loop 状态管理器 — 负责 .soloncode/loops/&lt;loopId&gt;/ 目录的创建、读写、清理。
 *
 * <p>状态目录结构：
 * <pre>
 * .soloncode/loops/&lt;loopId&gt;/
 * └── history.json         # 结构化执行历史
 * </pre>
 *
 * @author noear
 * @since 3.9.1
 */
public class LoopStateManager {
    private static final Logger LOG = LoggerFactory.getLogger(LoopStateManager.class);

    private static final String HISTORY_FILE = "history.json";
    /**
     * 获取 loop 状态目录的根路径（.soloncode/loops/）
     */
    public static Path getLoopBaseDir(String workspace) {
        return Paths.get(workspace, AgentFlags.getHarnessLoops());
    }

    /**
     * 获取指定任务的状态目录路径
     */
    public static Path getStateDir(String workspace, String loopId) {
        return Paths.get(workspace, AgentFlags.getHarnessLoops(), loopId);
    }

    /**
     * 初始化状态目录（创建目录和 history.json）
     *
     * @return 状态目录路径
     */
    public static String init(String workspace, String loopId, String prompt) {
        Path stateDir = getStateDir(workspace, loopId);
        try {
            Files.createDirectories(stateDir);

            // 创建空的 history.json
            if (!Files.exists(stateDir.resolve(HISTORY_FILE))) {
                writeFile(stateDir.resolve(HISTORY_FILE), "[]");
            }

            return stateDir.toString();
        } catch (Exception e) {
            LOG.warn("Failed to init loop state dir '{}': {}", stateDir, e.getMessage());
            return stateDir.toString();
        }
    }

    /**
     * 追加一条执行历史
     */
    public static void appendHistory(String workspace, String loopId, String result, int iteration) {
        appendHistory(workspace, loopId, result, iteration, "NONE");
    }

    /**
     * 追加一条执行历史
     */
    public static void appendHistory(String workspace, String loopId, String result, int iteration, String stopReason) {
        appendHistory(workspace, loopId, LoopExecutionResult.fromText(result), iteration, stopReason);
    }

    /**
     * 追加一条结构化执行历史（含 Goal 状态）
     */
    public static void appendHistory(String workspace, String loopId, LoopExecutionResult result, int iteration, String stopReason) {
        try {
            Path historyFile = getStateDir(workspace, loopId).resolve(HISTORY_FILE);
            if (!Files.exists(historyFile)) {
                writeFile(historyFile, "[]");
            }

            String json = new String(Files.readAllBytes(historyFile), StandardCharsets.UTF_8);
            ONode root = ONode.ofJson(json,Feature.Write_PrettyFormat);
            if (!root.isArray()) {
                root = ONode.ofJson("[]",Feature.Write_PrettyFormat);
            }

            ONode entry = new ONode();
            entry.set("iteration", iteration);
            entry.set("time", Instant.now().toString());
            entry.set("result", result != null && result.getFinalResult() != null ? result.getFinalResult() : "ok");
            if (result != null) {
                entry.set("submitted", result.isSubmitted());
                entry.set("completed", result.isCompleted());
                entry.set("goalAchieved", result.isGoalAchieved());
                if (result.getErrorMessage() != null) entry.set("error", result.getErrorMessage());
            }
            entry.set("stopReason", stopReason != null ? stopReason : "NONE");

            // ★ P0: 记录 goal 状态（如果有）
            // 由于这里没有 LoopTask 引用，goal 状态由调用方在 stopReason 中体现
            // 例如："GOAL_ACHIEVED", "BUDGET_LIMITED", "MAX_ITERATIONS_REACHED"

            root.add(entry);

            writeFile(historyFile, root.toJson());
        } catch (Exception e) {
            LOG.warn("Failed to append history for loop '{}': {}", loopId, e.getMessage());
        }
    }

    /**
     * 清理状态目录
     */
    public static void cleanup(String workspace, String loopId) {
        try {
            Path stateDir = getStateDir(workspace, loopId);
            if (Files.exists(stateDir)) {
                Files.walk(stateDir)
                        .sorted((a, b) -> b.compareTo(a)) // 先删文件再删目录
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (Exception ignored) {
                            }
                        });
            }
        } catch (Exception e) {
            LOG.warn("Failed to cleanup loop state '{}': {}", loopId, e.getMessage());
        }
    }

    // ==================== 内部工具方法 ====================

    private static void writeFile(Path file, String content) throws Exception {
        Path tempFile = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer w = new OutputStreamWriter(Files.newOutputStream(tempFile,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING),
                StandardCharsets.UTF_8)) {
            w.write(content);
        }
        Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
