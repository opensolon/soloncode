/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.codecli.channel.wechat;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进程级微信传输层：长轮询连接化的归属与生命周期契约。
 *
 * <p>核心不变量：同一 ilinkUserId 在进程内至多一条连接；同一会话重复绑定时连接被复用
 * （游标不重置）；断开后不再保有连接。</p>
 */
public class WeChatTransportTest {

    private static final class FakeTransport implements WeChatLink.Transport {
        @Override
        public Map<String, Object> getUpdates(String baseUrl, String botToken, String cursor) {
            return new HashMap<>();
        }

        @Override
        public boolean sendMessage(String baseUrl, String botToken, String toUserId, String contextToken, String text) {
            return true;
        }

        @Override
        public String getConfig(String baseUrl, String botToken, String ilinkUserId, String contextToken) {
            return null;
        }

        @Override
        public boolean sendTyping(String baseUrl, String botToken, String ilinkUserId, String typingTicket, int status) {
            return true;
        }
    }

    private static final WeChatTransport.Sink NOOP_SINK = new WeChatTransport.Sink() {
        @Override
        public boolean onWeChatText(String workspaceId, String sessionId, String userKey,
                                    String text, String replyTarget, String sourceUserId) {
            return true;
        }

        @Override
        public void onWeChatExpired(String workspaceId, String sessionId, String userKey) {
        }
    };

    private WeChatTransport newTransport() {
        return new WeChatTransport(new FakeTransport());
    }

    @Test
    public void sameSessionAndUser_shouldReuseConnection() {
        WeChatTransport transport = newTransport();
        try {
            transport.ensureConnected(NOOP_SINK, "ws1", "s1", "token", "bot", "user1", null);
            String userKey = transport.userKeyOf("ws1", "s1");

            // 重复确认（前端 2s 轮询会并发触发）：必须复用，不能重建（重建会重置游标）
            transport.ensureConnected(NOOP_SINK, "ws1", "s1", "token", "bot", "user1", null);

            assertEquals("user1", userKey);
            assertEquals("user1", transport.userKeyOf("ws1", "s1"));
            assertTrue(transport.isConnected("ws1", "s1"));
            assertEquals(1, transport.sessionsOf("ws1").size());
        } finally {
            transport.stopAll();
        }
    }

    @Test
    public void sameUserOnAnotherSession_shouldKeepSingleConnection() {
        WeChatTransport transport = newTransport();
        try {
            transport.ensureConnected(NOOP_SINK, "ws1", "s1", "token", "bot", "user1", null);
            transport.ensureConnected(NOOP_SINK, "ws1", "s2", "token", "bot", "user1", null);

            // 一个用户身份只能有一条连接：换会话后旧连接被断开
            assertFalse(transport.isConnected("ws1", "s1"));
            assertTrue(transport.isConnected("ws1", "s2"));
            assertEquals(1, transport.sessionsOf("ws1").size());
        } finally {
            transport.stopAll();
        }
    }

    @Test
    public void crossWorkspaceSameUser_shouldKeepSingleConnection() {
        WeChatTransport transport = newTransport();
        try {
            transport.ensureConnected(NOOP_SINK, "ws1", "s1", "token", "bot", "user1", null);
            transport.ensureConnected(NOOP_SINK, "ws2", "s1", "token", "bot", "user1", null);

            // 这是本次重构要消灭的核心症状：同一 bot 用户被两个工作区同时长轮询
            assertFalse(transport.isConnected("ws1", "s1"));
            assertTrue(transport.isConnected("ws2", "s1"));
            assertEquals(0, transport.sessionsOf("ws1").size());
            assertEquals(1, transport.sessionsOf("ws2").size());
        } finally {
            transport.stopAll();
        }
    }

    @Test
    public void disconnectBySession_shouldRemoveConnection() {
        WeChatTransport transport = newTransport();
        try {
            transport.ensureConnected(NOOP_SINK, "ws1", "s1", "token", "bot", "user1", null);
            transport.disconnectBySession("ws1", "s1");

            assertFalse(transport.isConnected("ws1", "s1"));
            // 断开后仍可重新绑定
            transport.ensureConnected(NOOP_SINK, "ws1", "s1", "token", "bot", "user1", null);
            assertTrue(transport.isConnected("ws1", "s1"));
        } finally {
            transport.stopAll();
        }
    }

    @Test
    public void sendReplyOnUnknownSession_shouldBeSilentlyIgnored() {
        WeChatTransport transport = newTransport();
        try {
            // 连接已不存在（例如工作区从未绑定）：出站路径不得抛异常
            transport.sendReply("ws1", "missing", "hello", true);
            transport.sendStatus("ws1", "missing", null, "detail", null, null, null);
        } finally {
            transport.stopAll();
        }
    }

    @Test
    public void nullArguments_shouldNotCreateConnection() {
        WeChatTransport transport = newTransport();
        try {
            transport.ensureConnected(NOOP_SINK, "ws1", "s1", null, "bot", "user1", null);
            transport.ensureConnected(NOOP_SINK, "ws1", null, "token", "bot", "user1", null);

            assertEquals(0, transport.sessionsOf("ws1").size());
        } finally {
            transport.stopAll();
        }
    }
}
