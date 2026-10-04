package org.noear.solon.codecli.workspace;

import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.codecli.loop.AutomationManager;
import org.noear.solon.codecli.channel.ChannelHub;
import org.noear.solon.codecli.workspace.filer.FileWatchService;
import org.noear.solon.codecli.loop.LoopScheduler;
import org.noear.solon.codecli.workspace.filer.FileService;
import org.noear.solon.codecli.workspace.git.GitService;
import org.noear.solon.codecli.session.SessionManager;
import org.noear.solon.codecli.config.AgentSettings;
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
    private final LoopScheduler loopScheduler;
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
                            LoopScheduler loopScheduler,
                            WorkspaceManager manager,
                            AgentSettings settings) {
        this(meta, engine, sessionManager, fileService, gitService, fileWatchService,
                loopScheduler, null, manager, settings);
    }

    public WorkspaceContext(WorkspaceMeta meta,
                            HarnessEngine engine,
                            SessionManager sessionManager,
                            FileService fileService,
                            GitService gitService,
                            FileWatchService fileWatchService,
                            LoopScheduler loopScheduler,
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
        this.loopScheduler = loopScheduler;
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

    public LoopScheduler getLoopScheduler() {
        return loopScheduler;
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
        if (channelHub != null) {
            try {
                channelHub.stop();
            } catch (Exception e) {
                // Ignore
            }
        }
        if (loopScheduler != null) {
            try {
                loopScheduler.shutdown();
            } catch (Exception e) {
                // Ignore
            }
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