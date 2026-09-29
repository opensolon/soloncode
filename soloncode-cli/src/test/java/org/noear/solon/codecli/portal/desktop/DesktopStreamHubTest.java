package org.noear.solon.codecli.portal.desktop;

import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.net.websocket.WebSocket;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopStreamHubTest {
    @Test
    void reconnectReplaysOnlyMessagesAfterAcknowledgedSequence() {
        DesktopStreamHub hub = new DesktopStreamHub();
        SocketRecorder original = new SocketRecorder();
        hub.begin("session-1", original.socket);

        hub.emit("session-1", new ONode().set("type", "text").set("text", "a").toJson());
        hub.emit("session-1", new ONode().set("type", "done").toJson());

        assertEquals(2, original.messages.size());
        assertEquals(1L, sequence(original.messages.get(0)));
        assertEquals(2L, sequence(original.messages.get(1)));

        SocketRecorder resumed = new SocketRecorder();
        assertTrue(hub.attach("session-1", resumed.socket, 1L));
        assertEquals(1, resumed.messages.size());
        assertEquals(2L, sequence(resumed.messages.get(0)));
    }

    @Test
    void reconnectFailsWhenRequestedSequenceHasFallenOutOfReplayBuffer() {
        DesktopStreamHub hub = new DesktopStreamHub();
        SocketRecorder original = new SocketRecorder();
        hub.begin("session-gap", original.socket);
        for (int i = 0; i < 4097; i++) {
            hub.emit("session-gap", new ONode().set("type", "text").set("text", Integer.toString(i)).toJson());
        }

        SocketRecorder resumed = new SocketRecorder();
        assertFalse(hub.attach("session-gap", resumed.socket, 0L));
        assertTrue(resumed.messages.isEmpty());
    }

    @Test
    void reconnectFailsForUnknownSession() {
        DesktopStreamHub hub = new DesktopStreamHub();
        assertFalse(hub.attach("missing", new SocketRecorder().socket, 0L));
    }

    private static long sequence(String json) {
        return ONode.ofJson(json).get("sequence").getLong();
    }

    private static final class SocketRecorder implements InvocationHandler {
        private final List<String> messages = new ArrayList<>();
        private final WebSocket socket = (WebSocket) Proxy.newProxyInstance(
                WebSocket.class.getClassLoader(),
                new Class<?>[]{WebSocket.class},
                this);

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if ("send".equals(name) && args != null && args.length == 1 && args[0] instanceof String) {
                messages.add((String) args[0]);
                return CompletableFuture.completedFuture(null);
            }
            if ("hashCode".equals(name)) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(name)) {
                return proxy == args[0];
            }
            if ("toString".equals(name)) {
                return "SocketRecorder";
            }
            Class<?> returnType = method.getReturnType();
            if (returnType == boolean.class) return true;
            if (returnType == long.class) return 0L;
            if (returnType == int.class) return 0;
            return null;
        }
    }
}
