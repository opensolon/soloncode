package org.noear.solon.codecli.command.builtin;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.harness.command.builtin.SteerCommand;
import org.noear.solon.codecli.command.WebCommandContext;
import org.noear.solon.ai.harness.session.steer.SteerInterceptor;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * /steer 全链路测试：harness SteerCommand 直连领域类（会话上下文即状态）。
 * 纯投递语义：任何失败态（未运行、邮箱满、轮次切换）都如实告知，不代为排队/直发。
 * <p>
 * 「未启动任务」的判据随命令体系改造从「回调未被调用」变为「未声明任务意图」——
 * 命令不再持有宿主回调，只声明意图。
 */
class SteerCommandTest {
    private final SteerCommand command = new SteerCommand();

    private WebCommandContext context(AgentSession session) {
        return new WebCommandContext(session, "Feishu", "user-1", "chat-1", "message-1",
                "/steer 补充要求", "steer", Collections.singletonList("补充要求"));
    }

    @Test
    void steeredAgainstActiveRunDeliversToBox() {
        AgentSession session = InMemoryAgentSession.of();
        session.attrs().put(SteerInterceptor.ATTR_ACTIVE_RUN_ID, "run-1");

        WebCommandContext ctx = context(session);
        command.execute(null, ctx);

        assertNull(ctx.getAgentTaskIntent(), "插话是投递，不是新一轮任务");
        assertEquals(1, SteerInterceptor.steerBox(session).size());
        assertEquals("补充要求", SteerInterceptor.steerBox(session).peek().getText());
        assertTrue(ctx.getCommandOutput().contains("已插话"));
    }

    @Test
    void idleSessionDoesNotStartTaskOrQueue() {
        AgentSession session = InMemoryAgentSession.of();

        WebCommandContext ctx = context(session);
        command.execute(null, ctx);

        assertNull(ctx.getAgentTaskIntent(), "空闲态不代为启动任务");
        assertNull(SteerInterceptor.steerBox(session), "空闲态不代为排队");
        assertTrue(ctx.getCommandOutput().contains("当前无正在执行的任务可插话"));
    }

    @Test
    void admittingSessionDoesNotStartTaskOrQueue() {
        AgentSession session = InMemoryAgentSession.of();
        session.attrs().put("session.input.admitting", Boolean.TRUE);

        WebCommandContext ctx = context(session);
        command.execute(null, ctx);

        assertNull(ctx.getAgentTaskIntent());
        assertNull(SteerInterceptor.steerBox(session));
        assertTrue(ctx.getCommandOutput().contains("当前无正在执行的任务可插话"));
    }

    @Test
    void fullBoxReportsLimitWithoutQueueing() {
        AgentSession session = InMemoryAgentSession.of();
        session.attrs().put(SteerInterceptor.ATTR_ACTIVE_RUN_ID, "run-1");
        for (int i = 0; i < SteerInterceptor.MAX_BOX_SIZE; i++) {
            assertTrue(SteerInterceptor.steer(session, "run-1", "seed-" + i, "种子" + i, "Feishu", null).isSteered());
        }

        WebCommandContext ctx = context(session);
        command.execute(null, ctx);

        assertNull(ctx.getAgentTaskIntent());
        assertEquals(SteerInterceptor.MAX_BOX_SIZE, SteerInterceptor.steerBox(session).size());
        assertTrue(ctx.getCommandOutput().contains("插话邮箱已满（上限 5 条），可用 /queue 排队"));
    }
}
