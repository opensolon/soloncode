package org.noear.solon.codecli.session;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import reactor.core.Disposable;
import reactor.core.Disposables;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionActivityTest {
    @Test
    void admittingInputIsNotAnActiveStream() {
        AgentSession session = InMemoryAgentSession.of();
        session.attrs().put(SessionActivity.ATTR_INPUT_ADMITTING, Boolean.TRUE);

        assertTrue(SessionActivity.isInputAdmitting(session));
        assertFalse(SessionActivity.hasActiveStream(session));
        assertTrue(SessionActivity.isBusy(session));
    }

    @Test
    void liveStreamMakesSessionBusy() {
        AgentSession session = InMemoryAgentSession.of();
        Disposable.Composite composite = Disposables.composite();
        session.attrs().put("disposable", composite);

        assertTrue(SessionActivity.hasActiveStream(session));
        assertTrue(SessionActivity.isBusy(session));

        composite.dispose();
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
