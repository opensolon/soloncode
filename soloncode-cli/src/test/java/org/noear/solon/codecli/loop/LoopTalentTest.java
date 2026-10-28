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
package org.noear.solon.codecli.loop;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.solon.codecli.config.AgentSettings;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LoopTalent（loop_ 工具族）单元测试
 */
class LoopTalentTest {
    private static final DateTimeFormatter AT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private StubLoopScheduler scheduler;
    private AgentSettings settings;
    private LoopTalent talent;

    @BeforeEach
    void setUp() {
        scheduler = new StubLoopScheduler();
        settings = new AgentSettings();
        talent = new LoopTalent(scheduler, settings);
    }

    /** 生成一个未来时刻的 7 位 cron（带年份，只命中一次），避免固定日期随时间腐烂 */
    private static String futureOneShotCron(LocalDateTime time) {
        return String.format("0 %d %d %d %d ? %d",
                time.getMinute(), time.getHour(),
                time.getDayOfMonth(), time.getMonthValue(), time.getYear());
    }

    // ==================== loop_add ====================

    @Test
    void loopAddUsesDefaults() {
        String result = talent.loopAdd("check deployment", null, null, null, null, null, "session-1");

        LoopTask task = scheduler.lastScheduledTask;
        assertNotNull(task);
        assertEquals("session-1", scheduler.lastSessionId);
        assertEquals("check deployment", task.getPrompt());
        assertEquals(5, task.getIntervalMinutes());
        assertFalse(task.isRunNow());
        assertFalse(task.isOneShot());
        assertTrue(result.contains("taskId"));
        assertTrue(result.contains("serverNow"));
        assertTrue(result.contains("timezone"));
    }

    @Test
    void loopAddPassesCronTypeAndBudgets() {
        String result = talent.loopAdd("finish migration", null, 15, "0 */15 * * * ? *", null, true, "session-2");

        LoopTask task = scheduler.lastScheduledTask;
        assertTrue(result.contains("taskId"));
        assertEquals("0 */15 * * * ? *", task.getCron());
        assertTrue(task.isRunNow());
        assertTrue(result.contains("nextFireTime")); // cron 模式回写首次触发时刻
    }

    @Test
    void loopAddAtConvertsToCronAndForcesOneShot() {
        LocalDateTime future = LocalDateTime.now().plusHours(3).withSecond(0).withNano(0);
        String at = future.format(AT_FORMAT);

        String result = talent.loopAdd("evening report", at, null, null, null, null, "session-1");

        LoopTask task = scheduler.lastScheduledTask;
        assertNotNull(task);
        assertTrue(task.isOneShot());
        assertTrue(task.isCronMode());
        assertEquals(futureOneShotCron(future), task.getCron());
        assertTrue(result.contains("\"oneShot\": true"));
        assertTrue(result.contains("nextFireTime"));
    }

    @Test
    void loopAddRejectsPastAt() {
        String past = LocalDateTime.now().minusHours(1).format(AT_FORMAT);

        String result = talent.loopAdd("late task", past, null, null, null, null, "session-1");

        assertTrue(result.startsWith("ERROR:"));
        assertNull(scheduler.lastScheduledTask);
    }

    @Test
    void loopAddRejectsMalformedAt() {
        String result = talent.loopAdd("bad at", "2026/09/05 18:00", null, null, null, null, "session-1");

        assertTrue(result.startsWith("ERROR:"));
        assertNull(scheduler.lastScheduledTask);
    }

    @Test
    void loopAddCronOneShot() {
        LocalDateTime future = LocalDateTime.now().plusDays(1).withSecond(0).withNano(0);
        String cron = futureOneShotCron(future);

        String result = talent.loopAdd("evening report", null, null, cron, true, null, "session-1");

        LoopTask task = scheduler.lastScheduledTask;
        assertNotNull(task);
        assertTrue(task.isOneShot());
        assertEquals(cron, task.getCron());
        assertTrue(result.contains("\"oneShot\": true"));
    }

    @Test
    void loopAddRejectsExpiredCron() {
        // 语法正确但年份已过：底层会判为 expired 停掉 job，任务记录却永久残留
        String result = talent.loopAdd("stale one-shot", null, null, "0 0 18 5 9 ? 2020", true, null, "session-1");

        assertTrue(result.startsWith("ERROR:"));
        assertTrue(result.contains("无有效触发时刻"), result);
        assertNull(scheduler.lastScheduledTask);
    }

    @Test
    void loopAddRejectsUnparsableCron() {
        String result = talent.loopAdd("bad cron", null, null, "every day at 6", null, null, "session-1");

        assertTrue(result.startsWith("ERROR:"));
        assertTrue(result.contains("无法解析"), result);
        assertNull(scheduler.lastScheduledTask);
    }

    @Test
    void loopAddNoLongerAcceptsGoalType() {
        // type 参数已从 loop_add 移除：goal 是会话级独立能力，不再经由定时任务创建。
        // 这里锁定「创建出来的一定是定时任务」，防止 GOAL 通道回流。
        talent.loopAdd("plain task", null, 10, null, null, null, "session-1");

        assertNotNull(scheduler.lastScheduledTask);
        assertTrue(scheduler.lastScheduledTask.getJobName().startsWith("loop-"));
    }

    @Test
    void loopAddRejectsOneShotWithoutCron() {
        String result = talent.loopAdd("bad one-shot", null, 10, null, true, null, "session-1");

        assertTrue(result.startsWith("ERROR:"));
        assertNull(scheduler.lastScheduledTask);
    }

    @Test
    void loopAddRejectsOneShotWithRunNow() {
        String future = LocalDateTime.now().plusHours(2).format(AT_FORMAT);

        String result = talent.loopAdd("conflict", future, null, null, null, true, "session-1");

        assertTrue(result.startsWith("ERROR:"));
        assertNull(scheduler.lastScheduledTask);
    }

    // ==================== loop_list ====================

    @Test
    void loopListReturnsTasksWithServerNow() {
        talent.loopAdd("task a", null, 10, null, null, null, "session-1");
        talent.loopAdd("task b", null, null, "0 */5 * * * ? *", null, null, "session-1");

        String result = talent.loopList("session-1");

        assertTrue(result.contains("task a"));
        assertTrue(result.contains("task b"));
        assertTrue(result.contains("\"total\": 2"));
        assertTrue(result.contains("intervalMinutes"));
        assertTrue(result.contains("cron"));
        assertTrue(result.contains("serverNow"));
    }

    @Test
    void loopListEmptySession() {
        String result = talent.loopList("no-such-session");

        assertTrue(result.contains("\"total\": 0"));
    }

    // ==================== loop_control ====================

    @Test
    void loopControlStopRemovesTaskFromCurrentSession() {
        talent.loopAdd("check status", null, 10, null, null, null, "session-3");
        String taskId = scheduler.lastScheduledTask.getId();

        String result = talent.loopControl("stop", taskId, "session-3");

        assertTrue(result.contains("\"action\": \"stop\""));
        assertNull(scheduler.getTaskById("session-3", taskId));
    }

    @Test
    void loopControlStopReportsMissingTask() {
        String result = talent.loopControl("stop", "missing", "session-4");

        assertTrue(result.startsWith("ERROR:"));
    }

    @Test
    void loopControlPauseResumeTogglesHeartbeatEnabled() {
        talent.loopAdd("heartbeat", null, 10, null, null, null, "session-5");
        String taskId = scheduler.lastScheduledTask.getId();

        String paused = talent.loopControl("pause", taskId, "session-5");
        assertTrue(paused.contains("\"enabled\": false"));
        assertFalse(scheduler.getTaskById("session-5", taskId).isEnabled());

        String resumed = talent.loopControl("resume", taskId, "session-5");
        assertTrue(resumed.contains("\"enabled\": true"));
        assertTrue(scheduler.getTaskById("session-5", taskId).isEnabled());
        assertEquals(2, scheduler.toggleCount); // 暂停+恢复各一次
    }

    @Test
    void loopControlPauseTogglesEnabled() {
        talent.loopAdd("heartbeat task", null, 10, null, null, null, "session-6");
        String taskId = scheduler.lastScheduledTask.getId();

        talent.loopControl("pause", taskId, "session-6");

        assertEquals(1, scheduler.toggleCount); // 定时任务统一走 enabled 开关
        assertFalse(scheduler.lastScheduledTask.isEnabled());
    }

    @Test
    void loopControlPauseReportsMissingTask() {
        String result = talent.loopControl("pause", "missing", "session-7");
        assertTrue(result.startsWith("ERROR:"));
    }

    @Test
    void loopControlTriggerSubmitsExecution() {
        talent.loopAdd("manual run", null, 10, null, null, null, "session-8");
        String taskId = scheduler.lastScheduledTask.getId();

        String result = talent.loopControl("trigger", taskId, "session-8");

        assertTrue(result.contains("\"submitted\": true"));
        assertEquals(taskId, scheduler.lastTriggeredId);
    }

    @Test
    void loopControlTriggerRejectsDisabledTask() {
        talent.loopAdd("paused run", null, 10, null, null, null, "session-9");
        String taskId = scheduler.lastScheduledTask.getId();
        talent.loopControl("pause", taskId, "session-9");

        String result = talent.loopControl("trigger", taskId, "session-9");

        assertTrue(result.startsWith("ERROR:"));
        assertNull(scheduler.lastTriggeredId);
    }

    @Test
    void loopControlRejectsUnknownAction() {
        String result = talent.loopControl("restart", "any", "session-10");

        assertTrue(result.startsWith("ERROR:"));
        assertTrue(result.contains("stop/pause/resume/trigger"));
    }

    // ==================== oneShot 序列化往返 ====================

    @Test
    void oneShotSurvivesSerializationRoundTrip() {
        LoopTask task = new LoopTask("one shot", 0, "0 0 18 5 9 ? 2026", false);
        task.setOneShot(true);

        org.noear.snack4.ONode node = task.toONode();
        assertTrue(node.getOrNull("oneShot") != null && node.get("oneShot").getBoolean());

        LoopTask restored = LoopTask.fromONode(node);
        assertTrue(restored.isOneShot());
    }

    @Test
    void legacyJsonWithoutOneShotDefaultsToFalse() {
        LoopTask task = new LoopTask("legacy", 5, null, false);
        org.noear.snack4.ONode node = task.toONode();
        assertNull(node.getOrNull("oneShot")); // 未开启时不写字段

        LoopTask restored = LoopTask.fromONode(node);
        assertFalse(restored.isOneShot());
    }

    // ==================== Stub ====================

    private static class StubLoopScheduler implements LoopTalent.LoopTaskOperations {
        private final Map<String, Map<String, LoopTask>> tasks = new HashMap<>();
        private String lastSessionId;
        private LoopTask lastScheduledTask;
        private String lastTriggeredId;
        private int toggleCount;

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

        @Override
        public List<LoopTask> listAll(String sessionId) {
            Map<String, LoopTask> sessionTasks = tasks.get(sessionId);
            if (sessionTasks == null) {
                return new ArrayList<>();
            }
            return new ArrayList<>(sessionTasks.values());
        }

        @Override
        public void toggle(String sessionId, String taskId) {
            toggleCount++;
            LoopTask task = getTaskById(sessionId, taskId);
            if (task != null) {
                task.setEnabled(!task.isEnabled());
            }
        }

        @Override
        public void trigger(String sessionId, String taskId) {
            lastTriggeredId = taskId;
        }
    }

    // ==================== isEnabled 响应设置 ====================

    @Test
    void isEnabledFollowsSettings() {
        // 默认开启
        assertTrue(talent.isEnabled());

        // 模拟 Web 设置页保存（bindTo 原地更新 settings 对象）：关闭后立即生效
        settings.getGeneral().setLoopsEnabled(false);
        assertFalse(talent.isEnabled());

        // 重新开启
        settings.getGeneral().setLoopsEnabled(true);
        assertTrue(talent.isEnabled());
    }
}
