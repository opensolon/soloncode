/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.codecli.channel.wechat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 微信通道绑定与收发行为单元测试。
 *
 * <p>覆盖「绑定成功后第一条微信消息收不到答复」的回归场景：扫码确认瞬间
 * 后端会被并发触发多次 bindSession，绑定必须幂等，否则游标与 context_token
 * 会被重置、在途长轮询会被中断，首条消息随之丢失。</p>
 *
 * <p>全程通过注入的假传输层运行，不发起任何网络请求。</p>
 *
 * @author soloncode 2026/9/6 created
 */
class WeChatLinkBindingTest {

    @TempDir
    Path tempDir;

    private FakeTransport transport;
    private TestLink link;

    @BeforeEach
    void setUp() {
        transport = new FakeTransport();
        link = new TestLink(new WeChatCredentialStore(tempDir.resolve("wechat-bindings.json")), transport);
    }

    @AfterEach
    void tearDown() {
        link.stop();
    }

    // ===== 幂等绑定 =====

    @Test
    void duplicateBind_shouldPreserveCursorAndReplyTarget() {
        link.bindSession("s1", "tk", "bot", "user");

        WeChatLink.WeChatBinding binding = link.getBinding("s1");
        assertNotNull(binding);
        assertEquals("", binding.cursor);
        assertEquals(1, link.startCount);

        // 模拟已经推进过的会话状态
        binding.cursor = "CURSOR_5";
        binding.replyTarget = new WeChatLink.ReplyTarget("user", "ctx-1");

        // 扫码确认瞬间的重复回调（前端 2s 轮询 + 接口对 confirmed 幂等，必然发生）
        link.bindSession("s1", "tk", "bot", "user");
        link.bindSession("s1", "tk", "bot", "user");

        WeChatLink.WeChatBinding after = link.getBinding("s1");
        assertSame(binding, after, "重复绑定不应重建 binding");
        assertEquals("CURSOR_5", after.cursor, "重复绑定不能把游标退回空串");
        assertNotNull(after.replyTarget, "重复绑定不能清掉 context_token");
        assertEquals("ctx-1", after.replyTarget.contextToken);
        assertEquals(1, link.startCount, "重复绑定不能重启长轮询：会中断在途请求并丢弃已投递消息");
        assertEquals(2, link.ensureCount, "重复绑定走守护路径，仅在轮询已死时才重建");
    }

    @Test
    void bindWithNewToken_shouldResetCursor() {
        link.bindSession("s1", "tk", "bot", "user");
        link.getBinding("s1").cursor = "CURSOR_5";

        // bot_token 变化意味着新的登录态，按协议游标必须清空
        link.bindSession("s1", "tk2", "bot", "user");

        assertEquals("", link.getBinding("s1").cursor);
        assertEquals("tk2", link.getBinding("s1").botToken);
        assertEquals(2, link.startCount);
    }

    @Test
    void bindSameUserToAnotherSession_shouldUnbindOldOne() {
        link.bindSession("s1", "tk", "bot", "user");
        link.bindSession("s2", "tk", "bot", "user");

        assertFalse(link.isBound("s1"), "同一微信用户只能绑定一个会话");
        assertTrue(link.isBound("s2"));
    }

    @Test
    void bindWithBlankCredential_shouldBeIgnored() {
        link.bindSession("s1", "", "bot", "user");
        assertFalse(link.isBound("s1"));
        assertEquals(0, link.startCount);
    }

    @Test
    void bindWithServerAssignedBaseUrl_shouldBeStored() {
        link.bindSession("s1", "tk", "bot", "user", "node3.weixin.qq.com");
        assertEquals("https://node3.weixin.qq.com", link.getBinding("s1").baseUrl);

        // 重复绑定时接入点可以刷新，但不影响会话游标
        link.getBinding("s1").cursor = "CURSOR_7";
        link.bindSession("s1", "tk", "bot", "user", "https://node4.weixin.qq.com");
        assertEquals("https://node4.weixin.qq.com", link.getBinding("s1").baseUrl);
        assertEquals("CURSOR_7", link.getBinding("s1").cursor);
    }

    // ===== 首条消息收发 =====

    @Test
    void firstInboundMessage_shouldBeDispatchedAndAnswered() {
        link.bindSession("s1", "tk", "bot", "user");
        // 重复确认回调
        link.bindSession("s1", "tk", "bot", "user");

        transport.enqueueUpdate("CURSOR_1", "user", "ctx-1", "第一条消息");

        assertEquals(WeChatLink.PollOutcome.OK, link.pollOnce("s1"));

        assertEquals(Collections.singletonList("第一条消息"), new ArrayList<>(link.dispatched));
        assertEquals("CURSOR_1", link.getBinding("s1").cursor);
        assertEquals("ctx-1", link.getBinding("s1").getLastContextToken());

        link.sendReply("s1", "这是答复", true);

        assertEquals(1, transport.sent.size());
        assertEquals("ctx-1", transport.sent.get(0).contextToken);
        assertEquals("user", transport.sent.get(0).toUserId);
        assertEquals("这是答复", transport.sent.get(0).text);
    }

    @Test
    void replyWithoutInboundContextToken_shouldNotSend() {
        link.bindSession("s1", "tk", "bot", "user");

        // iLink 不支持主动推送：没有入站带回的 context_token 就无处投递
        link.sendReply("s1", "无处投递的答复", true);

        assertTrue(transport.sent.isEmpty());
    }

    @Test
    void inboundWithoutContextToken_shouldBeDropped() {
        link.bindSession("s1", "tk", "bot", "user");
        transport.enqueueUpdate("C1", "user", null, "缺少上下文令牌");

        assertEquals(WeChatLink.PollOutcome.OK, link.pollOnce("s1"));

        // 处理了却无法回复只会变成"已读不回"，应直接丢弃
        assertTrue(link.dispatched.isEmpty());
    }

    @Test
    void busySession_shouldSendHintInsteadOfSilence() {
        link.acceptDispatch = false;
        link.bindSession("s1", "tk", "bot", "user");
        transport.enqueueUpdate("C1", "user", "ctx-1", "在忙吗");

        link.pollOnce("s1");

        assertEquals(1, transport.sent.size());
        assertTrue(transport.sent.get(0).text.contains("正在处理上一条消息"), transport.sent.get(0).text);
    }

    @Test
    void longReply_shouldBeChunkedAndTypingStoppedOnFinal() {
        link.bindSession("s1", "tk", "bot", "user");
        transport.enqueueUpdate("C1", "user", "ctx-1", "写点长的");
        link.pollOnce("s1");

        assertTrue(transport.typing.contains(1), "受理后应立刻发出「正在输入」");
        assertFalse(transport.typing.contains(2), "AI 还没出结果，不能马上停止「正在输入」");

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            sb.append("这是一行较长的内容用于触发分段发送。\n");
        }
        link.sendReply("s1", sb.toString(), true);

        assertTrue(transport.sent.size() > 1, "超长回复应分段发送，实际段数=" + transport.sent.size());
        assertTrue(transport.sent.get(1).text.startsWith("(2) "), transport.sent.get(1).text);
        assertTrue(transport.typing.contains(2), "最终回复发出后才停止「正在输入」");
    }

    @Test
    void retryShouldReuseClientIdForTheSameChunk() {
        link.bindSession("s1", "tk", "bot", "user");
        transport.enqueueUpdate("C1", "user", "ctx-1", "请回答");
        link.pollOnce("s1");
        transport.failNextSends = 1;

        link.sendReply("s1", "重试答复", true);

        assertEquals(2, transport.clientIds.size(), "首次结果未知后应重试一次");
        assertNotNull(transport.clientIds.get(0));
        assertEquals(transport.clientIds.get(0), transport.clientIds.get(1),
                "同一逻辑分段重试必须复用 client_id，避免微信收到重复气泡");
    }

    // ===== 轮询结果 =====

    @Test
    void expiredToken_shouldUnbindAndNotifyOnce() {
        link.bindSession("s1", "tk", "bot", "user");
        transport.enqueueExpired();

        assertEquals(WeChatLink.PollOutcome.STOP, link.pollOnce("s1"));
        assertFalse(link.isBound("s1"));
        assertEquals(Collections.singletonList("s1"), new ArrayList<>(link.expiredNotified));
    }

    @Test
    void unavailableResponse_shouldReportFailedForBackoff() {
        link.bindSession("s1", "tk", "bot", "user");

        // 队列为空 → 传输层返回 null，等价于请求失败，应走退避而不是当成"无消息"
        assertEquals(WeChatLink.PollOutcome.FAILED, link.pollOnce("s1"));
    }

    @Test
    void pollUnboundSession_shouldStop() {
        assertEquals(WeChatLink.PollOutcome.STOP, link.pollOnce("nobody"));
    }

    @Test
    void pollingShouldStartWithoutLifecycleRun() throws Exception {
        CountDownLatch dispatched = new CountDownLatch(1);
        FakeTransport realTransport = new FakeTransport();
        realTransport.enqueueUpdate("C1", "user", "ctx-1", "绑定后的第一条消息");

        // 不覆写 startPolling：跑真实的长轮询线程
        WeChatLink real = new WeChatLink(null,
                new WeChatCredentialStore(tempDir.resolve("lifecycle-bindings.json")), realTransport) {
            @Override
            protected boolean dispatchToAgent(String sessionId, String text) {
                dispatched.countDown();
                return true;
            }

            @Override
            protected void notifyExpired(String sessionId) {
            }

            @Override
            protected void submitSend(String sessionId, Runnable task) {
                task.run();
            }
        };

        try {
            // 注意：此处有意不调 run()。ChannelHub.start() 在代码库里无调用点，
            // 若轮询以 running 为前置条件，扫码绑定后将一条消息也收不到
            real.bindSession("s1", "tk", "bot", "user");
            assertTrue(dispatched.await(5, TimeUnit.SECONDS), "绑定后长轮询应立即开始工作");
        } finally {
            real.stop();
        }
    }

    // ===== 测试替身 =====

    private static final class TestLink extends WeChatLink {
        final List<String> dispatched = new CopyOnWriteArrayList<>();
        final List<String> expiredNotified = new CopyOnWriteArrayList<>();
        volatile boolean acceptDispatch = true;
        int startCount;
        int ensureCount;

        TestLink(WeChatCredentialStore credentialStore, Transport transport) {
            super(null, credentialStore, transport);
        }

        @Override
        protected void startPolling(String sessionId) {
            startCount++;
        }

        @Override
        protected void ensurePolling(String sessionId) {
            ensureCount++;
        }

        @Override
        protected boolean dispatchToAgent(String sessionId, String text) {
            dispatched.add(text);
            return acceptDispatch;
        }

        @Override
        protected void notifyExpired(String sessionId) {
            expiredNotified.add(sessionId);
        }

        @Override
        protected void submitSend(String sessionId, Runnable task) {
            task.run(); // 测试内同步执行，便于断言
        }
    }

    private static final class Sent {
        final String toUserId;
        final String contextToken;
        final String text;

        Sent(String toUserId, String contextToken, String text) {
            this.toUserId = toUserId;
            this.contextToken = contextToken;
            this.text = text;
        }
    }

    private static final class FakeTransport implements WeChatLink.Transport {
        final Deque<Map<String, Object>> updates = new ArrayDeque<>();
        final List<Sent> sent = new CopyOnWriteArrayList<>();
        final List<String> clientIds = new CopyOnWriteArrayList<>();
        final List<Integer> typing = new CopyOnWriteArrayList<>();
        volatile int failNextSends;

        void enqueueUpdate(String cursor, String userId, String contextToken, String text) {
            Map<String, String> msg = new LinkedHashMap<>();
            msg.put("text", text);
            msg.put("from_user_id", userId);
            msg.put("context_token", contextToken);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("cursor", cursor);
            result.put("messages", new ArrayList<Map<String, String>>(Collections.singletonList(msg)));
            updates.add(result);
        }

        void enqueueExpired() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("expired", true);
            updates.add(result);
        }

        @Override
        public Map<String, Object> getUpdates(String baseUrl, String botToken, String cursor) {
            return updates.poll();
        }

        @Override
        public boolean sendMessage(String baseUrl, String botToken, String toUserId, String contextToken, String text) {
            sent.add(new Sent(toUserId, contextToken, text));
            return true;
        }

        @Override
        public boolean sendMessage(String baseUrl, String botToken, String toUserId, String contextToken,
                                   String text, String clientId) {
            sent.add(new Sent(toUserId, contextToken, text));
            clientIds.add(clientId);
            if (failNextSends > 0) {
                failNextSends--;
                return false;
            }
            return true;
        }

        @Override
        public String getConfig(String baseUrl, String botToken, String ilinkUserId, String contextToken) {
            return "ticket-1";
        }

        @Override
        public boolean sendTyping(String baseUrl, String botToken, String ilinkUserId, String typingTicket, int status) {
            typing.add(status);
            return true;
        }
    }
}
