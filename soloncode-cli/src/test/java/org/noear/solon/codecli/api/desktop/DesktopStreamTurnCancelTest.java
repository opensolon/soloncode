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

    private static void runTurn(AgentSession session) {
        new DesktopStreamTurn(new DesktopStreamHub(), new DesktopEventConverter(mock(HarnessEngine.class)))
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
    void newTurnDoesNotAdvanceCancelGeneration() {
        AgentSession session = InMemoryAgentSession.of("desktop-turn-gen");
        SessionRuns.attach(session, handle(new AtomicInteger()));

        int gen = SessionRuns.cancelCount(session);

        runTurn(session);

        // 顶旧轮次不能推进代数：否则正要开始的这一流会因「调度期内已被取消」被自己拒绝
        assertEquals(gen, SessionRuns.cancelCount(session));
    }
}
