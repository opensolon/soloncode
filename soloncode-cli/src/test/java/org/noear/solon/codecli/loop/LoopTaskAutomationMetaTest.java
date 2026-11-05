package org.noear.solon.codecli.loop;

import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.harness.session.loop.LoopExtension;
import org.noear.solon.ai.harness.session.loop.LoopPatch;
import org.noear.solon.ai.harness.session.loop.LoopSpec;
import org.noear.solon.ai.harness.session.loop.LoopTask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自动任务覆盖层经 loop 插件扩展字段（键 {@code "automation"}）存取：
 * tasks.json 格式与历史一致，插件不认识宿主概念但原样保留。
 */
public class LoopTaskAutomationMetaTest {

    /** 只在内存、无调度：只验证任务定义与扩展字段。 */
    private static LoopExtension loop() {
        return LoopExtension.builder("ws").jobManager(null).defaultExecutor(false).build();
    }

    private static LoopTask roundTrip(LoopTask task, String sessionId) {
        ONode node = task.toONode();
        node.set("sessionId", sessionId);
        return LoopTask.fromONode(node);
    }

    @Test
    public void plainTaskHasNoAutomationKey() {
        LoopTask task = loop().schedule("web-1", LoopSpec.of("心跳检查").everyMinutes(5));
        assertFalse(task.toONode().hasKey(AutomationMeta.EXTRA_KEY), "普通会话循环任务不应写 automation 键");
        assertNull(AutomationMeta.of(task));
    }

    @Test
    public void automationTaskRoundTripKeepsMeta() {
        LoopTask task = loop().schedule("auto-abc123", new AutomationMeta("日报任务", "deepseek-chat", "code_reviewer")
                .writeTo(LoopSpec.of("每日日报").cron("0 0 22 * * ?")));

        LoopTask restored = roundTrip(task, "auto-abc123");
        AutomationMeta meta = AutomationMeta.of(restored);
        assertNotNull(meta);
        assertEquals("日报任务", meta.getName());
        assertEquals("deepseek-chat", meta.getModelName());
        assertEquals("code_reviewer", meta.getAgentName());
        assertEquals("0 0 22 * * ?", restored.getCron());
    }

    /** 历史文件的键名与字段形态必须可读（含已废弃的 automation.enabled，读取时忽略）。 */
    @Test
    public void legacyAutomationJsonIsReadable() {
        ONode node = loop().schedule("auto-1", LoopSpec.of("旧任务")).toONode();
        node.getOrNew("automation")
                .set("id", "abc123def456").set("name", "历史任务").set("enabled", false)
                .set("createdAt", "2026-01-01T00:00:00Z").set("updatedAt", "2026-01-02T00:00:00Z");

        LoopTask task = LoopTask.fromONode(node);
        AutomationMeta meta = AutomationMeta.of(task);
        assertEquals("abc123def456", meta.getId());
        assertEquals("历史任务", meta.getName());
        assertTrue(task.isEnabled(), "启停只认 task.enabled（单一真相源）");
    }

    @Test
    public void patchWithoutMetaKeepsAutomation() {
        LoopExtension loop = loop();
        LoopTask task = loop.schedule("auto-1", new AutomationMeta("名字", null, null)
                .writeTo(LoopSpec.of("旧任务").everyMinutes(5)));

        LoopTask updated = loop.update("auto-1", task.getId(), LoopPatch.create().prompt("新提示词").everyMinutes(10));
        assertNotNull(AutomationMeta.of(updated), "更新任务定义时必须保留 automation 覆盖层");
        assertEquals("名字", AutomationMeta.of(updated).getName());
        assertEquals("新提示词", updated.getPrompt());
    }

    @Test
    public void metaFromNodeMissingIdReturnsNull() {
        ONode node = new ONode();
        node.set("name", "无 id 条目");
        assertNull(AutomationMeta.fromONode(node));
    }

    @Test
    public void metaUpdateRequiresWriteBack() {
        LoopExtension loop = loop();
        AutomationMeta meta = new AutomationMeta("t", null, null);
        LoopTask task = loop.schedule("auto-1", meta.writeTo(LoopSpec.of("t")));

        meta.update("新名", "m1", "a1");
        assertEquals("t", AutomationMeta.of(loop.get("auto-1", task.getId())).getName(),
                "覆盖层是任务定义的一部分：未回写不生效");

        LoopTask updated = loop.update("auto-1", task.getId(), meta.writeTo(LoopPatch.create()));
        AutomationMeta reread = AutomationMeta.of(updated);
        assertEquals("新名", reread.getName());
        assertEquals("m1", reread.getModelName());
        assertEquals("a1", reread.getAgentName());
        assertFalse(updated.getExtra(AutomationMeta.EXTRA_KEY).hasKey("enabled"), "重复状态字段已删除");
        assertEquals("t", updated.getPrompt(), "空补丁不改任务定义");
    }
}
