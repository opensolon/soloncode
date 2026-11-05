package org.noear.solon.codecli.api.web.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.harness.session.goal.GoalExtension;
import org.noear.solon.ai.harness.session.goal.GoalState;
import org.noear.solon.codecli.workspace.WorkspaceContext;
import org.noear.solon.codecli.workspace.WorkspaceManager;
import org.noear.solon.core.handle.Result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LoopWebController 的 goal 端点行为基线（P0/P1 改造前先锁定行为）。
 *
 * <p>这些端点在改造中由「手工 pause + unschedule / resume + schedule / clear + unschedule」
 * 改为走 {@link GoalExtension} 门面。本测试锁定改造前后对外可见的行为：分流命中、
 * 状态校验、404/400 分支、以及「调度配对只由门面负责，控制器不再直接操作调度器」。</p>
 */
class GoalLoopEndpointTest {
    private static final String SESSION = "web-test";
    private static final String TASK_ID = "goal-1";

    private WorkspaceManager manager;
    private WorkspaceContext context;
    private GoalExtension goal;
    private AgentSession session;
    private GoalState state;
    private LoopWebController controller;

    @BeforeEach
    void setUp() {
        manager = mock(WorkspaceManager.class);
        context = mock(WorkspaceContext.class);
        goal = mock(GoalExtension.class);
        session = InMemoryAgentSession.of(SESSION);
        state = mock(GoalState.class);

        when(manager.currentContext()).thenReturn(context);
        when(context.getGoalExtension()).thenReturn(goal);
        when(goal.sessionOf(SESSION)).thenReturn(session);
        when(goal.state(session)).thenReturn(state);
        when(goal.stateOf(SESSION)).thenReturn(state);
        when(goal.find(SESSION, TASK_ID)).thenReturn(state);
        when(state.getId()).thenReturn(TASK_ID);

        controller = new LoopWebController(manager);
    }

    private void status(GoalState.Status status) {
        when(state.getStatus()).thenReturn(status);
    }

    @Test
    void pauseEndpointPausesThroughFacade() {
        status(GoalState.Status.PURSUING);

        Result result = controller.loopGoalPause(SESSION, TASK_ID);

        assertEquals(Result.SUCCEED_CODE, result.getCode());
        // 调度摘除由 pause 门面内含，控制器不得再单独操作调度器；
        // 且走 sessionId+taskId 重载：控制器不再自行解析会话与比对 taskId
        verify(goal).pause(SESSION, TASK_ID);
    }

    @Test
    void pauseEndpointRejectsNonPursuingGoal() {
        status(GoalState.Status.PAUSED);

        Result result = controller.loopGoalPause(SESSION, TASK_ID);

        assertEquals(400, result.getCode());
        verify(goal, never()).pause(anyString(), anyString());
    }

    @Test
    void pauseEndpointReturns404ForForeignTaskId() {
        Result result = controller.loopGoalPause(SESSION, "not-mine");

        assertEquals(404, result.getCode());
        verify(goal, never()).pause(anyString(), anyString());
    }

    @Test
    void resumeEndpointResumesThroughFacade() {
        status(GoalState.Status.PAUSED);
        when(goal.resume(SESSION, TASK_ID)).thenReturn(true);

        Result result = controller.loopGoalResume(SESSION, TASK_ID);

        assertEquals(Result.SUCCEED_CODE, result.getCode());
        // 调度恢复（runNow=true）由 resume 门面内含
        verify(goal).resume(SESSION, TASK_ID);
    }

    @Test
    void resumeEndpointRejectsTerminalGoal() {
        status(GoalState.Status.ACHIEVED);

        Result result = controller.loopGoalResume(SESSION, TASK_ID);

        assertEquals(400, result.getCode());
        verify(goal, never()).resume(anyString(), anyString());
    }

    @Test
    void clearEndpointClearsThroughFacade() {
        Result result = controller.loopGoalClear(SESSION, TASK_ID);

        assertEquals(Result.SUCCEED_CODE, result.getCode());
        // 先摘调度再清状态由 clear 门面内含
        verify(goal).clear(SESSION);
        verify(context, never()).getGoalScheduler();
    }

    @Test
    void clearEndpointReturns404WhenGoalMissing() {
        when(goal.find(SESSION, TASK_ID)).thenReturn(null);

        Result result = controller.loopGoalClear(SESSION, TASK_ID);

        assertEquals(404, result.getCode());
        verify(goal, never()).clear(anyString());
    }

    @Test
    void togglePausesPursuingGoalThroughFacade() {
        status(GoalState.Status.PURSUING);
        when(goal.pause(SESSION, TASK_ID)).thenReturn(true);

        Result result = controller.loopToggle(SESSION, TASK_ID);

        assertEquals(Result.SUCCEED_CODE, result.getCode());
        verify(goal).pause(SESSION, TASK_ID);
        verify(goal, never()).resume(anyString(), anyString());
    }

    @Test
    void toggleResumesResumableGoalThroughFacade() {
        status(GoalState.Status.PAUSED);
        when(goal.resume(SESSION, TASK_ID)).thenReturn(true);

        Result result = controller.loopToggle(SESSION, TASK_ID);

        assertEquals(Result.SUCCEED_CODE, result.getCode());
        verify(goal).resume(SESSION, TASK_ID);
        verify(goal, never()).pause(anyString(), anyString());
    }

    @Test
    void toggleRejectsTerminalGoal() {
        status(GoalState.Status.ACHIEVED);

        Result result = controller.loopToggle(SESSION, TASK_ID);

        assertEquals(400, result.getCode());
        verify(goal, never()).pause(anyString(), anyString());
        verify(goal, never()).resume(anyString(), anyString());
    }

    @Test
    void triggerRunsRoundOnlyForActiveGoal() {
        status(GoalState.Status.PURSUING);

        Result result = controller.loopTrigger(SESSION, TASK_ID);

        assertEquals(Result.SUCCEED_CODE, result.getCode());
        verify(goal).runRoundAsync(session);
    }

    @Test
    void triggerRejectsInactiveGoal() {
        status(GoalState.Status.PAUSED);

        Result result = controller.loopTrigger(SESSION, TASK_ID);

        assertEquals(400, result.getCode());
        verify(goal, never()).runRoundAsync(any());
    }
}
