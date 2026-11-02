package org.noear.solon.codecli.api.web;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.codecli.api.web.event.WebEvent;
import org.noear.solon.codecli.api.web.event.WebEventNames;
import org.noear.solon.codecli.workspace.WorkspaceContext;
import org.noear.solon.codecli.workspace.WorkspaceMessageGateway;
import org.noear.solon.codecli.workspace.WorkspaceRuntimePort;
import reactor.core.Disposables;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class WebGateRejectedInputTest {
    @Test
    void webGateExposesTheWorkspaceRuntimePorts() {
        List<String> events = new ArrayList<>();
        WebGate gate = recordingGate(events);
        WorkspaceMessageGateway gateway = gate;

        assertTrue(gate instanceof WorkspaceRuntimePort);
        assertTrue(gateway instanceof WorkspaceRuntimePort);

        gateway.emitErrorAndDone(null, "session-1", "expired");

        assertEquals(java.util.Arrays.asList(WebEventNames.SYSTEM_ERROR, WebEventNames.SYSTEM_DONE), events);
    }

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

    @Test
    void admittingInputAloneIsNotAnActiveStream() {
        AgentSession session = InMemoryAgentSession.of();
        session.attrs().put(WebGate.ATTR_INPUT_ADMITTING, Boolean.TRUE);
        assertTrue(WebGate.isInputAdmitting(session));
        assertFalse(WebGate.hasActiveStream(session));
        session.attrs().put("disposable", Disposables.composite());
        assertTrue(WebGate.hasActiveStream(session));
    }

    @Test
    void disposedStreamHandleIsRemovedFromSharedSlot() throws Exception {
        AgentSession session = InMemoryAgentSession.of();
        reactor.core.Disposable.Composite composite = Disposables.composite();
        reactor.core.Disposable child = Disposables.disposed();
        composite.add(child);
        session.attrs().put("disposable", composite);

        child.dispose();
        Method method = WebGate.class.getDeclaredMethod(
                "releaseStreamSlot", AgentSession.class, reactor.core.Disposable.Composite.class,
                reactor.core.Disposable.class);
        method.setAccessible(true);
        method.invoke(null, session, composite, child);

        assertNull(session.attrs().get("disposable"));
        Method busyMethod = WebGate.class.getDeclaredMethod("isSessionBusy", AgentSession.class);
        busyMethod.setAccessible(true);
        assertFalse((Boolean) busyMethod.invoke(new WebGate(null), session));
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
