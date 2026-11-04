package org.noear.solon.codecli.session;

import org.noear.solon.ai.harness.session.HarnessAttrs;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.session.SessionRuns;
import reactor.core.Disposable;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionActivityTest {
    @Test
    void admittingInputIsNotAnActiveStream() {
        AgentSession session = InMemoryAgentSession.of();
        session.attrs().put(HarnessAttrs.INPUT_ADMITTING, Boolean.TRUE);

        assertTrue(SessionActivity.isInputAdmitting(session));
        assertFalse(SessionActivity.hasActiveStream(session));
        assertTrue(SessionActivity.isBusy(session));
    }

    @Test
    void liveStreamMakesSessionBusy() {
        AgentSession session = InMemoryAgentSession.of();
        // 运行句柄由框架登记表持有（ReActRequest 自行 attach），宿主不再写 attrs
        reactor.core.Disposable handle = new reactor.core.Disposable() {
            private boolean disposed;
            @Override public void dispose() { disposed = true; }
            @Override public boolean isDisposed() { return disposed; }
        };
        SessionRuns.attach(session, handle);

        assertTrue(SessionActivity.hasActiveStream(session));
        assertTrue(SessionActivity.isBusy(session));

        SessionRuns.cancelAll(session);
        assertFalse(SessionActivity.hasActiveStream(session));
        assertFalse(SessionActivity.isBusy(session));
    }

    @Test
    void nullSessionIsIdle() {
        assertFalse(SessionActivity.isInputAdmitting(null));
        assertFalse(SessionActivity.hasActiveStream(null));
        assertFalse(SessionActivity.isBusy(null));
    }
}
