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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Loop 闭环能力测试。
 */
class LoopExecutionResultTest {


    @Test
    void copyWithUpdateShouldKeepIdentityAndRuntimeState() {
        LoopTask task = new LoopTask("old prompt", 1, null, null, false);
        task.updateLastExecution("last");
        task.incrementIteration();

        LoopTask updated = task.copyWithUpdate("new prompt", 5, null, LoopTask.TaskType.GOAL, false, null, null);

        assertEquals(task.getId(), updated.getId());
        assertEquals(task.getCreatedAt(), updated.getCreatedAt());
        assertEquals(1, updated.getCurrentIteration());
        assertEquals("last", updated.getLastResult());
        assertEquals("new prompt", updated.getPrompt());
        assertEquals(LoopTask.TaskType.GOAL, updated.getType());
        
    }



    @Test
    void submittedOnlyState() {
        LoopExecutionResult result = LoopExecutionResult.submittedOnly();

        assertTrue(result.isSubmitted());
        assertFalse(result.isCompleted());
        assertNull(result.getFinalResult());
    }

    @Test
    void fromTextShouldWork() {
        LoopExecutionResult result = LoopExecutionResult.fromText("normal response");

        assertTrue(result.isCompleted());
        assertEquals("normal response", result.getFinalResult());
    }
}