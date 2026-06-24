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

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Loop 闭环能力测试。
 */
class LoopExecutionResultTest {
    @Test
    void fromTextShouldDetectGoalAchieved() {
        LoopExecutionResult result = LoopExecutionResult.fromText("done\n[GOAL_ACHIEVED]");

        assertTrue(result.isSubmitted());
        assertTrue(result.isCompleted());
        assertTrue(result.isGoalAchieved());
        assertEquals("done\n[GOAL_ACHIEVED]", result.getFinalResult());
    }

    @Test
    void copyWithUpdateShouldKeepIdentityAndRuntimeState() {
        LoopTask task = new LoopTask("old prompt", 1, null, null, false, 2);
        task.updateLastExecution("last");
        task.incrementIteration();

        LoopTask updated = task.copyWithUpdate("new prompt", 5, null, "goal", true, 3, false);

        assertEquals(task.getId(), updated.getId());
        assertEquals(task.getCreatedAt(), updated.getCreatedAt());
        assertEquals(1, updated.getCurrentIteration());
        assertEquals("last", updated.getLastResult());
        assertEquals("new prompt", updated.getPrompt());
        assertEquals("goal", updated.getGoalCondition());
        assertTrue(updated.isWorktreeEnabled());
        assertFalse(updated.isMaxIterationsReached());
    }

    @Test
    void maxIterationsShouldWorkWithoutGoal() {
        LoopTask task = new LoopTask("prompt", 1, null, null, false, 2);

        assertFalse(task.isMaxIterationsReached());
        task.incrementIteration();
        assertFalse(task.isMaxIterationsReached());
        task.incrementIteration();
        assertTrue(task.isMaxIterationsReached());
    }

    @Test
    void appendHistoryShouldWriteStructuredResult() throws Exception {
        Path workspace = Files.createTempDirectory("loop-state-test-");
        LoopTask task = new LoopTask("prompt", 1, null, "goal", false, 2);

        LoopStateManager.init(workspace.toString(), task.getId(), task.getPrompt());
        LoopStateManager.appendHistory(workspace.toString(), task.getId(),
                LoopExecutionResult.fromText("done\n[GOAL_ACHIEVED]"),
                1, "GOAL_ACHIEVED");

        String json = new String(Files.readAllBytes(workspace.resolve(".soloncode").resolve("loops")
                .resolve(task.getId()).resolve("history.json")), "UTF-8");
        ONode root = ONode.ofJson(json);

        assertTrue(root.isArray());
        int count = 0;
        for (ONode ignored : root.getArray()) {
            count++;
        }
        assertEquals(1, count);
        assertEquals("GOAL_ACHIEVED", root.get(0).get("stopReason").getString());
        assertTrue(root.get(0).get("goalAchieved").getBoolean());
    }

    @Test
    void submittedOnlyShouldNotBeGoalAchieved() {
        LoopExecutionResult result = LoopExecutionResult.submittedOnly();

        assertTrue(result.isSubmitted());
        assertFalse(result.isCompleted());
        assertFalse(result.isGoalAchieved());
        assertNull(result.getFinalResult());
    }

    @Test
    void fromTextWithoutGoalMarkerShouldNotBeGoalAchieved() {
        LoopExecutionResult result = LoopExecutionResult.fromText("normal response");

        assertTrue(result.isCompleted());
        assertFalse(result.isGoalAchieved());
        assertEquals("normal response", result.getFinalResult());
    }
}
