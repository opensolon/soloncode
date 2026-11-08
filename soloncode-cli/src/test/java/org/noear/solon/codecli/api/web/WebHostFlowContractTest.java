package org.noear.solon.codecli.api.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.extension.queue.QueueExtension;
import org.noear.solon.ai.harness.extension.steer.SteerExtension;
import org.noear.solon.codecli.api.web.event.WebEvent;
import org.noear.solon.codecli.api.web.event.WebEventNames;
import org.noear.solon.codecli.channel.ChannelHub;
import org.noear.solon.codecli.session.SessionManager;
import org.noear.solon.codecli.workspace.WorkspaceContext;
import org.noear.solon.codecli.workspace.WorkspaceMeta;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Web 端接入 HarnessHost 后的包序契约（真实引擎 + 真实骨架，只替换 WebSocket 出口）。
 *
 * <p>前端协议的底线：每一轮以 {@code system.reset} 开始、以恰好一个 {@code system.done} 结束；
 * 没有开流的结局（拒收、过期审批、无任务回执）也必须给一个 done，否则输入面板永久停在流式态。</p>
 */
class WebHostFlowContractTest {
    @TempDir
    Path tmp;

    private final Map<String, AgentSession> sessions = new ConcurrentHashMap<>();
    private final List<String> events = new CopyOnWriteArrayList<>();
    private HarnessEngine engine;

    @AfterEach
    void shutdown() {
        if (engine != null) {
            engine.shutdown();
        }
    }

    private WorkspaceContext workspace() throws Exception {
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
                .extensionAdd(new SteerExtension())
                .build();

        WorkspaceContext ctx = mock(WorkspaceContext.class);
        WorkspaceMeta meta = new WorkspaceMeta();
        meta.setId("ws-flow");
        meta.setPath(tmp.resolve("ws").toString());
        when(ctx.getMeta()).thenReturn(meta);
        when(ctx.getEngine()).thenReturn(engine);
        SessionManager manager = mock(SessionManager.class);
        when(manager.getOwnedSession(anyString(), any())).thenAnswer(inv -> engine.getSession((String) inv.getArgument(0)));
        when(manager.getSessionOrNull(anyString())).thenAnswer(inv -> engine.getSessionOrNull((String) inv.getArgument(0)));
        when(ctx.getSessionManager()).thenReturn(manager);
        ChannelHub hub = mock(ChannelHub.class);
        when(hub.getImLinks()).thenReturn(Collections.emptyList());
        when(ctx.getChannelHub()).thenReturn(hub);
        when(ctx.getSessionPath(anyString())).thenAnswer(inv -> tmp.resolve("sessions").resolve((String) inv.getArgument(0)));
        return ctx;
    }

    private WebGate gate(WorkspaceContext ctx) {
        return new WebGate(TestWorkspaces.of(ctx)) {
            @Override
            public void emitToClient(WorkspaceContext context, String sessionId, WebEvent<?> event) {
                events.add(event.getEvent());
            }
        };
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

    private long count(String name) {
        return events.stream().filter(name::equals).count();
    }

    private List<String> compact() {
        // 只看协议骨架（去掉流内的具体事件）
        List<String> keep = new ArrayList<>();
        for (String e : events) {
            if (e.equals(WebEventNames.SYSTEM_RESET) || e.equals(WebEventNames.SYSTEM_DONE)
                    || e.equals(WebEventNames.SYSTEM_ERROR) || e.equals(WebEventNames.SYSTEM_COMMAND)
                    || e.equals(WebEventNames.SYSTEM_USER_INPUT)) {
                keep.add(e);
            }
        }
        return keep;
    }

    @Test
    void runStartsWithResetAndEndsWithExactlyOneDone() throws Exception {
        WorkspaceContext ctx = workspace();
        WebGate gate = gate(ctx);

        gate.onChatInput(ctx, "web-1", null, "hello", null, null, null, null, "web");

        await(() -> count(WebEventNames.SYSTEM_DONE) >= 1, "本轮应以 done 收尾");
        await(() -> engine.isIdle(engine.getSessionOrNull("web-1")), "本轮结束后会话应空闲");
        List<String> seq = compact();
        assertEquals(WebEventNames.SYSTEM_RESET, seq.get(0), "每轮必须先解封前端流门：" + seq);
        assertEquals(WebEventNames.SYSTEM_DONE, seq.get(seq.size() - 1), "done 必须在最后：" + seq);
        int trace = events.indexOf(WebEventNames.SYSTEM_TRACE);
        assertTrue(trace > 0 && trace < events.lastIndexOf(WebEventNames.SYSTEM_DONE), "trace 必须在本轮 done 之前：" + events);
        Thread.sleep(200);
        assertEquals(1, count(WebEventNames.SYSTEM_DONE), "done 恰好一次：" + events);
        assertFalse(seq.contains(WebEventNames.SYSTEM_USER_INPUT), "Web 前端已本地上屏，空闲受理不回放用户消息");
    }

    @Test
    void externalInputIsEchoedBeforeTheRun() throws Exception {
        WorkspaceContext ctx = workspace();
        WebGate gate = gate(ctx);

        assertTrue(gate.safeChatInput(ctx, "web-2", "from im", "feishu"));

        await(() -> count(WebEventNames.SYSTEM_DONE) >= 1, "本轮应以 done 收尾");
        List<String> seq = compact();
        assertEquals(WebEventNames.SYSTEM_USER_INPUT, seq.get(0), "IM / 外部输入受理后先回放用户消息：" + seq);
        assertEquals(WebEventNames.SYSTEM_RESET, seq.get(1), seq.toString());
    }

    @Test
    void interruptWithoutRunEmitsFallbackDoneOnce() throws Exception {
        WorkspaceContext ctx = workspace();
        WebGate gate = gate(ctx);
        engine.getSession("web-3");

        assertTrue(gate.interruptSession(ctx, "web-3"), "没有在跑的任务也算已处理");
        assertTrue(gate.interruptSession(ctx, "web-3"));
        assertEquals(Collections.singletonList(WebEventNames.SYSTEM_DONE), events,
                "没有在跑的任务：只兜底补一次 done（前端可能仍停在流式态）");
        assertFalse(gate.interruptSession(ctx, "ghost"), "会话不存在：false，且不为了中断把它建出来");
        assertNull(engine.getSessionOrNull("ghost"));
    }

    @Test
    void commandReplyIsWrappedInItsOwnTurn() throws Exception {
        WorkspaceContext ctx = workspace();
        WebGate gate = gate(ctx);
        String command = engine.getCommandRegistry().names().stream()
                .filter(n -> n.equals("help") || n.equals("status")).findFirst().orElse(null);
        org.junit.jupiter.api.Assumptions.assumeTrue(command != null, "引擎未注册 /help 或 /status");

        gate.onChatInput(ctx, "web-4", null, "/" + command, null, null, null, null, "web");

        await(() -> count(WebEventNames.SYSTEM_DONE) >= 1, "命令回执自成一轮，应以 done 收尾");
        List<String> seq = compact().stream().filter(e -> e.equals(WebEventNames.SYSTEM_RESET)
                || e.equals(WebEventNames.SYSTEM_COMMAND) || e.equals(WebEventNames.SYSTEM_DONE)).collect(Collectors.toList());
        assertEquals(WebEventNames.SYSTEM_RESET, seq.get(0), "空闲时的命令回执用完整的 reset … done 信封：" + seq);
        assertTrue(seq.contains(WebEventNames.SYSTEM_COMMAND), seq.toString());
        assertEquals(WebEventNames.SYSTEM_DONE, seq.get(seq.size() - 1), seq.toString());
    }

    @Test
    void programLabelsKeepLegacySourceNames() {
        assertEquals("Goal", WebRunSink.programLabel("goal"));
        assertEquals("Loop", WebRunSink.programLabel("loop"));
    }
}
