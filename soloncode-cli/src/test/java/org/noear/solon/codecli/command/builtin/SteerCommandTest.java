package org.noear.solon.codecli.command.builtin;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.codecli.command.WebCommandContext;
import org.noear.solon.ai.harness.session.steer.SteerInterceptor;
import org.noear.solon.codecli.workspace.SteerPortImpl;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * /steer 全链路测试：harness SteerCommand + CLI 真实端口适配器。
 * 纯投递语义：任何失败态（未运行、邮箱满、轮次切换）都如实告知，不代为排队/直发。
 */
class SteerCommandTest {
    private final org.noear.solon.ai.harness.command.builtin.SteerCommand command =
            new org.noear.solon.ai.harness.command.builtin.SteerCommand(new SteerPortImpl());

    private WebCommandContext context(AgentSession session, AtomicInteger starts) {
        return new WebCommandContext(session, "Feishu", "user-1", "chat-1", "message-1",
                "/steer 补充要求", "steer", Collections.singletonList("补充要求"),
                (prompt, model) -> starts.incrementAndGet());
    }

    @Test
    void steeredAgainstActiveRunDeliversToBox() {
        AgentSession session = InMemoryAgentSession.of();
        session.attrs().put(SteerInterceptor.ATTR_ACTIVE_RUN_ID, "run-1");
        AtomicInteger starts = new AtomicInteger();

        WebCommandContext ctx = context(session, starts);
        command.execute(null, ctx);

        assertEquals(0, starts.get());
        assertEquals(1, SteerInterceptor.steerBox(session).size());
        assertEquals("补充要求", SteerInterceptor.steerBox(session).peek().getText());
        assertTrue(ctx.getOutputBuffer().toString().contains("已插话"));
    }

    @Test
    void idleSessionDoesNotStartTaskOrQueue() {
        AgentSession session = InMemoryAgentSession.of();
        AtomicInteger starts = new AtomicInteger();

        WebCommandContext ctx = context(session, starts);
        command.execute(null, ctx);

        assertEquals(0, starts.get());
        assertNull(SteerInterceptor.steerBox(session));
        assertTrue(ctx.getOutputBuffer().toString().contains("当前无正在执行的任务可插话"));
    }

    @Test
    void admittingSessionDoesNotStartTaskOrQueue() {
        AgentSession session = InMemoryAgentSession.of();
        session.attrs().put("session.input.admitting", Boolean.TRUE);
        AtomicInteger starts = new AtomicInteger();

        WebCommandContext ctx = context(session, starts);
        command.execute(null, ctx);

        assertEquals(0, starts.get());
        assertNull(SteerInterceptor.steerBox(session));
        assertTrue(ctx.getOutputBuffer().toString().contains("当前无正在执行的任务可插话"));
    }

    @Test
    void fullBoxReportsLimitWithoutQueueing() {
        AgentSession session = InMemoryAgentSession.of();
        session.attrs().put(SteerInterceptor.ATTR_ACTIVE_RUN_ID, "run-1");
        for (int i = 0; i < SteerInterceptor.MAX_BOX_SIZE; i++) {
            assertTrue(SteerInterceptor.steer(session, "run-1", "seed-" + i, "种子" + i, "Feishu", null).isSteered());
        }
        AtomicInteger starts = new AtomicInteger();

        WebCommandContext ctx = context(session, starts);
        command.execute(null, ctx);

        assertEquals(0, starts.get());
        assertEquals(SteerInterceptor.MAX_BOX_SIZE, SteerInterceptor.steerBox(session).size());
        assertTrue(ctx.getOutputBuffer().toString().contains("插话邮箱已满（上限 5 条），可用 /queue 排队"));
    }
}
