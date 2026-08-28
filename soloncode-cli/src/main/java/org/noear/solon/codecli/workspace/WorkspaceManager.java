package org.noear.solon.codecli.workspace;

import org.noear.snack4.ONode;
import org.noear.snack4.Feature;
import org.noear.solon.Solon;
import org.noear.solon.Utils;
import org.noear.solon.ai.talents.lsp.LspManager;
import org.noear.solon.ai.chat.CacheControl;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessExtension;
import org.noear.solon.ai.talents.lsp.LspServerParameters;
import org.noear.solon.ai.talents.mount.MountDir;
import org.noear.solon.ai.talents.mount.MountType;
import org.noear.solon.codecli.command.builtin.*;
import org.noear.solon.codecli.config.AgentFlags;
import org.noear.solon.codecli.config.AgentSettings;
import org.noear.solon.codecli.config.ManagerExtension;
import org.noear.solon.codecli.config.ProxyConfig;
import org.noear.solon.codecli.config.entity.*;
import org.noear.solon.codecli.memory.MemoryProvider;
import org.noear.solon.codecli.portal.FileWatchService;
import org.noear.solon.codecli.portal.web.WebGate;
import org.noear.solon.codecli.portal.web.service.FileService;
import org.noear.solon.codecli.portal.web.service.GitService;
import org.noear.solon.codecli.session.SessionJanitor;
import org.noear.solon.codecli.session.SessionManager;
import org.noear.solon.codecli.util.JdkHomeUtil;
import org.noear.solon.codecli.util.LogDirUtil;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.RunUtil;
import org.noear.solon.net.http.HttpConfiguration;
import org.noear.solon.net.http.HttpExtension;
import org.noear.solon.net.http.HttpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * 工作区管理器，用于统一管理多工作区的生命周期、实例化底层引擎及持久化最近工作区。
 *
 * @author noear
 */
public class WorkspaceManager {
    private static final Logger LOG = LoggerFactory.getLogger(WorkspaceManager.class);
    /**
     * jdtls 启动所需的最低 JDK 主版本
     */
    private static final int JDTLS_MIN_JDK = 21;
    private static final String WORKSPACES_FILE_PATH = Paths.get(AgentFlags.getUserHome(), ".soloncode", "workspaces.json").toString();

    private final Map<String, WorkspaceContext> contexts = new ConcurrentHashMap<>();

    private final AgentSettings defaultSettings;
    private WorkspaceContext defaultContext;
    private WebGate webGate;

    /**
     * 闲置释放阈值：30 分钟无访问且无连接
     */
    private static final long IDLE_RELEASE_MS = 30 * 60 * 1000L;

    public WorkspaceManager(AgentSettings defaultSettings) {
        this.defaultSettings = defaultSettings;

        // HTTP 代理配置是进程级全局单例（HttpConfiguration 为静态注册），
        // 禁止在 createWorkspaceContext 中重复注册/覆盖，否则多工作区互相串扰且泄漏扩展。
        // 进程启动时用默认设置初始化一次即可；引擎侧的代理已由 httpCustomizeSet 按工作区生效。
        ProxyConfig.update(defaultSettings.getGeneral());
        HttpConfiguration.addExtension(new HttpExtension() {
            @Override
            public void onInit(HttpUtils http, String url) {
                ProxyConfig.applyIfNeeded(http);
            }
        });

        // LRU 闲置释放：定期扫描非默认工作区，释放长时间无访问且无 WS 连接的引擎资源
        java.util.concurrent.ScheduledExecutorService sweeper =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "workspace-idle-sweeper");
                    t.setDaemon(true);
                    return t;
                });
        sweeper.scheduleWithFixedDelay(this::releaseIdleWorkspaces, 10, 10, java.util.concurrent.TimeUnit.MINUTES);
    }

    public void setWebGate(WebGate webGate) {
        this.webGate = webGate;
    }

    public WebGate getWebGate() {
        return this.webGate;
    }

    private void releaseIdleWorkspaces() {
        try {
            long now = System.currentTimeMillis();
            List<String> idleIds = new ArrayList<>();
            for (WorkspaceContext ctx : contexts.values()) {
                if (ctx.getMeta().isDefault()) {
                    continue;
                }
                long last = ctx.getMeta().getLastAccessed();
                if (now - last > IDLE_RELEASE_MS
                        && (ctx.getConnections() == null || ctx.getConnections().isEmpty())
                        && (ctx.getLoopScheduler() == null || !ctx.getLoopScheduler().hasActiveTasks())) {
                    idleIds.add(ctx.getMeta().getId());
                }
            }
            for (String id : idleIds) {
                // 锁内二次确认：预检查到关闭之间可能有新访问/新连接，避免误释放活跃工作区
                closeIfIdle(id);
            }

        } catch (Throwable e) {
            LOG.warn("[Workspace] Idle sweep failed: {}", e.getMessage());
        }
    }

    /**
     * 初始化默认工作区
     */
    public synchronized void initDefaultWorkspace() {
        if (defaultContext != null) {
            return;
        }
        String userDir = AgentFlags.getUserDir();
        try {
            WorkspaceMeta meta = new WorkspaceMeta("default", getLastSegment(userDir), userDir, System.currentTimeMillis(), true);
            defaultContext = createWorkspaceContext(meta);
            // default 是虚拟工作区概念（每次启动随 user.dir 变化），不落 workspaces.json
            String normalizedUserDir = normalizePathStr(userDir);
            meta.setPath(normalizedUserDir);
            contexts.put("default", defaultContext);
            // 归一化路径 key 使用 putIfAbsent：若同路径已有其它工作区实例（理论上不该发生），
            // 不覆盖，避免旧 ws-xxx key 指向被覆盖前的旧实例造成双实例永不关闭
            contexts.putIfAbsent(normalizedUserDir, defaultContext);
        } catch (Exception e) {
            LOG.error("Failed to init default workspace: " + userDir, e);
        }
    }

    /**
     * 获取或创建工作区上下文。
     *
     * @param workspaceIdOrPath 工作区ID或物理绝对路径
     */
    public synchronized WorkspaceContext getOrCreate(String workspaceIdOrPath) {
        if (workspaceIdOrPath == null || workspaceIdOrPath.trim().isEmpty() || "default".equals(workspaceIdOrPath)) {
            if (defaultContext == null) {
                initDefaultWorkspace();
            }
            return defaultContext;
        }

        // 1. 尝试通过 ID 查找内存中已加载的上下文
        WorkspaceContext context = contexts.get(workspaceIdOrPath);
        if (context != null) {
            context.getMeta().setLastAccessed(System.currentTimeMillis());
            // 内存命中：同步更新 MRU 顺序到历史文件
            if (!context.getMeta().isDefault()) {
                saveWorkspaceToHistory(context.getMeta());
            }
            return context;
        }

        // 2. 如果是以 "ws-" 开头的 ID，在内存中没找到，则尝试从历史记录 workspaces.json 中寻找匹配的物理路径
        if (workspaceIdOrPath.startsWith("ws-")) {
            for (WorkspaceMeta meta : listWorkspaces()) {
                if (workspaceIdOrPath.equals(meta.getId())) {
                    // 找到了历史记录，沿用原有 meta（保留原 ID）加载，避免生成新 ID 造成历史重复
                    Path histPath;
                    try {
                        histPath = normalizePath(Paths.get(meta.getPath()));
                    } catch (Exception pe) {
                        LOG.warn("[Workspace] Illegal history path, skip: {} -> {}", meta.getId(), meta.getPath());
                        break;
                    }
                    if (!Files.isDirectory(histPath)) {
                        LOG.warn("[Workspace] History path missing, reject: {} -> {}", meta.getId(), meta.getPath());
                        break;
                    }
                    // 沿用原有 meta（保留原 ID），但把 path 归一化，避免同路径因格式差异匹配失败
                    meta.setPath(histPath.toString());
                    WorkspaceContext ctx;
                    try {
                        ctx = createWorkspaceContext(meta);
                    } catch (Exception e) {
                        LOG.error("[Workspace] Failed to load history workspace: " + meta.getId(), e);
                        break;
                    }
                    meta.setLastAccessed(System.currentTimeMillis());
                    contexts.put(meta.getId(), ctx);
                    contexts.put(histPath.toString(), ctx);
                    // 历史加载为低频事件，直接回写 lastAccessed，保证重启后 MRU 顺序准确
                    saveWorkspaceToHistory(meta);
                    return ctx;
                }
            }
            // 如果历史记录里也没有这个 ws-xxx ID，说明是非法或不存在的 ID：
            // 返回 null（绝不回退默认工作区，回退会掩盖错误），由调用方决定 404/失败响应
            LOG.warn("[Workspace] Unknown workspace id, reject: {}", workspaceIdOrPath);
            return null;
        }

        // 3. 此时 workspaceIdOrPath 应该是物理绝对路径。先做参数防护：
        //    挂载别名（@ 开头）、含 .. 的相对路径、非绝对路径均为非法参数，
        //    一律返回 null，绝不据此创建目录。
        if (workspaceIdOrPath.startsWith("@")
                || workspaceIdOrPath.contains("..")
                || Paths.get(workspaceIdOrPath).isAbsolute() == false) {
            LOG.warn("[Workspace] Illegal workspace parameter, reject: {}", workspaceIdOrPath);
            return null;
        }

        Path inputPath;
        try {
            inputPath = normalizePath(Paths.get(workspaceIdOrPath));
        } catch (Exception e) {
            // 如果解析路径失败，同样返回 null
            LOG.warn("[Workspace] Illegal workspace path, reject: {}", workspaceIdOrPath);
            return null;
        }
        String normalizedPathStr = inputPath.toString();
        context = contexts.get(normalizedPathStr);
        if (context != null) {
            // 物理路径命中同样更新 MRU（与 ID 命中行为一致）
            context.getMeta().setLastAccessed(System.currentTimeMillis());
            // 物理路径命中：同步更新 MRU 顺序到历史文件
            if (!context.getMeta().isDefault()) {
                saveWorkspaceToHistory(context.getMeta());
            }
            return context;
        }

        // 4. 新物理路径：目录不存在时不再自动创建（早期 bug 会在当前目录下误建目录），
        //    记录 warn 并返回 null，由调用方返回明确错误
        if (!Files.isDirectory(inputPath)) {
            LOG.warn("[Workspace] Directory not exists, reject: {}", normalizedPathStr);
            return null;
        }

        try {
            // 先按物理路径回查历史记录，命中则沿用原 meta（稳定 ID），
            // 避免重启/LRU 回收后重开同一目录时生成新 ws- ID，
            // 导致已渲染的旧卡片上的 ID 失效而 404 跳回默认工作区
            // 注：workspaceId 现已改为路径 md5 生成（幂等），此回查主要用于兼容旧随机 ID 存量
            WorkspaceMeta meta = null;
            for (WorkspaceMeta hist : listWorkspaces()) {
                // 按归一化绝对路径比较，避免同一路径因尾斜杠/相对段等格式差异
                // 匹配失败而生成新 ws- ID（ID 漂移导致旧卡片 404 跳回首页的隐患）
                if (hist.getPath() != null
                        && normalizedPathStr.equals(normalizePathStr(hist.getPath()))) {
                    hist.setLastAccessed(System.currentTimeMillis());
                    hist.setPath(normalizedPathStr);
                    meta = hist;
                    break;
                }
            }
            if (meta == null) {
                // 用路径 md5 生成幂等 ID：相同 path 恒得相同 ID，天然避免重开同目录时 ID 漂移
                String workspaceId = "ws-" + Utils.md5(normalizedPathStr);
                meta = new WorkspaceMeta(workspaceId, getLastSegment(normalizedPathStr), normalizedPathStr, System.currentTimeMillis(), false);
            }
            String ctxKey = meta.getId();
            meta.setLastAccessed(System.currentTimeMillis());

            context = createWorkspaceContext(meta);
            contexts.put(ctxKey, context);
            contexts.put(normalizedPathStr, context);
            saveWorkspaceToHistory(meta);
            return context;
        } catch (Exception e) {
            LOG.error("Failed to create workspace context: " + normalizedPathStr, e);
            return null;
        }
    }

    /**
     * 仅查内存缓存，不创建（供 WS onClose 等回调用，防止复活已释放工作区）
     */
    public WorkspaceContext getContextsCached(String workspaceIdOrPath) {
        if (Assert.isEmpty(workspaceIdOrPath) || "default".equals(workspaceIdOrPath)) {
            return defaultContext;
        }
        return contexts.get(workspaceIdOrPath);
    }

    /**
     * 校验工作区 ID 是否有效（存在于内存或 workspaces.json 历史中）。
     * 仅对 ws- 前缀的多工作区 ID 有意义。
     */
    public boolean isValidWorkspaceId(String wsId) {
        if (wsId == null || wsId.trim().isEmpty() || "default".equals(wsId)) {
            return true;
        }
        if (contexts.containsKey(wsId)) {
            return true;
        }
        if (wsId.startsWith("ws-")) {
            for (WorkspaceMeta meta : listWorkspaces()) {
                if (wsId.equals(meta.getId())) {
                    return true;
                }
            }
            return false;
        }
        // 物理路径形式：先做参数防护（与 getOrCreate 一致），再判目录存在
        if (wsId.startsWith("@") || wsId.contains("..")) {
            return false;
        }
        try {
            return Files.isDirectory(Paths.get(wsId));
        } catch (Exception e) {
            // Windows 非法字符等会抛 InvalidPathException，直接判定无效
            return false;
        }
    }

    /**
     * 获取所有已加载的工作区上下文
     */
    public Collection<WorkspaceContext> getContexts() {
        return contexts.values();
    }

    /**
     * 归一化路径：在 normalize 基础上尽量解析符号链接（toRealPath），
     * 避免 /tmp vs /private/tmp、目录软链、大小写不敏感盘产生双 ws- ID 漂移。
     */
    private static Path normalizePath(Path path) {
        Path p = path.toAbsolutePath().normalize();
        try {
            return p.toRealPath();
        } catch (IOException e) {
            // 目录不存在或无法解析时退回 normalize 结果
            return p;
        }
    }

    private static String normalizePathStr(String pathStr) {
        try {
            return normalizePath(Paths.get(pathStr)).toString();
        } catch (Exception e) {
            return pathStr;
        }
    }

    /**
     * 关闭并销毁工作区上下文
     */
    public synchronized void closeWorkspace(String workspaceIdOrPath) {
        if (workspaceIdOrPath == null || workspaceIdOrPath.trim().isEmpty()) {
            return;
        }
        // default 是虚拟工作区（随启动目录变化），不允许被关闭销毁
        if ("default".equals(workspaceIdOrPath)) {
            return;
        }
        WorkspaceContext context = contexts.remove(workspaceIdOrPath);
        if (context != null) {
            String id = context.getMeta().getId();
            String path = context.getMeta().getPath();
            // 同时移除可能存在的其他 Key（如绝对路径或 ID）
            contexts.remove(id);
            // id 与 path 相同时（极端情况：工作区 ID 恰好等于其归一化路径字符串）避免冗余 remove
            if (!id.equals(path)) {
                contexts.remove(path);
            }
            try {
                context.close();
            } catch (IOException e) {
                LOG.error("Failed to close workspace: " + workspaceIdOrPath, e);
            }
            //释放该工作区专属的日志 appender（停掉文件句柄，避免泄漏/文件锁）
            WorkspaceLogRouter.releaseWorkspace(path);
        }
    }

    /**
     * 锁内二次确认后释放闲置工作区（仅 LRU sweeper 使用）
     */
    private synchronized void closeIfIdle(String id) {
        WorkspaceContext ctx = contexts.get(id);
        if (ctx == null || ctx.getMeta().isDefault()) {
            return;
        }
        long last = ctx.getMeta().getLastAccessed();
        if (System.currentTimeMillis() - last <= IDLE_RELEASE_MS) {
            return; // 预检查后有新访问
        }
        if (ctx.getConnections() != null && !ctx.getConnections().isEmpty()) {
            return; // 预检查后有新连接
        }
        if (ctx.getLoopScheduler() != null && ctx.getLoopScheduler().hasActiveTasks()) {
            return;
        }
        LOG.info("[Workspace] Releasing idle workspace: {}", id);
        closeWorkspace(id);
    }

    /**
     * 从 workspaces.json 历史中彻底移除工作区条目（用于 /web/workspace/remove）
     */
    public synchronized void removeFromHistory(String workspaceId) {
        if (workspaceId == null || "default".equals(workspaceId)) {
            return;
        }
        try {
            // 必须先关内存 context：否则后续任何 getOrCreate 内存命中会重新同步写盘，
            // 把刚删的条目写回文件——删除被静默回滚
            closeWorkspace(workspaceId);

            Path file = Paths.get(WORKSPACES_FILE_PATH);
            if (!Files.exists(file)) {
                return;
            }
            Map<String, WorkspaceMeta> map = new LinkedHashMap<>();
            for (WorkspaceMeta w : readWorkspaceEntries()) {
                if (!workspaceId.equals(w.getId())) {
                    map.put(w.getId(), w);
                }
            }
            writeHistoryFile(file, map);
        } catch (Throwable e) {
            LOG.warn("Failed to remove workspace from history: " + workspaceId, e);
        }
    }

    /**
     * 实例化一个新的 WorkspaceContext（仿照 Configurator 中的构建逻辑）
     */
    private WorkspaceContext createWorkspaceContext(WorkspaceMeta meta) throws Exception {
        //整段构建过程（engine/LSP/MCP/挂载/记忆等初始化）都归属本工作区日志；
        //构建期间新建的子线程（LSP 读取线程、MCP stdio 传输线程等）靠继承式标记自动带上归属
        Object logScope = WorkspaceLogRouter.beginScope(meta.getPath());
        try {
            return doCreateWorkspaceContext(meta);
        } finally {
            WorkspaceLogRouter.endScope(logScope);
        }
    }

    private WorkspaceContext doCreateWorkspaceContext(WorkspaceMeta meta) throws Exception {
        String workspacePath = meta.getPath();

        //清理旧版本遗留的日志（工作区内的 + 上一版全局位置的），避免污染 IDE 全文搜索与残留空目录
        LogDirUtil.cleanLegacyLogs(workspacePath);

        //会话已改存到 ~/.soloncode/workspaces/<标识>/sessions/：记录反查标记，并把旧版工作区内的会话搬迁过去
        WorkspaceDataUtil.markWorkspace(workspacePath);
        WorkspaceDataUtil.migrateLegacySessions(workspacePath);

        // 多工作区配置隔离：非默认工作区按目录加载 global + 工作区覆盖；
        // 默认工作区沿用注入的全局 settings（与 CLI 启动语义一致）
        AgentSettings wsSettings = meta.isDefault()
                ? this.defaultSettings
                : AgentSettings.loadForWorkspace(workspacePath);
        String stealthIdentity = "<!--\n" +
                "  @poweredby: soloncode\n" +
                "  @build: " + AgentFlags.getVersion() + "\n" +
                "-->\n\n";

        // 初始化 HTTP 代理配置已移至构造函数（进程级一次性），见 WorkspaceManager(settings)

        HarnessEngine engine = HarnessEngine.of(workspacePath, AgentFlags.getHarnessHome())
                .userAgent(wsSettings.getGeneral().getUserAgent())
                .systemPrompt(stealthIdentity + AgentFlags.getAgentsMd())
                .maxTurns(wsSettings.getGeneral().getMaxTurns())
                .autoRethink(wsSettings.getGeneral().isAutoRethink())
                .sessionWindowSize(wsSettings.getGeneral().getSessionWindowSize())
                .sessionProvider(new SessionManager(workspacePath))
                .compressionDefaultContextLength(1024_000L) //1024k -> 1m
                .compressionThreshold(wsSettings.getGeneral().getCompressionThresholdMessages(), wsSettings.getGeneral().getCompressionThresholdPercent() / 100.0D)
                .memoryEnabled(wsSettings.getGeneral().isMemoryEnabled())
                .memoryRelevanceCount(wsSettings.getGeneral().getMemoryRelevanceCount())
                .memoryPriorityCount(wsSettings.getGeneral().getMemoryPriorityCount())
                .memorySummaryLength(wsSettings.getGeneral().getMemorySummaryLength())
                .memoryProvider(new MemoryProvider())
                .sandboxEnabled(wsSettings.getGeneral().isSandboxMode())
                .sandboxAllowUserHome(wsSettings.getGeneral().isSandboxAllowUserHome())
                .sandboxSystemRestrict(wsSettings.getGeneral().isSandboxSystemRestrict())
                .bashAsyncEnabled(wsSettings.getGeneral().isBashAsyncEnabled())
                .subagentEnabled(wsSettings.getGeneral().isSubagentEnabled())
                .hitlEnabled(wsSettings.getGeneral().isHitlEnabled())
                .apiRetries(wsSettings.getGeneral().getApiRetries())
                .modelRetries(wsSettings.getGeneral().getModelRetries())
                .mcpRetries(wsSettings.getGeneral().getModelRetries())
                .toolsAdd(wsSettings.getPermission().getTools())
                .disallowedToolsAdd(wsSettings.getPermission().getDisallowedTools())
                .cacheControl(CacheControl.ofEphemeral())
                .httpCustomizeSet(http -> {
                    ProxyConfig.applyIfNeeded(http);
                })
                .build();

        engine.setDefaultModel(wsSettings.getDefaultModel());


        engine.getTodoTalent().setWorkPathHook(new BiFunction<String, String, Path>() {
            @Override
            public Path apply(String __cwd, String __sessionId) {
                return WorkspaceDataUtil.sessionsPath(__cwd).resolve(__sessionId);
            }
        });

        //清理 web 端遗留的僵尸会话目录（新建对话未发消息即切走产生的空壳）
        Path wsSessionsRoot = WorkspaceDataUtil.sessionsPath(workspacePath);
        SessionJanitor.cleanWebSessions(wsSessionsRoot);

        for (ModelDo model : wsSettings.getModels().values()) {
            engine.addModel(model);
        }

        for (Map.Entry<String, MountDo> entry : wsSettings.getMountPools().entrySet()) {
            MountDo mount = entry.getValue();
            engine.addMount(MountDir.builder()
                    .alias(entry.getKey())
                    .description(mount.getDescription())
                    .type(mount.getType())
                    .path(mount.getPath())
                    .primary(mount.isPrimary())
                    .enabled(mount.isEnabled())
                    .writeable(mount.isWriteable())
                    .build());
        }

        engine.addMount(MountDir.builder().alias("@user-skills").type(MountType.SKILLS).path("~/" + engine.getHarnessSkills()).primary(true).build());
        engine.addMount(MountDir.builder().alias("@workspace-skills").type(MountType.SKILLS).path("./" + engine.getHarnessSkills()).primary(true).build());

        engine.addMount(MountDir.builder().alias("@user-agents").type(MountType.AGENTS).path("~/" + engine.getHarnessAgents()).primary(true).build());
        engine.addMount(MountDir.builder().alias("@workspace-agents").type(MountType.AGENTS).path("./" + engine.getHarnessAgents()).primary(true).build());

        // 灌入技能禁用清单
        engine.disallowSkillReset(wsSettings.getPermission().getDisallowedSkills());

        engine.getCommandRegistry().load(Paths.get(AgentFlags.getUserHome(), engine.getHarnessCommands()));
        engine.getCommandRegistry().load(Paths.get(workspacePath, engine.getHarnessCommands()));

        engine.getCommandRegistry().register(new ExitCommand());
        engine.getCommandRegistry().register(new ClearCommand());
        engine.getCommandRegistry().register(new ContinueCommand());
        engine.getCommandRegistry().register(new InterruptCommand());
        engine.getCommandRegistry().register(new RerunCommand());
        engine.getCommandRegistry().register(new RewindCommand());
        engine.getCommandRegistry().register(new ModelCommand());

        engine.getLspTalent().setEnabled(wsSettings.getGeneral().isLspEnabled());

        RunUtil.async(() -> addServers(engine, wsSettings));

        // loop scheduler
        LoopScheduler loopScheduler = new LoopScheduler(engine, wsSettings);

        // 初始化 Goal 验证器
        ValidatorFactory.initDefaults(workspacePath);

        // Goal 模式
        boolean goalsEnabled = wsSettings.getGeneral().isGoalsEnabled();
        GoalExtension goalExtension = new GoalExtension(loopScheduler);
        goalExtension.getGoalTalent().setEnabled(goalsEnabled);
        engine.addExtension(goalExtension);

        LoopCommand loopCommand = new LoopCommand(loopScheduler);
        engine.getCommandRegistry().register(loopCommand);
        engine.getCommandRegistry().register(new GoalCommand(loopCommand));

        engine.addExtension(new ManagerExtension(engine, wsSettings, loopScheduler));

        // 注入 HarnessExtension 扩展
        Solon.context().subBeansOfType(HarnessExtension.class, extension -> {
            engine.addExtension(extension);
        });


        // 为本工作区的 LoopScheduler 就地注册 web 端执行器与忙碌检查。
        // 注意：此处不能捕获 webGate 字段快照——默认工作区在 webServe 阶段（setWebGate）之前创建，
        // 快照为 null 会导致注册被跳过；改为 lambda 内动态取值，注入完成后自然生效。
        registerWebLoopExecutor(engine, meta.getId(), loopScheduler);

        // FileWatchService（自建轮询/初始化线程，需显式带上工作区日志归属）
        FileWatchService fileWatchService = new FileWatchService().logWorkspacePath(workspacePath);
        fileWatchService.addRoot("workspace", Paths.get(workspacePath).toAbsolutePath().normalize())
                .addHandler(changes -> {
                    // 动态取 gate：默认工作区创建早于 setWebGate，字段快照可能为 null
                    WebGate gate = getWebGate();
                    if (gate != null) {
                        gate.broadcastRaw(meta.getId(), FileWatchService.buildFrontendJson(changes));
                    }
                });

        for (MountDir mount : engine.getMounts()) {
            if (!mount.isEnabled()) continue;
            FileWatchService.WatchRoot root = fileWatchService.addRoot(mount.getAlias(), mount.getRealPath());
            switch (mount.getType()) {
                case FILES:
                    root.addHandler(changes -> {
                        WebGate gate = getWebGate();
                        if (gate != null) {
                            gate.broadcastRaw(meta.getId(), FileWatchService.buildFrontendJson(changes));
                        }
                    });
                    break;
                case SKILLS:
                    root.addHandler(changes -> engine.getSkillProvider().refreshByGroup(mount.getAlias()));
                    break;
                case AGENTS:
                    root.addHandler(changes -> engine.getAgentManager().refreshByMountAlias(mount.getAlias()));
                    break;
            }
        }
        fileWatchService.start();

        SessionManager sessionManager = (SessionManager) engine.getSessionProvider();
        FileService fileService = new FileService(workspacePath, engine);
        GitService gitService = new GitService(workspacePath, engine);

        WorkspaceContext context = new WorkspaceContext(meta, engine, sessionManager, fileService, gitService, fileWatchService, loopScheduler, this, wsSettings);

        // 拉起本工作区的 IM 渠道长连接（微信/飞书/钉钉），恢复已持久化的绑定连接。
        // Link.run() 内部有 running CAS 幂等保护，重复调用安全。
        RunUtil.async(WorkspaceLogRouter.withWorkspaceLogKey(workspacePath, context.getChannelHub()::start));

        return context;
    }

    /**
     * 为指定工作区的 LoopScheduler 注册 web 端任务执行器与忙碌检查器。
     *
     * <p>每个工作区拥有独立的 LoopScheduler 与 WebGate，执行器必须就地绑定本工作区的
     * WebGate，以保证定时触发的 AI 响应推送到本工作区的连接池，而非默认工作区。</p>
     */
    private void registerWebLoopExecutor(HarnessEngine engine, String workspaceId, LoopScheduler loopScheduler) {
        if (loopScheduler == null) {
            return;
        }

        // 会话繁忙守卫：session 正在执行任务时，loop 定时触发跳过本次执行
        loopScheduler.addBusyChecker(sessionId -> {
            if (sessionId == null || !sessionId.startsWith("web-")) {
                return false;
            }
            WebGate gate = getWebGate();
            return gate != null && gate.isSessionBusy(engine, sessionId);
        });

        loopScheduler.addTaskExecutor((sessionId, prompt, agentName) -> {
            if (sessionId == null || !sessionId.startsWith("web-")) {
                return null;
            }
            WebGate gate = getWebGate();
            if (gate == null) {
                return null;
            }
            // 如果指定了 agentName，将 prompt 拼接为 @agentName prompt 格式
            String effectiveInput = prompt;
            if (agentName != null && !agentName.isEmpty()) {
                effectiveInput = "@" + agentName + " " + prompt;
            }
            // Loop 任务可能长时间执行（数小时），使用 Loop 专用无限等待版本
            return gate.safeChatInputAndCaptureLoop(workspaceId, sessionId, effectiveInput, "Loop");
        });
    }

    private void addServers(HarnessEngine engine, AgentSettings wsSettings) {
        for (Map.Entry<String, McpServerDo> entry : wsSettings.getMcpServers().entrySet()) {
            engine.addMcpServer(entry.getKey(), entry.getValue());
        }

        for (Map.Entry<String, ApiSourceDo> entry : wsSettings.getApiServers().entrySet()) {
            engine.addApiServer(entry.getValue());
        }

        for (Map.Entry<String, LspServerDo> entry : wsSettings.getLspServers().entrySet()) {
            engine.addLspServer(entry.getKey(), entry.getValue());
        }

        //内置服务器清单收敛到 LspManager.buildLspServers() 单一来源。
        //只注册进引擎、不写入 settings.json：否则每个仓库的配置文件都会多出十几条与默认
        //完全相同的噪声条目，还会在内置默认升级后反过来压制新值
        for (Map.Entry<String, LspServerParameters> builtin
                : LspManager.buildLspServers().entrySet()) {
            addSystemLspServer(engine, wsSettings, builtin.getKey(),
                    builtin.getValue().getCommand(), builtin.getValue().getExtensions());
        }

        // 运行时补 env（放在同步之后：不写回 settings，避免把本机 JDK 路径提交进仓库）
        applyJdtlsJavaHome(engine);
    }

    /**
     * jdtls 要求 JDK 21+ 才能启动，而 soloncode 自身可能跑在 JDK 8 上；
     * 子进程会继承父进程的 JAVA_HOME，于是直接被 jdtls 的版本校验拒绝
     * （表现为进程启动后立刻退出、stderr 是一段 Python Traceback）。
     *
     * <p>这里为内置的 java 服务器补一个满足下限的 JAVA_HOME。只改注册到引擎的运行时副本，
     * 不动 settings 里的条目——那个文件可能随仓库提交，不该写入本机绝对路径。
     */
    private void applyJdtlsJavaHome(HarnessEngine engine) {
        org.noear.solon.ai.talents.lsp.LspServerParameters params = engine.getLspServers().get("java");

        if (params == null || params.isEnabled() == false) {
            return;
        }

        List<String> command = params.getCommand();
        if (command == null || command.isEmpty()
                || getLastSegment(command.get(0)).startsWith("jdtls") == false) {
            // 用户换了别的 Java 语言服务器，版本约束未必相同，不越权干预
            return;
        }

        if (params.getEnv() != null && params.getEnv().containsKey("JAVA_HOME")) {
            // 用户已显式指定，尊重其配置
            return;
        }

        if (JdkHomeUtil.currentJavaHomeSatisfies(JDTLS_MIN_JDK)) {
            return;
        }

        String javaHome = JdkHomeUtil.findJavaHomeAtLeast(JDTLS_MIN_JDK);
        if (javaHome == null) {
            LOG.warn("[LSP] jdtls requires JDK {}+, none found on this machine. "
                    + "Java diagnostics will be unavailable", JDTLS_MIN_JDK);
            return;
        }

        org.noear.solon.ai.talents.lsp.LspServerParameters runtime =
                new org.noear.solon.ai.talents.lsp.LspServerParameters();
        runtime.setCommand(params.getCommand());
        runtime.setExtensions(params.getExtensions());
        runtime.setEnabled(true);
        if (params.getInitialization() != null) {
            runtime.setInitialization(new LinkedHashMap<>(params.getInitialization()));
        }

        Map<String, String> env = new LinkedHashMap<>();
        if (params.getEnv() != null) {
            env.putAll(params.getEnv());
        }
        env.put("JAVA_HOME", javaHome);
        runtime.setEnv(env);

        engine.addLspServer("java", runtime);
        LOG.info("[LSP] jdtls JAVA_HOME -> {} (current JAVA_HOME is below JDK {})", javaHome, JDTLS_MIN_JDK);
    }

    /**
     * 注册内置 LSP 服务器（只进引擎，不落 settings.json）
     *
     * <p>用户在 settings.json 中的同名条目是「覆盖」：已在前面的循环注册进引擎，此处原样保留。
     * 存量文件里由旧版自动写入的条目不做清理——用户「有意停用某内置服务器」产生的条目与
     * 旧版自动注入的条目在磁盘上无法区分，删除会误伤用户意图。
     */
    private void addSystemLspServer(HarnessEngine engine, AgentSettings wsSettings, String name, List<String> command, List<String> extensions) {
        // 命令不在 PATH 就不启用：否则一旦被路由到（写文件时自动取诊断会路由）会反复 fork 失败进程
        boolean installed = LspManager.isCommandAvailable(command.get(0));

        // 以当前工作区合并后的 settings（global+workspace）为准，而非启动目录的 defaultSettings，
        // 否则非默认工作区在自身 settings.json 中自定义的同名 LSP 会被误跳过注入
        LspServerDo existing = wsSettings.getLspServers().get(name);
        if (existing != null) {
            // 迁移：早期版本把内置服务器一律注入为 enabled=false，导致 LSP 默认全哑。
            // 仅当条目与内置默认完全一致（即用户从没改过）且命令确实可用时，才自动放开，
            // 避免覆盖用户“有意关掉”的意图。
            if (installed && existing.isEnabled() == false && isPristineSystemLspEntry(existing, command, extensions)) {
                existing.setEnabled(true);
                engine.addLspServer(name, existing);
                LOG.info("[LSP] system server '{}' enabled (command found in PATH)", name);
            }
            return;
        }

        LspServerParameters builtin = new LspServerParameters(command, extensions);
        builtin.setEnabled(installed);
        engine.addLspServer(name, builtin);
    }

    /**
     * 判定一个已存在的条目是否“仅由旧版自动注入、用户未修改过”
     */
    private static boolean isPristineSystemLspEntry(LspServerDo entry, List<String> command, List<String> extensions) {
        return AgentFlags.SCOPE_LOCAL.equals(entry.getScope())
                && command.equals(entry.getCommand())
                && extensions.equals(entry.getExtensions())
                && (entry.getEnv() == null || entry.getEnv().isEmpty())
                && (entry.getInitialization() == null || entry.getInitialization().isEmpty());
    }

    private static String getLastSegment(String pathStr) {
        Path path = Paths.get(pathStr);
        Path fileName = path.getFileName();
        return fileName == null ? "" : fileName.toString();
    }

    /**
     * 获取最近的工作区列表
     */
    public List<WorkspaceMeta> listWorkspaces() {
        // 读取原始条目（map 已按 id 天然唯一，无需再去重）
        Collection<WorkspaceMeta> raw = readWorkspaceEntries();

        // 默认工作区（启动目录）物理路径：用于过滤早期 bug 在默认工作区内部误建目录的脏条目
        String defaultPathStr;
        try {
            defaultPathStr = normalizePathStr(AgentFlags.getUserDir());
        } catch (Exception e) {
            defaultPathStr = null;
        }

        List<WorkspaceMeta> result = new ArrayList<>();
        for (WorkspaceMeta w : raw) {
            // 归一化路径，避免同路径因格式差异比较失败
            String normalized;
            try {
                normalized = normalizePathStr(w.getPath());
            } catch (Exception e) {
                continue;
            }
            w.setPath(normalized);
            // 展示层过滤（不回写文件）：
            // a) 物理目录已不存在的脏条目（不删目录本身，只从列表隐去）
            if (!Files.isDirectory(Paths.get(normalized))) {
                LOG.debug("[Workspace] Filter entry (dir missing): {} -> {}", w.getId(), normalized);
                continue;
            }
            // b) path 指向默认工作区目录内部（非默认本身）的误建目录条目。
            //    例外：默认工作区即用户主目录（在 ~ 下启动）时跳过该过滤——
            //    ~ 下的子项目目录是正常工作区，不能被当作脏条目清洗，否则最近列表整体消失。
            boolean defaultIsUserHome = false;
            try {
                defaultIsUserHome = defaultPathStr != null
                        && normalizePathStr(AgentFlags.getUserHome()).equals(defaultPathStr);
            } catch (Exception ignore) {
            }

            if (!defaultIsUserHome
                    && defaultPathStr != null && !w.isDefault()
                    && normalized.startsWith(defaultPathStr + File.separator)) {
                LOG.debug("[Workspace] Filter entry (inside default dir): {} -> {}", w.getId(), normalized);
                continue;
            }
            result.add(w);
        }

        // 按最近访问时间倒序返回（MRU），不再依赖存储的插入顺序
        result.sort((a, b) -> Long.compare(b.getLastAccessed(), a.getLastAccessed()));
        return result;
    }

    /**
     * 从 workspaces.json 读取工作区条目。
     * 存储格式为 object/map：{ "ws-xxx": { name, path, lastAccessed }, ... }，key 即工作区 id。
     */
    private Collection<WorkspaceMeta> readWorkspaceEntries() {
        List<WorkspaceMeta> list = new ArrayList<>();
        try {
            Path file = Paths.get(WORKSPACES_FILE_PATH);
            if (!Files.exists(file)) {
                return list;
            }
            String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            ONode node = ONode.ofJson(json);
            if (node.isObject()) {
                for (Map.Entry<String, ONode> entry : node.getObject().entrySet()) {
                    String id = entry.getKey();
                    ONode item = entry.getValue();
                    // default 是虚拟工作区，持久化文件中不允许存在（存量脏数据自动清洗）
                    if (item == null || id == null || "default".equals(id)) {
                        continue;
                    }
                    WorkspaceMeta w = new WorkspaceMeta();
                    w.setId(id);
                    w.setName(item.get("name").getString());
                    w.setPath(item.get("path").getString());
                    w.setLastAccessed(item.get("lastAccessed").getLong());
                    if (w.getPath() != null) {
                        list.add(w);
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to read workspaces", e);
        }
        return list;
    }

    /**
     * 保存工作区历史记录
     */
    private synchronized void saveWorkspaceToHistory(WorkspaceMeta meta) {
        // default 是虚拟工作区，不持久化
        if (meta == null || "default".equals(meta.getId())) {
            return;
        }
        try {
            Path file = Paths.get(WORKSPACES_FILE_PATH);
            if (!Files.exists(file.getParent())) {
                Files.createDirectories(file.getParent());
            }

            // 以 map 形式组织：key=工作区 id（ws-md5(path)，与路径 1:1），value=元数据。
            // id 已与路径幂等，同 id 直接覆盖，无需再按路径 removeIf 去重。
            Map<String, WorkspaceMeta> map = new LinkedHashMap<>();
            for (WorkspaceMeta w : readWorkspaceEntries()) {
                if (w.getId() != null && w.getPath() != null && !"default".equals(w.getId())) {
                    map.put(w.getId(), w);
                }
            }

            map.put(meta.getId(), meta);

            writeHistoryFile(file, map);
        } catch (Throwable e) {
            LOG.warn("Failed to save workspace to history: " + meta.getPath(), e);
        }
    }

    /**
     * 原子写入历史文件（tmp + move），避免进程中断导致 workspaces.json 截断损坏
     */
    private void writeHistoryFile(Path file, Map<String, WorkspaceMeta> map) throws IOException {
        String newJson = ONode.ofBean(map, Feature.Write_PrettyFormat).toJson();
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.write(tmp, newJson.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            // move 失败时清理遗留 .tmp，避免脏临时文件残留
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignore) {
            }
        }
    }

    /**
     * 根据 http 上下文获取
     */
    public WorkspaceContext currentContext() {
        Context ctx = Context.current();
        WorkspaceContext wctx = null;
        if (ctx != null) {
            wctx = ctx.attr("WORKSPACE_CTX");
        }

        if (wctx == null) {
            wctx = getOrCreate(null); // 回退默认
        }

        return wctx;
    }
}