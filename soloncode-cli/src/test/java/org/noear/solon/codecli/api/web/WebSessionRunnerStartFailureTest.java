package org.noear.solon.codecli.api.web;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.harness.RunTicket;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.spi.RunDispatch;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 启动失败后的续发判定：普通输入续发积压，队列派发的一条流不立即重试。
 *
 * <p>回归：旧写法用 {@code ticket == null} 判定「非队列输入」，而 {@link RunTicket#begin}
 * 从不返回 null，普通输入启动失败后排队消息不再自动续发。</p>
 */
class WebSessionRunnerStartFailureTest {

    @Test
    void plainInputStartFailureDrainsBacklog() {
        AgentSession session = InMemoryAgentSession.of("plain");
        RunTicket ticket = RunTicket.begin(session);

        assertNotNull(ticket);
        assertNull(ticket.dispatch());
        boolean rolledBack = ticket.settle(false);

        assertFalse(rolledBack);
        assertTrue(WebSessionRunner.shouldDrainAfterStartFailure(ticket, rolledBack));
    }

    @Test
    void queueDispatchedStartFailureDoesNotRetryImmediately() {
        AgentSession session = InMemoryAgentSession.of("queued");
        FakeDispatch dispatch = new FakeDispatch(session);
        RunDispatch.bind(session, dispatch);

        RunTicket ticket = RunTicket.begin(session);
        assertSame(dispatch, ticket.dispatch());
        boolean rolledBack = ticket.settle(false);

        assertTrue(rolledBack);
        assertEquals(1, dispatch.rollbacks.get());
        assertFalse(WebSessionRunner.shouldDrainAfterStartFailure(ticket, rolledBack));
    }

    @Test
    void fallbackRollbackOfCurrentDispatchDoesNotRetryImmediately() {
        AgentSession session = InMemoryAgentSession.of("fallback");
        // 凭据领取时还没有派发；之后才登记（兜底路径会收口「会话当前派发」）
        RunTicket ticket = RunTicket.begin(session);
        FakeDispatch dispatch = new FakeDispatch(session);
        RunDispatch.bind(session, dispatch);

        assertNull(ticket.dispatch());
        boolean rolledBack = ticket.settle(false);

        assertTrue(rolledBack);
        assertFalse(WebSessionRunner.shouldDrainAfterStartFailure(ticket, rolledBack));
    }

    @Test
    void missingTicketStillDrains() {
        assertTrue(WebSessionRunner.shouldDrainAfterStartFailure(null, false));
        assertFalse(WebSessionRunner.shouldDrainAfterStartFailure(null, true));
    }

    private static final class FakeDispatch implements RunDispatch {
        private final AgentSession session;
        private final AtomicInteger rollbacks = new AtomicInteger();
        private boolean settled;

        FakeDispatch(AgentSession session) {
            this.session = session;
        }

        @Override
        public InputOrigin getOrigin() {
            return InputOrigin.NONE;
        }

        @Override
        public boolean isOnDispatchThread() {
            return false;
        }

        @Override
        public boolean isCurrent() {
            return RunDispatch.current(session) == this;
        }

        @Override
        public boolean settle(boolean started) {
            if (settled) return false;
            settled = true;
            if (!started) rollbacks.incrementAndGet();
            RunDispatch.unbind(session, this);
            return true;
        }
    }
}
