/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.noear.solon.codecli.portal.desktop;

import org.noear.snack4.ONode;
import org.noear.solon.net.websocket.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 桌面端流式消息中转站。
 *
 * <p>Agent 运行不再绑定首次建立的 WebSocket。每个会话保留一个有界增量缓冲区，
 * 新连接可以从最后确认的 sequence 继续回放，再无缝接收实时消息。</p>
 */
final class DesktopStreamHub {
    private static final Logger LOG = LoggerFactory.getLogger(DesktopStreamHub.class);
    private static final int MAX_REPLAY_MESSAGES = 4096;
    private static final long COMPLETED_TTL_MILLIS = 5 * 60 * 1000L;

    private final Map<String, StreamState> streams = new ConcurrentHashMap<>();

    void begin(String sessionId, WebSocket socket) {
        cleanupExpired();
        StreamState next = new StreamState(sessionId);
        next.addSubscriber(socket);
        streams.put(sessionId, next);
    }

    boolean attach(String sessionId, WebSocket socket, long afterSequence) {
        cleanupExpired();
        StreamState state = streams.get(sessionId);
        if (state == null) {
            return false;
        }
        return state.attachAndReplay(socket, Math.max(0L, afterSequence));
    }

    void subscribe(String sessionId, WebSocket socket) {
        StreamState state = streams.get(sessionId);
        if (state == null) {
            state = new StreamState(sessionId);
            streams.put(sessionId, state);
        }
        state.addSubscriber(socket);
    }

    void detach(WebSocket socket) {
        if (socket == null) {
            return;
        }
        for (StreamState state : streams.values()) {
            state.removeSubscriber(socket);
        }
    }

    boolean emit(String sessionId, String json) {
        if (json == null || json.isEmpty()) {
            return false;
        }
        StreamState state = streams.get(sessionId);
        if (state == null) {
            LOG.debug("[DesktopStreamHub] Ignore chunk without stream state: {}", sessionId);
            return false;
        }
        state.emit(json);
        return true;
    }

    private void cleanupExpired() {
        long now = System.currentTimeMillis();
        streams.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
    }

    private static final class StreamState {
        private final String sessionId;
        private final Set<WebSocket> subscribers = new HashSet<>();
        private final ArrayDeque<ReplayMessage> replay = new ArrayDeque<>();
        private long nextSequence = 1L;
        private long completedAt;

        private StreamState(String sessionId) {
            this.sessionId = sessionId;
        }

        synchronized void addSubscriber(WebSocket socket) {
            if (socket != null) {
                subscribers.add(socket);
            }
        }

        synchronized boolean attachAndReplay(WebSocket socket, long afterSequence) {
            if (socket == null) {
                return false;
            }
            ReplayMessage oldest = replay.peekFirst();
            if (oldest != null && oldest.sequence > afterSequence + 1L) {
                LOG.warn("[DesktopStreamHub] Replay gap for session {}: requested after {}, oldest is {}",
                        sessionId, afterSequence, oldest.sequence);
                return false;
            }
            subscribers.add(socket);
            for (ReplayMessage message : replay) {
                if (message.sequence > afterSequence && !send(socket, message.json)) {
                    subscribers.remove(socket);
                    return false;
                }
            }
            return true;
        }

        synchronized void removeSubscriber(WebSocket socket) {
            subscribers.remove(socket);
        }

        synchronized void emit(String json) {
            long sequence = nextSequence++;
            ONode node = ONode.ofJson(json).set("sequence", sequence);
            String sequencedJson = node.toJson();
            replay.addLast(new ReplayMessage(sequence, sequencedJson));
            while (replay.size() > MAX_REPLAY_MESSAGES) {
                replay.removeFirst();
            }

            String type = node.get("type") == null ? null : node.get("type").getString();
            if ("done".equalsIgnoreCase(type) || "error".equalsIgnoreCase(type)) {
                completedAt = System.currentTimeMillis();
            }

            List<WebSocket> failed = new ArrayList<>();
            for (WebSocket socket : subscribers) {
                if (!send(socket, sequencedJson)) {
                    failed.add(socket);
                }
            }
            subscribers.removeAll(failed);
        }

        synchronized boolean isExpired(long now) {
            return completedAt > 0L && now - completedAt >= COMPLETED_TTL_MILLIS;
        }

        private boolean send(WebSocket socket, String json) {
            try {
                socket.send(json);
                return true;
            } catch (Throwable error) {
                LOG.debug("[DesktopStreamHub] Send failed for session {}: {}", sessionId, error.getMessage());
                return false;
            }
        }
    }

    private static final class ReplayMessage {
        private final long sequence;
        private final String json;

        private ReplayMessage(long sequence, String json) {
            this.sequence = sequence;
            this.json = json;
        }
    }
}
