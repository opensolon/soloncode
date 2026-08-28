package org.noear.solon.codecli.portal.desktop;

import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.talents.mount.MountDir;
import org.noear.solon.annotation.*;
import org.noear.solon.codecli.config.AgentFlags;
import org.noear.solon.codecli.config.AgentSettings;
import org.noear.solon.codecli.config.models.ModelApiUrl;
import org.noear.solon.codecli.config.models.ModelInfo;
import org.noear.solon.codecli.config.models.ModelsAdapter;
import org.noear.solon.codecli.config.models.ModelsAdapterManager;
import org.noear.solon.codecli.command.builtin.GoalState;
import org.noear.solon.codecli.command.builtin.LoopScheduler;
import org.noear.solon.codecli.command.builtin.LoopTask;
import org.noear.solon.codecli.session.MessageLineUtil;
import org.noear.solon.codecli.session.SessionManager;
import org.noear.solon.codecli.workspace.WorkspaceDataUtil;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Result;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.net.URI;
import java.time.Instant;

/**
 * Desktop Controller
 *
 * @author bai
 */
public class WsController {
    private static final Logger LOG = LoggerFactory.getLogger(WsController.class);

    private final HarnessEngine engine;
    private final AgentSettings settings;
    private final ModelsAdapterManager modelsAdapterManager;
    private final WsGate wsGate;
    private final LoopScheduler loopScheduler;
    private final SessionManager sessionManager;

    public WsController(HarnessEngine engine, AgentSettings settings, WsGate wsGate, LoopScheduler loopScheduler,
                        SessionManager sessionManager) {
        this.engine = engine;
        this.settings = settings;
        this.wsGate = wsGate;
        this.loopScheduler = loopScheduler;
        this.sessionManager = sessionManager;
        this.modelsAdapterManager = ModelsAdapterManager.getInstance();

        if (loopScheduler != null) {
            loopScheduler.addBusyChecker(sessionId -> isDesktopSessionId(sessionId) && wsGate.isSessionBusy(sessionId));
            loopScheduler.addTaskExecutor((sessionId, prompt, agentName) -> {
                if (!isDesktopSessionId(sessionId)) {
                    return null;
                }
                return wsGate.runGoalRoundAndCapture(sessionId, prompt, agentName);
            });
        }
    }

    /**
     * 获取消息详细记录信息
     */
    @Get
    @Mapping("/desktop/version")
    public Result<Map> version() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", AgentFlags.getVersion());
        data.put("workspace", engine.getWorkspace());
        data.put("pid", currentProcessId());
        data.put("desktopManaged", "1".equals(System.getenv("SOLONCODE_DESKTOP_MANAGED")));
        return Result.succeed(data);
    }

    private long currentProcessId() {
        try {
            String runtimeName = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            return Long.parseLong(runtimeName.split("@", 2)[0]);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /**
     * 重新扫描桌面端指定的挂载池，使新创建的 Skill/Agent 立即进入运行时。
     */
    @Post
    @Mapping("/desktop/settings/mounts/refresh")
    public Result mountsRefresh(@Param("alias") String alias) {
        if (Assert.isEmpty(alias)) {
            return Result.failure("alias is required");
        }

        MountDir mountDir = engine.getMount(alias);
        if (mountDir == null) {
            return Result.failure("挂载池不存在: " + alias);
        }
        if (!mountDir.isEnabled()) {
            return Result.failure("挂载池未启用: " + alias);
        }

        try {
            engine.refreshMount(alias);
            return Result.succeed("刷新成功");
        } catch (Exception e) {
            LOG.warn("[Desktop] Failed to refresh mount {}: {}", alias, e.getMessage());
            return Result.failure("刷新挂载池失败");
        }
    }


    /**
     * 通过 ModelsAdapterManager 从远程 API 获取可用模型列表
     */
    @Post
    @Mapping("/desktop/chat/models/fetch")
    public Result<List<Map>> fetchModels(Context ctx) throws Exception {
        ONode root = ONode.ofJson(ctx.body());
        return fetchModels(root.get("apiUrl").getString(),
                root.get("apiKey").getString(),
                root.get("provider").getString(),
                root.get("model").getString());
    }

    /** 兼容只支持 GET 的早期桌面客户端；新版客户端仍优先使用 POST，避免密钥出现在 URL。 */
    @Get
    @Mapping("/desktop/chat/models/fetch")
    public Result<List<Map>> fetchModelsLegacy(@Param("apiUrl") String apiUrl,
                                                @Param(value = "apiKey", required = false) String apiKey,
                                                @Param(value = "provider", required = false) String provider,
                                                @Param(value = "model", required = false) String model) throws Exception {
        return fetchModels(apiUrl, apiKey, provider, model);
    }

    private Result<List<Map>> fetchModels(String apiUrl, String apiKey, String provider, String model) throws Exception {
        if (Assert.isEmpty(apiUrl)) {
            return Result.failure("apiUrl is required");
        }
        if (apiUrl.length() > 2048 || (apiKey != null && apiKey.length() > 8192)
                || (provider != null && provider.length() > 64) || (model != null && model.length() > 256)) {
            return Result.failure(400, "Model configuration is too long");
        }
        try {
            URI uri = URI.create(apiUrl);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || Assert.isEmpty(uri.getHost())) {
                return Result.failure(400, "Invalid apiUrl");
            }
        } catch (RuntimeException e) {
            return Result.failure(400, "Invalid apiUrl");
        }

        ModelsAdapter modelsAdapter = modelsAdapterManager.getAdapter(provider);
        String baseUrl = modelsAdapter.deriveBaseUrl(apiUrl);
        List<ModelInfo> models = modelsAdapter.fetchModels(settings.getGeneral().getUserAgent(), baseUrl, null, apiKey);

        if (models.isEmpty() && Assert.isNotEmpty(model)) {
            ChatModel chatModel = ChatModel.of(apiUrl)
                    .apiKey(apiKey)
                    .standard(provider)
                    .model(model)
                    .build();
            chatModel.prompt("hi").call();

            models.add(ModelInfo.builder()
                    .id(model)
                    .object("model")
                    .created(System.currentTimeMillis() / 1000)
                    .ownedBy(Assert.isEmpty(provider) ? "openai-compatible" : provider)
                    .build());
        }

        List<Map> list = new ArrayList<>();
        for (ModelInfo mi : models) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", mi.getId());
            item.put("object", mi.getObject());
            item.put("displayName", mi.getDisplayName());
            item.put("ownedBy", mi.getOwnedBy());
            item.put("owned_by", mi.getOwnedBy());
            item.put("type", mi.getType());
            item.put("maxInputTokens", mi.getMaxInputTokens());
            item.put("max_input_tokens", mi.getMaxInputTokens());
            item.put("maxTokens", mi.getMaxTokens());
            item.put("max_tokens", mi.getMaxTokens());
            if (mi.getMaxInputTokens() != null && mi.getMaxInputTokens() > 0) {
                item.put("contextLength", mi.getMaxInputTokens());
                item.put("context_length", mi.getMaxInputTokens());
            } else if (mi.getMaxTokens() != null && mi.getMaxTokens() > 0) {
                item.put("contextLength", mi.getMaxTokens());
                item.put("context_length", mi.getMaxTokens());
            }

            ChatConfig config = new ChatConfig();
            config.setName(mi.getId());
            config.setApiUrl(apiUrl);
            config.setApiKey(apiKey);
            config.setModel(mi.getId());
            if (Assert.isNotEmpty(provider)) {
                config.setStandard(provider);
            }
            engine.removeModel(mi.getId());
            engine.addModel(config);
            list.add(item);
        }

        return Result.succeed(list);
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
        if (!isDesktopSessionId(sourceId) || !isDesktopSessionId(targetId) || sourceId.equals(targetId)) {
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

    private boolean isDesktopSessionId(String value) {
        return value != null && value.matches("[0-9]{1,18}");
    }

    /** 获取桌面会话的服务端消息，用于可靠定位回退位置。 */
    @Get
    @Mapping("/desktop/chat/messages")
    public Result<List<Map>> messages(@Param("sessionId") String sessionId) {
        if (!isDesktopSessionId(sessionId)) {
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
        if (!isDesktopSessionId(sessionId) || count <= 0 || count > 100_000) {
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

    private Path resolveSessionMessageFile(String sessionId) {
        Path wsSessionsRoot = WorkspaceDataUtil.sessionsPath(engine.getWorkspace());
        Path sessionDir = wsSessionsRoot.resolve(sessionId).normalize();
        if (!sessionDir.startsWith(wsSessionsRoot)) {
            throw new IllegalArgumentException("Invalid session path");
        }
        return sessionDir.resolve(sessionId + ".messages.ndjson");
    }

    /** 删除桌面会话的服务端历史；仅允许数字 ID 和已存在的绝对工作区。 */
    @Post
    @Mapping("/desktop/chat/sessions/delete")
    public Result deleteSession(Context ctx) throws Exception {
        ONode root = ONode.ofJson(ctx.body());
        String sessionId = root.get("sessionId").getString();
        if (!isDesktopSessionId(sessionId)) {
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

    /** 当前桌面会话的 Goal 列表。 */
    @Get
    @Mapping("/desktop/chat/goals/list")
    public Result<List<Map>> goalsList(@Param("sessionId") String sessionId) {
        if (!isDesktopSessionId(sessionId) || loopScheduler == null) {
            return Result.failure(400, "Invalid session id");
        }
        loopScheduler.restore(sessionId);
        List<Map> items = new ArrayList<>();
        for (LoopTask task : loopScheduler.listAll(sessionId)) {
            if (task.isGoalMode()) {
                items.add(buildGoalTaskMap(task));
            }
        }
        return Result.succeed(items);
    }

    /** 从对话输入框启动 Goal；目标正文通过 JSON 传输，不经过命令行解析。 */
    @Post
    @Mapping("/desktop/chat/goals/add")
    public Result goalsAdd(Context ctx) throws Exception {
        if (loopScheduler == null) {
            return Result.failure(503, "Goal service is unavailable");
        }
        ONode root = ONode.ofJson(ctx.body());
        String sessionId = root.get("sessionId").getString();
        String prompt = root.get("prompt").getString();
        if (!isDesktopSessionId(sessionId)) {
            return Result.failure(400, "Invalid session id");
        }
        if (Assert.isEmpty(prompt) || prompt.trim().length() > 20_000) {
            return Result.failure(400, "Goal prompt is required and must not exceed 20000 characters");
        }

        Long maxTokens = optionalPositiveLong(root, "maxTokens", 1_000_000_000L);
        Long maxDurationMinutes = optionalPositiveLong(root, "maxDurationMinutes", 525_600L);
        Integer maxIterations = optionalNonNegativeInt(root, "maxIterations", 10_000);
        if (maxTokens != null && maxTokens < 0 || maxDurationMinutes != null && maxDurationMinutes < 0
                || maxIterations != null && maxIterations < 0) {
            return Result.failure(400, "Invalid Goal budget");
        }

        try {
            wsGate.configureGoalSession(
                    sessionId,
                    root.get("modelName").getString(),
                    root.get("agent").getString(),
                    root.get("workspace").getString(),
                    root.get("reasoningEffort").getString());

            synchronized (loopScheduler) {
                LoopTask active = loopScheduler.findActiveGoalInSession(sessionId);
                if (active != null) {
                    return Result.failure(409, "A Goal is already active in this session");
                }
                LoopTask task = new LoopTask(prompt.trim(), 0, null, LoopTask.TaskType.GOAL, true);
                if (maxTokens != null) {
                    task.setMaxTokens(maxTokens);
                }
                if (maxDurationMinutes != null) {
                    task.setMaxDurationMs(Math.multiplyExact(maxDurationMinutes, 60_000L));
                }
                if (maxIterations != null) {
                    task.getGoalState().setMaxIterations(maxIterations);
                }
                loopScheduler.schedule(sessionId, task);
                return Result.succeed(task.getId());
            }
        } catch (IllegalArgumentException | ArithmeticException error) {
            return Result.failure(400, error.getMessage());
        } catch (IllegalStateException error) {
            return Result.failure(409, error.getMessage());
        }
    }

    @Post
    @Mapping("/desktop/chat/goals/update")
    public Result goalsUpdate(Context ctx) throws Exception {
        if (loopScheduler == null) {
            return Result.failure(503, "Goal service is unavailable");
        }
        ONode root = ONode.ofJson(ctx.body());
        String sessionId = root.get("sessionId").getString();
        String taskId = root.get("taskId").getString();
        if (!isDesktopSessionId(sessionId) || Assert.isEmpty(taskId) || taskId.length() > 64) {
            return Result.failure(400, "Invalid Goal request");
        }

        loopScheduler.restore(sessionId);
        LoopTask task = loopScheduler.getTaskById(sessionId, taskId);
        if (task == null || !task.isGoalMode()) {
            return Result.failure(404, "Goal not found");
        }
        if (task.getGoalState().getStatus().isTerminal()) {
            return Result.failure(409, "Completed Goal cannot be edited");
        }

        String objective = root.getOrNull("prompt") == null
                ? task.getGoalState().getCondition() : root.get("prompt").getString().trim();
        if (Assert.isEmpty(objective) || objective.length() > 20_000) {
            return Result.failure(400, "Goal prompt is required and must not exceed 20000 characters");
        }
        Long maxTokens = optionalNonNegativeLong(root, "maxTokens", 1_000_000_000L);
        Integer maxIterations = optionalNonNegativeInt(root, "maxIterations", 10_000);
        if (maxTokens != null && maxTokens < 0 || maxIterations != null && maxIterations < 0) {
            return Result.failure(400, "Invalid Goal limit");
        }

        boolean resumeAfterUpdate = task.getGoalState().getStatus() == GoalState.Status.PURSUING;
        if (resumeAfterUpdate) {
            loopScheduler.pauseGoal(sessionId, taskId);
            wsGate.interruptGoalSession(sessionId);
            if (task.getGoalState().getStatus() != GoalState.Status.PAUSED) {
                return Result.failure(409, "Goal state changed before settings could be applied");
            }
            for (int attempt = 0; task.isRunning() && attempt < 200; attempt++) {
                Thread.sleep(10L);
            }
            if (task.isRunning()) {
                return Result.failure(409, "Goal is still stopping; please retry");
            }
        }

        loopScheduler.updateGoalConfiguration(
                sessionId,
                taskId,
                objective,
                maxTokens != null ? maxTokens : task.getGoalState().getMaxTokens(),
                maxIterations != null ? maxIterations : task.getGoalState().getMaxIterations());
        if (resumeAfterUpdate) {
            loopScheduler.resumeGoal(sessionId, taskId);
            loopScheduler.trigger(sessionId, taskId);
        }
        return Result.succeed();
    }

    @Post
    @Mapping("/desktop/chat/goals/pause")
    public Result goalsPause(Context ctx) throws Exception {
        return operateGoal(ctx, "pause");
    }

    @Post
    @Mapping("/desktop/chat/goals/resume")
    public Result goalsResume(Context ctx) throws Exception {
        return operateGoal(ctx, "resume");
    }

    @Post
    @Mapping("/desktop/chat/goals/trigger")
    public Result goalsTrigger(Context ctx) throws Exception {
        return operateGoal(ctx, "trigger");
    }

    @Post
    @Mapping("/desktop/chat/goals/remove")
    public Result goalsRemove(Context ctx) throws Exception {
        return operateGoal(ctx, "remove");
    }

    private Result operateGoal(Context ctx, String action) throws Exception {
        if (loopScheduler == null) {
            return Result.failure(503, "Goal service is unavailable");
        }
        ONode root = ONode.ofJson(ctx.body());
        String sessionId = root.get("sessionId").getString();
        String taskId = root.get("taskId").getString();
        if (!isDesktopSessionId(sessionId) || Assert.isEmpty(taskId) || taskId.length() > 64) {
            return Result.failure(400, "Invalid Goal request");
        }
        loopScheduler.restore(sessionId);
        LoopTask task = loopScheduler.getTaskById(sessionId, taskId);
        if (task == null || !task.isGoalMode()) {
            return Result.failure(404, "Goal not found");
        }
        switch (action) {
            case "pause":
                loopScheduler.pauseGoal(sessionId, taskId);
                wsGate.interruptGoalSession(sessionId);
                break;
            case "resume":
                loopScheduler.resumeGoal(sessionId, taskId);
                break;
            case "trigger":
                loopScheduler.trigger(sessionId, taskId);
                break;
            case "remove":
                if (task.getGoalState().getStatus() == GoalState.Status.PURSUING) {
                    loopScheduler.pauseGoal(sessionId, taskId);
                }
                wsGate.interruptGoalSession(sessionId);
                loopScheduler.remove(sessionId, task);
                break;
            default:
                return Result.failure(400, "Invalid Goal action");
        }
        return Result.succeed();
    }

    private Long optionalPositiveLong(ONode root, String name, long maximum) {
        ONode node = root.get(name);
        if (node == null || node.isNull()) {
            return null;
        }
        long value = node.getLong();
        if (value < 0) {
            return -1L;
        }
        if (value == 0) {
            return null;
        }
        if (value > maximum) {
            return -1L;
        }
        return value;
    }

    private Long optionalNonNegativeLong(ONode root, String name, long maximum) {
        if (root.getOrNull(name) == null) {
            return null;
        }
        long value = root.get(name).getLong();
        return value < 0 || value > maximum ? -1L : value;
    }

    private Integer optionalNonNegativeInt(ONode root, String name, int maximum) {
        if (root.getOrNull(name) == null) {
            return null;
        }
        long value = root.get(name).getLong();
        return value < 0 || value > maximum ? -1 : (int) value;
    }

    private Map<String, Object> buildGoalTaskMap(LoopTask task) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", task.getId());
        item.put("type", task.getType().name());
        item.put("prompt", task.getPrompt());
        item.put("enabled", task.isEnabled());
        item.put("running", task.isRunning());
        item.put("currentIteration", task.getCurrentIteration());
        if (task.getMaxTokens() != null) item.put("maxTokens", task.getMaxTokens());
        if (task.getMaxDurationMs() != null) item.put("maxDurationMs", task.getMaxDurationMs());
        if (task.getLastResult() != null) item.put("lastResult", task.getLastResult());
        if (task.getLastExecutedAt() != null) item.put("lastExecutedAt", task.getLastExecutedAt().toString());

        GoalState state = task.getGoalState();
        Map<String, Object> goal = new LinkedHashMap<>();
        goal.put("condition", state.getCondition());
        goal.put("status", state.getStatus().name());
        goal.put("iteration", task.getCurrentIteration());
        goal.put("maxIterations", state.getMaxIterations());
        goal.put("consumedTokens", state.getConsumedTokens());
        goal.put("maxTokens", state.getMaxTokens());
        if (state.getStartEpochMs() > 0) {
            goal.put("startedAt", Instant.ofEpochMilli(state.getStartEpochMs()).toString());
        }
        item.put("goal", goal);
        return item;
    }

    /**
     * 动态添加模型配置
     */
    @Post
    @Mapping("/desktop/chat/models/add")
    public Result modelsAdd(Context ctx) throws Exception {
        ONode root = ONode.ofJson(ctx.body());

        String apiUrl = root.get("apiUrl").getString();
        String apiKey = root.get("apiKey").getString();
        String model = root.get("model").getString();
        String provider = root.get("provider").getString();

        if (Assert.isEmpty(apiUrl) || Assert.isEmpty(model)) {
            return Result.failure("apiUrl and model are required");
        }

        String name = root.get("name").getString();
        if (Assert.isEmpty(name)) {
            name = model;
        }

        ChatConfig config = new ChatConfig();
        config.setName(name);
        config.setApiUrl(apiUrl);
        config.setApiKey(apiKey);
        config.setModel(model);
        config.setStandard(provider);
        ModelApiUrl.normalize(config);
        if (Assert.isEmpty(config.getStandard())) {
            config.setStandard(null);
        }

        // timeout
        String timeout = root.get("timeout").getString();
        if (Assert.isNotEmpty(timeout)) {
            config.setTimeout(java.time.Duration.parse(timeout));
        }

        // userAgent
        String userAgent = root.get("userAgent").getString();
        if (Assert.isNotEmpty(userAgent)) {
            config.setUserAgent(userAgent);
        }
        engine.removeModel(model);
        engine.addModel(config);

        LOG.info("[Desktop] Model added: {}", name);
        return Result.succeed(name);
    }

    /**
     * 选择桌面端默认使用的模型。
     */
    @Post
    @Mapping("/desktop/chat/models/select")
    public Result modelsSelect(@Param("modelName") String modelName) throws Exception {
        if (Assert.isEmpty(modelName)) {
            return Result.failure("modelName is required");
        }

        if (engine.getModelOrNil(modelName) == null) {
            return Result.failure("Model not found: " + modelName);
        }

        engine.setDefaultModel(modelName);
        LOG.info("[Desktop] Model selected: {}", modelName);
        return Result.succeed();
    }

    /**
     * 动态移除模型配置
     */
    @Post
    @Mapping("/desktop/chat/models/remove")
    public Result modelsRemove(@Param("modelName") String modelName) throws Exception {
        if (Assert.isEmpty(modelName)) {
            return Result.failure("modelName is required");
        }

        if (modelName.equals(engine.getMainModel().getNameOrModel())) {
            return Result.failure("Cannot remove the active main model");
        }

        engine.removeModel(modelName);

        LOG.info("[Desktop] Model removed: {}", modelName);
        return Result.succeed();
    }
}
