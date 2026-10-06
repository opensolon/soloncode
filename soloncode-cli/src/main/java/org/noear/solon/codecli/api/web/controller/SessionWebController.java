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
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.annotation.Get;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Post;
import org.noear.solon.codecli.api.web.AbstractWebController;
import org.noear.solon.codecli.channel.ImGateway;
import org.noear.solon.codecli.api.web.event.WebEvent;
import org.noear.solon.codecli.api.web.service.LastTraceService;
import org.noear.solon.codecli.session.MessageLineUtil;
import org.noear.solon.codecli.session.SessionJanitor;
import org.noear.solon.codecli.session.SessionMeta;
import org.noear.solon.codecli.session.SessionRewindService;
import org.noear.solon.codecli.workspace.WorkspaceDataUtil;
import org.noear.solon.codecli.workspace.WorkspaceContext;
import org.noear.solon.codecli.workspace.WorkspaceManager;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.handle.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 会话管理 Controller（原 WebController 的 session 域）。
 *
 * <p>职责：会话列表、清空未置顶会话、删除/分叉/重命名/置顶、消息历史读取、
 * 最后一轮回放（last-trace）、中断、回退（rewind）。</p>
 */
public class SessionWebController extends AbstractWebController {
    private static final Logger LOG = LoggerFactory.getLogger(SessionWebController.class);

    /**
     * 「最后一轮执行过程」还原服务（无状态，可共享）
     */
    private static final LastTraceService LAST_TRACE_SERVICE = new LastTraceService();
    private static final SessionRewindService REWIND_SERVICE = new SessionRewindService();

    public SessionWebController(WorkspaceManager workspaceManager) {
        super(workspaceManager);
    }

    /**
     * 加载 Web 端会话列表。
     * <p>扫描 ~/.soloncode/workspaces/&lt;标识&gt;/sessions/ 下以 "web-" 开头的会话文件夹，
     * 读取每个会话的标签（优先使用 meta.json 自定义标签，否则取首条用户消息），
     * 按置顶 + 创建时间（createdAt）倒序排列返回。同时恢复每个会话关联的循环任务。</p>
     *
     * @return 会话列表，每项包含 sessionId、label、time、isPinned
     * @throws Exception 文件读取异常
     */
    @Get
    @Mapping("/web/chat/sessions")
    public Result<List<Map>> sessions() throws Exception {
        // 获取当前用户 ID（仅在认证和对话隔离同时开启时用于过滤会话，否则返回 null）
        String userId = getCurrentUserId();
        Path sessionsPath = currentContext().getSessionsRoot();
        File sessionsDir = sessionsPath.toFile();
        List<Map> data = new ArrayList<>();

        if (sessionsDir.exists() && sessionsDir.isDirectory()) {
            //先清理僵尸会话目录（无消息、无排队任务、meta 空），避免空壳一直出现在列表扫描中
            SessionJanitor.cleanWebSessions(sessionsPath);
            File[] dirs = sessionsDir.listFiles(f -> f.isDirectory() && f.getName().startsWith("web-"));
            if (dirs != null) {
                // 不在 dirs 层面排序，后面统一按置顶+创建时间排序

                for (File dir : dirs) {
                    String sid = dir.getName();
                    SessionMeta meta = SessionMeta.load(dir);

                    // 对话隔离开启时，过滤非当前用户的会话
                    if (userId != null) {
                        String ownerId = meta.getOwnerUserId();
                        // 认证开启后只显示明确归属于当前用户的会话；旧会话由管理员迁移，不能默认共享。
                        if (ownerId == null || ownerId.isEmpty() || !ownerId.equals(userId)) {
                            continue;
                        }
                    }

                    // 优先使用自定义标签
                    String label = meta.getLabel();
                    if (Assert.isEmpty(label)) {
                        File msgFile = new File(dir, sid + ".messages.ndjson");
                        if (!msgFile.exists()) continue;

                        label = extractFirstUserMessage(msgFile);
                    }

                    if (Assert.isEmpty(label)) {
                        continue;
                    }

                    long createdAt = meta.getCreatedAt();
                    if (createdAt <= 0L) {
                        createdAt = dir.lastModified();
                    }

                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("sessionId", sid);
                    item.put("label", label.length() > 30 ? label.substring(0, 30) + "..." : label);
                    item.put("time", createdAt);
                    item.put("isPinned", meta.isPinned());
                    data.add(item);

                    //恢复定时任务
                    loopScheduler().restore(sid);
                }

                // 排序：置顶优先（按 time/createdAt 降序），非置顶在后（按 time/createdAt 降序）
                data.sort((a, b) -> {
                    boolean aPinned = (Boolean) a.getOrDefault("isPinned", false);
                    boolean bPinned = (Boolean) b.getOrDefault("isPinned", false);
                    if (aPinned != bPinned) {
                        return aPinned ? -1 : 1;
                    }
                    Long aTime = (Long) a.getOrDefault("time", 0L);
                    Long bTime = (Long) b.getOrDefault("time", 0L);
                    return bTime.compareTo(aTime);
                });
            }
        }

        return Result.succeed(data);
    }

    /**
     * 清空当前工作区中所有未置顶且未运行的 Web 会话。
     * 置顶会话和正在运行的会话始终保留，避免误删重要内容或打断任务。
     */
    @Post
    @Mapping("/web/chat/sessions/clear")
    public Result<Map<String, Object>> clearUnpinnedSessions() throws Exception {
        Path sessionsPath = currentContext().getSessionsRoot();
        String userId = getCurrentUserId();
        List<String> deletedSessionIds = new ArrayList<>();
        int skippedBusy = 0;
        File sessionsDir = sessionsPath.toFile();
        File[] dirs = sessionsDir.listFiles(f -> f.isDirectory() && f.getName().startsWith("web-"));
        if (dirs != null) {
            for (File dir : dirs) {
                String sessionId = dir.getName();
                SessionMeta meta = SessionMeta.load(dir);
                String ownerId = meta.getOwnerUserId();
                if (userId != null && (ownerId == null || ownerId.isEmpty() || !userId.equals(ownerId))) {
                    continue;
                }
                if (meta.isPinned()) {
                    continue;
                }
                if (runtimePort().isSessionBusy(engine(), sessionId)) {
                    skippedBusy++;
                    continue;
                }
                if (loopScheduler() != null) {
                    loopScheduler().stopAll(sessionId);
                }
                sessionManager().removeSession(sessionId);
                // 主动清理该会话的 IM 绑定，避免悬挂路由
                ImGateway.getInstance(engine())
                        .onSessionRemoved(currentContext().getMeta().getId(), sessionId);
                try {
                    deleteDirectory(dir.toPath());
                    deletedSessionIds.add(sessionId);
                } catch (IOException e) {
                    LOG.error("Session clear failed for {}: {}", sessionId, e.getMessage());
                }
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deletedSessionIds", deletedSessionIds);
        data.put("skippedBusy", skippedBusy);
        return Result.succeed(data);
    }

    /**
     * 删除指定会话及其所有消息记录。
     * <p>执行路径安全检查后，递归删除会话目录下的所有文件。</p>
     *
     * @param sessionId 待删除的会话 ID
     * @param workspace 会话所属工作区；桌面端跨项目删除时显式传入
     * @return 操作结果
     * @throws Exception 文件删除异常
     */
    @Post
    @Mapping("/web/chat/sessions/delete")
    public Result deleteSession(@org.noear.solon.annotation.Param("sessionId") String sessionId,
                                @org.noear.solon.annotation.Param(value = "workspace", required = false) String workspace) throws Exception {
        if (!isValidSessionId(sessionId)) {
            return Result.failure(400, "Invalid sessionId");
        }

        HarnessEngine currentEngine = engine();
        Path workspaceRoot;
        try {
            if (Assert.isEmpty(workspace)) {
                workspaceRoot = Paths.get(currentEngine.getWorkspace()).toAbsolutePath().normalize();
            } else {
                Path requestedWorkspace = Paths.get(workspace);
                if (!requestedWorkspace.isAbsolute()) {
                    return Result.failure(400, "Workspace must be absolute");
                }
                workspaceRoot = requestedWorkspace.normalize();
            }
        } catch (RuntimeException e) {
            return Result.failure(400, "Invalid workspace");
        }

        if (!Files.isDirectory(workspaceRoot)) {
            return Result.failure(404, "Workspace not found");
        }

        //会话根目录按目标工作区计算（支持跨工作区删除），防穿越由 sessionPath 落在对应 sessionsRoot 内保证
        Path sessionsRoot = WorkspaceDataUtil.sessionsPath(workspaceRoot.toString());
        // 会话统一存储在 sessions/<sessionId>，用户归属由 _meta.json.ownerUserId 校验。
        Path sessionPath = sessionsRoot.resolve(sessionId).normalize();
        if (!sessionPath.startsWith(sessionsRoot)) {
            return Result.failure(400, "Invalid session path");
        }
        boolean sessionPathExists = Files.exists(sessionPath, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        if (sessionPathExists) {
            SessionMeta sessionMeta = SessionMeta.load(sessionPath);
            if ("AUTOMATION".equals(sessionMeta.getSessionType())) {
                return Result.failure(409, "Automation session is managed by its task");
            }
        }
        if (sessionPathExists && !ownsSession(sessionPath)) {
            return Result.failure(404, "Session not found");
        }
        if (sessionPathExists && !Files.isDirectory(sessionPath) && !Files.isSymbolicLink(sessionPath)) {
            return Result.failure(409, "Session path is not a directory");
        }

        Path activeWorkspace = Paths.get(currentEngine.getWorkspace()).toAbsolutePath().normalize();
        boolean activeWorkspaceSession = workspaceRoot.equals(activeWorkspace);
        if (activeWorkspaceSession && runtimePort().isSessionBusy(engine(), sessionId)) {
            return Result.failure(409, "Session is running");
        }

        // 仅清理当前运行时工作区的内存状态，避免数字会话 ID 在不同项目间碰撞。
        if (activeWorkspaceSession) {
            if (loopScheduler() != null) {
                loopScheduler().stopAll(sessionId);
            }
            sessionManager().removeSession(sessionId);
        }

        if (sessionPathExists) {
            try {
                deleteDirectory(sessionPath);
            } catch (IOException e) {
                LOG.error("Session delete failed for {}: {}", sessionId, e.getMessage());
                return Result.failure(500, "Session delete failed");
            }
        }

        // 主动清理该会话的 IM 绑定（支持跨工作区删除：按目标工作区路径反查 workspaceId，
        // 内存未命中则跳过，由消息到达时的惰性校验兜底），避免悬挂路由
        WorkspaceContext targetContext =
                workspaceManager.getContextsCached(workspaceRoot.toString());
        if (targetContext != null) {
            ImGateway.getInstance(engine())
                    .onSessionRemoved(targetContext.getMeta().getId(), sessionId);
        }

        return Result.succeed();
    }

    /**
     * Fork（分叉）会话：将源会话的所有消息历史和自定义标签复制到一个新会话。
     * <p>新会话拥有独立的 sessionId 与目录，不影响源会话的消息流。
     * 复制完成后需刷新前端会话列表并切换到新会话以加载历史消息。
     * 注意：循环任务和会话级 IM 绑定不复制，避免误触发新的循环执行。</p>
     *
     * @param sessionId 源会话 ID（必须以 web- 前缀开头并符合命名规范）
     * @return 包含新会话 sessionId 的结果对象
     * @throws Exception 文件复制异常
     */
    @Post
    @Mapping("/web/chat/sessions/fork")
    public Result<Map> forkSession(@org.noear.solon.annotation.Param("sessionId") String sessionId) throws Exception {
        if (!isValidSessionId(sessionId)) {
            return Result.failure(400, "Invalid sessionId");
        }

        String userId = getCurrentUserId();
        Path sessionsRoot = currentContext().getSessionsRoot();
        Path sourcePath = sessionsRoot.resolve(sessionId).normalize();
        File sourceDir = sourcePath.toFile();

        if (!sourceDir.exists() || !sourceDir.isDirectory() || !ownsSession(sourcePath)) {
            return Result.failure(404, "Source session not found");
        }
        // 防止路径穿越：确保解析后的目录仍在 sessions 根目录之内
        if (!sourcePath.startsWith(sessionsRoot)) {
            return Result.failure(400, "Invalid session path");
        }

        // 生成唯一新 sessionId（短 ID 形式），避免与既有会话冲突
        String basePrefix = "web-";
        String newSessionId;
        for (int i = 0; i < 16; i++) {
            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            newSessionId = basePrefix + suffix;
            File targetDir = new File(sessionsRoot.toFile(), newSessionId);
            if (!targetDir.exists()) {
                if (!targetDir.mkdirs()) {
                    return Result.failure(500, "Failed to create forked session directory");
                }
                File sourceMarker = new File(sourceDir, sessionId + ".messages.ndjson");
                File targetMarker = new File(targetDir, newSessionId + ".messages.ndjson");
                if (sourceMarker.exists()) {
                    Files.copy(sourceMarker.toPath(), targetMarker.toPath());
                }
                // 复制会话 meta（label/pinned），并刷新 createdAt
                SessionMeta.copy(sourceDir, targetDir);
                SessionMeta targetMeta = SessionMeta.load(targetDir);
                String name = targetMeta.getLabel();
                if (Assert.isEmpty(name)) {
                    name = newSessionId;
                }
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("sessionId", newSessionId);
                data.put("name", name);
                return Result.succeed(data);
            }
        }
        return Result.failure(500, "Failed to allocate unique sessionId");
    }

    /**
     * 重命名会话标签。
     * <p>写入会话目录 meta.json 的 label 字段，标签最大长度 50 字符。</p>
     *
     * @param sessionId 待重命名的会话 ID
     * @param label     新的会话标签文本
     * @return 操作结果
     * @throws Exception 文件写入异常
     */
    @Post
    @Mapping("/web/chat/sessions/rename")
    public Result renameSession(@org.noear.solon.annotation.Param("sessionId") String sessionId,
                                @org.noear.solon.annotation.Param("label") String label) throws Exception {
        if (!isValidSessionId(sessionId)) {
            return Result.failure();
        }
        if (label == null || label.trim().isEmpty()) {
            return Result.failure(400, "Label is required");
        }
        // 限制标签长度
        if (label.length() > 50) {
            label = label.substring(0, 50);
        }

        Path sessionPath = currentContext().getSessionPath(sessionId);

        if (!sessionPath.toFile().exists() || !sessionPath.toFile().isDirectory() || !ownsSession(sessionPath)) {
            return Result.failure(404, "Session not found");
        }

        SessionMeta.updateLabel(sessionPath, label.trim());

        return Result.succeed();
    }

    /**
     * 置顶/取消置顶会话。
     * <p>写入会话目录 meta.json 的 pinned 字段。</p>
     *
     * @param sessionId 会话 ID
     * @param pinned    是否置顶
     * @return 操作结果
     * @throws Exception 文件写入异常
     */
    @Post
    @Mapping("/web/chat/sessions/pin")
    public Result pinSession(@org.noear.solon.annotation.Param("sessionId") String sessionId,
                             @org.noear.solon.annotation.Param("pinned") boolean pinned) throws Exception {
        if (!isValidSessionId(sessionId)) {
            return Result.failure(400, "Invalid sessionId");
        }

        Path sessionsRoot = currentContext().getSessionsRoot();
        Path sessionPath = sessionsRoot.resolve(sessionId).normalize();
        if (!sessionPath.startsWith(sessionsRoot)) {
            return Result.failure(400, "Invalid session path");
        }

        File sessionDir = sessionPath.toFile();
        if (!sessionDir.exists() || !sessionDir.isDirectory() || !ownsSession(sessionPath)) {
            return Result.failure(404, "Session not found");
        }

        SessionMeta.updatePinned(sessionDir, pinned);

        return Result.succeed();
    }

    /**
     * 获取指定会话的消息历史记录。
     *
     * <p>取数优先走内存会话实例：消息文件是 {@code FileAgentSession} 的实现细节，其内存缓存与磁盘同源
     * （缓存层 maxMessages=0，不做窗口裁剪），而回退/清空等写动作都先落内存再同步磁盘，
     * 读内存才不会与写侧脱节。仅当该会话尚未被打开（内存中无实例）时才读文件，
     * 以免为「只是点开看一下」的会话凭空创建常驻内存会话。</p>
     *
     * @param sessionId 会话 ID
     * @return 消息列表，每项包含 role、content、createdAt
     * @throws Exception 文件读取异常
     */
    @Get
    @Mapping("/web/chat/messages")
    public Result<List<Map>> messages(@org.noear.solon.annotation.Param("sessionId") String sessionId) throws Exception {
        if (!isValidSessionId(sessionId)) {
            return Result.failure(400, "Invalid sessionId");
        }
        return messagesForSession(sessionId);
    }

    private Result<List<Map>> messagesForSession(String sessionId) throws Exception {
        Path sessionPath = currentContext().getSessionPath(sessionId);
        if (!ownsSession(sessionPath)) return Result.failure(404, "Session not found");

        org.noear.solon.ai.agent.AgentSession session = sessionManager().getSession(sessionId);
        return Result.succeed(readMessagesFromSession(session));
    }

    /**
     * 从内存会话实例读取历史消息。
     *
     * @param session 会话实例
     * @return 消息列表
     */
    private List<Map> readMessagesFromSession(org.noear.solon.ai.agent.AgentSession session) {
        /* getMessages() 返回的是会话内部的活列表（非副本、无同步）：Agent 线程可能正在 addMessage 追加，
         * 必须先快照再遍历，否则并发下会抛 ConcurrentModificationException。 */
        List<ChatMessage> messages = new ArrayList<>(session.getMessages());
        List<Map> data = new ArrayList<>(messages.size());

        for (ChatMessage msg : messages) {
            if (msg == null || msg.getRole() == null || msg.getRole() == ChatRole.SYSTEM) {
                // System 消息不落历史（与写 ndjson 的过滤规则一致）
                continue;
            }

            String content = MessageLineUtil.readContent(msg);
            if (content == null) {
                continue;
            }

            data.add(buildMessageItem(msg.getRole().name(), content,
                    String.valueOf(msg.getCreatedAt()), msg.getMetadata()));
        }

        return data;
    }

    /**
     * 组装单条历史消息的前端视图（两条取数路径共用，确保内存读与文件读输出同构）。
     *
     * @param role      角色（USER / ASSISTANT）
     * @param content   正文
     * @param createdAt 创建时间戳（字符串形式，前端两种都能解析，保持既有接口契约）
     * @param metadata  消息元数据
     * @return 前端视图
     */
    private Map<String, Object> buildMessageItem(String role, String content, String createdAt,
                                                 Map<String, Object> metadata) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("role", role);
        item.put("content", content);
        item.put("createdAt", createdAt);

        String source = metaString(metadata, "source");
        if (source != null) {
            item.put("source", source); //可能有 {source:xxx}
            item.put("sourceLabel", WebEvent.toSourceLabel(source));
        }

        // 子代理标记：该条用户消息实际交由哪个子代理执行（主 Agent 时无此字段）
        String agentMeta = metaString(metadata, "agent");
        if (Assert.isNotEmpty(agentMeta)) {
            item.put("agentName", agentMeta);
        }

        /* 运行 ID：同一轮任务产出的所有消息共享它。前端历史行据此打 data-run-id，
         * 删除/重跑才能把该轮的气泡、思考块、工具卡成批清掉；缺了它只能退化成「只删当前行」。 */
        String runIdMeta = metaString(metadata, org.noear.solon.ai.agent.AgentTrace.META_RUN_ID);
        if (Assert.isNotEmpty(runIdMeta)) {
            item.put("runId", runIdMeta);
        }

        // 解析附件元数据（图片文件名等），供历史消息恢复时渲染
        List<Map<String, String>> attachments = parseAttachments(metaString(metadata, "attachments"));
        if (attachments != null) {
            item.put("attachments", attachments);
        }

        return item;
    }

    /**
     * 解析附件元数据（本身是一段 JSON 字符串，内存与 ndjson 两侧同值）。
     *
     * @param attachStr 附件元数据 JSON
     * @return 附件列表；无附件或格式异常时返回 {@code null}
     */
    private List<Map<String, String>> parseAttachments(String attachStr) {
        if (Assert.isEmpty(attachStr)) {
            return null;
        }

        try {
            ONode attachArr = ONode.ofJson(attachStr);
            if (attachArr.isArray()) {
                List<Map<String, String>> attachList = new ArrayList<>();
                for (ONode a : attachArr.getArray()) {
                    Map<String, String> am = new LinkedHashMap<>();
                    am.put("name", a.get("name").getString());
                    am.put("type", a.get("type").getString());
                    attachList.add(am);
                }
                return attachList;
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    /**
     * 取元数据中的字符串值；缺失返回 {@code null}。
     */
    private String metaString(Map<String, Object> metadata, String key) {
        Object val = (metadata == null ? null : metadata.get(key));
        if (val == null) {
            return null;
        }
        return (val instanceof String ? (String) val : String.valueOf(val));
    }

    /**
     * 获取指定会话「最后一轮」的工具执行过程，供前端在历史消息之上回放执行细节。
     *
     * <p>ndjson 只落用户输入与最终回答，中间的工具调用过程仅存在于会话上下文的
     * {@link org.noear.solon.ai.agent.react.ReActTrace} WorkingMemory 中。该接口把最近一轮的
     * 工具调用序列取出，前端据此合成与实时流同构的事件回放，使刷新页面后仍能看到执行过程。</p>
     *
     * <p>本接口是纯增量能力：任何异常/不对齐/无过程的情况都返回 {@code aligned=false}，
     * 前端随即退回原有纯文本渲染，因此不会影响历史加载主路径。</p>
     *
     * @param sessionId 会话 ID
     * @return {@code {aligned, running, runId, turns[], truncated}}
     */
    @Get
    @Mapping("/web/chat/messages/last-trace")
    public Result<Map<String, Object>> messages_lastTrace(@org.noear.solon.annotation.Param("sessionId") String sessionId) {
        if (!isValidSessionId(sessionId)) {
            return Result.failure(400, "Invalid sessionId");
        }

        try {
            org.noear.solon.ai.agent.AgentSession session = sessionManager().getSession(sessionId);
            String lastUserMsg = readLastUserMessage(session);
            boolean running = runtimePort().isSessionBusy(engine(), sessionId);

            Map<String, Object> data = LAST_TRACE_SERVICE.buildLastTrace(session, running, lastUserMsg);
            return Result.succeed(data);
        } catch (Throwable e) {
            // 回放属于增强能力，失败即静默降级，不能让它影响会话切换
            LOG.debug("[SessionWebController] last-trace failed for session {}: {}", sessionId, e.getMessage());
            return Result.succeed(LAST_TRACE_SERVICE.buildLastTrace(null, false, null));
        }
    }

    /**
     * 读取 ndjson 中最后一条用户消息的内容，用于 trace 对齐校验（内存会话路径）。
     *
     * @return 最后一条 USER 消息内容；无则返回 null
     */
    private String readLastUserMessage(org.noear.solon.ai.agent.AgentSession session) {
        List<ChatMessage> messages = new ArrayList<>(session.getMessages());

        for (int i = messages.size() - 1; i > -1; i--) {
            ChatMessage m1 = messages.get(i);
            if (m1 instanceof org.noear.solon.ai.chat.message.UserMessage) {
                return m1.getContent();
            }
        }

        return null;
    }

    /**
     * 中断指定会话的当前 AI 处理。
     * <p>执行 sessionId 安全校验后，委派给 WebGate 中断该会话正在进行的 AI 任务。</p>
     *
     * @param sessionId 待中断的会话 ID
     * @return 操作结果
     */
    @Post
    @Mapping("/web/chat/interrupt")
    public Result interruptSession(@org.noear.solon.annotation.Param("sessionId") String sessionId) {
        if (!isValidSessionId(sessionId)) {
            return Result.failure();
        }

        if (!canWriteSession(sessionId)) return Result.failure(404, "Session not found");
        // 按当前请求工作区上下文取 WebGate，避免非默认工作区会话中断时推送串到默认工作区
        if (!runtimePort().interruptSession(currentContext(), sessionId)) {
            return Result.failure(500, "Failed to cancel session queue; task was not interrupted");
        }

        // 暂停该 session 的活跃 Goal，防止 Goal 调度器在 interrupt 后立即重新触发
        org.noear.solon.codecli.loop.LoopScheduler loopScheduler = loopScheduler();
        if (loopScheduler != null) {
            org.noear.solon.codecli.loop.LoopTask activeGoal = loopScheduler.findActiveGoalInSession(sessionId);
            if (activeGoal != null) {
                loopScheduler.pauseGoal(sessionId, activeGoal.getId());
                LOG.info("[SessionWebController] Goal '{}' paused due to session interrupt", activeGoal.getId());
            }
        }

        return Result.succeed();
    }

    /**
     * 回退会话消息：删除锚点消息（含）之后的全部消息。
     *
     * <p>删除经由 {@link org.noear.solon.codecli.session.SessionRewindService} 走
     * {@code AgentSession.removeLatestMessage}，与 CLI 的 {@code /rewind} 同一条路径。
     * 早期实现是直接截断 ndjson 文件：会话是「内存缓存 + 文件」双层结构，绕过缓存改文件后，
     * 任何后续持久化都会把陈旧缓存写回、已删消息成批复活，且按行删还会留下孤立 ToolMessage。</p>
     *
     * <p>定位优先用 {@code anchorRunId}（同一轮任务的消息共享它）。{@code count} 仅作老数据降级，
     * 因为前端能数的 DOM 行与 ndjson 行并非一一对应（系统通知行、被中断轮次的空气泡无服务端记录；
     * 连续 assistant 会被历史渲染合并成一个气泡）。</p>
     *
     * @param sessionId   会话 ID
     * @param count       降级条数（无 anchorRunId 时生效），默认 2（一对用户消息 + 助手回复）
     * @param anchorRunId 锚点运行 ID
     * @param anchorRole  锚点角色（assistant / user）
     * @return 操作结果，data 含 removed / effectiveAnchor / degraded
     */
    @Post
    @Mapping("/web/chat/rewind")
    public Result rewindSession(@org.noear.solon.annotation.Param("sessionId") String sessionId,
                                @org.noear.solon.annotation.Param(value = "count", required = false) Integer count,
                                @org.noear.solon.annotation.Param(value = "anchorRunId", required = false) String anchorRunId,
                                @org.noear.solon.annotation.Param(value = "anchorRole", required = false) String anchorRole) throws Exception {
        if (!isValidSessionId(sessionId)) {
            return Result.failure(400, "Invalid sessionId");
        }
        if (count == null || count <= 0) {
            count = 2; // 默认回退2条（用户+助手）
        }
        Path sessionPath = currentContext().getSessionPath(sessionId);
        if (!ownsSession(sessionPath)) return Result.failure(404, "Session not found");
        if (runtimePort().isSessionBusy(engine(), sessionId)) {
            return Result.failure(409, "Session is running");
        }

        try {
            org.noear.solon.ai.agent.AgentSession session = sessionManager().getSession(sessionId, getCurrentUserId());
            SessionRewindService.RewindResult rr = REWIND_SERVICE.rewind(
                    session, anchorRunId, anchorRole, count);

            if (rr.isAnchorMissing()) {
                // 宁可不删，也不能删错条数：前端应改为重载历史
                return Result.failure(409, "ANCHOR_NOT_FOUND");
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("removed", rr.getRemoved());
            data.put("effectiveAnchor", rr.getEffectiveAnchor());
            data.put("degraded", rr.isDegraded());
            return Result.succeed(data);
        } catch (Exception e) {
            LOG.error("Rewind failed for session {}: {}", sessionId, e.getMessage());
            return Result.failure(500, "Session rewind failed");
        }
    }

    /**
     * 递归删除目录及其所有子文件和子目录。
     *
     * @param dir 待删除的目录
     */
    private void deleteDirectory(Path dir) throws IOException {
        // walkFileTree 默认不跟随符号链接；Files.delete 会把失败可靠地向上传播。
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * 从 ndjson 消息文件中提取第一条用户（USER 角色）消息的内容。
     * <p>逐行读取消息文件，找到第一条 role 为 USER 的记录并返回其 content 字段。</p>
     *
     * @param msgFile ndjson 格式的消息文件
     * @return 第一条用户消息内容，若未找到则返回 null
     */
    private String extractFirstUserMessage(File msgFile) {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(msgFile), "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                ONode node = ONode.ofJson(line);
                String role = node.get("role").getString();
                if ("USER".equals(role)) {
                    return MessageLineUtil.readContent(node);
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }
}
