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
package org.noear.solon.codecli.portal.web;

import org.noear.snack4.Feature;
import org.noear.snack4.ONode;
import org.noear.solon.Solon;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentTrace;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.agent.AgentDefinition;
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.ai.talents.mount.MountDir;
import org.noear.solon.ai.talents.mount.MountType;
import org.noear.solon.ai.talents.mount.SkillDir;
import org.noear.solon.annotation.*;
import org.noear.solon.codecli.portal.web.event.WebEvent;
import org.noear.solon.codecli.workspace.WorkspaceDataUtil;
import org.noear.solon.codecli.workspace.WorkspaceManager;
import org.noear.solon.codecli.workspace.WorkspaceContext;
import org.noear.solon.codecli.config.AgentFlags;
import org.noear.solon.codecli.command.builtin.*;
import org.noear.solon.codecli.portal.web.service.FileService;
import org.noear.solon.codecli.portal.web.service.GitService;
import org.noear.solon.codecli.portal.web.service.LastTraceService;
import org.noear.solon.codecli.session.SessionManager;
import org.noear.solon.codecli.session.SessionJanitor;
import org.noear.solon.codecli.session.MessageLineUtil;
import org.noear.solon.codecli.session.SessionMeta;
import org.noear.solon.codecli.session.SessionRewindService;
import org.noear.solon.codecli.util.ReasoningSupportUtil;
import org.noear.solon.codecli.workspace.WorkspaceMeta;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Result;
import org.noear.solon.core.handle.UploadedFile;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.util.List;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;

/**
 * Web 门户控制器 —— SolonCode Web UI 的核心 HTTP 入口。
 *
 * <p>职责：接收前端浏览器的 HTTP 请求，将聊天输入、会话管理、Git 操作、文件树浏览等
 * 业务委派给 {@link WebGate}（WebSocket 推送）和 {@link HarnessEngine}（AI 引擎）处理。</p>
 *
 * <h3>主要功能分组</h3>
 * <ul>
 *   <li><b>页面入口与元信息</b>：首页重定向、应用标题/版本/工作区路径查询</li>
 *   <li><b>聊天会话管理</b>：会话列表加载、删除、重命名、消息历史、回退、中断</li>
 *   <li><b>模型管理</b>：可用模型列表查询、当前会话模型切换</li>
 *   <li><b>聊天输入</b>：接收用户消息与附件，路由到 WebGate 进行 AI 处理</li>
 *   <li><b>Git 集成</b>：仓库状态检测、初始化、Diff 查看、文件内容获取、提交（委派给 {@link GitService}）</li>
 *   <li><b>文件浏览</b>：工作区目录结构浏览、文件搜索、文件内容读取（委派给 {@link FileService}）</li>
 * </ul>
 *
 * <h3>架构位置</h3>
 * <p>位于 {@code portal.web} 层，是 Solon MVC 的 Controller。
 * 向上对接浏览器前端，向下通过 {@link WebGate}（WebSocket 推送通道）和
 * {@link HarnessEngine}（AI Agent 引擎）完成实际业务处理。</p>
 *
 * @author oisin 2026-3-13
 * @author noear 2026-4-18
 * @see WebGate    WebSocket 推送网关
 * @see GitService  Git 业务逻辑服务
 * @see FileService 文件浏览业务逻辑服务
 * @see HarnessEngine AI Agent 执行引擎
 */
public class WebController {
    /**
     * 日志记录器
     */
    private static final Logger LOG = LoggerFactory.getLogger(WebController.class);

    /**
     * 「最后一轮执行过程」还原服务（无状态，可共享）
     */
    private static final LastTraceService LAST_TRACE_SERVICE = new LastTraceService();
    private static final SessionRewindService REWIND_SERVICE = new SessionRewindService();

    private final WorkspaceManager workspaceManager;

    private WorkspaceContext currentContext() {
        return workspaceManager.currentContext();
    }

    private HarnessEngine engine() {
        return currentContext().getEngine();
    }

    private WebGate webGate() {
        return currentContext().getWebGate();
    }

    private LoopScheduler loopScheduler() {
        return currentContext().getLoopScheduler();
    }

    private SessionManager sessionManager() {
        return currentContext().getSessionManager();
    }

    /**
     * 获取当前用户 ID。
     * 用户认证启用时返回 userId，否则返回 null（使用传统非隔离路径）。
     * 优先从上下文属性获取，若未设置则尝试从 token 中提取。
     */
    private String getCurrentUserId() {
        Context ctx = Context.current();
        if (ctx != null) {
            // 优先从 UserAuthFilter 设置的上下文属性获取
            String userId = ctx.attr("user_id");
            if (userId != null) {
                return userId;
            }
            // 回退：从 token 中提取 userId（用于 UserAuthFilter 未设置属性但有有效 token 的场景）
            try {
                String token = org.noear.solon.codecli.auth.UserLoginController.extractToken(ctx);
                if (token != null) {
                    org.noear.solon.codecli.auth.UserSessionManager sessionMgr = 
                            org.noear.solon.Solon.context().getBean(org.noear.solon.codecli.auth.UserSessionManager.class);
                    if (sessionMgr != null) {
                        org.noear.solon.codecli.auth.UserSessionManager.UserSession session = sessionMgr.getSession(token);
                        if (session != null) {
                            return session.getUserId();
                        }
                    }
                }
            } catch (Exception e) {
                // 忽略异常，回退返回 null
            }
        }
        return null;
    }

    private FileService fileService() {
        return currentContext().getFileService();
    }

    private GitService gitService() {
        return currentContext().getGitService();
    }

    /**
     * 构造函数：初始化核心依赖并注册 Web 端 Loop 任务执行器。
     */
    public WebController(WorkspaceManager workspaceManager) {
        this.workspaceManager = workspaceManager;

        // 说明：Web 端 Loop 执行器/繁忙检查器的注册已迁移到
        // WorkspaceManager.registerWebLoopExecutor()，由每个工作区在创建其 LoopScheduler 时
        // 就地绑定本工作区的 WebGate 注册，从而保证多工作区下 loop/goal 任务能各自执行并推送到
        // 正确的连接池（此处不再统一注册，避免只覆盖默认工作区）。
    }

    @Get
    @Mapping("/web/workspace/list")
    public Result<Map<String, Object>> listWorkspaces() {
        Map<String, Object> data = new LinkedHashMap<>();

        // 1. 启动目录（默认工作区，虚拟条目：随 user.dir 变化，不落 workspaces.json）
        try {
            WorkspaceContext defCtx = workspaceManager.getOrCreate(null);
            if (defCtx != null && defCtx.getMeta() != null) {
                WorkspaceMeta dm = defCtx.getMeta();
                Map<String, Object> launch = new LinkedHashMap<>();
                launch.put("id", dm.getId());
                launch.put("name", dm.getName());
                launch.put("path", dm.getPath());
                data.put("launch", launch);
            }
        } catch (Exception e) {
            LOG.warn("[Workspace] Failed to resolve launch workspace", e);
        }

        // 2. 最近的工作区（历史列表，不含 default）
        data.put("workspaces", workspaceManager.listWorkspaces());

        // 3. 文件挂载（仅启用的 FILES 类型，每项：别名 + realPath）
        List<Map<String, Object>> mounts = new ArrayList<>();
        try {
            for (MountDir entry : engine().getMounts()) {
                if (entry.getType() != MountType.FILES) continue;
                if (!entry.isEnabled()) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("alias", entry.getAlias());
                item.put("path", entry.getRealPath() != null ? entry.getRealPath().toString() : "");
                mounts.add(item);
            }
        } catch (Exception e) {
            LOG.warn("[Workspace] Failed to collect file mounts", e);
        }
        data.put("mounts", mounts);

        return Result.succeed(data);
    }

    @Post
    @Mapping("/web/workspace/open")
    public Result<WorkspaceMeta> openWorkspace(String path) {
        if (path == null || path.isEmpty()) {
            return Result.failure("Path is required");
        }

        // 挂载别名转真实路径：@alias/sub → 解析到挂载 realPath，后续走工作区同等链路（打开过即落历史）
        if (path.startsWith("@")) {
            try {
                int slash = path.indexOf('/');
                String alias = slash < 0 ? path : path.substring(0, slash);
                org.noear.solon.ai.talents.mount.MountDir mount = engine().getMount(alias);
                if (mount == null || mount.getRealPath() == null) {
                    return Result.failure("挂载不存在: " + alias);
                }
                Path realBase = mount.getRealPath();
                Path real = slash < 0 ? realBase : realBase.resolve(path.substring(slash + 1));
                real = real.toAbsolutePath().normalize();
                // 防越权：解析后路径必须仍在挂载目录下
                if (!real.startsWith(realBase.toAbsolutePath().normalize())) {
                    return Result.failure("非法路径: " + path);
                }
                path = real.toString();
            } catch (Exception e) {
                return Result.failure("挂载路径解析失败: " + e.getMessage());
            }
        }

        try {
            WorkspaceContext wctx = workspaceManager.getOrCreate(path);
            if (wctx != null) {
                return Result.succeed(wctx.getMeta());
            }
            // getOrCreate 已收紧：目录不存在/非法路径返回 null
            return Result.failure("目录不存在: " + path);
        } catch (Exception e) {
            return Result.failure(e.getMessage());
        }
    }

    @Post
    @Mapping("/web/workspace/remove")
    public Result<Void> removeWorkspace(String id) {
        if (id == null || id.isEmpty()) {
            return Result.failure("Id is required");
        }

        // "移除"语义必须同时删历史条目，否则重启后条目重新出现（closeWorkspace 由 removeFromHistory 内部负责，避免双重关闭）
        workspaceManager.removeFromHistory(id);
        return Result.succeed();
    }

    @Get
    @Mapping("/web/workspace/current")
    public Result<WorkspaceMeta> currentWorkspace() {
        WorkspaceContext wctx = currentContext();
        if (wctx != null) {
            return Result.succeed(wctx.getMeta());
        }
        return Result.failure("No current workspace");
    }

    /**
     * 首页入口：将根路径请求转发到静态页面 web.html。
     *
     * @param ctx Solon 请求上下文
     * @throws Throwable 转发异常
     */
    @Get
    @Mapping("/")
    public void index(Context ctx) throws Throwable {
        ctx.forward("/web.html");
    }

    /**
     * 页面元信息接口：供前端启动时一次性获取应用标题、版本号、工作区路径等基础信息。
     *
     * @return 包含 appTitle、appVersion、workspace、workname 的结果对象
     * @throws Exception 读取配置异常
     */
    /**
     * 前端脚本清单：返回所有已加载扩展登记的前端脚本 URL，前端据此动态注入。
     * 各扩展在自己的 Plugin.start() 中向系统属性 "soloncode.frontend.scripts" 追加自身脚本地址，
     * 核心对此无感知。
     *
     * @return 脚本 URL 列表
     */
    @Get
    @Mapping("/web/frontend/scripts")
    public Result<List<String>> frontendScripts() {
        String v = System.getProperty("soloncode.frontend.scripts", "");
        List<String> list = new ArrayList<>();
        if (!v.isEmpty()) {
            for (String s : v.split(",")) {
                s = s.trim();
                if (!s.isEmpty()) list.add(s);
            }
        }
        return Result.succeed(list);
    }

    @Get
    @Mapping("/web/chat/meta")
    public Result<Map> meta() {
        HarnessEngine currentEngine = engine();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("appTitle", Solon.cfg().appTitle());
        data.put("appVersion", AgentFlags.getVersion());
        //更新检查（启动时已异步预热，此处仅读缓存比较）
        if (AgentFlags.checkUpdate()) {
            data.put("updateAvailable", true);
            data.put("latestVersion", AgentFlags.getLastVersion());
        }
        data.put("workspace", currentEngine.getWorkspace());
        data.put("workname", getLastSegment(currentEngine.getWorkspace()));
        // 是否已配置至少一个可用模型，供前端首帧渲染引导面板，避免界面闪现
        boolean modelConfigured = false;
        for (ChatConfig config : currentEngine.getModels()) {
            if (config.isEnabled()) {
                modelConfigured = true;
                break;
            }
        }
        data.put("modelConfigured", modelConfigured);
        return Result.succeed(data);
    }

    /**
     * 从文件路径中提取最后一段（即文件名或目录名）。
     *
     * @param pathStr 完整文件路径字符串
     * @return 路径最后一段，若路径为空则返回空字符串
     */
    private static String getLastSegment(String pathStr) {
        Path path = Paths.get(pathStr);
        Path fileName = path.getFileName();
        return fileName == null ? "" : fileName.toString();
    }

    // ==================== 会话管理 ====================

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
        // 获取当前用户ID（用户认证启用时用于过滤会话，否则返回 null）
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

                    // 用户认证启用时，过滤非当前用户的会话
                    if (userId != null) {
                        String ownerId = meta.getOwnerUserId();
                        // 仅显示当前用户拥有的会话（无 owner 的会话视为旧版未隔离会话，也显示）
                        if (ownerId != null && !ownerId.isEmpty() && !ownerId.equals(userId)) {
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
    public Result deleteSession(@Param("sessionId") String sessionId,
                                @Param(value = "workspace", required = false) String workspace) throws Exception {
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
        // 用户认证启用时，会话路径包含用户 ID 前缀
        String userId = getCurrentUserId();
        Path sessionPath = (userId != null ? sessionsRoot.resolve(userId) : sessionsRoot).resolve(sessionId).normalize();
        if (!sessionPath.startsWith(sessionsRoot)) {
            return Result.failure(400, "Invalid session path");
        }
        boolean sessionPathExists = Files.exists(sessionPath, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        if (sessionPathExists && !Files.isDirectory(sessionPath) && !Files.isSymbolicLink(sessionPath)) {
            return Result.failure(409, "Session path is not a directory");
        }

        Path activeWorkspace = Paths.get(currentEngine.getWorkspace()).toAbsolutePath().normalize();
        boolean activeWorkspaceSession = workspaceRoot.equals(activeWorkspace);
        if (activeWorkspaceSession && webGate().isSessionBusy(engine(), sessionId)) {
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
    public Result<Map> forkSession(@Param("sessionId") String sessionId) throws Exception {
        if (!isValidSessionId(sessionId)) {
            return Result.failure(400, "Invalid sessionId");
        }

        String userId = getCurrentUserId();
        Path sessionsRoot = currentContext().getSessionsRoot();
        Path sourcePath = sessionsRoot.resolve(sessionId).normalize();
        File sourceDir = sourcePath.toFile();

        if (!sourceDir.exists() || !sourceDir.isDirectory()) {
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
    public Result renameSession(@Param("sessionId") String sessionId, @Param("label") String label) throws Exception {
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

        String userId = getCurrentUserId();
        Path sessionPath = currentContext().getSessionPath(sessionId);

        if (!sessionPath.toFile().exists() || !sessionPath.toFile().isDirectory()) {
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
    public Result pinSession(@Param("sessionId") String sessionId,
                             @Param("pinned") boolean pinned) throws Exception {
        if (!isValidSessionId(sessionId)) {
            return Result.failure(400, "Invalid sessionId");
        }

        String userId = getCurrentUserId();
        Path sessionsRoot = currentContext().getSessionsRoot();
        Path sessionPath = sessionsRoot.resolve(sessionId).normalize();
        if (!sessionPath.startsWith(sessionsRoot)) {
            return Result.failure(400, "Invalid session path");
        }

        File sessionDir = sessionPath.toFile();
        if (!sessionDir.exists() || !sessionDir.isDirectory()) {
            return Result.failure(404, "Session not found");
        }

        SessionMeta.updatePinned(sessionDir, pinned);

        return Result.succeed();
    }

    /**
     * 查询可用 AI 模型列表及当前选中模型、推理水平。
     * <p>从引擎配置中获取所有可用模型，若指定了 sessionId 则返回该会话当前选中的模型，
     * 否则返回引擎默认主模型。每项附带 supportsReasoning / reasoningEfforts 等能力字段。</p>
     *
     * @param sessionId 可选的会话 ID，用于获取该会话当前选中的模型
     * @return 包含 list、selected、reasoningEffort 的结果对象
     * @throws Exception 会话查询异常
     */
    @Get
    @Mapping("/web/chat/models")
    public Result<Map> models(@Param(value = "sessionId", required = false) String sessionId) throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        List<Map> list = new ArrayList<>();

        HarnessEngine currentEngine = engine();
        for (ChatConfig config : currentEngine.getModels()) {
            if (config.isEnabled()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("model", config.getModel());
                item.put("name", config.getNameOrModel());
                item.put("description", config.getDescriptionOrModel());
                item.put("contextLength", config.getContextLength());
                item.put("standard", config.getStandardOrProvider());
                ReasoningSupportUtil.ModelCapability cap = ReasoningSupportUtil.resolveCapability(config);
                item.putAll(ReasoningSupportUtil.toCapabilityMap(cap));
                list.add(item);
            }
        }
        list.sort((a, b) -> {
            String nameA = (String) a.getOrDefault("name", "");
            String nameB = (String) b.getOrDefault("name", "");
            return nameA.compareToIgnoreCase(nameB);
        });

        data.put("list", list);

        String selected = "";
        String reasoningEffort = null;
        String thinkingMode = null;

        if (Assert.isNotEmpty(list)) {
            if (Assert.isNotEmpty(sessionId)) {
                AgentSession session = currentEngine.getSession(sessionId);
                selected = session.getContext().getAs(HarnessEngine.CTX_MODEL_SELECTED);

                if (selected != null) {
                    selected = currentEngine.getModelOrDef(selected).getNameOrModel();
                } else {
                    selected = currentEngine.getModelOrDef(null).getNameOrModel();
                }

                reasoningEffort = ReasoningSupportUtil.getSessionEffort(session);
                thinkingMode = ReasoningSupportUtil.getSessionThinkingMode(session);
            } else {
                selected = currentEngine.getModelOrDef(null).getNameOrModel();
            }

            // 防御：默认模型可能被禁用（getModelOrDef 不校验 isEnabled），导致 selected
            // 不在启用列表 list 中，前端 getCurrentModelMeta() 返回 null 后会把
            // 思考模式/推理强度面板隐藏。此处确保 selected 一定落在 list 内。
            if (!containsModelName(list, selected)) {
                selected = (String) list.get(0).get("name");
            }
        }

        data.put("selected", selected);
        data.put("reasoningEffort", reasoningEffort == null ? "" : reasoningEffort);
        data.put("thinkingMode", thinkingMode == null ? "" : thinkingMode);

        // 读取该会话已选中的子代理
        String selectedAgent = "";
        if (Assert.isNotEmpty(sessionId)) {
            try {
                AgentSession session = currentEngine.getSession(sessionId);
                String agentVal = session.getContext().getAs(HarnessEngine.CTX_AGENT_SELECTED);
                selectedAgent = (agentVal != null) ? agentVal : "";
            } catch (Exception ignored) {
                // 会话不存在或已过期
            }
        }
        data.put("selectedAgent", selectedAgent);

        return Result.succeed(data);
    }

    /**
     * 判断 selected 模型名是否存在于已过滤 enabled 的模型列表中。
     */
    private static boolean containsModelName(List<Map> list, String name) {
        if (name == null || list == null || list.isEmpty()) {
            return false;
        }
        for (Map item : list) {
            if (name.equals(item.get("name"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 切换指定会话的 AI 模型 / 推理水平 / 思考模式。
     * <p>将选项写入会话上下文并更新快照，后续该会话的 AI 交互将使用新配置。
     * 思考模式（thinkingMode）与推理强度（reasoningEffort）是独立维度。</p>
     *
     * @param sessionId       会话 ID
     * @param modelName       目标模型名称（可选，仅改 effort 时可省略）
     * @param reasoningEffort 推理水平 low|medium|high|max|auto（可选）
     * @param thinkingMode    思考模式 on|off|auto（可选，独立于推理强度）
     * @return 操作结果
     * @throws Exception 会话操作异常
     */
    @Post
    @Mapping("/web/chat/models/select")
    public Result models_select(@Param("sessionId") String sessionId,
                                @Param(value = "modelName", required = false) String modelName,
                                @Param(value = "reasoningEffort", required = false) String reasoningEffort,
                                @Param(value = "thinkingMode", required = false) String thinkingMode) throws Exception {
        String userId = getCurrentUserId();
        AgentSession session = sessionManager().getSession(sessionId, userId);

        if (Assert.isNotEmpty(modelName)) {
            session.getContext().put(HarnessEngine.CTX_MODEL_SELECTED, modelName);
        }

        // reasoningEffort 参数出现即写入（含空串表示 auto 清除）
        boolean effortProvided = reasoningEffort != null;
        ReasoningSupportUtil.putSessionEffort(session, reasoningEffort, effortProvided);

        // thinkingMode 参数出现即写入（含空串表示不干预/清除）
        boolean modeProvided = thinkingMode != null;
        ReasoningSupportUtil.putSessionThinkingMode(session, thinkingMode, modeProvided);

        session.updateSnapshot();

        return Result.succeed();
    }

    /**
     * 切换指定会话的子代理选择器状态。
     * <p>将选择写入会话上下文并更新快照，后续请求将使用新配置。</p>
     *
     * @param sessionId 会话 ID
     * @param agentName 目标子代理名称（空值或无效值表示使用主 Agent）
     * @return 操作结果
     */
    @Post
    @Mapping("/web/chat/agents/select")
    public Result agents_select(@Param("sessionId") String sessionId,
                                @Param(value = "agentName", required = false) String agentName) throws Exception {
        String userId = getCurrentUserId();
        AgentSession session = sessionManager().getSession(sessionId, userId);
        session.getContext().put(HarnessEngine.CTX_AGENT_SELECTED, agentName != null ? agentName : "");
        session.updateSnapshot();
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
    public Result<List<Map>> messages(@Param("sessionId") String sessionId) throws Exception {
        if (!isValidSessionId(sessionId)) {
            return Result.failure(400, "Invalid sessionId");
        }

        AgentSession session = sessionManager().getSessionIfPresent(sessionId);
        if (session != null) {
            return Result.succeed(readMessagesFromSession(session));
        }

        Path sessionsRoot = currentContext().getSessionsRoot();
        Path sessionsPath = sessionsRoot.resolve(sessionId).normalize();
        if (!sessionsPath.startsWith(sessionsRoot)) {
            return Result.failure(400, "Invalid session path");
        }

        return Result.succeed(readMessagesFromFile(new File(sessionsPath.toFile(), sessionId + ".messages.ndjson")));
    }

    /**
     * 从内存会话实例读取历史消息。
     *
     * @param session 会话实例
     * @return 消息列表
     */
    private List<Map> readMessagesFromSession(AgentSession session) {
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
     * 从 ndjson 消息文件逐行读取历史消息（会话未打开时的回落路径）。
     *
     * @param msgFile 消息文件
     * @return 消息列表
     */
    private List<Map> readMessagesFromFile(File msgFile) throws Exception {
        List<Map> data = new ArrayList<>();

        if (msgFile.exists()) {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(msgFile), "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    ONode node = ONode.ofJson(line);
                    String role = node.get("role").getString();
                    // 助手消息自 solon-ai 4.1 起不再落 content 字段（拆成 text/thinking），须按兼容顺序读
                    String content = MessageLineUtil.readContent(node);

                    if (role != null && content != null) {
                        data.add(buildMessageItem(role, content, node.get("createdAt").getString(),
                                toMetadataMap(node.get("metadata"))));
                    }
                }
            }
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
        String runIdMeta = metaString(metadata, AgentTrace.META_RUN_ID);
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
     * 把 ndjson 行里的 metadata 节点摊平成 Map（元数据均为标量，与内存侧 getMetadata() 对齐）。
     */
    private Map<String, Object> toMetadataMap(ONode metadata) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (metadata != null && metadata.isObject()) {
            for (Map.Entry<String, ONode> entry : metadata.getObject().entrySet()) {
                ONode val = entry.getValue();
                if (val != null && val.isValue()) {
                    map.put(entry.getKey(), val.getString());
                }
            }
        }
        return map;
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
    public Result<Map<String, Object>> messages_lastTrace(@Param("sessionId") String sessionId) {
        if (!isValidSessionId(sessionId)) {
            return Result.failure(400, "Invalid sessionId");
        }

        try {
            Path sessionsRoot = currentContext().getSessionsRoot();
            Path sessionsPath = sessionsRoot.resolve(sessionId).normalize();
            if (!sessionsPath.startsWith(sessionsRoot)) {
                return Result.failure(400, "Invalid session path");
            }

            // 快照不存在说明该会话从未运行过（或为 fork 出来的纯消息副本）：
            // 不走 getSession，避免为其凭空创建内存会话
            File snapshotFile = new File(sessionsPath.toFile(), sessionId + ".snapshot.json");
            if (!snapshotFile.exists()) {
                return Result.succeed(LAST_TRACE_SERVICE.buildLastTrace(null, null, false, null));
            }

            String lastUserMsg = readLastUserMessage(new File(sessionsPath.toFile(), sessionId + ".messages.ndjson"));

            AgentSession session = sessionManager().getSession(sessionId, getCurrentUserId());
            boolean running = webGate().isSessionBusy(engine(), sessionId);

            Map<String, Object> data = LAST_TRACE_SERVICE.buildLastTrace(session, AgentFlags.TRACE_KEY_MAIN, running, lastUserMsg);
            return Result.succeed(data);
        } catch (Throwable e) {
            // 回放属于增强能力，失败即静默降级，不能让它影响会话切换
            LOG.debug("[WebController] last-trace failed for session {}: {}", sessionId, e.getMessage());
            return Result.succeed(LAST_TRACE_SERVICE.buildLastTrace(null, null, false, null));
        }
    }

    /**
     * 读取 ndjson 中最后一条用户消息的内容，用于 trace 对齐校验。
     *
     * @return 最后一条 USER 消息内容；无则返回 null
     */
    private String readLastUserMessage(File msgFile) {
        if (msgFile == null || !msgFile.exists()) {
            return null;
        }

        String lastUser = null;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(msgFile), "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                ONode node = ONode.ofJson(line);
                if ("USER".equals(node.get("role").getString())) {
                    lastUser = MessageLineUtil.readContent(node);
                }
            }
        } catch (Exception e) {
            return null;
        }

        return lastUser;
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
    public Result interruptSession(@Param("sessionId") String sessionId) {
        if (!isValidSessionId(sessionId)) {
            return Result.failure();
        }

        // 按当前请求工作区上下文取 WebGate，避免非默认工作区会话中断时推送串到默认工作区
        webGate().interruptSession(currentContext(), sessionId);

        // 暂停该 session 的活跃 Goal，防止 Goal 调度器在 interrupt 后立即重新触发
        LoopScheduler loopScheduler = loopScheduler();
        if (loopScheduler != null) {
            LoopTask activeGoal = loopScheduler.findActiveGoalInSession(sessionId);
            if (activeGoal != null) {
                loopScheduler.pauseGoal(sessionId, activeGoal.getId());
                LOG.info("[WebController] Goal '{}' paused due to session interrupt", activeGoal.getId());
            }
        }

        return Result.succeed();
    }

    /**
     * 运行中插话（steer）：向正在运行的会话任务插入一条用户消息。
     *
     * <p>消息存入会话级邮箱（transient，不落快照），由 SteerInterceptor 在下一个推理回合
     * 开始时（onReasonStart 采样边界）注入工作记忆，不打断进行中的模型流与工具调用。
     * 参考方案：docs/steering-inject-plan.md（对齐 Codex steering 三件套）。</p>
     *
     * <p>应答契约：200 STEERED=已接受（下一步生效）；409 NOT_RUNNING=会话空闲，前端回落为普通发送；
     * 409 TURN_CHANGED=runId 与当前运行不符，前端转为排队；409 BOX_FULL=邮箱满；
     * 400 EMPTY_TEXT / TEXT_TOO_LONG=参数非法。</p>
     *
     * @param sessionId 会话 ID
     * @param runId     前端所见的当前运行 ID（可选；来自事件信封 runId，防跨任务错投）
     * @param text      插话文本
     * @return 操作结果
     */
    @Post
    @Mapping("/web/chat/steer")
    public Result steerSession(@Param("sessionId") String sessionId,
                               @Param(value = "runId", required = false) String runId,
                               @Param("text") String text) {
        if (!isValidSessionId(sessionId)) {
            return Result.failure(400, "Invalid sessionId");
        }
        if (text == null || text.trim().isEmpty()) {
            return Result.failure(400, "EMPTY_TEXT");
        }
        text = text.trim();
        if (text.length() > SteerInterceptor.MAX_TEXT_LENGTH) {
            return Result.failure(400, "TEXT_TOO_LONG");
        }
        if (!webGate().isSessionBusy(engine(), sessionId)) {
            return Result.failure(409, "NOT_RUNNING");
        }

        AgentSession session = engine().getSession(sessionId);
        if (session == null) {
            return Result.failure(409, "NOT_RUNNING");
        }

        // runId 防护：仅在前端携带 runId 且后端已记录活跃 runId 时比对。
        // 任务刚启动、首个 reason 事件未到达时后端值为 null，此时接受是安全的
        // （守卫1会把注入推迟到第二轮，仍属本任务）
        String activeRunId = (String) session.attrs().get(SteerInterceptor.ATTR_ACTIVE_RUN_ID);
        if (runId != null && activeRunId != null && !runId.equals(activeRunId)) {
            return Result.failure(409, "TURN_CHANGED");
        }

        @SuppressWarnings("unchecked")
        java.util.Queue<String> box = (java.util.Queue<String>) session.attrs()
                .computeIfAbsent(SteerInterceptor.ATTR_STEER_BOX,
                        k -> new java.util.concurrent.ConcurrentLinkedQueue<String>());
        if (box.size() >= SteerInterceptor.MAX_BOX_SIZE) {
            return Result.failure(409, "BOX_FULL");
        }
        box.offer(text);

        // offer 后复查（决策点原子性兜底）：本方法与 SteerInterceptor.onAgentEnd 的
        // attrs().remove(ATTR_STEER_BOX) 存在竞态。若在 remove 之后才 offer，本条会写进
        // 一个无人排空的孤儿队列——既不会生效也不会有 dropped 事件（悬挂）。
        // 此处发现任务已结束或邮箱已被摘掉则回滚本条，改报 NOT_RUNNING 让前端回落为普通发送
        if (!webGate().isSessionBusy(engine(), sessionId)
                || session.attrs().get(SteerInterceptor.ATTR_STEER_BOX) != box) {
            box.remove(text);
            session.attrs().remove(SteerInterceptor.ATTR_STEER_BOX, box);
            return Result.failure(409, "NOT_RUNNING");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "STEERED");
        data.put("queued", box.size());
        return Result.succeed(data);
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
    public Result rewindSession(@Param("sessionId") String sessionId,
                                @Param(value = "count", required = false) Integer count,
                                @Param(value = "anchorRunId", required = false) String anchorRunId,
                                @Param(value = "anchorRole", required = false) String anchorRole) throws Exception {
        if (!isValidSessionId(sessionId)) {
            return Result.failure(400, "Invalid sessionId");
        }
        if (count == null || count <= 0) {
            count = 2; // 默认回退2条（用户+助手）
        }
        if (webGate().isSessionBusy(engine(), sessionId)) {
            return Result.failure(409, "Session is running");
        }

        try {
            AgentSession session = sessionManager().getSession(sessionId, getCurrentUserId());
            SessionRewindService.RewindResult rr = REWIND_SERVICE.rewind(
                    session, AgentFlags.TRACE_KEY_MAIN, anchorRunId, anchorRole, count);

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
     * 获取可用的命令和子代理列表。
     * <p>从引擎的命令注册表中获取所有非 CLI-Only 的命令，
     * 以及所有已注册的子代理（Agent），合并返回给前端用于命令补全和展示。</p>
     *
     * @return 命令/子代理列表，每项包含 name、description、type（command 或 subagent）
     */
    @Get
    @Mapping("/web/chat/hints")
    public Result<List<Map>> hints() {
        List<Map> data = new ArrayList<>();
        HarnessEngine currentEngine = engine();
        for (Command cmd : currentEngine.getCommandRegistry().all()) {
            if (cmd.cliOnly()) {
                continue;
            }
            Map<String, String> item = new LinkedHashMap<>();
            item.put("name", cmd.name());
            item.put("description", cmd.description());
            item.put("type", "command");
            data.add(item);
        }

        for (AgentDefinition definition : currentEngine.getAgentManager().getAgents()) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("name", definition.getName());
            item.put("description", definition.getDescription());
            item.put("type", "subagent");
            data.add(item);
        }

        Set<String> added = new HashSet<>();
        for (SkillDir skill : currentEngine.getSkills()) {
            if (added.contains(skill.getName())) {
                continue;
            } else {
                added.add(skill.getName());
            }

            String desc = skill.getDescription();
            if (desc != null) {
                // 取第一行，并限制最大长度
                int newlineIdx = desc.indexOf('\n');
                if (newlineIdx > 0) {
                    desc = desc.substring(0, newlineIdx);
                }
                if (desc.length() > 30) {
                    desc = desc.substring(0, 30) + "...";
                }
            }

            Map<String, String> item = new LinkedHashMap<>();
            item.put("name", skill.getName());
            item.put("description", desc);
            item.put("mountAlias", skill.getMountAlias());
            item.put("type", "skill");
            data.add(item);
        }

        return Result.succeed(data);
    }

    /**
     * 聊天输入入口：解析请求参数后路由到 WebGate 处理。
     * <p>接收用户输入的文本消息、附件文件、模型选择、推理选项和会话标识，
     * 经安全校验后委派给 {@link WebGate#onChatInput} 进行异步 AI 处理。
     * AI 处理结果通过 WebSocket 实时推送到前端，本接口仅返回简单成功响应。</p>
     *
     * @param ctx             Solon 请求上下文，用于读取请求头
     * @param input           用户输入的文本消息
     * @param attachments     上传的附件文件数组，可为 null
     * @param attachmentTypes 附件类型数组，与 attachments 一一对应
     * @param model           指定的 AI 模型名称，可为 null（使用默认模型）
     * @param sessionId       会话 ID，若为空则从请求头 X-Session-Id 获取
     * @param selectedAgent   子代理选择器指定的名称，可为 null 或空（使用主 Agent）
     * @return 操作结果（AI 结果通过 WebSocket 推送）
     */
    @Mapping("/web/chat/input")
    public Result chat_input(Context ctx, String input, UploadedFile[] attachments, String attachmentTypes[],
                             String model, String sessionId,
                             @Param(value = "reasoningEffort", required = false) String reasoningEffort,
                             @Param(value = "thinkingMode", required = false) String thinkingMode,
                             @Param(value = "selectedAgent", required = false) String selectedAgent) {
        try {
            if (sessionId == null || sessionId.isEmpty()) {
                sessionId = ctx.headerOrDefault("X-Session-Id", "web");
            }
            String sessionCwd = ctx.header("X-Session-Cwd");

            if (!isValidSessionId(sessionId)) {
                ctx.status(400);
                ctx.output("Invalid Session ID");
                return null;
            }

            if (Assert.isNotEmpty(sessionCwd)) {
                if (sessionCwd.contains("..")) {
                    ctx.status(400);
                    ctx.output("Invalid Session Cwd");
                    return null;
                }
            }

            String hitlAction = ctx.param("hitlAction");
            String hitlCallId = ctx.param("hitlCallId");

            // HITL 审批时，将前端回传的 callUuid 写入 session context，供 WebGate 精确定位决策
            if (Assert.isNotEmpty(hitlAction) && Assert.isNotEmpty(hitlCallId)) {
                String userId = getCurrentUserId();
                sessionManager().getSession(sessionId, userId).getContext().put(WebGate.CTX_HITL_CALL_ID, hitlCallId);
            }

            // 路由到 WebGate 处理（AI 结果通过 WebSocket 推送到前端）
            webGate().onChatInput(currentContext(), sessionId, sessionCwd, input, model, attachments, attachmentTypes, hitlAction, null,
                    reasoningEffort, thinkingMode, selectedAgent);

            // 返回简单 JSON，前端通过 WebSocket 接收 AI 结果
            return Result.succeed();
        } catch (Throwable e) {
            LOG.error("[Web] chat_input error: {}", e.getMessage());
            return Result.failure(500, e.getMessage());
        }
    }

    /**
     * UI 动作回传入口（对应 SAEP 2.0 {@code ui.action}）。
     *
     * <p>前端在 UI 块（{@code ui.render} 渲染）上点击动作时调用本接口，将动作封装为
     * {@code {"__ui_action__":{blockId, actionId, formData}}} 的标准回传结构，并复用既有聊天
     * 输入通道（{@link WebGate#onChatInput}）作为一条用户消息下发，使 Agent 在新一轮中响应该动作。
     * 与 HITL 不同，UI 动作不阻塞原工具：它作为独立的用户回合进入，由 LLM 决定后续行为。</p>
     *
     * @param sessionId  会话 ID，若为空则从请求头 X-Session-Id 获取
     * @param blockId    UI 块实例稳定 ID（与 ui.render 的 blockId 对应），必填
     * @param actionId   动作 ID（与 ui.render 的 actions[].id 对应），必填
     * @param formData   动作附带的表单数据，JSON 对象字符串，可为空
     * @param model      指定的 AI 模型名称，可为 null（使用默认模型）
     * @param selectedAgent 子代理选择器指定的名称，可为 null 或空（使用主 Agent）
     * @return 操作结果（Agent 响应通过 WebSocket 推送）
     */
    @Mapping("/web/chat/ui_action")
    public Result chat_ui_action(Context ctx, String sessionId, String blockId, String actionId,
                                  @Param(value = "formData", required = false) String formData,
                                  String model,
                                  @Param(value = "selectedAgent", required = false) String selectedAgent) {
        try {
            if (sessionId == null || sessionId.isEmpty()) {
                sessionId = ctx.headerOrDefault("X-Session-Id", "web");
            }
            String sessionCwd = ctx.header("X-Session-Cwd");

            if (!isValidSessionId(sessionId)) {
                ctx.status(400);
                ctx.output("Invalid Session ID");
                return null;
            }
            if (Assert.isNotEmpty(sessionCwd) && sessionCwd.contains("..")) {
                ctx.status(400);
                ctx.output("Invalid Session Cwd");
                return null;
            }
            if (Assert.isEmpty(blockId) || Assert.isEmpty(actionId)) {
                ctx.status(400);
                ctx.output("blockId and actionId are required");
                return null;
            }

            ONode action = new ONode();
            action.set("blockId", blockId);
            action.set("actionId", actionId);
            if (Assert.isNotEmpty(formData)) {
                try {
                    action.set("formData", ONode.ofJson(formData));
                } catch (Throwable ex) {
                    action.set("formData", new ONode());
                }
            } else {
                action.set("formData", new ONode());
            }
            ONode payload = new ONode();
            payload.set("__ui_action__", action);
            String input = payload.toJson();

            // 复用既有输入通道：作为一条来源为 web 的用户消息下发
            webGate().onChatInput(currentContext(), sessionId, sessionCwd, input, model, null, null, null, "web",
                    null, null, selectedAgent);

            return Result.succeed();
        } catch (Throwable e) {
            LOG.error("[Web] chat_ui_action error: {}", e.getMessage());
            return Result.failure(500, e.getMessage());
        }
    }


    // ==================== Git 集成（委派给 GitService） ====================

    /**
     * Git 状态检测：返回 Git 可用性、仓库初始化状态、当前分支名及变更文件列表。
     * <p>依次执行以下检测：
     * <ol>
     *   <li>git --version 检测 Git 是否安装且可用</li>
     *   <li>git rev-parse 检测工作区是否已初始化为 Git 仓库</li>
     *   <li>git branch --show-current 获取当前分支名</li>
     *   <li>git status --porcelain=v1 解析变更文件（分为 changed、staged、untracked 三类）</li>
     * </ol></p>
     *
     * @return 包含 gitAvailable、initialized、branch、changed、staged、untracked 的结果对象
     * @throws Exception Git 命令执行异常
     */

    private Result<Map> withGitWorkspace(String mount, GitOperation op) throws Exception {
        GitService currentGitService = gitService(); // 已按当前物理工作区隔离（WorkspaceContext 持有独立实例）
        String targetWsId = (mount == null || mount.isEmpty()) ? "workspace" : mount;
        File originalDir = currentGitService.getDefaultWorkspaceDir();
        // 同一工作区内挂载切换存在共享 workspaceDir 的并发风险，用服务实例锁串行化
        synchronized (currentGitService) {
            if (!"workspace".equals(targetWsId)) {
                File targetDir = currentGitService.resolveGitDir(targetWsId);
                currentGitService.setWorkspaceDir(targetDir);
            }
            try {
                return op.execute();
            } finally {
                currentGitService.setWorkspaceDir(originalDir);
            }
        }
    }

    @FunctionalInterface
    private interface GitOperation {
        Result<Map> execute() throws Exception;
    }

    @Get
    @Mapping("/web/chat/git/status")
    public Result<Map> gitStatus(@Param(value = "mount", required = false) String mount) throws Exception {
        String wsId = (mount != null && !mount.isEmpty()) ? mount : null;
        return withGitWorkspace(wsId, () -> gitService().status());
    }

    @Post
    @Mapping("/web/chat/git/init")
    public Result<Map> gitInit(@Param(value = "mount", required = false) String mount,
                               @Param(value = "initialCommit", required = false) Boolean initialCommit) throws Exception {
        String wsId = (mount != null && !mount.isEmpty()) ? mount : null;
        return withGitWorkspace(wsId, () -> gitService().init(initialCommit));
    }

    @Get
    @Mapping("/web/chat/git/diff")
    public Result<Map> gitDiff(@Param(value = "mount", required = false) String mount,
                               @Param(value = "path", required = false) String path) throws Exception {
        String wsId = (mount != null && !mount.isEmpty()) ? mount : null;
        return withGitWorkspace(wsId, () -> gitService().diff(path));
    }

    @Post
    @Mapping("/web/chat/git/stage")
    public Result<Map> gitStage(@Body String body,
                                @Param(value = "mount", required = false) String mount) throws Exception {
        String path = parseJsonPath(body);
        String wsId = (mount != null && !mount.isEmpty()) ? mount : null;
        return withGitWorkspace(wsId, () -> gitService().stage(path));
    }

    @Post
    @Mapping("/web/chat/git/unstage")
    public Result<Map> gitUnstage(@Body String body,
                                  @Param(value = "mount", required = false) String mount) throws Exception {
        String path = parseJsonPath(body);
        String wsId = (mount != null && !mount.isEmpty()) ? mount : null;
        return withGitWorkspace(wsId, () -> gitService().unstage(path));
    }

    @Post
    @Mapping("/web/chat/git/discard")
    public Result<Map> gitDiscard(@Body String body,
                                  @Param(value = "mount", required = false) String mount) throws Exception {
        String path = parseJsonPath(body);
        String wsId = (mount != null && !mount.isEmpty()) ? mount : null;
        return withGitWorkspace(wsId, () -> gitService().discard(path));
    }

    @Get
    @Mapping("/web/chat/git/file-content")
    public Result<Map> gitFileContent(@Param(value = "mount", required = false) String mount,
                                      @Param("path") String path,
                                      @Param(value = "ref", required = false) String ref) throws Exception {
        String wsId = (mount != null && !mount.isEmpty()) ? mount : null;
        return withGitWorkspace(wsId, () -> gitService().fileContent(path, ref));
    }

    @Post
    @Mapping("/web/chat/git/commit")
    public Result<Map> gitCommit(@Body String body,
                                 @Param(value = "mount", required = false) String mount) throws Exception {
        String message = null;
        List<String> files = null;
        if (body != null && !body.trim().isEmpty()) {
            try {
                ONode json = ONode.ofJson(body);
                if (json != null && json.isObject()) {
                    ONode msgNode = json.get("message");
                    if (msgNode != null && msgNode.isString()) {
                        message = msgNode.getString();
                    }
                    ONode filesNode = json.get("files");
                    if (filesNode != null && filesNode.isArray()) {
                        files = new ArrayList<>();
                        for (ONode f : filesNode.getArray()) {
                            files.add(f.getString());
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        final String finalMsg = message;
        final List<String> finalFiles = files;
        String wsId = (mount != null && !mount.isEmpty()) ? mount : null;
        return withGitWorkspace(wsId, () -> gitService().commit(finalMsg, finalFiles));
    }

    @Post
    @Mapping("/web/chat/git/summary")
    public Result<Map> gitSummary(@Param(value = "mount", required = false) String mount,
                                  @Param("sessionId") String sessionId,
                                  @Param("paths") String paths) throws Exception {
        if (sessionId == null || sessionId.isEmpty()) {
            return Result.failure(400, "sessionId is required");
        }
        if (!isValidSessionId(sessionId)) {
            return Result.failure(400, "Invalid sessionId");
        }

        // 解析文件路径列表
        List<String> files = new ArrayList<>();
        if (paths != null && !paths.trim().isEmpty()) {
            try {
                ONode json = ONode.ofJson(paths);
                if (json != null && json.isArray()) {
                    for (ONode f : json.getArray()) {
                        String p = f.getString();
                        if (p != null && !p.isEmpty()) {
                            files.add(p);
                        }
                    }
                }
            } catch (Exception e) {
                return Result.failure(400, "Invalid paths format, expected JSON array");
            }
        }

        String wsId = (mount != null && !mount.isEmpty()) ? mount : null;
        return withGitWorkspace(wsId, () -> gitService().summary(sessionId, files));
    }

    /**
     * 从 JSON 请求体中解析 path 字段。
     *
     * @param body JSON 字符串，如 { "path": "src/App.java" }
     * @return path 值，解析失败返回 null
     */
    private String parseJsonPath(String body) {
        if (body != null && !body.trim().isEmpty()) {
            try {
                ONode json = ONode.ofJson(body);
                if (json != null && json.isObject()) {
                    ONode pathNode = json.get("path");
                    if (pathNode != null && pathNode.isString()) {
                        return pathNode.getString();
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }


    // ==================== 循环任务管理 ====================

    /**
     * 获取当前会话的循环任务列表（含已停用的）。
     */
    @Get
    @Mapping("/web/chat/loop/list")
    public Result<List<Map>> loopList(@Param("sessionId") String sessionId) {
        if (!isValidSessionId(sessionId)) {
            return Result.failure(400, "Invalid sessionId");
        }

        List<LoopTask> tasks = loopScheduler().listAll(sessionId);
        List<Map> data = new ArrayList<>();
        for (LoopTask t : tasks) {
            Map<String, Object> item = buildTaskMap(t);
            data.add(item);
        }
        return Result.succeed(data);
    }

    /**
     * 获取所有会话的循环任务列表。
     * <p>每个任务额外包含 sessionId，供后续删除、启停和手动触发使用。</p>
     */
    @Get
    @Mapping("/web/chat/loop/all")
    public Result<List<Map>> loopAll() {
        LoopScheduler loopScheduler = loopScheduler();
        loopScheduler.restoreAll();
        Map<String, List<LoopTask>> tasksBySession = loopScheduler.listAll();
        List<Map> data = new ArrayList<>();

        for (Map.Entry<String, List<LoopTask>> entry : tasksBySession.entrySet()) {
            for (LoopTask task : entry.getValue()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("sessionId", entry.getKey());
                item.putAll(buildTaskMap(task));
                data.add(item);
            }
        }

        return Result.succeed(data);
    }

    /**
     * 构建任务 Map（通用方法，供 list/get 复用）
     */
    private Map<String, Object> buildTaskMap(LoopTask t) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", t.getId());
        item.put("type", t.getType().name());  // 任务类型（LOOP / GOAL）
        item.put("prompt", t.getPrompt());
        item.put("intervalMinutes", t.getIntervalMinutes());
        if (t.getCron() != null) item.put("cron", t.getCron());
        item.put("enabled", t.isEnabled());
        item.put("cancelled", t.isCancelled());
        item.put("running", t.isRunning());
        item.put("currentIteration", t.getCurrentIteration());
        if (t.getLastResult() != null) item.put("lastResult", t.getLastResult());
        if (t.getLastExecutedAt() != null) item.put("lastExecutedAt", t.getLastExecutedAt().toString());


        item.put("runNow", t.isRunNow());

        // ★ P1: 预算字段
        if (t.getMaxTokens() != null) item.put("maxTokens", t.getMaxTokens());
        if (t.getMaxDurationMs() != null) item.put("maxDurationMs", t.getMaxDurationMs());

        // ★ P0: Goal 状态信息
        if (t.isGoalMode()) {
            GoalState gs = t.getGoalState();
            Map<String, Object> goalMap = new LinkedHashMap<>();
            goalMap.put("condition", gs.getCondition());
            goalMap.put("status", gs.getStatus().name());
            goalMap.put("iteration", t.getCurrentIteration());
            goalMap.put("consumedTokens", gs.getConsumedTokens());
            goalMap.put("maxTokens", gs.getMaxTokens());
            if (gs.getStartEpochMs() > 0) {
                goalMap.put("startedAt", Instant.ofEpochMilli(gs.getStartEpochMs()).toString());
            }

            item.put("goal", goalMap);
        }

        return item;
    }

    /**
     * 获取单个循环任务详情（用于编辑回填）。
     */
    @Get
    @Mapping("/web/chat/loop/get")
    public Result<Map> loopGet(@Param("sessionId") String sessionId,
                               @Param("taskId") String taskId) {
        if (!isValidSessionId(sessionId)) {
            return Result.failure(400, "Invalid sessionId");
        }
        if (taskId == null || taskId.trim().isEmpty()) {
            return Result.failure(400, "taskId is required");
        }

        List<LoopTask> tasks = loopScheduler().listAll(sessionId);
        for (LoopTask t : tasks) {
            if (t.getId().equals(taskId)) {
                return Result.succeed(buildTaskMap(t));
            }
        }
        return Result.failure(404, "Task not found");
    }

    /**
     * 新增循环任务。
     */
    @Post
    @Mapping("/web/chat/loop/add")
    public Result loopAdd(@Param("sessionId") String sessionId,
                          @Param("prompt") String prompt,
                          @Param(value = "intervalMinutes", required = false) Integer intervalMinutes,
                          @Param(value = "cron", required = false) String cron,
                          @Param(value = "type", required = false) String type,
                          @Param(value = "runNow", required = false) Boolean runNow,
                          @Param(value = "maxTokens", required = false) Long maxTokens,
                          @Param(value = "maxDurationMs", required = false) Long maxDurationMs) {
        if (!isValidSessionId(sessionId)) {
            return Result.failure(400, "Invalid sessionId");
        }
        if (prompt == null || prompt.trim().isEmpty()) {
            return Result.failure(400, "prompt is required");
        }


        LoopTask.TaskType taskType = (type != null && "GOAL".equalsIgnoreCase(type))
                ? LoopTask.TaskType.GOAL
                : LoopTask.TaskType.HEARTBEAT;

        // 初始化状态目录
        int interval = intervalMinutes != null ? intervalMinutes : 5;
        LoopTask task = new LoopTask(
                prompt, interval, cron,
                taskType,
                runNow != null && runNow
        );
        // ★ P1: 预算字段
        if (maxTokens != null) task.setMaxTokens(maxTokens);
        if (maxDurationMs != null) task.setMaxDurationMs(maxDurationMs);

        try {
            loopScheduler().schedule(sessionId, task);
        } catch (IllegalStateException e) {
            return Result.failure(400, e.getMessage());
        }

        return Result.succeed(task.getId());
    }

    /**
     * 更新循环任务定义。
     */
    @Post
    @Mapping("/web/chat/loop/update")
    public Result loopUpdate(@Param("sessionId") String sessionId,
                             @Param("taskId") String taskId,
                             @Param(value = "prompt", required = false) String prompt,
                             @Param(value = "intervalMinutes", required = false) Integer intervalMinutes,
                             @Param(value = "cron", required = false) String cron,
                             @Param(value = "type", required = false) String type,
                             @Param(value = "channelNotify", required = false) String channelNotify,
                             @Param(value = "runNow", required = false) Boolean runNow,
                             @Param(value = "maxTokens", required = false) Long maxTokens,
                             @Param(value = "maxDurationMs", required = false) Long maxDurationMs) {
        if (!isValidSessionId(sessionId)) {
            return Result.failure(400, "Invalid sessionId");
        }
        if (taskId == null || taskId.isEmpty()) {
            return Result.failure(400, "taskId is required");
        }

        LoopScheduler loopScheduler = loopScheduler();
        LoopTask existing = loopScheduler.getTaskById(sessionId, taskId);
        if (existing == null) {
            return Result.failure(404, "Task not found");
        }

        // 基于现有任务构建新任务（保留 id、createdAt、expireAt 等）
        int interval = intervalMinutes != null ? intervalMinutes : existing.getIntervalMinutes();
        String effectiveCron = cron != null ? cron : existing.getCron();
        String effectivePrompt = (prompt != null && !prompt.trim().isEmpty()) ? prompt.trim() : existing.getPrompt();
        LoopTask.TaskType newType = (type != null) ? LoopTask.TaskType.valueOf(type.toUpperCase()) : null;

        LoopTask newTask = existing.copyWithUpdate(
                effectivePrompt, interval, effectiveCron,
                newType,
                runNow != null ? runNow : existing.isRunNow(),
                maxTokens,
                maxDurationMs
        );

        // 保留 enabled
        newTask.setEnabled(existing.isEnabled());

        loopScheduler.update(sessionId, taskId, newTask);
        return Result.succeed();
    }

    // ==================== Goal 管理端点 (P0) ====================

    /**
     * 暂停 goal 调度
     */
    @Post
    @Mapping("/web/chat/loop/goal-pause")
    public Result loopGoalPause(@Param("sessionId") String sessionId,
                                @Param("taskId") String taskId) {
        if (!isValidSessionId(sessionId)) {
            return Result.failure(400, "Invalid sessionId");
        }
        if (taskId == null || taskId.isEmpty()) {
            return Result.failure(400, "taskId is required");
        }

        LoopScheduler loopScheduler = loopScheduler();
        LoopTask task = loopScheduler.getTaskById(sessionId, taskId);
        if (task == null) {
            return Result.failure(404, "Task not found");
        }
        if (!task.isGoalMode()) {
            return Result.failure(400, "Task has no goal");
        }

        GoalState gs = task.getGoalState();
        if (gs.getStatus() != GoalState.Status.PURSUING) {
            return Result.failure(400, "Goal cannot be paused in state: " + gs.getStatus());
        }

        loopScheduler.pauseGoal(sessionId, taskId);
        return Result.succeed();
    }

    /**
     * 恢复 goal 调度
     */
    @Post
    @Mapping("/web/chat/loop/goal-resume")
    public Result loopGoalResume(@Param("sessionId") String sessionId,
                                 @Param("taskId") String taskId) {
        if (!isValidSessionId(sessionId)) {
            return Result.failure(400, "Invalid sessionId");
        }
        if (taskId == null || taskId.isEmpty()) {
            return Result.failure(400, "taskId is required");
        }

        LoopScheduler loopScheduler = loopScheduler();
        LoopTask task = loopScheduler.getTaskById(sessionId, taskId);
        if (task == null) {
            return Result.failure(404, "Task not found");
        }
        if (!task.isGoalMode()) {
            return Result.failure(400, "Task has no goal");
        }

        GoalState gs = task.getGoalState();
        if (gs.getStatus() != GoalState.Status.PAUSED) {
            return Result.failure(400, "Goal cannot be resumed in state: " + gs.getStatus()
                    + " (only PAUSED or BLOCKED can be resumed)");
        }

        loopScheduler.resumeGoal(sessionId, taskId);
        return Result.succeed();
    }

    /**
     * 清除 goal（任务保留，仅清除 goal 标记）
     */
    @Post
    @Mapping("/web/chat/loop/goal-clear")
    public Result loopGoalClear(@Param("sessionId") String sessionId,
                                @Param("taskId") String taskId) {
        if (!isValidSessionId(sessionId)) {
            return Result.failure(400, "Invalid sessionId");
        }
        if (taskId == null || taskId.isEmpty()) {
            return Result.failure(400, "taskId is required");
        }

        LoopScheduler loopScheduler = loopScheduler();
        LoopTask task = loopScheduler.getTaskById(sessionId, taskId);
        if (task == null) {
            return Result.failure(404, "Task not found");
        }
        if (!task.isGoalMode()) {
            return Result.failure(400, "Task has no goal");
        }

        loopScheduler.clearGoal(sessionId, taskId);
        return Result.succeed();
    }

    /**
     * 获取 goal 详细状态（含完整评估历史）
     */
    @Post
    @Mapping("/web/chat/loop/goal-status")
    public Result<Map> loopGoalStatus(@Param("sessionId") String sessionId,
                                      @Param("taskId") String taskId) {
        if (!isValidSessionId(sessionId)) {
            return Result.failure(400, "Invalid sessionId");
        }
        if (taskId == null || taskId.isEmpty()) {
            return Result.failure(400, "taskId is required");
        }

        LoopTask task = loopScheduler().getTaskById(sessionId, taskId);
        if (task == null) {
            return Result.failure(404, "Task not found");
        }
        if (!task.isGoalMode()) {
            return Result.failure(400, "Task has no goal");
        }

        GoalState gs = task.getGoalState();
        Map<String, Object> goalMap = new LinkedHashMap<>();
        goalMap.put("condition", gs.getCondition());
        goalMap.put("status", gs.getStatus().name());
        goalMap.put("iteration", task.getCurrentIteration());
        goalMap.put("consumedTokens", gs.getConsumedTokens());
        goalMap.put("maxTokens", gs.getMaxTokens());

        if (gs.getStartEpochMs() > 0) {
            goalMap.put("startedAt", Instant.ofEpochMilli(gs.getStartEpochMs()).toString());
        }

        return Result.succeed(goalMap);
    }

    /**
     * 删除循环任务。
     */
    @Post
    @Mapping("/web/chat/loop/remove")
    public Result loopRemove(@Param("sessionId") String sessionId, @Param("taskId") String taskId) {
        if (!isValidSessionId(sessionId)) {
            return Result.failure(400, "Invalid sessionId");
        }
        if (taskId == null || taskId.isEmpty()) {
            return Result.failure(400, "taskId is required");
        }

        LoopScheduler loopScheduler = loopScheduler();
        LoopTask task = loopScheduler.getTaskById(sessionId, taskId);
        if (task == null) {
            return Result.failure(400, "the task does not exist.");
        }

        loopScheduler.remove(sessionId, task);
        return Result.succeed();
    }

    /**
     * 启用/停用循环任务（toggle）。
     */
    @Post
    @Mapping("/web/chat/loop/toggle")
    public Result loopToggle(@Param("sessionId") String sessionId, @Param("taskId") String taskId) {
        if (!isValidSessionId(sessionId)) {
            return Result.failure(400, "Invalid sessionId");
        }
        if (taskId == null || taskId.isEmpty()) {
            return Result.failure(400, "taskId is required");
        }

        loopScheduler().toggle(sessionId, taskId);
        return Result.succeed();
    }

    /**
     * 手动触发一次循环任务执行。
     */
    @Post
    @Mapping("/web/chat/loop/trigger")
    public Result loopTrigger(@Param("sessionId") String sessionId, @Param("taskId") String taskId) {
        if (!isValidSessionId(sessionId)) {
            return Result.failure(400, "Invalid sessionId");
        }
        if (taskId == null || taskId.isEmpty()) {
            return Result.failure(400, "taskId is required");
        }

        loopScheduler().trigger(sessionId, taskId);
        return Result.succeed();
    }


    // ==================== 工具方法 ====================

    /**
     * 校验 web 会话 ID 格式（白名单方式，防止路径遍历攻击）
     *
     * @param sessionId 会话 ID
     * @return true 表示合法
     */
    private static boolean isValidSessionId(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return false;
        }
        // Web 会话以 web 开头；桌面端的持久化会话使用正整数主键。
        // 两类均使用严格白名单，保证后续 resolve 后不会出现路径穿越。
        return sessionId.matches("^web(-[a-zA-Z0-9._-]+)?$")
                || sessionId.matches("^[1-9][0-9]{0,18}$");
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

        HarnessEngine currentEngine = engine();
        Path todoPath = currentEngine.getTodoTalent().getTodoPath(currentEngine.getWorkspace(), sessionId);

        Map<String, Object> data = new LinkedHashMap<>();

        if (!Files.exists(todoPath)) {
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
            String raw = new String(Files.readAllBytes(todoPath), "UTF-8");
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

    /**
     * 获取指定会话的消息排队（任务排队）。
     * <p>从会话目录下 {@code queue-tasks.json} 读取（兼容旧名 {@code queue.json}）。
     * 仅作为前端队列的落盘缓存，不由服务端调度发送。
     * V1 仅持久化文本与模型元数据，不保存浏览器附件二进制。</p>
     *
     * @param sessionId 会话 ID
     * @return 包含 exists、items、updatedAt 的结果对象
     */
    @Get
    @Mapping("/web/chat/queue")
    public Result<Map> getQueue(@Param("sessionId") String sessionId) {
        if (!isValidSessionId(sessionId)) {
            return Result.failure(400, "Invalid sessionId");
        }

        Path queuePath = resolveSessionQueuePath(sessionId);
        if (queuePath == null) {
            return Result.failure(400, "Invalid session path");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        Path legacyPath = queuePath.getParent() != null
                ? queuePath.getParent().resolve("queue.json") : null;
        Path readPath = Files.exists(queuePath) ? queuePath
                : (legacyPath != null && Files.exists(legacyPath) ? legacyPath : null);

        if (readPath == null) {
            data.put("exists", false);
            data.put("items", new ArrayList<>());
            data.put("updatedAt", 0L);
            return Result.succeed(data);
        }

        try {
            String raw = new String(Files.readAllBytes(readPath), "UTF-8");
            ONode root = ONode.ofJson(raw);
            List<Map> items = new ArrayList<>();
            long updatedAt = 0L;

            if (root != null && root.isObject()) {
                ONode updatedNode = root.get("updatedAt");
                if (updatedNode != null && !updatedNode.isNull()) {
                    try {
                        updatedAt = updatedNode.getLong();
                    } catch (Exception ignored) {
                    }
                }
                ONode itemsNode = root.get("items");
                if (itemsNode != null && itemsNode.isArray()) {
                    int limit = 10;
                    int count = 0;
                    for (ONode itemNode : itemsNode.getArray()) {
                        if (count >= limit) break;
                        Map<String, Object> item = sanitizeQueueItem(itemNode);
                        if (item != null) {
                            items.add(item);
                            count++;
                        }
                    }
                }
            }

            data.put("exists", true);
            data.put("items", items);
            data.put("updatedAt", updatedAt);
            return Result.succeed(data);
        } catch (Exception e) {
            LOG.error("Failed to read queue-tasks for session {}: {}", sessionId, e.getMessage());
            return Result.failure(500, "Queue read failed");
        }
    }

    /**
     * 整表覆盖保存会话消息排队到 {@code queue-tasks.json}。
     * <p>请求体：{@code {"sessionId":"web-xxx","items":[{id,text,displayText,model,reasoningEffort,createdAt}]}}。
     * items 为空数组时删除 queue-tasks.json（及旧名 queue.json）。</p>
     *
     * @param body JSON 请求体
     * @return 操作结果
     */
    @Post
    @Mapping("/web/chat/queue")
    public Result<Map> saveQueue(@Body String body) {
        if (body == null || body.trim().isEmpty()) {
            return Result.failure(400, "Body is required");
        }

        try {
            ONode root = ONode.ofJson(body);
            if (root == null || !root.isObject()) {
                return Result.failure(400, "Invalid JSON body");
            }

            String sessionId = root.get("sessionId").getString();
            if (!isValidSessionId(sessionId)) {
                return Result.failure(400, "Invalid sessionId");
            }

            Path queuePath = resolveSessionQueuePath(sessionId);
            if (queuePath == null) {
                return Result.failure(400, "Invalid session path");
            }
            Path legacyPath = queuePath.getParent() != null
                    ? queuePath.getParent().resolve("queue.json") : null;

            List<Map<String, Object>> items = new ArrayList<>();
            ONode itemsNode = root.get("items");
            if (itemsNode != null && itemsNode.isArray()) {
                int limit = 10;
                int count = 0;
                for (ONode itemNode : itemsNode.getArray()) {
                    if (count >= limit) break;
                    Map<String, Object> item = sanitizeQueueItem(itemNode);
                    if (item != null) {
                        items.add(item);
                        count++;
                    }
                }
            }

            long updatedAt = System.currentTimeMillis();
            ONode clientUpdated = root.get("updatedAt");
            if (clientUpdated != null && !clientUpdated.isNull()) {
                try {
                    long ts = clientUpdated.getLong();
                    if (ts > 0) updatedAt = ts;
                } catch (Exception ignored) {
                }
            }

            // 空队列：删除新/旧文件，避免会话目录堆积空文件
            if (items.isEmpty()) {
                if (Files.exists(queuePath)) {
                    Files.delete(queuePath);
                }
                if (legacyPath != null && Files.exists(legacyPath)) {
                    Files.delete(legacyPath);
                }
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("exists", false);
                data.put("items", new ArrayList<>());
                data.put("updatedAt", 0L);
                return Result.succeed(data);
            }

            // 会话目录可能尚未创建（首次发消息前）；若不存在则创建
            Path sessionDir = queuePath.getParent();
            if (sessionDir != null && !Files.exists(sessionDir)) {
                Files.createDirectories(sessionDir);
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("version", 1);
            payload.put("updatedAt", updatedAt);
            payload.put("items", items);

            String json = ONode.ofBean(payload, Feature.Write_PrettyFormat).toJson();
            Path tempPath = queuePath.resolveSibling(queuePath.getFileName() + ".tmp");
            Files.write(tempPath, json.getBytes("UTF-8"));
            try {
                Files.move(tempPath, queuePath,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tempPath, queuePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            // 迁移后清理旧文件名，避免双份
            if (legacyPath != null && Files.exists(legacyPath)) {
                try {
                    Files.delete(legacyPath);
                } catch (Exception ignored) {
                }
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("exists", true);
            data.put("items", items);
            data.put("updatedAt", updatedAt);
            return Result.succeed(data);
        } catch (Exception e) {
            LOG.error("Failed to save queue-tasks: {}", e.getMessage());
            return Result.failure(500, "Queue save failed");
        }
    }

    /**
     * 解析并校验会话目录下的 queue-tasks.json 路径（防止路径穿越）。
     */
    private Path resolveSessionQueuePath(String sessionId) {
        String userId = getCurrentUserId();
        Path sessionsRoot = currentContext().getSessionsRoot();
        Path sessionPath = sessionsRoot.resolve(sessionId).normalize();
        if (!sessionPath.startsWith(sessionsRoot)) {
            return null;
        }
        return sessionPath.resolve("queue-tasks.json");
    }

    /**
     * 清洗单条队列项：只保留可安全序列化的字段，截断过长文本，忽略附件二进制。
     */
    private Map<String, Object> sanitizeQueueItem(ONode itemNode) {
        if (itemNode == null || !itemNode.isObject()) {
            return null;
        }

        String text = safeQueueString(itemNode.get("text"), 20000);
        String displayText = safeQueueString(itemNode.get("displayText"), 500);
        if ((text == null || text.isEmpty()) && (displayText == null || displayText.isEmpty())) {
            // V1 不持久化附件；无文本的纯附件项不落盘
            return null;
        }

        String id = safeQueueString(itemNode.get("id"), 80);
        if (id == null || id.isEmpty()) {
            id = "q_" + Long.toHexString(System.currentTimeMillis()) + "_" + Integer.toHexString((int) (Math.random() * 0xffff));
        }

        String model = safeQueueString(itemNode.get("model"), 200);
        String modelName = safeQueueString(itemNode.get("modelName"), 200);
        String selectedAgent = safeQueueString(itemNode.get("selectedAgent"), 128);
        String reasoningEffort = safeQueueString(itemNode.get("reasoningEffort"), 50);
        String thinkingMode = safeQueueString(itemNode.get("thinkingMode"), 50);

        long createdAt = System.currentTimeMillis();
        ONode createdNode = itemNode.get("createdAt");
        if (createdNode != null && !createdNode.isNull()) {
            try {
                long ts = createdNode.getLong();
                if (ts > 0) createdAt = ts;
            } catch (Exception ignored) {
            }
        }

        if (displayText == null || displayText.isEmpty()) {
            displayText = text != null ? text : "";
            if (displayText.length() > 60) {
                displayText = displayText.substring(0, 60) + "…";
            }
        }

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("text", text != null ? text : "");
        item.put("displayText", displayText);
        if (model != null && !model.isEmpty()) {
            item.put("model", model);
        }
        if (modelName != null && !modelName.isEmpty()) {
            item.put("modelName", modelName);
        }
        if (selectedAgent != null && !selectedAgent.isEmpty()) {
            item.put("selectedAgent", selectedAgent);
        }
        if (reasoningEffort != null && !reasoningEffort.isEmpty()) {
            item.put("reasoningEffort", reasoningEffort);
        }
        if (thinkingMode != null && !thinkingMode.isEmpty()) {
            item.put("thinkingMode", thinkingMode);
        }
        item.put("createdAt", createdAt);
        // 附件不持久化；保留 hasFiles 标记，便于前端提示
        boolean hasFiles = false;
        ONode hasFilesNode = itemNode.get("hasFiles");
        if (hasFilesNode != null && !hasFilesNode.isNull()) {
            try {
                hasFiles = hasFilesNode.getBoolean();
            } catch (Exception ignored) {
            }
        }
        if (!hasFiles) {
            ONode filesNode = itemNode.get("files");
            if (filesNode != null && filesNode.isArray() && filesNode.getArray().size() > 0) {
                hasFiles = true;
            }
        }
        if (hasFiles) {
            item.put("hasFiles", true);
        }
        return item;
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

    // ==================== 文件浏览（委派给 FileService） ====================

    @Get
    @Mapping("/web/chat/filer/workspaces")
    public Result<List<Map>> fileWorkspaces() throws Exception {
        return fileService().listWorkspaces();
    }

    /**
     * 工作区文件树浏览接口。
     */
    @Get
    @Mapping("/web/chat/filer/tree")
    public Result<List<Map>> fileTree(@Param(value = "mount", required = false) String workspace,
                                      @Param(value = "path", required = false) String path,
                                      @Param(value = "depth", required = false) Integer depth) throws Exception {
        return fileService().tree(workspace, path, depth);
    }

    /**
     * 工作区文件搜索接口。
     */
    @Get
    @Mapping("/web/chat/filer/search")
    public Result<List<Map>> fileSearch(@Param(value = "mount", required = false) String workspace,
                                        @Param("keyword") String keyword) throws Exception {
        return fileService().search(workspace, keyword);
    }

    /**
     * 读取工作区文件内容接口。
     */
    @Get
    @Mapping("/web/chat/filer/read")
    public Result<Map> fileRead(@Param(value = "mount", required = false) String workspace,
                                @Param("path") String path) throws Exception {
        return fileService().read(workspace, path);
    }

    /**
     * 读取工作区文件原始二进制内容（用于图片、视频等媒体文件展示）。
     *
     * <p>直接以原始字节流输出文件内容，并设置正确的 Content-Type，
     * 以便浏览器直接渲染图片或视频。</p>
     */
    @Get
    @Mapping("/web/chat/filer/read-raw")
    public void fileReadRaw(Context ctx,
                            @Param(value = "mount", required = false) String mount,
                            @Param("path") String path) throws Exception {
        if (path == null || path.trim().isEmpty()) {
            ctx.status(400);
            ctx.output("Path is required");
            return;
        }
        try {
            Path targetPath = fileService().resolveFilePath(mount, path);
            byte[] bytes = Files.readAllBytes(targetPath);
            String contentType = guessContentType(path);
            ctx.contentType(contentType);
            ctx.headerSet("Cache-Control", "private, max-age=3600");
            ctx.output(bytes);
        } catch (IllegalArgumentException e) {
            ctx.status(404);
            ctx.output(e.getMessage());
        } catch (SecurityException e) {
            ctx.status(403);
            ctx.output(e.getMessage());
        } catch (Exception e) {
            ctx.status(500);
            ctx.output("Failed to read file: " + e.getMessage());
        }
    }

    /**
     * 根据文件扩展名推测 MIME 类型（用于原始文件输出）。
     */
    private String guessContentType(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".ico")) return "image/x-icon";
        if (lower.endsWith(".bmp")) return "image/bmp";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".mov")) return "video/quicktime";
        if (lower.endsWith(".avi")) return "video/x-msvideo";
        if (lower.endsWith(".mkv")) return "video/x-matroska";
        if (lower.endsWith(".ogg")) return "video/ogg";
        return "application/octet-stream";
    }
}
