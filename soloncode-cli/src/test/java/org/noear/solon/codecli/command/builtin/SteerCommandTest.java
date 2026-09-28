package org.noear.solon.codecli.command.builtin;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.codecli.command.WebCommandContext;
import org.noear.solon.codecli.portal.web.SessionQueue;
import org.noear.solon.codecli.portal.web.SessionQueueItem;
import org.noear.solon.codecli.portal.web.SteerInterceptor;
import reactor.core.Disposable;
import reactor.core.Disposables;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SteerCommandTest {
    private final SteerCommand command = new SteerCommand();

    private WebCommandContext context(AgentSession session, AtomicInteger starts) {
        return new WebCommandContext(session, "Feishu", "user-1", "chat-1", "message-1",
                null, "/steer 补充要求", "steer", Collections.singletonList("补充要求"),
                (prompt, model) -> starts.incrementAndGet());
    }

    @Test
    void admittingBeforeRunIdQueuesInsteadOfStartingAnotherTask() {
        AgentSession session = InMemoryAgentSession.of();
        session.attrs().put("session.input.admitting", Boolean.TRUE);
        AtomicInteger starts = new AtomicInteger();
        WebCommandContext ctx = context(session, starts);

        command.execute(ctx);

        assertEquals(0, starts.get());
        assertEquals(1, SessionQueue.pendingSize(session));
        SessionQueueItem item = SessionQueue.snapshot(session).get(0);
        assertEquals("补充要求", item.getText());
        assertEquals("Feishu", item.getSource());
        assertEquals("user-1", item.getSourceUserId());
        assertEquals("chat-1", item.getReplyTarget());
        assertEquals("message-1", item.getMessageId());
        assertTrue(ctx.getOutputBuffer().toString().contains("已改为排队"));
    }

    @Test
    void activeSlotBeforeRunIdQueuesInsteadOfStartingAnotherTask() {
        AgentSession session = InMemoryAgentSession.of();
        Disposable.Composite slot = Disposables.composite();
        session.attrs().put("disposable", slot);
        AtomicInteger starts = new AtomicInteger();

        command.execute(context(session, starts));

        assertEquals(0, starts.get());
        assertEquals(1, SessionQueue.pendingSize(session));
        assertNull(session.attrs().get(SteerInterceptor.ATTR_ACTIVE_RUN_ID));
        slot.dispose();
    }

    @Test
    void idleSessionStillStartsTaskDirectly() {
        AgentSession session = InMemoryAgentSession.of();
        Disposable.Composite slot = Disposables.composite();
        slot.dispose();
        session.attrs().put("disposable", slot);
        AtomicInteger starts = new AtomicInteger();

        command.execute(context(session, starts));

        assertEquals(1, starts.get());
        assertEquals(0, SessionQueue.pendingSize(session));
    }

    @Test
    void fullQueueDoesNotStartTaskOrClaimInputWasQueued() {
        AgentSession session = InMemoryAgentSession.of();
        session.attrs().put("session.input.admitting", Boolean.TRUE);
        for (int i = 0; i < SessionQueue.MAX_QUEUE_SIZE; i++) {
            assertTrue(SessionQueue.enqueue(session, "task-" + i, "Feishu") > 0);
        }
        AtomicInteger starts = new AtomicInteger();
        WebCommandContext ctx = context(session, starts);

        command.execute(ctx);

        assertEquals(0, starts.get());
        assertEquals(SessionQueue.MAX_QUEUE_SIZE, SessionQueue.pendingSize(session));
        assertTrue(ctx.getOutputBuffer().toString().contains("排队失败"));
    }
}
