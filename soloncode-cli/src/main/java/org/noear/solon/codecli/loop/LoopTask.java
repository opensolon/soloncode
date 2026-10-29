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

import lombok.Getter;
import org.noear.snack4.ONode;

import java.time.Instant;
import java.util.UUID;

/**
 * 定时循环任务模型，用于 /loop 命令
 *
 * <p>支持 Loop Engineering 的 6 个基元：
 * <ul>
 *   <li>Automations — 定时/cron 触发（intervalMinutes / cron）</li>
 *   <li>Skills — AI 根据 prompt 自动匹配可用技能</li>
 *   <li>Connectors — channelNotify 结果通知</li>
 *   <li>State — stateDir 持久状态目录 (.soloncode/loops/&lt;id&gt;/)</li>
 * </ul>
 *
 * <p>本类只承载「按时间表触发的独立任务」。会话目标（Goal）已是独立的会话级能力，
 * 由 {@code harness.session.goal} 包承载，二者不共享模型。</p>
 *
 * @author noear
 * @since 3.9.1
 */
@Getter
public class LoopTask {

    private static final int MIN_INTERVAL = 1;
    private static final int MAX_INTERVAL = 1440; // 24h

    // ---- 核心调度字段 ----
    private final String id;
    private volatile String prompt;
    private final int intervalMinutes;
    private final String cron;
    private final Instant createdAt;
    private final boolean autoInterval;

    // ---- Loop Engineering 扩展字段 ----
    private final boolean runNow;            // 注册后立即执行首次（initialDelay=0）

    // ---- 自动任务覆盖层（null = 普通会话循环任务） ----
    private volatile AutomationMeta automation;

    // ---- 运行时状态 ----
    private volatile boolean running;
    private volatile boolean cancelled;
    private volatile String lastResult;
    private volatile Instant lastExecutedAt;
    private volatile int currentIteration;
    private volatile boolean enabled = true; // 启用/停用
    private volatile boolean oneShot; // 一次性任务：调度触发执行一轮后自动注销

    /**
     * 固定间隔构造
     */
    public LoopTask(String prompt, int intervalMinutes) {
        this(prompt, intervalMinutes, null, false);
    }

    /**
     * cron 表达式构造
     */
    public LoopTask(String prompt, String cron) {
        this(prompt, 0, cron, false);
    }

    /**
     * 全参数构造（由 Builder、copyWithUpdate 调用）
     */
    LoopTask(String id, String prompt, int intervalMinutes, String cron,
             Instant createdAt, boolean autoInterval,
             boolean enabled,
             boolean runNow,
             boolean cancelled, String lastResult, Instant lastExecutedAt, int currentIteration) {
        this.id = id;
        this.prompt = prompt;
        this.intervalMinutes = intervalMinutes;
        this.cron = cron;
        this.createdAt = createdAt;
        this.autoInterval = autoInterval;
        this.enabled = enabled;
        this.runNow = runNow;
        this.cancelled = cancelled;
        this.lastResult = lastResult;
        this.lastExecutedAt = lastExecutedAt;
        this.currentIteration = currentIteration;
    }

    /**
     * 便捷构造（固定间隔 + cron + runNow）
     */
    public LoopTask(String prompt, int intervalMinutes, String cron, boolean runNow) {
        this.id = UUID.randomUUID().toString().substring(0, 8);
        this.prompt = prompt;
        // 间隔收敛在 [MIN_INTERVAL, MAX_INTERVAL]，不存在 0 间隔（cron 模式忽略该值）
        this.intervalMinutes = Math.max(MIN_INTERVAL, Math.min(MAX_INTERVAL, intervalMinutes));
        this.cron = cron;
        this.createdAt = Instant.now();
        this.autoInterval = false;
        this.runNow = runNow;
        this.currentIteration = 0;
        this.enabled = true;
    }

    /**
     * 基于当前任务复制出一份更新后的任务定义，保留任务身份和运行时状态。
     */
    public LoopTask copyWithUpdate(String prompt, int intervalMinutes, String cron,
                                   Boolean runNow) {
        LoopTask task = new LoopTask(
                this.id,
                prompt,
                Math.max(MIN_INTERVAL, Math.min(MAX_INTERVAL, intervalMinutes)),
                cron,
                this.createdAt,
                this.autoInterval,
                this.enabled,
                runNow != null ? runNow : this.runNow,
                this.cancelled,
                this.lastResult,
                this.lastExecutedAt,
                this.currentIteration
        );
        task.running = false;
        task.oneShot = this.oneShot;
        task.automation = this.automation;
        return task;
    }

    /**
     * 是否为 cron 模式
     */
    public boolean isCronMode() {
        return cron != null && !cron.isEmpty();
    }

    /**
     * 是否仍处于活跃状态（未取消且未过期）
     */
    public boolean isActive() {
        return !cancelled;
    }

    /**
     * 尝试标记为运行中（CAS 语义）
     *
     * @return true 表示成功获取执行权
     */
    public synchronized boolean tryStart() {
        if (running) {
            return false;
        }
        running = true;
        return true;
    }

    /**
     * 标记执行结束
     */
    public synchronized void finish() {
        running = false;
    }

    /**
     * 获取 IJobManager 注册用的任务名称
     */
    public String getJobName() {
        return "loop-" + id;
    }

    /**
     * 取消任务
     */
    public void cancel() {
        cancelled = true;
    }

    /**
     * 更新最近一次执行信息
     */
    public void updateLastExecution(String result) {
        this.lastResult = result;
        this.lastExecutedAt = Instant.now();
    }

    /**
     * 递增迭代计数
     *
     * @return 递增后的当前迭代次数
     */
    public int incrementIteration() {
        return ++currentIteration;
    }

    /** 更新提示词。 */
    public void setPrompt(String prompt) { this.prompt = prompt; }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public AutomationMeta getAutomation() { return automation; }

    public void setAutomation(AutomationMeta automation) { this.automation = automation; }

    /** 是否为自动任务（带 automation 覆盖层）。 */
    public boolean isAutomation() { return automation != null; }

    public void setOneShot(boolean oneShot) { this.oneShot = oneShot; }

    public void setLastResult(String lastResult) { this.lastResult = lastResult; }

    public void setCurrentIteration(int currentIteration) { this.currentIteration = currentIteration; }

    /**
     * 序列化为 ONode
     */
    public ONode toONode() {
        ONode node = new ONode();
        // 核心调度字段
        node.set("id", id);
        node.set("prompt", prompt);
        node.set("intervalMinutes", intervalMinutes);
        if (cron != null) {
            node.set("cron", cron);
        }
        node.set("createdAt", createdAt.toString());
        node.set("autoInterval", autoInterval);
        node.set("cancelled", cancelled);
        node.set("enabled", enabled);
        // running 是瞬态运行时锁，不持久化 — restore() 时兜底解锁

        // 运行时状态
        if (lastResult != null) {
            node.set("lastResult", lastResult);
        }
        if (lastExecutedAt != null) {
            node.set("lastExecutedAt", lastExecutedAt.toString());
        }
        node.set("currentIteration", currentIteration);

        if (runNow) node.set("runNow", true);

        // ★ 一次性任务标记（缺省 false，旧 JSON 兼容）
        if (oneShot) node.set("oneShot", true);

        // ★ 自动任务覆盖层（普通会话循环任务不写该键）
        if (automation != null) {
            node.set("automation", automation.toONode());
        }

        return node;
    }

    /**
     * 从 ONode 反序列化（向后兼容：缺失字段给默认值）
     */
    public static LoopTask fromONode(ONode node) {
        String lastResultVal = node.getOrNull("lastResult") != null
                ? node.get("lastResult").getString()
                : null;
        Instant lastExecutedAtVal = node.getOrNull("lastExecutedAt") != null
                ? Instant.parse(node.get("lastExecutedAt").getString())
                : null;
        String cronVal = node.getOrNull("cron") != null
                ? node.get("cron").getString()
                : null;

        // 向后兼容：缺失时给默认值
        boolean enabledVal = node.getOrNull("enabled") != null
                ? node.get("enabled").getBoolean() : true;

        int currentIterationVal = node.getOrNull("currentIteration") != null
                ? node.get("currentIteration").getInt() : 0;

        boolean runNowVal = node.getOrNull("runNow") != null
                && node.get("runNow").getBoolean();

        // 读取一次性标记（旧 JSON 缺省 false）
        boolean oneShotVal = node.getOrNull("oneShot") != null
                && node.get("oneShot").getBoolean();

        LoopTask task = new LoopTask(
                node.get("id").getString(),
                node.get("prompt").getString(),
                node.get("intervalMinutes").getInt(),
                cronVal,
                Instant.parse(node.get("createdAt").getString()),
                node.get("autoInterval").getBoolean(),
                enabledVal,
                runNowVal,
                node.getOrNull("cancelled") != null
                        ? node.get("cancelled").getBoolean() : false,
                lastResultVal,
                lastExecutedAtVal,
                currentIterationVal
        );

        task.oneShot = oneShotVal;

        // 恢复自动任务覆盖层（缺失 = 普通会话循环任务）
        if (node.getOrNull("automation") != null) {
            task.automation = AutomationMeta.fromONode(node.get("automation"));
        }

        // running 是瞬态锁，反序列化后始终为 false
        return task;
    }
}
