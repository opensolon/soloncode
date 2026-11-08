package org.noear.solon.codecli.api.desktop;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.extension.queue.QueueExtension;
import org.noear.solon.net.websocket.WebSocket;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 桌面端接入 HarnessHost 后的包序契约（真实引擎 + 真实骨架，只替换 WebSocket）。
 *
 * <p>前端协议的底线：每轮恰好一个终态（done / error / hitl），流内消息带递增序号（可断线回放）；
 * 会话级回执（拒收、过期审批卡、停止）直接发给发起请求的连接。</p>
 */
class DesktopHostFlowContractTest {
    @TempDir
    Path tmp;

    private final Map<String, AgentSession> sessions = new ConcurrentHashMap<>();
    private final List<ONode> sent = new CopyOnWriteArrayList<>();
    private HarnessEngine engine;

    @AfterEach
    void shutdown() {
        if (engine != null) {
            engine.shutdown();
        }
    }

    private WsGate gate() throws Exception {
        ChatConfig model = new ChatConfig();
        model.setName("fake");
        model.setModel("fake-model");
        model.setApiUrl("http://localhost:1/v1"); // 连接即失败：ReAct 把错误收成回答文本，本轮很快结束
        model.setApiKey("test");
        engine = HarnessEngine.of(Files.createDirectories(tmp.resolve("ws")).toString(), tmp.resolve("home").toString())
                .sessionProvider((id, create) -> create ? sessions.computeIfAbsent(id, InMemoryAgentSession::of) : sessions.get(id))
                .modelAdd(model)
                .defaultModel("fake")
                .extensionAdd(QueueExtension.builder().build())
                .build();
        return new WsGate(engine, null, null);
    }

    private WebSocket socket(String sessionId) {
        WebSocket socket = mock(WebSocket.class);
        when(socket.paramOrDefault(eq("sessionId"), anyString())).thenReturn(sessionId);
        when(socket.id()).thenReturn("sock-" + sessionId);
        doAnswer(inv -> {
            sent.add(ONode.ofJson((String) inv.getArgument(0)));
            return null;
        }).when(socket).send(anyString());
        return socket;
    }

    private List<String> types() {
        List<String> types = new ArrayList<>();
        for (ONode n : sent) {
            types.add(n.get("type").getString());
        }
        return types;
    }

    private long count(String type) {
        return types().stream().filter(type::equals).count();
    }

    private static void await(Supplier<Boolean> cond, String message) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (cond.get()) {
                return;
            }
            Thread.sleep(20);
        }
        fail(message);
    }

    @Test
    void runEndsWithExactlyOneSequencedTerminal() throws Exception {
        WsGate gate = gate();
        WebSocket socket = socket("101");

        gate.onMessage(socket, "{\"input\":\"hello\"}");

        await(() -> count("done") + count("error") >= 1, "本轮应以终态收尾：" + types());
        await(() -> engine.isIdle(engine.getSessionOrNull("101")), "本轮结束后会话应空闲");
        Thread.sleep(200);
        assertEquals(1, count("done") + count("error"), "终态恰好一次：" + types());
        ONode terminal = sent.get(sent.size() - 1);
        assertTrue(terminal.get("sequence").getLong() > 0, "流内终态经流中转站投递（带序号、可回放）：" + terminal.toJson());
        assertEquals("101", terminal.get("sessionId").getString());
    }

    @Test
    void interruptWithoutRunSendsReasonAndDoneOnce() throws Exception {
        WsGate gate = gate();
        WebSocket socket = socket("102");

        gate.onMessage(socket, "{\"input\":\"[(sec)interrupt]\"}");

        List<String> types = types();
        assertEquals(2, types.size(), types.toString());
        assertEquals("reason", types.get(0));
        assertEquals("done", types.get(1));
    }

    @Test
    void staleHitlDecisionIsReportedToTheRequestingSocket() throws Exception {
        WsGate gate = gate();
        WebSocket socket = socket("103");
        engine.getSession("103");

        gate.onMessage(socket, "{\"type\":\"hitl_action\",\"sessionId\":\"103\",\"action\":\"approve\",\"callId\":\"nope\"}");

        assertEquals(1, sent.size(), types().toString());
        assertEquals("error", sent.get(0).get("type").getString());
        assertEquals("No pending HITL task", sent.get(0).get("text").getString());
    }

    @Test
    void selectionIsWrittenOnlyAfterAdmission() throws Exception {
        WsGate gate = gate();
        WebSocket socket = socket("104");

        gate.onMessage(socket, "{\"input\":\"hello\",\"mode\":\"plan\",\"reasoningEffort\":\"high\"}");

        await(() -> count("done") + count("error") >= 1, "本轮应以终态收尾：" + types());
        AgentSession session = engine.getSessionOrNull("104");
        assertEquals("plan", session.attrs().get(DesktopRunSink.SESSION_ATTR_RUN_MODE), "受理后写入运行模式");
    }
}
