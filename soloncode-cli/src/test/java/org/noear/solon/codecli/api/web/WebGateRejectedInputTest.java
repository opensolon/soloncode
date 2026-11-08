package org.noear.solon.codecli.api.web;

import org.noear.solon.ai.harness.session.HarnessAttrs;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.session.SessionRuns;
import org.noear.solon.codecli.api.web.event.WebEvent;
import org.noear.solon.codecli.api.web.event.WebEventNames;
import org.noear.solon.codecli.session.SessionActivity;
import org.noear.solon.codecli.workspace.WorkspaceContext;
import org.noear.solon.codecli.workspace.WorkspaceMessageGateway;
import org.noear.solon.codecli.workspace.WorkspaceRuntimePort;

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
        // 模拟一条在跑的流：句柄由框架登记表持有（不再写 attrs 字符串 key）
        SessionRuns.attach(session, newHandle());

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
        session.attrs().put(HarnessAttrs.INPUT_ADMITTING, Boolean.TRUE);

        guardedDone(gate, session);

        assertEquals(java.util.Collections.singletonList(WebEventNames.SYSTEM_DONE), events);
    }

    @Test
    void admittingInputAloneIsNotAnActiveStream() {
        AgentSession session = InMemoryAgentSession.of();
        session.attrs().put(HarnessAttrs.INPUT_ADMITTING, Boolean.TRUE);
        assertTrue(SessionActivity.isInputAdmitting(session));
        assertFalse(SessionActivity.hasActiveStream(session));

        SessionRuns.attach(session, newHandle());
        assertTrue(SessionActivity.hasActiveStream(session));
    }

    @Test
    void detachedHandleIsNoLongerAnActiveStream() throws Exception {
        AgentSession session = InMemoryAgentSession.of();
        reactor.core.Disposable handle = newHandle();
        SessionRuns.attach(session, handle);
        assertTrue(SessionActivity.hasActiveStream(session));

        SessionRuns.detach(session, handle);

        assertFalse(SessionActivity.hasActiveStream(session));
        assertFalse(SessionRuns.isBusy(session));
        // 会话 attrs 不再承载任何句柄
        assertTrue(session.attrs().keySet().stream().noneMatch(k -> k.contains("disposable")));
    }

    /** 一个可取消且 isDisposed 可信的句柄（与 ReActRequest 内的实现同构）。 */
    private static reactor.core.Disposable newHandle() {
        return new reactor.core.Disposable() {
            private boolean disposed;

            @Override
            public void dispose() {
                disposed = true;
            }

            @Override
            public boolean isDisposed() {
                return disposed;
            }
        };
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
