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
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SessionJanitor 单元测试：僵尸目录判定与清理。
 */
public class SessionJanitorTest {
    @TempDir
    Path tmp;

    private Path mkSession(String name) throws Exception {
        Path dir = tmp.resolve(name);
        Files.createDirectories(dir);
        // 压回修改时间，避开新鲜度窗口
        Files.setLastModifiedTime(dir, FileTime.fromMillis(System.currentTimeMillis() - 60 * 60 * 1000L));
        return dir;
    }

    @Test
    @DisplayName("完全空目录 + 仅含 _meta.json 的目录被清理")
    public void cleanZombieDirs() throws Exception {
        Path empty = mkSession("web-empty");
        Path metaOnly = mkSession("web-meta-only");
        new SessionMeta().save(metaOnly);
        // 写入 meta 后目录 mtime 变新，重新压回新鲜度窗口
        Files.setLastModifiedTime(metaOnly, FileTime.fromMillis(System.currentTimeMillis() - 60 * 60 * 1000L));

        List<String> removed = SessionJanitor.cleanWebSessions(tmp);

        assertTrue(removed.contains("web-empty"));
        assertTrue(removed.contains("web-meta-only"));
        assertFalse(Files.exists(empty));
        assertFalse(Files.exists(metaOnly));
    }

    @Test
    @DisplayName("有消息 / 有排队任务 / 有标签 / 已置顶 / 非 web 前缀 / 新鲜目录 均保留")
    public void keepMeaningfulDirs() throws Exception {
        Path withMsg = mkSession("web-msg");
        Files.write(withMsg.resolve("web-msg.messages.ndjson"), "{}\n".getBytes());

        Path withQueue = mkSession("web-queue");
        Files.write(withQueue.resolve("queue-tasks.json"), "{}".getBytes());

        Path labeled = mkSession("web-labeled");
        SessionMeta.updateLabel(labeled, "重要会话");

        Path pinned = mkSession("web-pinned");
        SessionMeta.updatePinned(pinned, true);

        Path notWeb = mkSession("cli-xxx");

        Path fresh = tmp.resolve("web-fresh");
        Files.createDirectories(fresh); // 不压时间，处于新鲜窗口

        List<String> removed = SessionJanitor.cleanWebSessions(tmp);

        assertTrue(removed.isEmpty(), "不应删除任何目录: " + removed);
        for (Path p : Arrays.asList(withMsg, withQueue, labeled, pinned, notWeb, fresh)) {
            assertTrue(Files.isDirectory(p), "应保留: " + p);
        }
    }
}
