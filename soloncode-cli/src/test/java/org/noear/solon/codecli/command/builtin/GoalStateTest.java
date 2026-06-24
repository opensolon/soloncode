/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.codecli.command.builtin;

import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Goal 状态机 + 序列化 单元测试
 */
class GoalStateTest {

    // ===== 1. 状态机转移 =====

    @Test
    void initialStateShouldBePursuing() {
        GoalState gs = new GoalState("refactor module", 10000);
        assertEquals(GoalState.Status.PURSUING, gs.getStatus());
        assertTrue(gs.getStatus().isActive());
        assertFalse(gs.getStatus().isTerminal());
        assertEquals(0, gs.getConsumedTokens());
        assertEquals(10000, gs.getMaxTokens());
        assertNotNull(gs.getId());
        assertNotNull(gs.getCondition());
    }

    @Test
    void pauseShouldTransitionToPaused() {
        GoalState gs = new GoalState("test", 1000);
        assertTrue(gs.pause());
        assertEquals(GoalState.Status.PAUSED, gs.getStatus());
        assertFalse(gs.getStatus().isActive());
        assertTrue(gs.getStatus().isResumable());
    }

    @Test
    void resumeShouldTransitionBackToPursuing() {
        GoalState gs = new GoalState("test", 1000);
        gs.pause();
        assertTrue(gs.resume());
        assertEquals(GoalState.Status.PURSUING, gs.getStatus());
    }

    @Test
    void achieveShouldTransitionToAchieved() {
        GoalState gs = new GoalState("test", 1000);
        gs.achieve();
        assertEquals(GoalState.Status.ACHIEVED, gs.getStatus());
        assertTrue(gs.getStatus().isTerminal());
    }

    @Test
    void markBudgetLimitedShouldTransitionToBudgetLimited() {
        GoalState gs = new GoalState("test", 1000);
        gs.markBudgetLimited();
        assertEquals(GoalState.Status.BUDGET_LIMITED, gs.getStatus());
        assertTrue(gs.getStatus().isTerminal());
    }

    @Test
    void markBlockedShouldTransitionToBlocked() {
        GoalState gs = new GoalState("test", 1000);
        gs.markBlocked();
        assertEquals(GoalState.Status.BLOCKED, gs.getStatus());
        assertFalse(gs.getStatus().isActive());
        assertTrue(gs.getStatus().isResumable());
    }

    @Test
    void resumeFromBlockedShouldTransitionToPursuing() {
        GoalState gs = new GoalState("test", 1000);
        gs.markBlocked();
        assertTrue(gs.resume());
        assertEquals(GoalState.Status.PURSUING, gs.getStatus());
    }

    @Test
    void pauseShouldFailOnNonPursuingState() {
        GoalState gs = new GoalState("test", 1000);
        gs.achieve();
        assertFalse(gs.pause(), "should not pause an achieved goal");
    }

    @Test
    void resumeShouldFailOnNonResumableState() {
        GoalState gs = new GoalState("test", 1000);
        assertFalse(gs.resume(), "should not resume a pursuing goal");
    }

    @Test
    void achieveShouldNotWorkOnPausedState() {
        GoalState gs = new GoalState("test", 1000);
        gs.pause();
        gs.achieve(); // only works on PURSUING
        assertEquals(GoalState.Status.PAUSED, gs.getStatus(), "achieve should not fire on PAUSED");
    }

    @Test
    void markBlockedShouldNotWorkOnPausedState() {
        GoalState gs = new GoalState("test", 1000);
        gs.pause();
        gs.markBlocked(); // only works on PURSUING
        assertEquals(GoalState.Status.PAUSED, gs.getStatus(), "markBlocked should not fire on PAUSED");
    }

    // ===== P0 守卫路径：markBudgetLimited 不覆盖 ACHIEVED =====

    @Test
    void markBudgetLimitedShouldNotOverrideAchieved() {
        GoalState gs = new GoalState("test", 1000);
        gs.achieve();
        assertEquals(GoalState.Status.ACHIEVED, gs.getStatus());

        // markBudgetLimited 有 PURSUING 守卫，不应覆盖 ACHIEVED
        gs.markBudgetLimited();
        assertEquals(GoalState.Status.ACHIEVED, gs.getStatus(),
                "markBudgetLimited should not override ACHIEVED");
    }

    @Test
    void markBudgetLimitedShouldNotOverrideAchievedFromWrapUp() {
        // 模拟守卫路径：wrap-up 中 achieve() → 然后 markBudgetLimited()
        // 这是 P0 修复的核心场景：executeBudgetLimitWrapUp 调用 gs.achieve()
        // 之后守卫路径调用 gs.markBudgetLimited()，不应覆盖 ACHIEVED
        GoalState gs = new GoalState("test", 1000);
        gs.achieve();
        gs.markBudgetLimited();
        assertEquals(GoalState.Status.ACHIEVED, gs.getStatus(),
                "budget wrap-up achieve must survive markBudgetLimited");
        assertTrue(gs.getStatus().isTerminal());
    }

    // ===== 2. Token 预算 =====
    void addTokensShouldAccumulate() {
        GoalState gs = new GoalState("test", 10000);
        gs.addTokens(500);
        gs.addTokens(300);
        assertEquals(800, gs.getConsumedTokens());
    }

    @Test
    void isBudgetExceededShouldReturnTrueWhenConsumedReachesMax() {
        GoalState gs = new GoalState("test", 1000);
        assertFalse(gs.isBudgetExceeded());
        gs.addTokens(1000);
        assertTrue(gs.isBudgetExceeded());
    }

    @Test
    void isBudgetExceededShouldReturnFalseWhenMaxIsZero() {
        GoalState gs = new GoalState("test", 0);
        gs.addTokens(999999);
        assertFalse(gs.isBudgetExceeded(), "maxTokens=0 means unlimited");
    }

    @Test
    void isBudgetCriticalShouldReturnTrueAt85Percent() {
        GoalState gs = new GoalState("test", 1000);
        gs.addTokens(849);
        assertFalse(gs.isBudgetCritical());
        gs.addTokens(1); // 850 = 85%
        assertTrue(gs.isBudgetCritical());
    }

    // ===== 3. 序列化往返 =====

    @Test
    void serializationRoundTripShouldPreserveAllFields() {
        GoalState gs = new GoalState("refactor the login module", 50000);
        gs.addTokens(1234);
        gs.pause();

        ONode node = gs.toONode();
        GoalState restored = GoalState.fromONode(node);

        assertEquals(gs.getId(), restored.getId());
        assertEquals(gs.getCondition(), restored.getCondition());
        assertEquals(gs.getStatus(), restored.getStatus());
        assertEquals(gs.getConsumedTokens(), restored.getConsumedTokens());
        assertEquals(gs.getMaxTokens(), restored.getMaxTokens());
        assertEquals(gs.getStartEpochMs(), restored.getStartEpochMs());
        assertEquals(gs.getPausedAtEpochMs(), restored.getPausedAtEpochMs());
    }

    @Test
    void serializationOfBlockedStateShouldPreserveStatus() {
        GoalState gs = new GoalState("test blocked", 5000);
        gs.markBlocked();

        ONode node = gs.toONode();
        GoalState restored = GoalState.fromONode(node);

        assertEquals(GoalState.Status.BLOCKED, restored.getStatus());
        assertTrue(restored.getStatus().isResumable());
    }

    // ===== 4. LoopTask Goal 模式基础 =====

    @Test
    void goalModeShouldBeTrueWhenTypeIsGoal() {
        LoopTask task = new LoopTask("prompt", 0, null, LoopTask.TaskType.GOAL, true);
        assertTrue(task.isGoalMode());
        assertNotNull(task.getGoalState());
        assertEquals("prompt", task.getGoalState().getCondition());
    }

    @Test
    void goalModeShouldBeFalseWithoutGoalCondition() {
        LoopTask task = new LoopTask("prompt", 1, null, null, false);
        assertFalse(task.isGoalMode());
        assertNull(task.getGoalState());
    }

    @Test
    void goalTaskSerializationRoundTripShouldPreserveGoalState() {
        LoopTask task = new LoopTask("test prompt", 0, null, LoopTask.TaskType.GOAL, true);
        task.getGoalState().addTokens(5000);
        task.incrementIteration();
        task.incrementIteration();

        ONode node = task.toONode();
        LoopTask restored = LoopTask.fromONode(node);

        assertTrue(restored.isGoalMode());
        assertEquals("test prompt", restored.getGoalState().getCondition());
        assertEquals(5000, restored.getGoalState().getConsumedTokens());
        assertEquals(2, restored.getCurrentIteration());
    }

    @Test
    void goalTaskWithBlockedStateSerializationRoundTrip() {
        LoopTask task = new LoopTask("test prompt", 0, null, LoopTask.TaskType.GOAL, true);
        task.getGoalState().markBlocked();

        ONode node = task.toONode();
        LoopTask restored = LoopTask.fromONode(node);

        assertEquals(GoalState.Status.BLOCKED, restored.getGoalState().getStatus());
        assertTrue(restored.getGoalState().getStatus().isResumable());
    }
}
