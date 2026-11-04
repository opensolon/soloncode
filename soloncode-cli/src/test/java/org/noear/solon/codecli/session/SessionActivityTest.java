package org.noear.solon.codecli.session;

import org.noear.solon.ai.harness.session.HarnessAttrs;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.session.SessionRuns;
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.ai.agent.react.intercept.HITLTask;
import reactor.core.Disposable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionActivityTest {
    @Test
    void admittingInputIsNotAnActiveStream() {
        AgentSession session = InMemoryAgentSession.of();
        session.attrs().put(HarnessAttrs.INPUT_ADMITTING, Boolean.TRUE);

        assertTrue(SessionActivity.isInputAdmitting(session));
        assertFalse(SessionActivity.hasActiveStream(session));
        assertTrue(SessionActivity.isBusy(session));
    }

    @Test
    void liveStreamMakesSessionBusy() {
        AgentSession session = InMemoryAgentSession.of();
        // 运行句柄由框架登记表持有（ReActRequest 自行 attach），宿主不再写 attrs
        reactor.core.Disposable handle = new reactor.core.Disposable() {
            private boolean disposed;
            @Override public void dispose() { disposed = true; }
            @Override public boolean isDisposed() { return disposed; }
        };
        SessionRuns.attach(session, handle);

        assertTrue(SessionActivity.hasActiveStream(session));
        assertTrue(SessionActivity.isBusy(session));

        SessionRuns.cancelAll(session);
        assertFalse(SessionActivity.hasActiveStream(session));
        assertFalse(SessionActivity.isBusy(session));
    }

    @Test
    void nullSessionIsIdle() {
        assertFalse(SessionActivity.isInputAdmitting(null));
        assertFalse(SessionActivity.hasActiveStream(null));
        assertFalse(SessionActivity.isBusy(null));
        assertFalse(SessionActivity.isOccupied(null));
        assertFalse(SessionActivity.hasPendingHitl(null));
    }

    /**
     * 审批挂起时：不可受理（occupied），但不是「有活跃流」（busy）。
     *
     * <p>两个口径分开是受理判定的根：用 isBusy 判受理会漏掉 HITL，
     * 新回合与挂起 trace 并存后，点「同意」时续流被运行态认领拒绝（表现为“点了没反应”）。</p>
     */
    @Test
    void pendingHitlOccupiesSessionWithoutAnActiveStream() {
        AgentSession session = InMemoryAgentSession.of();
        HITLTask task = new HITLTask("call-1", "bash", new java.util.LinkedHashMap<>(), null);
        session.getContext().put(HITL.PENDING_TASKS, new java.util.ArrayList<>(java.util.Collections.singletonList(task)));

        assertTrue(SessionActivity.hasPendingHitl(session));
        assertFalse(SessionActivity.hasActiveStream(session), "审批挂起时引擎里没有运行流");
        assertTrue(SessionActivity.isOccupied(session), "但绝不能再开新回合");
    }

    /** 同一会话的 attrs 必须能承载并发写入（桌面端多个线程直接写，不额外加锁）。 */
    @Test
    void attrsToleratesConcurrentWrites() throws Exception {
        AgentSession session = InMemoryAgentSession.of();
        int threads = 8;
        int perThread = 500;
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(threads);
        java.util.List<Throwable> errors = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        for (int t = 0; t < threads; t++) {
            final int id = t;
            new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        session.attrs().put("k" + id + "_" + i, i);
                    }
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    done.countDown();
                }
            }).start();
        }

        start.countDown();
        assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS), "并发写入超时");
        assertTrue(errors.isEmpty(), "并发写入抛异常: " + errors);
        assertEquals(threads * perThread, session.attrs().size(), "并发写入不得丢更新");
    }
}
