package org.noear.solon.codecli.loop;

import org.junit.jupiter.api.Test;
import org.noear.solon.codecli.config.AgentSettings;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * loop 执行通道内联后的契约：无认领就是「无认领」，绝不假装成功。
 *
 * <p>拆桥之前，loop 没有执行器时会落到 goal 的缺省 handler 同步执行并返回文本；
 * 若那条路也断了，结果就是 {@code null} → 调用方记 {@code "ok"} → 定时任务
 * 看起来一直在跑、实际什么也没做，且没有任何日志。本测试锁住这个回归。</p>
 */
class LoopSchedulerExecutorTest {

    private static LoopScheduler scheduler() {
        // engine 为 null：本测试只覆盖「投递」语义，不触碰会话快照判定
        return new LoopScheduler(null, new AgentSettings());
    }

    @Test
    void executeReturnsNullWhenNoExecutorRegistered() {
        LoopScheduler scheduler = scheduler();

        assertFalse(scheduler.hasExecutor());
        assertNull(scheduler.execute("cli", "prompt"),
                "无执行器必须返回 null（调用方据此不记 ok、不计迭代），不得伪造成功");
    }

    @Test
    void firstClaimingExecutorWins() {
        LoopScheduler scheduler = scheduler();
        scheduler.addExecutor((sessionId, prompt) -> "web:" + prompt);
        scheduler.addExecutor((sessionId, prompt) -> "cli:" + prompt);

        assertEquals("web:prompt", scheduler.execute("web-1", "prompt"),
                "按注册顺序取首个认领者");
    }

    @Test
    void nonClaimingExecutorFallsThroughToNext() {
        LoopScheduler scheduler = scheduler();
        // 认领 web-* 之外返回 null（真实宿主即按 sessionId 前缀认领）
        scheduler.addExecutor((sessionId, prompt) ->
                sessionId.startsWith("web-") ? "web:" + prompt : null);
        scheduler.addExecutor((sessionId, prompt) -> "cli:" + prompt);

        assertEquals("cli:prompt", scheduler.execute("cli", "prompt"),
                "不认领时须继续尝试下一个执行器");
    }

    /**
     * 执行器签名不得再有 {@code agentName} 参数。
     *
     * <p>子代理归属是<b>会话属性</b>（{@code CTX_AGENT_SELECTED}，自动化任务创建时写入），
     * 执行器从 session 读即可。旧签名带该参数，但唯一调用点恒传 {@code null}——
     * 三个宿主里「拼 {@code @agentName} 前缀」的分支从未执行，是彻底的死代码。
     * 若有人把参数加回来，此测试直接报错，而不是让它再当一轮摆设。</p>
     */
    @Test
    void executorSignatureHasNoAgentNameParameter() throws Exception {
        Method handle = LoopScheduler.LoopExecutor.class.getMethod("handle", String.class, String.class);
        Parameter[] params = handle.getParameters();
        assertEquals(2, params.length, "执行器只接收 sessionId 与 prompt");
        assertEquals("sessionId", params[0].getName());
        assertEquals("prompt", params[1].getName());
    }

    /**
     * 无执行器认领时，不得把 {@code "ok"} 写进 lastResult、也不得计迭代。
     *
     * <p>这是拆桥时暴露的最隐蔽故障形态：旧实现把 {@code null} 结果当作「成功但无文本」，
     * 记成 {@code "ok"}——定时任务看起来一直在跑（lastResult 有值、时间戳在刷新），
     * 实际什么也没做，且不计迭代、不报错、不打日志。拆桥后 loop 不再落到其他域的缺省 handler，
     * 这条路径会真被走到，所以必须锁住。</p>
     */
    @Test
    void noExecutorMustNotRecordOk() {
        LoopScheduler scheduler = scheduler();
        LoopTask task = new LoopTask("prompt", 5);

        scheduler.doTrigger("cli", task, false);

        assertNull(task.getLastResult(),
                "无执行器认领时不得伪造成功（记 ok 会让故障完全静默）");
        assertEquals(0, task.getCurrentIteration(),
                "未真正执行的轮次不得计入迭代");
    }

    /** 有执行器认领时，结果原样记账（对照用例，确保上一条不是恒真断言）。 */
    @Test
    void claimingExecutorRecordsResultAndCountsIteration() {
        LoopScheduler scheduler = scheduler();
        scheduler.addExecutor((sessionId, prompt) -> "done:" + prompt);
        LoopTask task = new LoopTask("p", 5);

        scheduler.doTrigger("cli", task, false);

        assertEquals("done:p", task.getLastResult());
        assertEquals(1, task.getCurrentIteration());
    }

    /**
     * 注入屏障必须真实拦住一轮注入（经 doTrigger 验证真实行为，而非读内部字段）。
     *
     * <p>缺省恒开：非 CLI 宿主（web/desktop）不关心终端所有权，loop 照常执行；
     * CLI 将 gate 设为「主线程在读输入」后，gate 关闭时本轮不得执行。</p>
     */
    @Test
    void injectionGateDefaultsOpenAndBlocksRoundWhenClosed() {
        LoopScheduler scheduler = scheduler();
        scheduler.addExecutor((sessionId, prompt) -> "done:" + prompt);

        // 缺省恒开
        LoopTask openTask = new LoopTask("p", 5);
        scheduler.doTrigger("cli", openTask, false);
        assertEquals("done:p", openTask.getLastResult(), "缺省必须允许注入");

        // 终端被占（主线程跑非流命令）→ 拦住
        scheduler.setInjectionGate(() -> false);
        LoopTask blockedTask = new LoopTask("p", 5);
        scheduler.doTrigger("cli", blockedTask, false);
        assertNull(blockedTask.getLastResult(), "终端被占用时不得注入（CLI 主线程模型）");
        assertEquals(0, blockedTask.getCurrentIteration(), "被拦住的一轮不得计入迭代");
    }
}
