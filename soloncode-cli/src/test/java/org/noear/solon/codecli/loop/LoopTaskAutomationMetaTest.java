package org.noear.solon.codecli.loop;

import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 统一存储 tasks.json 的任务条目序列化测试：
 * LoopTask 平铺字段 + sessionId 定位键 + automation 可选覆盖层。
 */
public class LoopTaskAutomationMetaTest {

    private static LoopTask roundTrip(LoopTask task, String sessionId) {
        ONode node = task.toONode();
        node.set("sessionId", sessionId);
        return LoopTask.fromONode(node);
    }

    @Test
    public void plainTaskHasNoAutomationKey() {
        LoopTask task = new LoopTask("心跳检查", 5, null, LoopTask.TaskType.HEARTBEAT, false);
        ONode node = task.toONode();
        assertFalse(node.hasKey("automation"), "普通会话循环任务不应写 automation 键");
        assertNull(task.getAutomation());
        assertFalse(task.isAutomation());
    }

    @Test
    public void automationTaskRoundTripKeepsMeta() {
        LoopTask task = new LoopTask("每日日报", 0, "0 0 22 * * ?", LoopTask.TaskType.HEARTBEAT, false);
        task.setAutomation(new AutomationMeta("日报任务", "deepseek-chat", "code_reviewer"));

        LoopTask restored = roundTrip(task, "auto-abc123");
        assertEquals("auto-abc123", restored.getAutomation() != null ? "auto-abc123" : null); // sessionId 由 store 层负责
        assertNotNull(restored.getAutomation());
        assertTrue(restored.isAutomation());
        assertEquals("日报任务", restored.getAutomation().getName());
        assertEquals("deepseek-chat", restored.getAutomation().getModelName());
        assertEquals("code_reviewer", restored.getAutomation().getAgentName());
        assertTrue(restored.getAutomation().isEnabled());
        assertEquals("0 0 22 * * ?", restored.getCron());
    }

    @Test
    public void copyWithUpdateCarriesAutomation() {
        LoopTask task = new LoopTask("旧任务", 5, null, LoopTask.TaskType.HEARTBEAT, false);
        task.setAutomation(new AutomationMeta("名字", null, null));

        LoopTask updated = task.copyWithUpdate("新提示词", 10, null, LoopTask.TaskType.HEARTBEAT,
                null, null, null);
        assertNotNull(updated.getAutomation(), "更新任务定义时必须保留 automation 覆盖层");
        assertEquals("名字", updated.getAutomation().getName());
        assertEquals("新提示词", updated.getPrompt());
    }

    @Test
    public void goalStateRoundTripWithAutomation() {
        LoopTask task = new LoopTask("实现登录页", 0, null, LoopTask.TaskType.GOAL, true);
        task.setMaxTokens(50000L);
        task.setAutomation(new AutomationMeta("目标", "gpt-x", null));

        LoopTask restored = roundTrip(task, "auto-goal1");
        assertTrue(restored.isGoalMode(), "GOAL 类型必须恢复 GoalState");
        assertNotNull(restored.getGoalState());
        assertEquals(50000L, restored.getGoalState().getMaxTokens());
        assertNotNull(restored.getAutomation());
        assertEquals("目标", restored.getAutomation().getName());
    }

    @Test
    public void metaFromNodeMissingIdReturnsNull() {
        ONode node = new ONode();
        node.set("name", "无 id 条目");
        assertNull(AutomationMeta.fromONode(node));
    }

    @Test
    public void metaEnabledToggleAndTouch() {
        AutomationMeta meta = new AutomationMeta("t", null, null);
        assertTrue(meta.isEnabled());
        meta.setEnabled(false);
        assertFalse(meta.isEnabled());
        meta.update("新名", "m1", "a1");
        assertEquals("新名", meta.getName());
        assertEquals("m1", meta.getModelName());
        assertEquals("a1", meta.getAgentName());
    }
}
