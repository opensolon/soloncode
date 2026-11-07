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
package org.noear.solon.codecli.loop;

import org.noear.snack4.Feature;
import org.noear.snack4.ONode;
import org.noear.snack4.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * 一次性数据迁移：旧版每会话 {@code sessions/<id>/loop-tasks.json} → 工作区统一存储 {@code tasks.json}。
 *
 * <p>这是 soloncode 的存储历史，不属于 loop 插件。写出的 tasks.json 在 {@code LoopExtension} 挂接时
 * 由插件再拆成按会话分存的 {@code loop/<sid>.json}（旧文件改名备份或删除）。</p>
 *
 * <p><b>执行时机</b>：工作区装配期、{@code LoopExtension} 挂接之前。仅当 tasks.json 与 {@code loop/} 目录
 * 都不存在时执行；条目按原始 JSON 搬运（补 sessionId），不经任务模型解析，因此不认识的字段也不会丢。
 * 任一步失败保留现场，下次启动重试。</p>
 *
 * <p>扫完没有任何旧文件时只建空的 {@code loop/} 目录：它就是「已迁移」的标记，
 * 否则没有定时任务的工作区每次启动都会重新扫一遍会话目录。</p>
 *
 * @author noear
 * @since 4.2.0
 */
public final class LoopDataMigrator {
    private static final Logger LOG = LoggerFactory.getLogger(LoopDataMigrator.class);
    private static final String LEGACY_TASKS_FILE = "loop-tasks.json";
    /** loop 插件按会话分存的目录（与 WorkspaceManager 装配一致） */
    public static final String LOOP_DIR = "loop";

    private LoopDataMigrator() {
    }

    /**
     * @param tasksFile   工作区的 tasks.json
     * @param sessionsDir 会话目录根
     * @return 迁移的会话数（0 表示无旧数据、已迁移或失败待重试）
     */
    public static int migrate(Path tasksFile, Path sessionsDir) {
        if (tasksFile == null || sessionsDir == null) {
            return 0;
        }
        Path loopDir = tasksFile.resolveSibling(LOOP_DIR);
        if (Files.isRegularFile(tasksFile) || Files.isDirectory(loopDir) || !Files.isDirectory(sessionsDir)) {
            return 0;
        }

        try {
            ONode root = new ONode(Options.of(Feature.Write_PrettyFormat));
            root.set("version", 2);
            ONode array = root.getOrNew("tasks").asArray();

            int sessionFiles = 0;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(sessionsDir)) {
                for (Path sessionPath : stream) {
                    Path file = sessionPath.resolve(LEGACY_TASKS_FILE);
                    if (!Files.isRegularFile(file)) continue;
                    int added = appendLegacy(array, file, sessionPath.getFileName().toString());
                    if (added > 0) {
                        sessionFiles++;
                    }
                }
            }
            if (sessionFiles == 0) {
                // 没有旧数据：只建 loop/ 目录作「已迁移」标记，下次启动不再扫描
                Files.createDirectories(loopDir);
                return 0;
            }
            atomicWrite(tasksFile, root.toJson());

            // 迁移成功后删除旧文件（删除失败不影响结果，残留会被下次扫描忽略：tasks.json 已存在）
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(sessionsDir)) {
                for (Path sessionPath : stream) {
                    Files.deleteIfExists(sessionPath.resolve(LEGACY_TASKS_FILE));
                }
            }
            LOG.info("[LoopMigrator] migrated {} sessions of legacy loop tasks to tasks.json", sessionFiles);
            return sessionFiles;
        } catch (Exception e) {
            LOG.warn("[LoopMigrator] migrate legacy tasks failed (will retry next start): {}", e.getMessage());
            return 0;
        }
    }

    private static int appendLegacy(ONode array, Path file, String sessionId) {
        int added = 0;
        try {
            ONode legacy = ONode.ofJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            if (legacy == null || !legacy.isArray()) {
                return 0;
            }
            for (ONode node : legacy.getArray()) {
                if (node == null || !node.isObject() || node.getOrNull("id") == null) {
                    continue;
                }
                node.set("sessionId", sessionId);
                array.add(node);
                added++;
            }
        } catch (Exception e) {
            LOG.warn("[LoopMigrator] read legacy tasks failed {}: {}", file, e.getMessage());
        }
        return added;
    }

    private static void atomicWrite(Path target, String content) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(temp, content.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException fallback) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
