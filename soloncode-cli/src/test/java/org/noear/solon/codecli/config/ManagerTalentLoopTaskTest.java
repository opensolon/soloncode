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
package org.noear.solon.codecli.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.solon.codecli.command.builtin.LoopTask;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ManagerTalentLoopTaskTest {
    private StubLoopScheduler scheduler;
    private ManagerTalent talent;

    @BeforeEach
    void setUp() {
        scheduler = new StubLoopScheduler();
        talent = new ManagerTalent(null, new AgentSettings(), scheduler);
    }

    @Test
    void addLoopTaskUsesWebEndpointDefaults() {
        String result = talent.addLoopTask(
                "check deployment", null, null, null, null, null, null,
                "session-1");

        LoopTask task = scheduler.lastScheduledTask;
        assertNotNull(task);
        assertEquals("session-1", scheduler.lastSessionId);
        assertEquals("check deployment", task.getPrompt());
        assertEquals(5, task.getIntervalMinutes());
        assertEquals(LoopTask.TaskType.HEARTBEAT, task.getType());
        assertFalse(task.isRunNow());
        assertTrue(result.contains("taskId=" + task.getId()));
    }

    @Test
    void addLoopTaskPassesCronTypeAndBudgets() {
        String result = talent.addLoopTask(
                "finish migration", 15, "0 */15 * * * ? *", "goal", true,
                2000L, 60000L, "session-2");

        LoopTask task = scheduler.lastScheduledTask;
        assertTrue(result.startsWith("OK:"));
        assertEquals("0 */15 * * * ? *", task.getCron());
        assertEquals(LoopTask.TaskType.GOAL, task.getType());
        assertTrue(task.isRunNow());
        assertEquals(Long.valueOf(2000L), task.getMaxTokens());
        assertEquals(Long.valueOf(60000L), task.getMaxDurationMs());
    }

//    @Test
//    void removeLoopTaskRemovesTaskFromCurrentSession() {
//        talent.addLoopTask("check status", 10, null, null, false, null, null, "session-3");
//        String taskId = scheduler.lastScheduledTask.getId();
//
//        String result = talent.removeLoopTask(taskId, "session-3");
//
//        assertTrue(result.startsWith("OK:"));
//        assertNull(scheduler.getTaskById("session-3", taskId));
//    }
//
//    @Test
//    void removeLoopTaskReportsMissingTask() {
//        String result = talent.removeLoopTask("missing", "session-4");
//
//        assertTrue(result.startsWith("ERROR:"));
//    }

    private static class StubLoopScheduler implements ManagerTalent.LoopTaskOperations {
        private final Map<String, Map<String, LoopTask>> tasks = new HashMap<>();
        private String lastSessionId;
        private LoopTask lastScheduledTask;

        @Override
        public LoopTask schedule(String sessionId, LoopTask task) {
            lastSessionId = sessionId;
            lastScheduledTask = task;
            tasks.computeIfAbsent(sessionId, key -> new HashMap<>()).put(task.getId(), task);
            return task;
        }

        @Override
        public LoopTask getTaskById(String sessionId, String taskId) {
            Map<String, LoopTask> sessionTasks = tasks.get(sessionId);
            return sessionTasks == null ? null : sessionTasks.get(taskId);
        }

        @Override
        public void remove(String sessionId, LoopTask task) {
            Map<String, LoopTask> sessionTasks = tasks.get(sessionId);
            if (sessionTasks != null) {
                sessionTasks.remove(task.getId());
            }
        }
    }
}
