package org.noear.solon.ai.codecli.portal;

import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.react.ReActChunk;
import org.noear.solon.ai.agent.react.task.ActionChunk;
import org.noear.solon.ai.agent.react.task.ReasonChunk;
import org.noear.solon.ai.codecli.core.CodeAgent;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Handler;
import org.noear.solon.core.util.MimeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Web 通道实现 (基于 Solon 框架)
 * 提供 HTTP API 和 WebSocket 支持
 */
public class WebChannel implements BotChannel, Handler {
    private static final Logger logger = LoggerFactory.getLogger(WebChannel.class);

    private final String channelId;
    private final int port;
    private final CodeAgent codeAgent;
    private final Map<String, String> messageStore = new ConcurrentHashMap<>();
    private boolean isRunning = false;

    public WebChannel(String channelId, int port, CodeAgent codeAgent) {
        this.channelId = channelId;
        this.port = port;
        this.codeAgent = codeAgent;
    }

    @Override
    public String getChannelId() {
        return channelId;
    }

    @Override
    public void start() {
        if (!isRunning) {
            logger.info("Starting WebChannel on port: {}", port);
            // 在实际实现中，这里会启动 Solon 服务器
            // solon.app().add("/bot", this);
            isRunning = true;
        }
    }

    @Override
    public void stop() {
        if (isRunning) {
            logger.info("Stopping WebChannel on port: {}", port);
            // 在实际实现中，这里会停止 Solon 服务器
            isRunning = false;
        }
    }

    @Override
    public String push(String targetId, ReActChunk chunk, Map<String, Object> metadata) {
        String messageId = UUID.randomUUID().toString();
        String content = formatChunkContent(chunk);

        // 存储消息 ID 用于后续更新
        messageStore.put(messageId, content);

        // 推送到客户端
        pushToClient(targetId, messageId, content, metadata);

        return messageId;
    }

    @Override
    public void update(String targetId, String messageId, String newContent) {
        if (messageStore.containsKey(messageId)) {
            messageStore.put(messageId, newContent);
            // 发送更新到客户端
            updateClient(targetId, messageId, newContent);
        }
    }

    @Override
    public void pushText(String targetId, String text, Map<String, Object> metadata) {
        String messageId = UUID.randomUUID().toString();
        messageStore.put(messageId, text);
        pushToClient(targetId, messageId, text, metadata);
    }

    @Override
    public String pushRich(String targetId, RichMessage message, Map<String, Object> metadata) {
        String messageId = UUID.randomUUID().toString();
        String content = formatRichMessage(message);
        messageStore.put(messageId, content);
        pushToClient(targetId, messageId, content, metadata);
        return messageId;
    }

    @Override
    public String pushAttachment(String targetId, Attachment attachment, Map<String, Object> metadata) {
        String messageId = UUID.randomUUID().toString();
        String content = formatAttachment(attachment);
        messageStore.put(messageId, content);
        pushToClient(targetId, messageId, content, metadata);
        return messageId;
    }

    @Override
    public void addReaction(String targetId, String messageId, String emoji) {
        // 实现表情反应功能
        logger.info("Adding reaction {} to message {} for target {}", emoji, messageId, targetId);
    }

    @Override
    public void removeReaction(String targetId, String messageId, String emoji) {
        logger.info("Removing reaction {} from message {} for target {}", emoji, messageId, targetId);
    }

    @Override
    public void sendTyping(String targetId, boolean isTyping) {
        logger.info("Sending typing status {} for target {}", isTyping, targetId);
    }

    @Override
    public ChannelCapabilities getCapabilities() {
        ChannelCapabilities capabilities = new ChannelCapabilities();
        capabilities.enableMarkdown();
        capabilities.enableAttachments();
        capabilities.enableReactions();
        return capabilities;
    }

    @Override
    public boolean hasPermission(String targetId, String userId, String permission) {
        // 简单的权限检查，实际项目中可能需要更复杂的逻辑
        return true;
    }

    @Override
    public ThreadContext getThreadContext(String targetId, String messageId) {
        // 简单的线程上下文实现
        return new ThreadContext(messageId, null);
    }

    @Override
    public HealthStatus healthCheck() {
        boolean healthy = isRunning;
        String status = healthy ? "WebChannel is running" : "WebChannel is stopped";
        return new HealthStatus(healthy, status);
    }

    @Override
    public void handle(Context ctx) throws Throwable {
        String targetId = ctx.headerOrDefault("X-Target-Id", "default");
        String action = ctx.param("action");

        if ("send".equals(action)) {
            String input = ctx.param("message");
            if (input != null && !input.trim().isEmpty()) {
                // 处理收到的消息
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("type", "ai_response");
                codeAgent.stream(targetId, org.noear.solon.ai.chat.prompt.Prompt.of(input))
                        .subscribe(chunk -> {
                            // 将 AgentChunk 转换为文本消息推送
                            if (chunk != null && chunk.hasContent()) {
                                pushText(targetId, chunk.getContent(), metadata);
                            }
                        });

                ctx.output("Message received");
            } else {
                ctx.status(400);
                ctx.output("Empty message");
            }
        } else {
            ctx.status(404);
            ctx.output("Not found");
        }
    }

    // ==================== 私有辅助方法 ====================

    private String formatChunkContent(ReActChunk chunk) {
        String type = "unknown";
        String content = chunk.getContent();

        if (chunk.getClass().getSimpleName().equals("ReasonChunk")) {
            type = "reason";
        } else if (chunk.getClass().getSimpleName().equals("ActionChunk")) {
            type = "action";
        }

        Map<String, Object> data = new HashMap<>();
        data.put("type", type);
        data.put("content", content);
        data.put("timestamp", System.currentTimeMillis());

        return ONode.serialize(data);
    }

    private String formatRichMessage(RichMessage message) {
        Map<String, Object> data = new HashMap<>();
        data.put("type", "rich");
        data.put("text", message.text);
        data.put("format", message.format);
        data.put("extra", message.extra);
        data.put("timestamp", System.currentTimeMillis());

        return ONode.serialize(data);
    }

    private String formatAttachment(Attachment attachment) {
        Map<String, Object> data = new HashMap<>();
        data.put("type", "attachment");
        data.put("name", attachment.name);
        data.put("mimeType", attachment.mimeType);
        data.put("url", attachment.url);
        data.put("size", attachment.size);
        data.put("timestamp", System.currentTimeMillis());

        return ONode.serialize(data);
    }

    private void pushToClient(String targetId, String messageId, String content, Map<String, Object> metadata) {
        // 实际实现中会通过 WebSocket 或 SSE 推送到客户端
        logger.info("Pushing to client {}: messageId={}, content={}", targetId, messageId, content);

        // 这里可以通过消息队列或直接推送到连接的客户端
        // 例如：WebSocketHandler.broadcast(targetId, messageId, content);
    }

    private void updateClient(String targetId, String messageId, String newContent) {
        // 实际实现中会发送更新消息到客户端
        logger.info("Updating client {}: messageId={}, newContent={}", targetId, messageId, newContent);
    }
}