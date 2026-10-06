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

import org.noear.solon.codecli.channel.ImStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程级微信传输层：把「每会话一条长轮询」包装成与飞书/钉钉 WebSocket 同形的连接。
 *
 * <p>与 {@code FeishuTransport} / {@code DingTalkTransport} 对齐的语义：</p>
 * <ul>
 *   <li>连接归属进程，不随工作区关闭（LRU 回收）而断开；</li>
 *   <li>每条连接由一个 {@link WeChatLink} 引擎实例承载（长轮询 + 游标 + 回复目标 + typing），
 *       引擎的投递回调改由本层 {@link Sink} 承接，从而在消息到达时按绑定挂点动态唤醒工作区；</li>
 *   <li>同一 ilinkUserId（用户身份）在进程内至多一条连接：换绑/迁移时先断旧连接再建新连接；</li>
 *   <li>连接自身不落盘，绑定持久化由 {@code ImGateway} 单点负责（引擎使用空存储）。</li>
 * </ul>
 *
 * @author noear
 */
public class WeChatTransport {
    private static final Logger LOG = LoggerFactory.getLogger(WeChatTransport.class);

    /** 引擎侧空存储：微信绑定的落盘由 ImGateway 单点负责，避免双写漂移。 */
    private static final WeChatCredentialStore NOOP_STORE = new WeChatCredentialStore(
            Paths.get(System.getProperty("java.io.tmpdir"), "soloncode-wechat-transport-noop.json")) {
        @Override
        public Map<String, WeChatLink.WeChatBinding> load() {
            return Collections.emptyMap();
        }

        @Override
        public void save(Map<String, WeChatLink.WeChatBinding> bindings) {
            // no-op：持久化归 ImGateway
        }

        @Override
        public void saveThrottled(Map<String, WeChatLink.WeChatBinding> bindings) {
            // no-op：持久化归 ImGateway
        }
    };

    /** 入站事件出口，由 ImGateway 实现。 */
    public interface Sink {
        /**
         * 收到一条文本消息。
         *
         * @param replyTarget 服务端下达的 context_token（可能为空）
         * @return 是否被接受进入处理流程
         */
        boolean onWeChatText(String workspaceId, String sessionId, String userKey,
                             String text, String replyTarget, String sourceUserId);

        /** 登录态失效，绑定需要解除。 */
        void onWeChatExpired(String workspaceId, String sessionId, String userKey);
    }

    /** 一条包装后的微信连接。 */
    public static final class Conn {
        private final String workspaceId;
        private final String sessionId;
        private final String userKey;
        private final WeChatLink engine;

        Conn(String workspaceId, String sessionId, String userKey, WeChatLink engine) {
            this.workspaceId = workspaceId;
            this.sessionId = sessionId;
            this.userKey = userKey;
            this.engine = engine;
        }

        public String getWorkspaceId() {
            return workspaceId;
        }

        public String getSessionId() {
            return sessionId;
        }

        public String getUserKey() {
            return userKey;
        }
    }

    private final Map<String, Conn> byUserKey = new ConcurrentHashMap<>();
    private final Map<String, Conn> bySession = new ConcurrentHashMap<>();

    /** 引擎侧使用的接入传输层（测试可注入假实现，避免真实 HTTP）。 */
    private final WeChatLink.Transport engineTransport;

    public WeChatTransport() {
        this(WeChatLink.DEFAULT_TRANSPORT);
    }

    /** 测试用构造：注入引擎传输层。 */
    WeChatTransport(WeChatLink.Transport engineTransport) {
        this.engineTransport = engineTransport;
    }

    /**
     * 建立（或复用）某绑定的长轮询连接，幂等。
     *
     * <p>同一 sessionId 已是同一 userKey 时直接复用，绝不可重建——重建会让游标退回空值，
     * 服务端已投递的消息不会补发。</p>
     */
    public synchronized void ensureConnected(Sink sink, String workspaceId, String sessionId,
                                             String botToken, String ilinkBotId,
                                             String ilinkUserId, String baseUrl) {
        if (sessionId == null || botToken == null || ilinkUserId == null) {
            return;
        }

        Conn same = bySession.get(sessionKey(workspaceId, sessionId));
        if (same != null && Objects.equals(same.userKey, ilinkUserId)) {
            return;
        }
        if (same != null) {
            disconnect(same);
        }

        // 一个 ilinkUserId 在进程内只能有一条连接：换绑时先断开旧连接
        Conn stale = byUserKey.get(ilinkUserId);
        if (stale != null) {
            disconnect(stale);
        }

        WeChatLink engine = createEngine(sink, workspaceId);
        engine.bindSession(sessionId, botToken, ilinkBotId, ilinkUserId, baseUrl);

        Conn conn = new Conn(workspaceId, sessionId, ilinkUserId, engine);
        byUserKey.put(ilinkUserId, conn);
        bySession.put(sessionKey(workspaceId, sessionId), conn);
        LOG.info("[WeChatTransport] Connected: workspaceId={}, sessionId={}, userKey={}", workspaceId, sessionId, ilinkUserId);
    }

    /** 按会话断开连接。 */
    public synchronized void disconnectBySession(String workspaceId, String sessionId) {
        disconnect(bySession.get(sessionKey(workspaceId, sessionId)));
    }

    /** 按用户身份断开连接。 */
    public synchronized void disconnectByUserKey(String ilinkUserId) {
        if (ilinkUserId == null) {
            return;
        }
        disconnect(byUserKey.get(ilinkUserId));
    }

    public boolean isConnected(String workspaceId, String sessionId) {
        return bySession.containsKey(sessionKey(workspaceId, sessionId));
    }

    /** 该会话当前连接的绑定用户身份；未连接时返回 null。 */
    public String userKeyOf(String workspaceId, String sessionId) {
        Conn conn = bySession.get(sessionKey(workspaceId, sessionId));
        return conn == null ? null : conn.userKey;
    }

    /** 本工作区当前持有的连接会话 ID 集合。 */
    public Set<String> sessionsOf(String workspaceId) {
        Set<String> result = new LinkedHashSet<>();
        for (Conn conn : bySession.values()) {
            if (Objects.equals(conn.workspaceId, workspaceId)) {
                result.add(conn.sessionId);
            }
        }
        return result;
    }

    /** 向某会话的微信用户下发回复。 */
    public void sendReply(String workspaceId, String sessionId, String reply, boolean isFinal) {
        Conn conn = bySession.get(sessionKey(workspaceId, sessionId));
        if (conn != null) {
            conn.engine.sendReply(sessionId, reply, isFinal);
        }
    }

    /** 使用入站消息的回复目标快照下发回复。 */
    public void sendReply(String workspaceId, String sessionId, String reply, boolean isFinal,
                          String sourceUserId, String replyTarget, String messageId) {
        Conn conn = bySession.get(sessionKey(workspaceId, sessionId));
        if (conn != null) {
            conn.engine.sendReply(sessionId, reply, isFinal, sourceUserId, replyTarget, messageId);
        }
    }

    /** 下发交互状态信号。 */
    public void sendStatus(String workspaceId, String sessionId, ImStatus status, String detail,
                           String sourceUserId, String replyTarget, String messageId) {
        Conn conn = bySession.get(sessionKey(workspaceId, sessionId));
        if (conn != null) {
            conn.engine.sendStatus(sessionId, status, detail, sourceUserId, replyTarget, messageId);
        }
    }

    /** 停止全部连接（进程退出时调用）。 */
    public synchronized void stopAll() {
        for (Conn conn : new ArrayList<>(byUserKey.values())) {
            disconnect(conn);
        }
    }

    private WeChatLink createEngine(Sink sink, String workspaceId) {
        return new WeChatLink(null, NOOP_STORE, engineTransport) {
            @Override
            protected boolean dispatchToAgent(String sessionId, String text, String sourceUserId, String replyTarget) {
                return sink.onWeChatText(workspaceId, sessionId, sourceUserId, text, replyTarget, sourceUserId);
            }

            @Override
            protected void notifyExpired(String sessionId) {
                sink.onWeChatExpired(workspaceId, sessionId, null);
            }
        };
    }

    private void disconnect(Conn conn) {
        if (conn == null) {
            return;
        }
        byUserKey.remove(conn.userKey, conn);
        bySession.remove(sessionKey(conn.workspaceId, conn.sessionId), conn);
        try {
            conn.engine.stop();
        } catch (Exception e) {
            LOG.warn("[WeChatTransport] Stop connection failed: {}", e.getMessage());
        }
        LOG.info("[WeChatTransport] Disconnected: workspaceId={}, sessionId={}", conn.workspaceId, conn.sessionId);
    }

    private static String sessionKey(String workspaceId, String sessionId) {
        return (workspaceId == null ? "" : workspaceId) + '\u0000' + (sessionId == null ? "" : sessionId);
    }
}
