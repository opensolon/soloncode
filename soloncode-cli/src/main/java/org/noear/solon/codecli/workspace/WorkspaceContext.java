package org.noear.solon.codecli.workspace;

import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessExtension;
import org.noear.solon.codecli.loop.AutomationManager;
import org.noear.solon.codecli.channel.ChannelHub;
import org.noear.solon.codecli.workspace.filer.FileWatchService;
import org.noear.solon.ai.harness.extension.goal.GoalExtension;
import org.noear.solon.ai.harness.extension.loop.LoopExtension;
import org.noear.solon.codecli.workspace.filer.FileService;
import org.noear.solon.codecli.workspace.git.GitService;
import org.noear.solon.codecli.session.SessionManager;
import org.noear.solon.codecli.config.AgentSettings;
import org.noear.solon.lang.Nullable;
import org.noear.solon.net.websocket.WebSocket;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 工作区上下文，持有属于该工作区的一整套服务与引擎实例。
 *
 * @author noear
 */
public class WorkspaceContext implements Closeable {
    private final WorkspaceMeta meta;
    private final HarnessEngine engine;
    private final Path sessionsRoot;
    private final SessionManager sessionManager;
    private final FileService fileService;
    private final GitService gitService;
    private final FileWatchService fileWatchService;
    /** Goal 一键装配扩展（goal 域的唯一宿主交互面；运行期操作经其门面中转）。 */
    private volatile GoalExtension goalExtension;
    private final AutomationManager automationManager;
    private final WorkspaceManager manager;
    private volatile WorkspaceMessageGateway messageGateway;
    private final AgentSettings settings;
    private final List<WebSocket> connections;
    private final ChannelHub channelHub;

    /**
     * 兼容旧版测试和扩展构造方式：没有自动任务管理器的上下文仍然有效。
     * 自动任务功能由 WorkspaceManager 创建的正式上下文注入 AutomationManager。
     */
    public WorkspaceContext(WorkspaceMeta meta,
                            HarnessEngine engine,
                            SessionManager sessionManager,
                            FileService fileService,
                            GitService gitService,
                            FileWatchService fileWatchService,
                            WorkspaceManager manager,
                            AgentSettings settings) {
        this(meta, engine, sessionManager, fileService, gitService, fileWatchService,
                null, manager, settings);
    }

    public WorkspaceContext(WorkspaceMeta meta,
                            HarnessEngine engine,
                            SessionManager sessionManager,
                            FileService fileService,
                            GitService gitService,
                            FileWatchService fileWatchService,
                            AutomationManager automationManager,
                            WorkspaceManager manager,
                            AgentSettings settings) {
        this.meta = meta;
        this.engine = engine;
        this.sessionsRoot = WorkspaceDataUtil.sessionsPath(meta.getPath());
        this.sessionManager = sessionManager;
        this.fileService = fileService;
        this.gitService = gitService;
        this.fileWatchService = fileWatchService;
        this.automationManager = automationManager;
        this.manager = manager;
        this.messageGateway = manager == null ? null : manager.getMessageGateway();
        this.settings = settings;
        this.connections = new CopyOnWriteArrayList<>();
        this.channelHub = new ChannelHub(this);
    }

    public WorkspaceMeta getMeta() {
        return meta;
    }

    public AgentSettings getSettings() {
        return settings;
    }

    public HarnessEngine getEngine() {
        return engine;
    }

    /**
     * 取扩展（未装配时返回 null，与 {@code @Nullable} 声明一致）。
     *
     * <p>此前实现是 {@code engine.getExtension(type).get()}——一个永不返回 null 的
     * {@code Optional.get()}，导致调用方的判空分支全成了死代码，而真正会抛
     * {@code NoSuchElementException} 的地方（扩展未装配）反而没被保护。</p>
     */
    public <T extends HarnessExtension> @Nullable T getExtension(Class<T> type) {
        return engine.getExtension(type).orElse(null);
    }

    public Path getSessionsRoot() {
        return sessionsRoot;
    }

    public Path getSessionPath(String sessionId) {
        return sessionsRoot.resolve(sessionId);
    }

    public SessionManager getSessionManager() {
        return sessionManager;
    }

    public FileService getFileService() {
        return fileService;
    }

    public GitService getGitService() {
        return gitService;
    }

    public FileWatchService getFileWatchService() {
        return fileWatchService;
    }

    /**
     * Goal 域的唯一交互面（装配 + 运行期门面）。
     *
     * <p>宿主只经它操作 goal：{@code goal.get/create/update/pause/resume/trigger/remove} 均按 sessionId 定位，
     * 已内含调度配对，不会出现「改了状态忘了挂/摘 job」。</p>
     */
    public GoalExtension getGoalExtension() {
        return goalExtension;
    }

    /** 由 WorkspaceManager 装配时注入（避免构造器签名膨胀）。 */
    public void attachGoal(GoalExtension goalExtension) {
        if (goalExtension != null) {
            this.goalExtension = goalExtension;
        }
    }

    /**
     * loop（会话定时任务）插件门面；未装配时为 null。
     *
     * <p>不再持字段：扩展由引擎持有，生命周期（onDetach 卸载 job）也随引擎。</p>
     */
    public @Nullable LoopExtension getLoopExtension() {
        return getExtension(LoopExtension.class);
    }

    public AutomationManager getAutomationManager() {
        return automationManager;
    }

    /**
     * 获取当前工作区的消息入口端口。
     *
     * <p>正式 Web 模式启动后由 WorkspaceManager 动态绑定；CLI/headless 模式下可以为空。</p>
     */
    public WorkspaceMessageGateway getMessageGateway() {
        return messageGateway;
    }

    /**
     * 更新工作区消息入口。仅由 WorkspaceManager 在入口就绪时调用。
     */
    void setMessageGateway(WorkspaceMessageGateway messageGateway) {
        this.messageGateway = messageGateway;
    }

    /**
     * 当前工作区的入口运行时端口。
     *
     * <p>正式 Web 模式启动后由 WorkspaceManager 动态绑定；CLI/headless 模式下可以为空。</p>
     */
    public WorkspaceRuntimePort getRuntimePort() {
        return manager != null ? manager.getRuntimePort() : null;
    }

    public List<WebSocket> getConnections() {
        return connections;
    }

    public ChannelHub getChannelHub() {
        return channelHub;
    }

    @Override
    public void close() throws IOException {
        // 不再调用 channelHub.stop()：IM 连接（飞书/钉钉 WS、微信长轮询）已归进程级
        // ImGateway 持有，工作区 LRU 释放/关闭不得断开它们，否则消息会丢失（方案 3.3）。
        // 停掉本工作区全部 goal / loop job：JobManager 是 JVM 单例，
        // 不注销则 job 会带着已关闭引擎的引用在全局调度器里空转。
        // engine.shutdown() 会遍历回调各扩展的 onDetach（GoalExtension → 卸载全部 goal job；
        // LoopExtension → 卸载 job 但保留 tasks.json 记录），
        // 并关闭子代理委派的私有执行器。不再单独取 goalExtension 打补丁。
        try {
            engine.shutdown();
        } catch (Exception e) {
            // Ignore
        }
        if (fileWatchService != null) {
            try {
                fileWatchService.stop();
            } catch (Exception e) {
                // Ignore
            }
        }
        for (WebSocket socket : connections) {
            try {
                socket.close();
            } catch (Exception e) {
                // Ignore
            }
        }
        connections.clear();
    }
}