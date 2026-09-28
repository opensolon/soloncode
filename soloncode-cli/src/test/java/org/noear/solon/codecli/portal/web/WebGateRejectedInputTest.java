package org.noear.solon.codecli.portal.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.codecli.portal.web.event.WebEvent;
import org.noear.solon.codecli.portal.web.event.WebEventNames;
import org.noear.solon.codecli.workspace.WorkspaceContext;
import reactor.core.Disposables;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
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

    @Test
    void admittingInputAloneIsNotAnActiveStream() throws Exception {
        AgentSession session = InMemoryAgentSession.of();
        Method method = WebGate.class.getDeclaredMethod("hasActiveStream", AgentSession.class);
        method.setAccessible(true);
        session.attrs().put("session.input.admitting", Boolean.TRUE);
        assertFalse((Boolean) method.invoke(null, session));
        session.attrs().put("disposable", Disposables.composite());
        assertTrue((Boolean) method.invoke(null, session));
    }

    @Test
    void failedSteerQueueSaveKeepsMailboxAndDoesNotClaimDropped(@TempDir Path dir) {
        List<String> events = new ArrayList<>();
        WebGate gate = recordingGate(events);
        AgentSession session = InMemoryAgentSession.of();
        SessionQueue.bindStorage(session, dir);
        for (int i = 0; i < SessionQueue.MAX_QUEUE_SIZE; i++) {
            assertTrue(SessionQueue.enqueue(session, "task " + i, "WEB") > 0);
        }
        Queue<SteerMessage> box = new ConcurrentLinkedQueue<>();
        box.add(new SteerMessage("steer-1", "do not lose me"));
        gate.handleDroppedSteers(null, session, box, "run-1");

        assertEquals(SessionQueue.MAX_QUEUE_SIZE, SessionQueue.pendingSize(session));
        assertEquals("steer-1", SteerInterceptor.steerBox(session).peek().getId());
        assertTrue(events.contains(WebEventNames.SYSTEM_ERROR));
        assertFalse(events.contains(WebEventNames.SYSTEM_STEER_DROPPED));

        assertTrue(SessionQueue.cancelItem(session, SessionQueue.snapshot(session).get(0).getId()));
        Queue<SteerMessage> retry = SteerInterceptor.steerBox(session);
        session.attrs().remove(SteerInterceptor.ATTR_STEER_BOX);
        gate.handleDroppedSteers(null, session, retry, "run-1");
        assertTrue(events.contains(WebEventNames.SYSTEM_STEER_DROPPED));
        assertEquals("do not lose me", SessionQueue.snapshot(session).get(SessionQueue.pendingSize(session) - 1).getText());
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
