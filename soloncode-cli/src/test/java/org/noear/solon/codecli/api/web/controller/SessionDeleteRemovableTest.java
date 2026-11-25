package org.noear.solon.codecli.api.web.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.codecli.config.AgentSettings;
import org.noear.solon.codecli.session.SessionManager;
import org.noear.solon.codecli.workspace.WorkspaceContext;
import org.noear.solon.codecli.workspace.WorkspaceDataUtil;
import org.noear.solon.codecli.workspace.WorkspaceManager;
import org.noear.solon.codecli.workspace.WorkspaceRuntimePort;
import org.noear.solon.core.handle.Result;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Web 删除 / 批量清空的判定口径：一律看「可删除」（不占用且没有仍在执行的子任务），不是「占用」。
 *
 * <p>父流取消后子任务可能还在跑：此时会话不占用，但子会话存在它的目录下，删了会被子任务收尾写回来。
 * 这里让运行时端口给出「不占用、但不可删」，若哪个入口退回用 isSessionOccupied 判定，就会把目录删掉。</p>
 */
class SessionDeleteRemovableTest {
    private static final String SESSION = "web-del-1";

    @TempDir
    Path workspace;

    private final Map<String, AgentSession> sessions = new ConcurrentHashMap<>();
    private HarnessEngine engine;
    private WorkspaceRuntimePort port;
    private SessionWebController controller;

    @BeforeEach
    void setUp() throws IOException {
        engine = HarnessEngine.of(workspace.toString(), workspace.resolve(".home").toString())
                .sessionProvider((id, parentId, create) -> create ? sessions.computeIfAbsent(id, InMemoryAgentSession::of) : sessions.get(id))
                .build();

        port = mock(WorkspaceRuntimePort.class);
        when(port.isSessionOccupied(any(), anyString())).thenReturn(false);
        when(port.isSessionRemovable(any(), anyString())).thenReturn(false);

        WorkspaceContext context = mock(WorkspaceContext.class);
        when(context.getEngine()).thenReturn(engine);
        when(context.getRuntimePort()).thenReturn(port);
        when(context.getSettings()).thenReturn(mock(AgentSettings.class)); // 未开用户隔离
        when(context.getSessionManager()).thenReturn(mock(SessionManager.class));
        when(context.getSessionsRoot()).thenReturn(sessionsRoot());
        WorkspaceManager manager = mock(WorkspaceManager.class);
        when(manager.currentContext()).thenReturn(context);
        controller = new SessionWebController(manager);

        Files.createDirectories(sessionsRoot().resolve(SESSION).resolve(SessionManager.SUBTASKS_DIR)
                .resolve("task-0123456789abcdef0123456789abcdef"));
    }

    @AfterEach
    void cleanup() throws IOException {
        engine.shutdown();
        // 会话数据落在 ~/.soloncode/workspaces/<工作区标识>/ 下，用完清掉
        Path dataDir = sessionsRoot().getParent();
        if (dataDir != null && Files.exists(dataDir)) {
            try (Stream<Path> paths = Files.walk(dataDir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private Path sessionsRoot() {
        return WorkspaceDataUtil.sessionsPath(workspace.toString());
    }

    @Test
    void deleteRefusesWhileNotRemovableThenDeletes() throws Exception {
        Result<?> refused = controller.deleteSession(SESSION, null);
        assertEquals(409, refused.getCode(), "不占用但有子任务在跑：拒绝删除");
        assertTrue(Files.isDirectory(sessionsRoot().resolve(SESSION)), "拒绝时不动目录");

        when(port.isSessionRemovable(any(), anyString())).thenReturn(true);
        assertEquals(Result.SUCCEED_CODE, controller.deleteSession(SESSION, null).getCode());
        assertFalse(Files.exists(sessionsRoot().resolve(SESSION)), "可删除时连同 subtasks 整个删掉");
    }

    @Test
    void clearSkipsSessionsThatAreNotRemovable() throws Exception {
        Map<String, Object> data = controller.clearUnpinnedSessions().getData();

        assertEquals(1, data.get("skippedBusy"), String.valueOf(data));
        assertTrue(Files.isDirectory(sessionsRoot().resolve(SESSION)), "不可删除的会话保留");
    }
}
