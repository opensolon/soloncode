package org.noear.solon.codecli.api.web.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.extension.goal.GoalExtension;
import org.noear.solon.ai.harness.extension.goal.GoalOutcome;
import org.noear.solon.ai.harness.extension.goal.GoalProjection;
import org.noear.solon.ai.harness.extension.goal.GoalTask;
import org.noear.solon.codecli.workspace.WorkspaceContext;
import org.noear.solon.codecli.workspace.WorkspaceManager;
import org.noear.solon.core.handle.Result;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LoopWebController 的 goal 端点行为：分流命中、状态校验、404/400 分支。
 *
 * <p>用真实 {@link GoalExtension}（内存会话、无调度）而非 mock：端点只经门面操作 goal，
 * 断言落在门面可见的状态上，不依赖控制器内部调用顺序。初始状态经 {@link GoalProjection} 写入会话快照，
 * 首次访问时由插件 hydrate（与重启恢复同一路径）。</p>
 */
class GoalLoopEndpointTest {
    private static final String SESSION = "web-test";
    private static final String TASK_ID = "goal-1";

    @TempDir
    Path workspace;

    private final Map<String, AgentSession> sessions = new ConcurrentHashMap<>();
    private GoalExtension goal;
    private LoopWebController controller;

    @BeforeEach
    void setUp() {
        goal = GoalExtension.builder("ws-endpoint").jobManager(null).build();
        HarnessEngine.of(workspace.toString(), workspace.resolve("home").toString())
                .sessionProvider(id -> sessions.computeIfAbsent(id, InMemoryAgentSession::of))
                .extensionAdd(goal)
                .build();
        // 触发轮次时不调用模型：本通道认领并 skip
        goal.addExecutor("web", null, (sid, prompt) -> GoalOutcome.skip("test"));

        WorkspaceManager manager = mock(WorkspaceManager.class);
        WorkspaceContext context = mock(WorkspaceContext.class);
        when(manager.currentContext()).thenReturn(context);
        when(context.getGoalExtension()).thenReturn(goal);
        controller = new LoopWebController(manager);
    }

    /** 预置一条指定状态的 goal（经快照投影，首次访问时 hydrate）。 */
    private void seed(GoalTask.Status status) {
        AgentSession session = sessions.computeIfAbsent(SESSION, InMemoryAgentSession::of);
        GoalProjection.builder()
                .goalId(TASK_ID)
                .objective("目标")
                .status(status)
                .startEpochMs(System.currentTimeMillis())
                .build()
                .writeTo(session.getContext());
    }

    private GoalTask.Status statusNow() {
        GoalTask task = goal.get(SESSION);
        return task == null ? null : task.getStatus();
    }

    @Test
    void pauseEndpointPausesPursuingGoal() {
        seed(GoalTask.Status.PURSUING);

        Result result = controller.loopGoalPause(SESSION, TASK_ID);

        assertEquals(Result.SUCCEED_CODE, result.getCode());
        assertEquals(GoalTask.Status.PAUSED, statusNow());
    }

    @Test
    void pauseEndpointRejectsNonPursuingGoal() {
        seed(GoalTask.Status.PAUSED);

        assertEquals(400, controller.loopGoalPause(SESSION, TASK_ID).getCode());
        assertEquals(GoalTask.Status.PAUSED, statusNow());
    }

    @Test
    void pauseEndpointReturns404ForForeignTaskId() {
        seed(GoalTask.Status.PURSUING);

        assertEquals(404, controller.loopGoalPause(SESSION, "not-mine").getCode());
        assertEquals(GoalTask.Status.PURSUING, statusNow());
    }

    @Test
    void resumeEndpointResumesPausedGoal() {
        seed(GoalTask.Status.PAUSED);

        assertEquals(Result.SUCCEED_CODE, controller.loopGoalResume(SESSION, TASK_ID).getCode());
        assertEquals(GoalTask.Status.PURSUING, statusNow());
    }

    @Test
    void resumeEndpointRejectsTerminalGoal() {
        seed(GoalTask.Status.ACHIEVED);

        assertEquals(400, controller.loopGoalResume(SESSION, TASK_ID).getCode());
        assertEquals(GoalTask.Status.ACHIEVED, statusNow());
    }

    @Test
    void clearEndpointRemovesGoal() {
        seed(GoalTask.Status.PURSUING);

        assertEquals(Result.SUCCEED_CODE, controller.loopGoalClear(SESSION, TASK_ID).getCode());
        assertNull(goal.get(SESSION));
    }

    @Test
    void clearEndpointReturns404WhenGoalMissing() {
        assertEquals(404, controller.loopGoalClear(SESSION, TASK_ID).getCode());
    }

    @Test
    void togglePausesPursuingGoal() {
        seed(GoalTask.Status.PURSUING);

        assertEquals(Result.SUCCEED_CODE, controller.loopToggle(SESSION, TASK_ID).getCode());
        assertEquals(GoalTask.Status.PAUSED, statusNow());
    }

    @Test
    void toggleResumesResumableGoal() {
        seed(GoalTask.Status.BLOCKED);

        assertEquals(Result.SUCCEED_CODE, controller.loopToggle(SESSION, TASK_ID).getCode());
        assertEquals(GoalTask.Status.PURSUING, statusNow());
    }

    @Test
    void toggleRejectsTerminalGoal() {
        seed(GoalTask.Status.ACHIEVED);

        assertEquals(400, controller.loopToggle(SESSION, TASK_ID).getCode());
        assertEquals(GoalTask.Status.ACHIEVED, statusNow());
    }

    @Test
    void triggerRunsRoundOnlyForActiveGoal() {
        seed(GoalTask.Status.PURSUING);
        assertEquals(Result.SUCCEED_CODE, controller.loopTrigger(SESSION, TASK_ID).getCode());
    }

    @Test
    void triggerRejectsInactiveGoal() {
        seed(GoalTask.Status.PAUSED);
        assertEquals(400, controller.loopTrigger(SESSION, TASK_ID).getCode());
    }

    @Test
    void listRowUsesPluginStandardView() {
        seed(GoalTask.Status.PAUSED);

        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) controller.loopGet(SESSION, TASK_ID).getData();

        assertEquals("GOAL", row.get("type"));
        assertEquals(false, row.get("enabled"), "非活跃 goal 的 enabled 由插件视图给出");
        assertEquals(false, row.get("cancelled"));
    }
}
