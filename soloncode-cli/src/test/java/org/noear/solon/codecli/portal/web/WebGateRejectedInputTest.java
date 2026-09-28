package org.noear.solon.codecli.portal.web;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.codecli.portal.web.event.WebEvent;
import org.noear.solon.codecli.portal.web.event.WebEventNames;
import org.noear.solon.codecli.workspace.WorkspaceContext;
import reactor.core.Disposables;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class WebGateRejectedInputTest {
    @Test
    void rejectedInputDoesNotFinishAnExistingStream() throws Exception {
        List<String> events = new ArrayList<>();
        WebGate gate = recordingGate(events);
        AgentSession session = InMemoryAgentSession.of();
        AtomicBoolean done = new AtomicBoolean(false);
        session.attrs().put("streamDoneSent", done);
        session.attrs().put("disposable", Disposables.composite());

        guardedDone(gate, session);

        assertTrue(events.isEmpty());
        assertFalse(done.get());
    }

    @Test
    void failedInputWithoutStreamStillFinishesItsTurn() throws Exception {
        List<String> events = new ArrayList<>();
        WebGate gate = recordingGate(events);
        AgentSession session = InMemoryAgentSession.of();
        session.attrs().put("streamDoneSent", new AtomicBoolean(true));
        session.attrs().put("session.input.admitting", Boolean.TRUE);

        guardedDone(gate, session);

        assertEquals(java.util.Collections.singletonList(WebEventNames.SYSTEM_DONE), events);
    }

    private static WebGate recordingGate(List<String> events) {
        return new WebGate(null) {
            @Override
            public void emitToClient(WorkspaceContext context, String sessionId, WebEvent<?> event) {
                events.add(event.getEvent());
            }
        };
    }

    private static void guardedDone(WebGate gate, AgentSession session) throws Exception {
        Method method = WebGate.class.getDeclaredMethod("emitDoneGuarded", WorkspaceContext.class, AgentSession.class);
        method.setAccessible(true);
        method.invoke(gate, null, session);
    }
}
