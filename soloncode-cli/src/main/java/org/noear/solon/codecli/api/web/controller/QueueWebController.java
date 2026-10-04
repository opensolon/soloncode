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
package org.noear.solon.codecli.api.web.controller;

import org.noear.snack4.ONode;
import org.noear.solon.annotation.Body;
import org.noear.solon.annotation.Get;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Post;
import org.noear.solon.annotation.Param;
import org.noear.solon.codecli.api.web.AbstractWebController;
import org.noear.solon.codecli.session.queue.SessionQueue;
import org.noear.solon.codecli.session.queue.SessionQueueItem;
import org.noear.solon.codecli.session.queue.SessionQueueStore;
import org.noear.solon.codecli.workspace.WorkspaceManager;
import org.noear.solon.core.handle.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话队列 Controller（原 WebController 的 queue/todo 域）。
 *
 * <p>职责：会话统一队列视图读取、任务级入队/取消/执行（run），以及会话 TODO 列表解析。</p>
 */
public class QueueWebController extends AbstractWebController {
    private static final Logger LOG = LoggerFactory.getLogger(QueueWebController.class);

    public QueueWebController(WorkspaceManager workspaceManager) {
        super(workspaceManager);
    }

    /**
     * 获取指定会话的 TODO 列表。
     * <p>从会话对应的 TODO.md 文件中解析 checkbox 任务项，返回结构化的任务列表及统计信息。</p>
     *
     * @param sessionId 会话 ID
     * @return 包含 exists、raw、items、stats 的结果对象
     */
    @Get
    @Mapping("/web/chat/todos")
    public Result<Map> todos(@Param("sessionId") String sessionId) {
        if (!isValidSessionId(sessionId)) {
            return Result.failure(400, "Invalid sessionId");
        }

        org.noear.solon.ai.harness.HarnessEngine currentEngine = engine();
        Path todoPath = currentEngine.getTodoTalent().getTodoPath(currentEngine.getWorkspace(), sessionId);

        Map<String, Object> data = new LinkedHashMap<>();

        if (!java.nio.file.Files.exists(todoPath)) {
            data.put("exists", false);
            data.put("items", new ArrayList<>());
            Map<String, Integer> stats = new LinkedHashMap<>();
            stats.put("total", 0);
            stats.put("pending", 0);
            stats.put("inProgress", 0);
            stats.put("done", 0);
            data.put("stats", stats);
            return Result.succeed(data);
        }

        try {
            String raw = new String(java.nio.file.Files.readAllBytes(todoPath), "UTF-8");
            data.put("exists", true);
            data.put("raw", raw);

            List<Map> items = new ArrayList<>();
            String currentGroup = "";
            int total = 0, pending = 0, inProgress = 0, done = 0;

            String[] lines = raw.split("\n");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];

                // 匹配 ## 标题行作为 group
                if (line.matches("^\\s*##\\s+.+$")) {
                    currentGroup = line.replaceFirst("^\\s*##\\s+", "").trim();
                    continue;
                }

                // 匹配 checkbox 行: - [ ] / - [/] / - [x] / - [X]
                if (line.matches("^\\s*-\\s*\\[( |x|X|/)\\]\\s+.+$")) {
                    total++;

                    char statusChar = line.replaceAll("^\\s*-\\s*\\[([ xX/])]\\s+.+$", "$1").charAt(0);
                    String status;
                    if (statusChar == ' ') {
                        status = "pending";
                        pending++;
                    } else if (statusChar == '/') {
                        status = "in_progress";
                        inProgress++;
                    } else {
                        status = "done";
                        done++;
                    }

                    String text = line.replaceFirst("^\\s*-\\s*\\[[ xX/]]\\s+", "").trim();

                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("line", i + 1);
                    item.put("status", status);
                    item.put("text", text);
                    item.put("raw", line.trim());
                    item.put("group", currentGroup);
                    items.add(item);
                }
            }

            data.put("items", items);

            Map<String, Integer> stats = new LinkedHashMap<>();
            stats.put("total", total);
            stats.put("pending", pending);
            stats.put("inProgress", inProgress);
            stats.put("done", done);
            data.put("stats", stats);

            return Result.succeed(data);
        } catch (Exception e) {
            LOG.error("Failed to read TODO for session {}: {}", sessionId, e.getMessage());
            return Result.failure(500, e.getMessage());
        }
    }

    /** 获取 session 的统一队列视图；旧文件仅作一次性迁移读取。 */
    @Get
    @Mapping("/web/chat/queue")
    public Result<Map> getQueue(@Param("sessionId") String sessionId) {
        if (!isValidSessionId(sessionId)) {
            return Result.failure(400, "Invalid sessionId");
        }

        Path queuePath = resolveSessionQueuePath(sessionId);
        if (queuePath == null || queuePath.getParent() == null) {
            return Result.failure(400, "Invalid session path");
        }
        if (!ownsSession(queuePath.getParent())) {
            return Result.failure(404, "Session not found");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        try {
            Path sessionDir = queuePath.getParent();
            List<SessionQueueItem> unified = SessionQueueStore.load(sessionDir);
            List<Map> items = new ArrayList<>();
            for (SessionQueueItem item : unified) {
                Map<String, Object> row = new LinkedHashMap<>(item.toMap());
                row.put("displayText", item.getText());
                items.add(row);
            }
            data.put("exists", !items.isEmpty());
            data.put("items", items);
            data.put("updatedAt", items.isEmpty() ? 0L : System.currentTimeMillis());
            return Result.succeed(data);
        } catch (Exception e) {
            LOG.error("Failed to read queue-tasks for session {}: {}", sessionId, e.getMessage());
            return Result.failure(500, "Queue read failed");
        }
    }

    /** 旧整表接口不再写入；客户端应使用任务级操作，避免旧快照复活任务。 */
    @Post
    @Mapping("/web/chat/queue")
    public Result<Map> saveQueue(@Body String body) {
        return Result.failure(410, "Use queue item operations");
    }

    /** 任务级新增：不再要求客户端提交整表快照。 */
    @Post
    @Mapping("/web/chat/queue/item")
    public Result<Map> enqueueQueueItem(@Body String body) {
        try {
            ONode root = ONode.ofJson(body);
            String sessionId = root == null || root.get("sessionId") == null ? null : root.get("sessionId").getString();
            String text = root == null || root.get("text") == null ? null : root.get("text").getString();
            if (!isValidSessionId(sessionId) || text == null || text.trim().isEmpty()) return Result.failure(400, "sessionId and text are required");
            Path queuePath = resolveSessionQueuePath(sessionId);
            if (queuePath == null || queuePath.getParent() == null || !ownsSession(queuePath.getParent())) return Result.failure(404, "Session not found");
            org.noear.solon.ai.agent.AgentSession session = sessionManager().getSession(sessionId);
            if (session == null) return Result.failure(404, "Session not found");
            SessionQueue.bindStorage(session, queuePath.getParent());
            String id = root.get("id") == null ? null : safeQueueString(root.get("id"), 80);
            String model = root.get("model") == null ? null : safeQueueString(root.get("model"), 200);
            String reasoningEffort = root.get("reasoningEffort") == null ? null : safeQueueString(root.get("reasoningEffort"), 50);
            String thinkingMode = root.get("thinkingMode") == null ? null : safeQueueString(root.get("thinkingMode"), 50);
            String selectedAgent = root.get("selectedAgent") == null ? null : safeQueueString(root.get("selectedAgent"), 128);
            boolean hasFiles = root.get("hasFiles") != null && root.get("hasFiles").getBoolean();
            if (hasFiles) return Result.failure(400, "Attachments cannot be queued");
            int position = SessionQueue.enqueue(session, text, "WEB", null, null, null, id,
                    model, reasoningEffort, thinkingMode, selectedAgent, false);
            if (position < 0) return Result.failure(409, "Queue is full or storage failed");
            runtimePort().drainSessionQueue(currentContext(), session);
            return Result.succeed(queueResult(queuePath.getParent()));
        } catch (Exception e) { return Result.failure(400, "Invalid queue item"); }
    }

    /** 任务级取消：按 id 删除，绝不依赖客户端旧快照。 */
    @Post
    @Mapping("/web/chat/queue/item/cancel")
    public Result<Map> cancelQueueItem(@Body String body) {
        try {
            ONode root = ONode.ofJson(body);
            String sessionId = root == null ? null : root.get("sessionId").getString();
            String itemId = root == null ? null : root.get("itemId").getString();
            Path queuePath = resolveSessionQueuePath(sessionId);
            org.noear.solon.ai.agent.AgentSession session = sessionManager().getSession(sessionId);
            if (!isValidSessionId(sessionId) || session == null || queuePath == null || queuePath.getParent() == null || !ownsSession(queuePath.getParent())) return Result.failure(404, "Session not found");
            SessionQueue.bindStorage(session, queuePath.getParent());
            if (!SessionQueue.cancelItem(session, itemId)) return Result.failure(404, "Queue item not found");
            return Result.succeed(queueResult(queuePath.getParent()));
        } catch (Exception e) { return Result.failure(400, "Invalid queue operation"); }
    }

    /** 任务级执行：将指定任务提升到队首，由后端唯一 drainer 消费。 */
    @Post
    @Mapping("/web/chat/queue/item/run")
    public Result<Map> runQueueItem(@Body String body) {
        try {
            ONode root = ONode.ofJson(body);
            String sessionId = root == null ? null : root.get("sessionId").getString();
            String itemId = root == null ? null : root.get("itemId").getString();
            Path queuePath = resolveSessionQueuePath(sessionId);
            org.noear.solon.ai.agent.AgentSession session = sessionManager().getSession(sessionId);
            if (!isValidSessionId(sessionId) || session == null || queuePath == null || queuePath.getParent() == null || !ownsSession(queuePath.getParent())) return Result.failure(404, "Session not found");
            SessionQueue.bindStorage(session, queuePath.getParent());
            if (!SessionQueue.promote(session, itemId)) return Result.failure(404, "Queue item not found");
            runtimePort().drainSessionQueue(currentContext(), session);
            return Result.succeed(queueResult(queuePath.getParent()));
        } catch (Exception e) { return Result.failure(400, "Invalid queue operation"); }
    }

    private Map<String, Object> queueResult(Path sessionDir) throws IOException {
        Map<String, Object> data = new LinkedHashMap<>();
        List<Map> rows = new ArrayList<>();
        for (SessionQueueItem item : SessionQueueStore.load(sessionDir)) rows.add(new LinkedHashMap<>(item.toMap()));
        data.put("exists", !rows.isEmpty());
        data.put("items", rows);
        data.put("updatedAt", rows.isEmpty() ? 0L : System.currentTimeMillis());
        return data;
    }

    /**
     * 解析并校验会话目录下的 queue-tasks.json 路径（防止路径穿越）。
     */
    private Path resolveSessionQueuePath(String sessionId) {
        Path sessionsRoot = currentContext().getSessionsRoot();
        Path sessionPath = sessionsRoot.resolve(sessionId).normalize();
        if (!sessionPath.startsWith(sessionsRoot)) {
            return null;
        }
        return sessionPath.resolve("queue-tasks.json");
    }

    private String safeQueueString(ONode node, int maxLen) {
        if (node == null || node.isNull()) {
            return null;
        }
        String s;
        try {
            s = node.getString();
        } catch (Exception e) {
            return null;
        }
        if (s == null) {
            return null;
        }
        if (s.length() > maxLen) {
            return s.substring(0, maxLen);
        }
        return s;
    }
}
