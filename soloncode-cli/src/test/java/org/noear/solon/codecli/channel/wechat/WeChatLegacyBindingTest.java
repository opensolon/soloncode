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
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.codecli.channel.ImBindingStore;
import org.noear.solon.codecli.channel.ImGateway;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 微信历史无归属条目（{@code workspaceId == null}）的认领语义。
 *
 * <p>历史条目不再自动认领（双工作区下归属不确定，自动认领会引入随机归属），
 * 但也不得被当成「已被别处占用」——否则用户重新绑定会撞上无法解释的冲突，
 * 且与 {@code /web/chat/wechat/status} 报出的「未绑定」态自相矛盾。</p>
 */
public class WeChatLegacyBindingTest {
    @TempDir
    Path tempDir;

    private WeChatTransport transport;

    /** 只应答空结果，绝不发真实 HTTP。 */
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

    /**
     * 预置一条历史无归属绑定（写在旧版 {@code wechat-bindings.json} 里），
     * 交给 {@link ImBindingStore} 一次性迁入统一登记表，再装配一个只连假传输层的进程级网关。
     */
    private ImGateway gatewayWithLegacyEntry() throws Exception {
        String legacyJson = "{\n"
                + "  \"session-1\": {\n"
                + "    \"botToken\": \"bot-1\",\n"
                + "    \"ilinkBotId\": \"ilink-bot\",\n"
                + "    \"ilinkUserId\": \"user-1\",\n"
                + "    \"baseUrl\": \"https://example.invalid\",\n"
                + "    \"cursor\": \"\"\n"
                + "  }\n"
                + "}";
        Files.write(tempDir.resolve(ImBindingStore.LEGACY_WECHAT_FILE),
                legacyJson.getBytes(StandardCharsets.UTF_8));

        ImBindingStore store = new ImBindingStore(tempDir.resolve(ImBindingStore.STORE_FILE));
        assertFalse(store.load().isEmpty(), "旧微信条目应迁入统一登记表");

        transport = new WeChatTransport(new FakeTransport());
        ImGateway gateway = new ImGateway(store, transport);
        gateway.reload();
        return gateway;
    }

    @Test
    public void legacyEntryIsNotReportedAsBoundElsewhere() throws Exception {
        ImGateway gw = gatewayWithLegacyEntry();
        try {
            ImGateway.WeChatStatus status = gw.wechatStatus("ws-a", "session-1");
            assertFalse(status.isBound());
            assertFalse(status.isBoundElsewhere());
        } finally {
            transport.stopAll();
        }
    }

    @Test
    public void legacyEntryIsClaimedByRebindWithoutConflict() throws Exception {
        ImGateway gw = gatewayWithLegacyEntry();
        try {
            ImGateway.AdoptResult result = gw.adoptWeChat(
                    "user-1", "bot-1", "ilink-bot", "https://example.invalid", "ws-a", "session-1", false);

            assertTrue(result.isAccepted());
            assertNull(result.getConflict());

            ImGateway.WeChatStatus status = gw.wechatStatus("ws-a", "session-1");
            assertTrue(status.isBound());
            assertFalse(status.isBoundElsewhere());
            assertNotNull(gw.wechatStatus("ws-a", "session-1").getWorkspaceId());
            assertFalse(gw.wechatStatus("ws-b", "session-1").isBound());
            assertTrue(gw.wechatStatus("ws-b", "session-1").isBoundElsewhere());
        } finally {
            transport.stopAll();
        }
    }
}
