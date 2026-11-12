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
import org.noear.solon.ai.harness.extension.goal.GoalStatus;
import org.noear.solon.ai.harness.extension.goal.GoalSnapshot;
import org.noear.solon.codecli.workspace.WorkspaceContext;
import org.noear.solon.codecli.workspace.WorkspaceManager;
import org.noear.solon.core.handle.Result;

import java.nio.file.Path;
import java.util.Locale;
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
        goal = GoalExtension.builder().jobManager(null).build();
        HarnessEngine.of(workspace.toString(), workspace.resolve("home").toString())
                .sessionProvider((id, create) -> create ? sessions.computeIfAbsent(id, InMemoryAgentSession::of) : sessions.get(id))
                .extensionAdd(goal)
                .build();
        // 触发轮次时不调用模型：本通道认领并 skip
        goal.addExecutor("web", null, (sid, prompt, locale) -> GoalOutcome.skip("test"));

        WorkspaceManager manager = mock(WorkspaceManager.class);
        WorkspaceContext context = mock(WorkspaceContext.class);
        when(manager.currentContext()).thenReturn(context);
        when(context.getGoalExtension()).thenReturn(goal);
        controller = new LoopWebController(manager);
    }

    /** 预置一条指定状态的 goal（经快照投影，首次访问时 hydrate）。 */
    private void seed(GoalStatus status) {
        AgentSession session = sessions.computeIfAbsent(SESSION, InMemoryAgentSession::of);
        GoalProjection.builder()
                .goalId(TASK_ID)
                .objective("目标")
                .status(status)
                .startEpochMs(System.currentTimeMillis())
                .build()
                .writeTo(session.getContext());
    }

    private GoalStatus statusNow() {
        GoalSnapshot task = goal.get(SESSION);
        return task == null ? null : task.getStatus();
    }

    @Test
    void pauseEndpointPausesPursuingGoal() {
        seed(GoalStatus.PURSUING);

        Result result = controller.loopGoalPause(SESSION, TASK_ID);

        assertEquals(Result.SUCCEED_CODE, result.getCode());
        assertEquals(GoalStatus.PAUSED, statusNow());
    }

    @Test
    void pauseEndpointRejectsNonPursuingGoal() {
        seed(GoalStatus.PAUSED);

        assertEquals(400, controller.loopGoalPause(SESSION, TASK_ID).getCode());
        assertEquals(GoalStatus.PAUSED, statusNow());
    }

    @Test
    void pauseEndpointReturns404ForForeignTaskId() {
        seed(GoalStatus.PURSUING);

        assertEquals(404, controller.loopGoalPause(SESSION, "not-mine").getCode());
        assertEquals(GoalStatus.PURSUING, statusNow());
    }

    @Test
    void resumeEndpointResumesPausedGoal() {
        seed(GoalStatus.PAUSED);

        assertEquals(Result.SUCCEED_CODE, controller.loopGoalResume(SESSION, TASK_ID).getCode());
        assertEquals(GoalStatus.PURSUING, statusNow());
    }

    @Test
    void resumeEndpointRejectsTerminalGoal() {
        seed(GoalStatus.ACHIEVED);

        assertEquals(400, controller.loopGoalResume(SESSION, TASK_ID).getCode());
        assertEquals(GoalStatus.ACHIEVED, statusNow());
    }

    @Test
    void clearEndpointRemovesGoal() {
        seed(GoalStatus.PURSUING);

        assertEquals(Result.SUCCEED_CODE, controller.loopGoalClear(SESSION, TASK_ID).getCode());
        assertNull(goal.get(SESSION));
    }

    @Test
    void clearEndpointReturns404WhenGoalMissing() {
        assertEquals(404, controller.loopGoalClear(SESSION, TASK_ID).getCode());
    }

    @Test
    void togglePausesPursuingGoal() {
        seed(GoalStatus.PURSUING);

        assertEquals(Result.SUCCEED_CODE, controller.loopToggle(SESSION, TASK_ID).getCode());
        assertEquals(GoalStatus.PAUSED, statusNow());
    }

    @Test
    void toggleResumesResumableGoal() {
        seed(GoalStatus.BLOCKED);

        assertEquals(Result.SUCCEED_CODE, controller.loopToggle(SESSION, TASK_ID).getCode());
        assertEquals(GoalStatus.PURSUING, statusNow());
    }

    @Test
    void toggleRejectsTerminalGoal() {
        seed(GoalStatus.ACHIEVED);

        assertEquals(400, controller.loopToggle(SESSION, TASK_ID).getCode());
        assertEquals(GoalStatus.ACHIEVED, statusNow());
    }

    @Test
    void triggerRunsRoundOnlyForActiveGoal() {
        seed(GoalStatus.PURSUING);
        assertEquals(Result.SUCCEED_CODE, controller.loopTrigger(SESSION, TASK_ID).getCode());
    }

    @Test
    void triggerRejectsInactiveGoal() {
        seed(GoalStatus.PAUSED);
        assertEquals(400, controller.loopTrigger(SESSION, TASK_ID).getCode());
    }

    @Test
    void listRowUsesPluginStandardView() {
        seed(GoalStatus.PAUSED);

        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) controller.loopGet(SESSION, TASK_ID).getData();

        assertEquals("GOAL", row.get("type"));
        assertEquals(false, row.get("enabled"), "非活跃 goal 的 enabled 由插件视图给出");
        assertEquals(false, row.get("cancelled"));
    }

    /** 面板编辑时带界面语言：目标换成它；不带时保持目标现有的语言。 */
    @Test
    void updateEndpointAppliesTheRequestLocale() {
        seed(GoalStatus.PAUSED);

        assertEquals(Result.SUCCEED_CODE,
                controller.loopUpdate(SESSION, TASK_ID, "新目标", null, null, "GOAL", null, null, null, "en").getCode());
        assertEquals(Locale.ENGLISH, goal.get(SESSION).getLocale());

        // 不传 locale = 不改（而不是清空）
        assertEquals(Result.SUCCEED_CODE,
                controller.loopUpdate(SESSION, TASK_ID, "再改一次", null, null, "GOAL", null, null, null, null).getCode());
        assertEquals(Locale.ENGLISH, goal.get(SESSION).getLocale());

        // 空串 = 同样不改（表单留空不会把语言清掉）
        assertEquals(Result.SUCCEED_CODE,
                controller.loopUpdate(SESSION, TASK_ID, "再改三次", null, null, "GOAL", null, null, null, "").getCode());
        assertEquals(Locale.ENGLISH, goal.get(SESSION).getLocale());
    }
}
