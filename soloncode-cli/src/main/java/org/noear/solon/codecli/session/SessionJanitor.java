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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 会话目录清理器：僵尸 web 会话 + 过期的子代理任务会话。
 *
 * <p>子代理任务会话（目录名满足 {@link SessionIdOwnership#isSubtask} 的完整格式；仅前缀相同的目录如
 * {@code task-board} 不动）存在父会话目录之下（{@code sessions/<父>/subtasks/<taskId>/}，见 {@link SessionManager}），
 * 随父会话目录一并删除；另按过期兜底：最近修改超过 {@link #SUBTASK_TTL_MS} 即删（快照原子写会刷新目录修改时间），
 * 避免长期不删的会话（如 CLI 的固定会话）下子会话越积越多；{@code subtasks/} 清空后一并删掉。
 * 旧版本平铺在 sessions 下的子任务会话同样按过期清理。</p>
 *
 * <p>web.html 新建对话时会先创建会话目录（含 _meta.json，位于 ~/.soloncode/workspaces/&lt;标识&gt;/sessions/），
 * 用户未发消息即切走就会留下无实质内容的空壳目录，且会一直被 sessions 列表扫描。
 * 本清理器只处理 {@code web-} 前缀目录，判定"无实质内容"标准：</p>
 * <ul>
 *   <li>不存在任何 {@code *.messages.ndjson} 消息文件；</li>
 *   <li>会话快照无排队任务投影（经 {@link SessionSnapshots} 基于上下文判定；
 *       迁移前的旧版队列文件也不得误删）；</li>
 *   <li>meta 无自定义标题且未置顶（{@link SessionMeta#isEmpty()}）。</li>
 * </ul>
 *
 * <p>安全约束：浅层检查 + 逐文件删除，失败静默跳过待下次重试；
 * 最近修改时间在 {@link #FRESH_WINDOW_MS} 内的目录视为"正在创建中"，跳过不删。</p>
 *
 * @author noear
 */
public class SessionJanitor {
    private static final Logger LOG = LoggerFactory.getLogger(SessionJanitor.class);

    /** web 端会话目录前缀 */
    public static final String WEB_SESSION_PREFIX = "web-";
    /** 新鲜度保护窗口：最近修改在窗口内的目录可能正在被写入，暂不清理 */
    private static final long FRESH_WINDOW_MS = 10 * 60 * 1000L;
    /** 子代理任务会话保留时长（过期后不能再续跑，也不能回放子过程） */
    public static final long SUBTASK_TTL_MS = TimeUnit.DAYS.toMillis(7);

    private SessionJanitor() {
    }

    /**
     * 清理指定 sessions 根目录下的僵尸 web 会话目录与过期子任务会话目录（启动时、每次列会话时调用）。
     *
     * @param sessionsRoot sessions 根目录（~/.soloncode/workspaces/&lt;标识&gt;/sessions/）
     * @return 被清理的会话 ID 列表（嵌套的子任务会话记为 {@code <父会话>/<taskId>}；无删除或目录不存在时为空列表）
     */
    public static List<String> cleanSessions(Path sessionsRoot) {
        List<String> removed = new ArrayList<>();
        if (sessionsRoot == null || !Files.isDirectory(sessionsRoot)) {
            return removed;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(sessionsRoot)) {
            for (Path dir : stream) {
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                String name = dir.getFileName().toString();
                if (SessionIdOwnership.isSubtask(name) == false) {
                    cleanNestedSubtasks(dir, name, removed);
                }
                boolean expired;
                if (name.startsWith(WEB_SESSION_PREFIX)) {
                    expired = isZombieWebSession(dir);
                } else if (SessionIdOwnership.isSubtask(name)) {
                    expired = isExpiredSubtaskSession(dir);
                } else {
                    continue;
                }
                if (expired && deleteDirectory(dir)) {
                    removed.add(name);
                }
            }
        } catch (Exception e) {
            LOG.warn("[SessionJanitor] Failed to scan sessions root {}: {}", sessionsRoot, e.getMessage());
        }
        return removed;
    }

    /** 清理一个会话目录下过期的子任务会话；{@code subtasks/} 已空时一并删掉（非空删除失败即放弃，不影响并发新建） */
    private static void cleanNestedSubtasks(Path sessionDir, String sessionId, List<String> removed) {
        Path subtasks = sessionDir.resolve(SessionManager.SUBTASKS_DIR);
        if (Files.isDirectory(subtasks) == false) {
            return;
        }

        boolean empty = true;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(subtasks)) {
            for (Path dir : stream) {
                String name = dir.getFileName().toString();
                if (Files.isDirectory(dir) && SessionIdOwnership.isSubtask(name)
                        && isExpiredSubtaskSession(dir) && deleteDirectory(dir)) {
                    removed.add(sessionId + "/" + name);
                } else {
                    empty = false;
                }
            }
        } catch (Exception e) {
            LOG.warn("[SessionJanitor] Failed to scan subtasks of {}: {}", sessionDir, e.getMessage());
            return;
        }

        if (empty) {
            try {
                Files.deleteIfExists(subtasks);
            } catch (Exception e) {
                LOG.debug("[SessionJanitor] Skip remove subtasks dir: {} ({})", subtasks, e.getMessage());
            }
        }
    }

    /** 子任务会话目录是否已过期（最近修改超过 {@link #SUBTASK_TTL_MS}） */
    static boolean isExpiredSubtaskSession(Path sessionDir) {
        try {
            return System.currentTimeMillis() - Files.getLastModifiedTime(sessionDir).toMillis() > SUBTASK_TTL_MS;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 判定单个 web 会话目录是否为僵尸目录（无消息、无排队任务、meta 空、非新鲜）。
     */
    public static boolean isZombieWebSession(Path sessionDir) {
        try {
            String sessionId = sessionDir.getFileName() == null ? null : sessionDir.getFileName().toString();

            // 新鲜度保护：可能正在创建/写入中
            long lastModified = Files.getLastModifiedTime(sessionDir).toMillis();
            if (System.currentTimeMillis() - lastModified < FRESH_WINDOW_MS) {
                return false;
            }

            // 有消息文件 → 有实质内容
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(sessionDir, "*.messages.ndjson")) {
                for (Path ignored : stream) {
                    return false;
                }
            }

            // 有排队任务（会话快照投影）→ 有实质内容；迁移前的旧版队列文件也不得误删。
            if (Files.isRegularFile(sessionDir.resolve("queue-tasks.json"))
                    || Files.isRegularFile(sessionDir.resolve("queue.json"))) {
                return false;
            }
            // 经快照上下文判定（不直接耦合存储实现，见 SessionSnapshots）
            if (SessionSnapshots.hasQueue(sessionDir, sessionId)) {
                return false;
            }

            // 有 goal（会话级单槽投影）→ 有实质内容，不得当僵尸清除
            if (SessionSnapshots.hasGoal(sessionDir, sessionId)) {
                return false;
            }

            // meta 有自定义标题或已置顶 → 保留
            return SessionMeta.load(sessionDir).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 逐文件删除目录内容后删目录；任何失败都返回 false（下次再试），不抛异常。
     */
    private static boolean deleteDirectory(Path dir) {
        try {
            File[] files = dir.toFile().listFiles();
            if (files != null) {
                for (File f : files) {
                    if (!deleteRecursively(f)) {
                        return false;
                    }
                }
            }
            Files.deleteIfExists(dir);
            LOG.info("[SessionJanitor] Removed session dir: {}", dir);
            return true;
        } catch (Exception e) {
            LOG.debug("[SessionJanitor] Skip remove (in use or failed): {} ({})", dir, e.getMessage());
            return false;
        }
    }

    private static boolean deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File c : children) {
                    if (!deleteRecursively(c)) {
                        return false;
                    }
                }
            }
        }
        try {
            return file.delete();
        } catch (Exception e) {
            return false;
        }
    }
}
