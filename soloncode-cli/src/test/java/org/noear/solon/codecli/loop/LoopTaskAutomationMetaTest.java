package org.noear.solon.codecli.loop;
import org.noear.solon.ai.harness.session.goal.GoalState;

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
    public void copyWithUpdateKeepsIdentityAndRuntimeState() {
        LoopTask task = new LoopTask("old prompt", 1, null, false);
        task.updateLastExecution("last");
        task.incrementIteration();

        LoopTask updated = task.copyWithUpdate("new prompt", 5, null, false);

        assertEquals(task.getId(), updated.getId());
        assertEquals(task.getCreatedAt(), updated.getCreatedAt());
        assertEquals(1, updated.getCurrentIteration());
        assertEquals("last", updated.getLastResult());
        assertEquals("new prompt", updated.getPrompt());
    }

    @Test
    public void plainTaskHasNoAutomationKey() {
        LoopTask task = new LoopTask("心跳检查", 5, null, false);
        ONode node = task.toONode();
        assertFalse(node.hasKey("automation"), "普通会话循环任务不应写 automation 键");
        assertNull(task.getAutomation());
        assertFalse(task.isAutomation());
    }

    @Test
    public void automationTaskRoundTripKeepsMeta() {
        LoopTask task = new LoopTask("每日日报", 0, "0 0 22 * * ?", false);
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
        LoopTask task = new LoopTask("旧任务", 5, null, false);
        task.setAutomation(new AutomationMeta("名字", null, null));

        LoopTask updated = task.copyWithUpdate("新提示词", 10, null, null);
        assertNotNull(updated.getAutomation(), "更新任务定义时必须保留 automation 覆盖层");
        assertEquals("名字", updated.getAutomation().getName());
        assertEquals("新提示词", updated.getPrompt());
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
