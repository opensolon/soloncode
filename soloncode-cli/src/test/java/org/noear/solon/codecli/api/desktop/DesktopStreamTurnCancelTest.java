package org.noear.solon.codecli.api.desktop;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActRequest;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.session.SessionRuns;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.net.websocket.WebSocket;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 桌面端「新输入顶掉旧轮次」契约。
 *
 * <p>迁移前由 {@code session.attrs().put("disposable", ...)} 的返回值完成顶流；
 * 句柄上收到 {@link SessionRuns} 后，这段语义必须显式保留——否则同一会话连发两条
 * 消息时两条流会同时往同一个 {@code streamHub} 写，前端两个轮次的输出交错。</p>
 */
class DesktopStreamTurnCancelTest {

    private static Disposable handle(AtomicInteger disposed) {
        return new Disposable() {
            @Override
            public void dispose() {
                disposed.incrementAndGet();
            }

            @Override
            public boolean isDisposed() {
                return disposed.get() > 0;
            }
        };
    }

    /**
     * 流永不结束：本组用例只关心「开新回合前的顶流动作」，不触碰终态路径
     * （终态需要 ChatModel/HarnessEngine 的完整装配，与本契约无关）。
     */
    private static ReActRequest stubRequest() {
        ReActRequest request = mock(ReActRequest.class);
        doAnswer(inv -> request).when(request).options(any());
        when(request.stream()).thenAnswer(inv -> Flux.never());
        return request;
    }

    /** 引擎替身：停流与状态查询委托给真实静态实现（否则 mock 会让顶流变成空操作）。 */
    private static HarnessEngine stubEngine() {
        HarnessEngine engine = mock(HarnessEngine.class);
        when(engine.interruptStream(any())).thenAnswer(
                inv -> org.noear.solon.ai.harness.session.interrupt.InterruptHelper
                        .interruptStreamOnly(inv.getArgument(0)));
        when(engine.status(any())).thenAnswer(
                inv -> org.noear.solon.ai.harness.session.SessionStatus.of(inv.getArgument(0)));
        return engine;
    }

    private static void runTurn(AgentSession session) {
        HarnessEngine engine = stubEngine();
        new DesktopStreamTurn(engine, new DesktopStreamHub(), new DesktopEventConverter(engine))
                .run(session, stubRequest(), mock(ChatModel.class), "s1", ".", null,
                        mock(WebSocket.class), false, null);
    }

    @Test
    void newTurnCancelsPreviousRunOnSameSession() {
        AgentSession session = InMemoryAgentSession.of("desktop-turn-cancel");
        AtomicInteger disposed = new AtomicInteger();
        assertTrue(SessionRuns.attach(session, handle(disposed)));

        runTurn(session);

        assertEquals(1, disposed.get(), "上一轮遗留流必须被停掉");
    }

    @Test
    void newTurnAdvancesCancelGenerationSoPreviousTurnCanTellItWasCanceled() {
        AgentSession session = InMemoryAgentSession.of("desktop-turn-gen");
        SessionRuns.attach(session, handle(new AtomicInteger()));

        int gen = SessionRuns.cancelCount(session);

        runTurn(session);

        // 取消现在以 complete 收尾，旧流仍会走 doOnComplete。
        // 必须推进代数，旧流才能据此判定「自己是被顶掉的」，从而不再向新一轮发 done。
        assertEquals(gen + 1, SessionRuns.cancelCount(session));
    }

    @Test
    void canceledTurnDoesNotEmitSecondDone() {
        AgentSession session = InMemoryAgentSession.of("desktop-cancel-done");
        WebSocket socket = mock(WebSocket.class);

        ReActRequest request = mock(ReActRequest.class);
        doAnswer(inv -> request).when(request).options(any());
        when(request.stream()).thenAnswer(inv -> {
            // 模拟「开流后随即被取消」：取消后框架以 complete 收尾，会触发 doOnComplete
            SessionRuns.cancelAll(session);
            return Flux.<org.noear.solon.ai.agent.AgentEvent>empty();
        });

        ChatModel chatModel = mock(ChatModel.class);
        org.noear.solon.ai.chat.ChatConfig stubConfig = new org.noear.solon.ai.chat.ChatConfig();
        stubConfig.setName("stub-model");
        when(chatModel.getConfig()).thenReturn(new org.noear.solon.ai.chat.ChatConfigReadonly(stubConfig));

        HarnessEngine engine = stubEngine();
        new DesktopStreamTurn(engine, new DesktopStreamHub(), new DesktopEventConverter(engine))
                .run(session, request, chatModel, "s1", ".", null, socket, false, null);

        // 中断入口已同步发过 reason + done，本轮 doOnComplete 不得再发一个终态帧
        verify(socket, never()).send(org.mockito.ArgumentMatchers.contains("\"done\""));
    }
}
