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
package org.noear.solon.codecli.api.desktop.controller;

import org.noear.snack4.ONode;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.annotation.Get;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Param;
import org.noear.solon.annotation.Post;
import org.noear.solon.codecli.loop.LoopScheduler;
import org.noear.solon.codecli.session.MessageLineUtil;
import org.noear.solon.codecli.session.SessionManager;
import org.noear.solon.codecli.workspace.WorkspaceDataUtil;
import org.noear.solon.codecli.api.desktop.WsGate;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Result;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Desktop 会话历史 Controller。
 *
 * <p>承载桌面端服务端会话历史的管理：分叉（fork，支持跨工作区）、消息读取
 * （messages，供前端可靠定位回退位置）、原子回退（rewind，保留前 N 条并使
 * 内存会话失效）、递归删除（delete，目录倒序清理）。前端的会话元数据仍由
 * 桌面 IndexedDB 管理，本 Controller 只操作服务端 Agent 历史文件。</p>
 *
 * <p>安全规则：仅接受数字会话 ID 与已存在的绝对工作区路径，resolve 后必须
 * 落在对应工作区 sessions 目录内（防路径穿越）；当前活跃工作区的运行中会话
 * 拒绝分叉/删除/回退。</p>
 *
 * @author bai
 */
public class DesktopSessionController extends AbstractDesktopController {
    private static final Logger LOG = LoggerFactory.getLogger(DesktopSessionController.class);

    private final WsGate wsGate;

    public DesktopSessionController(HarnessEngine engine, WsGate wsGate, LoopScheduler loopScheduler,
                                    SessionManager sessionManager) {
        super(engine, loopScheduler, sessionManager);
        this.wsGate = wsGate;
    }

    /**
     * 分叉桌面会话的 Agent 历史文件。前端的会话元数据仍由桌面 IndexedDB 管理。
     */
    @Post
    @Mapping("/desktop/chat/sessions/fork")
    public Result forkSession(Context ctx) throws Exception {
        ONode root = ONode.ofJson(ctx.body());
        String sourceId = root.get("sourceId").getString();
        String targetId = root.get("targetId").getString();
        if (!DesktopSessionIds.isValid(sourceId) || !DesktopSessionIds.isValid(targetId) || sourceId.equals(targetId)) {
            return Result.failure(400, "Invalid session id");
        }

        Path workspaceRoot;
        try {
            workspaceRoot = resolveWorkspaceRoot(root.get("workspace").getString());
        } catch (IllegalArgumentException e) {
            return Result.failure(400, "Invalid workspace");
        }
        //会话根目录按目标工作区计算（支持跨工作区分叉），防穿越由目录落在对应 sessionsRoot 内保证
        Path wsSessionsRoot = WorkspaceDataUtil.sessionsPath(workspaceRoot.toString());
        Path sourceDir = wsSessionsRoot.resolve(sourceId).normalize();
        Path targetDir = wsSessionsRoot.resolve(targetId).normalize();
        if (!sourceDir.startsWith(wsSessionsRoot) || !targetDir.startsWith(wsSessionsRoot)) {
            return Result.failure(400, "Invalid session path");
        }
        Path sourceMessages = sourceDir.resolve(sourceId + ".messages.ndjson");
        if (isActiveWorkspace(workspaceRoot) && wsGate.isSessionBusy(sourceId)) {
            return Result.failure(409, "Source session is running");
        }
        if (!Files.isRegularFile(sourceMessages) || Files.exists(targetDir)) {
            return Result.failure(404, "Source session not found or target exists");
        }

        Files.createDirectories(targetDir);
        try {
            Files.copy(sourceMessages, targetDir.resolve(targetId + ".messages.ndjson"), StandardCopyOption.COPY_ATTRIBUTES);
            Path sourceLabel = sourceDir.resolve("label.txt");
            if (Files.isRegularFile(sourceLabel)) {
                Files.copy(sourceLabel, targetDir.resolve("label.txt"), StandardCopyOption.COPY_ATTRIBUTES);
            }
            return Result.succeed(targetId);
        } catch (Exception e) {
            try {
                Files.deleteIfExists(targetDir.resolve(targetId + ".messages.ndjson"));
                Files.deleteIfExists(targetDir.resolve("label.txt"));
                Files.deleteIfExists(targetDir);
            } catch (Exception ignored) {
            }
            LOG.warn("[Desktop] Failed to fork session {}: {}", sourceId, e.getMessage());
            return Result.failure("Failed to fork session");
        }
    }

    /** 获取桌面会话的服务端消息，用于可靠定位回退位置。 */
    @Get
    @Mapping("/desktop/chat/messages")
    public Result<List<Map>> messages(@Param("sessionId") String sessionId) {
        if (!DesktopSessionIds.isValid(sessionId)) {
            return Result.failure(400, "Invalid session id");
        }

        List<Map> data = new ArrayList<>();
        Path messageFile = resolveSessionMessageFile(sessionId);
        if (!Files.isRegularFile(messageFile)) {
            return Result.succeed(data);
        }

        try {
            for (String line : Files.readAllLines(messageFile, StandardCharsets.UTF_8)) {
                if (Assert.isEmpty(line)) {
                    continue;
                }
                ONode node = ONode.ofJson(line);
                String role = node.get("role").getString();
                // 助手消息自 solon-ai 4.1 起不再落 content 字段（拆成 text/thinking），须按兼容顺序读
                String content = MessageLineUtil.readContent(node);
                if (role == null || content == null) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("role", role);
                item.put("content", content);
                item.put("createdAt", node.get("createdAt").getString());
                data.add(item);
            }
            return Result.succeed(data);
        } catch (Exception e) {
            LOG.warn("[Desktop] Failed to read messages for {}: {}", sessionId, e.getMessage());
            return Result.failure(500, "Failed to read session messages");
        }
    }

    /** 原子删除桌面会话最近 N 条服务端消息，并清除内存会话以便下次重建上下文。 */
    @Post
    @Mapping("/desktop/chat/rewind")
    public Result rewind(Context ctx) throws Exception {
        ONode root = ONode.ofJson(ctx.body());
        String sessionId = root.get("sessionId").getString();
        int count = root.get("count").getInt();
        if (!DesktopSessionIds.isValid(sessionId) || count <= 0 || count > 100_000) {
            return Result.failure(400, "Invalid rewind request");
        }
        if (wsGate.isSessionBusy(sessionId)) {
            return Result.failure(409, "Session is running");
        }

        Path messageFile = resolveSessionMessageFile(sessionId);
        if (!Files.isRegularFile(messageFile)) {
            sessionManager.removeSession(sessionId);
            return Result.succeed();
        }

        Path tempFile = messageFile.resolveSibling(messageFile.getFileName() + ".rewind.tmp");
        try {
            List<String> lines = new ArrayList<>();
            for (String line : Files.readAllLines(messageFile, StandardCharsets.UTF_8)) {
                if (Assert.isNotEmpty(line)) {
                    lines.add(line);
                }
            }
            int keepCount = Math.max(0, lines.size() - count);
            StringBuilder content = new StringBuilder();
            for (int index = 0; index < keepCount; index++) {
                content.append(lines.get(index)).append('\n');
            }
            Files.write(tempFile, content.toString().getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tempFile, messageFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile, messageFile, StandardCopyOption.REPLACE_EXISTING);
            }
            sessionManager.removeSession(sessionId);
            return Result.succeed();
        } catch (Exception e) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (Exception ignored) {
            }
            LOG.warn("[Desktop] Failed to rewind session {}: {}", sessionId, e.getMessage());
            return Result.failure(500, "Failed to rewind session");
        }
    }

    /** 删除桌面会话的服务端历史；仅允许数字 ID 和已存在的绝对工作区。 */
    @Post
    @Mapping("/desktop/chat/sessions/delete")
    public Result deleteSession(Context ctx) throws Exception {
        ONode root = ONode.ofJson(ctx.body());
        String sessionId = root.get("sessionId").getString();
        if (!DesktopSessionIds.isValid(sessionId)) {
            return Result.failure(400, "Invalid session id");
        }
        Path workspaceRoot;
        try {
            workspaceRoot = resolveWorkspaceRoot(root.get("workspace").getString());
        } catch (IllegalArgumentException e) {
            return Result.failure(400, "Invalid workspace");
        }
        Path wsSessionsRoot = WorkspaceDataUtil.sessionsPath(workspaceRoot.toString());
        Path sessionDir = wsSessionsRoot.resolve(sessionId).normalize();
        if (!sessionDir.startsWith(wsSessionsRoot)) {
            return Result.failure(400, "Invalid session path");
        }
        if (!Files.exists(sessionDir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return Result.succeed();
        }
        if (isActiveWorkspace(workspaceRoot) && wsGate.isSessionBusy(sessionId)) {
            return Result.failure(409, "Session is running");
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(sessionDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            return Result.succeed();
        } catch (Exception e) {
            LOG.warn("[Desktop] Failed to delete session {}: {}", sessionId, e.getMessage());
            return Result.failure("Failed to delete session");
        }
    }

    private Path resolveSessionMessageFile(String sessionId) {
        Path wsSessionsRoot = WorkspaceDataUtil.sessionsPath(engine.getWorkspace());
        Path sessionDir = wsSessionsRoot.resolve(sessionId).normalize();
        if (!sessionDir.startsWith(wsSessionsRoot)) {
            throw new IllegalArgumentException("Invalid session path");
        }
        return sessionDir.resolve(sessionId + ".messages.ndjson");
    }

    private Path resolveWorkspaceRoot(String workspace) {
        Path requested = Assert.isEmpty(workspace) ? Paths.get(engine.getWorkspace()) : Paths.get(workspace);
        if (!requested.isAbsolute()) {
            throw new IllegalArgumentException("workspace must be absolute");
        }
        // 与 workspaceKey/meta.getPath() 同源：只做 normalize，不解析符号链接
        Path root = requested.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("workspace not found");
        }
        return root;
    }

    private boolean isActiveWorkspace(Path workspaceRoot) {
        return workspaceRoot.equals(Paths.get(engine.getWorkspace()).toAbsolutePath().normalize());
    }
}
