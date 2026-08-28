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

/**
 * 会话僵尸目录清理器。
 *
 * <p>web.html 新建对话时会先创建会话目录（含 _meta.json，位于 ~/.soloncode/workspaces/&lt;标识&gt;/sessions/），
 * 用户未发消息即切走就会留下无实质内容的空壳目录，且会一直被 sessions 列表扫描。
 * 本清理器只处理 {@code web-} 前缀目录，判定"无实质内容"标准：</p>
 * <ul>
 *   <li>不存在任何 {@code *.messages.ndjson} 消息文件；</li>
 *   <li>不存在 queue-tasks.json（含旧名 queue.json）有效排队任务；</li>
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

    private SessionJanitor() {
    }

    /**
     * 清理指定 sessions 根目录下的僵尸 web 会话目录。
     *
     * @param sessionsRoot sessions 根目录（~/.soloncode/workspaces/&lt;标识&gt;/sessions/）
     * @return 被清理的会话 ID 列表（无删除或目录不存在时为空列表）
     */
    public static List<String> cleanWebSessions(Path sessionsRoot) {
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
                if (!name.startsWith(WEB_SESSION_PREFIX)) {
                    continue;
                }
                if (isZombieWebSession(dir) && deleteDirectory(dir)) {
                    removed.add(name);
                }
            }
        } catch (Exception e) {
            LOG.warn("[SessionJanitor] Failed to scan sessions root {}: {}", sessionsRoot, e.getMessage());
        }
        return removed;
    }

    /**
     * 判定单个 web 会话目录是否为僵尸目录（无消息、无排队任务、meta 空、非新鲜）。
     */
    public static boolean isZombieWebSession(Path sessionDir) {
        try {
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

            // 有排队任务文件 → 有实质内容
            if (Files.isRegularFile(sessionDir.resolve("queue-tasks.json"))
                    || Files.isRegularFile(sessionDir.resolve("queue.json"))) {
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
            LOG.info("[SessionJanitor] Removed zombie web session dir: {}", dir);
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
