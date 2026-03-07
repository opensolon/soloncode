package org.noear.solon.bot.codecli.portal;

import org.noear.solon.ai.agent.react.ReActChunk;
import java.util.List;
import java.util.Map;

/**
 * 机器人通道接口
 * 100% 对齐 OpenClaw：支持消息推送、异步更新及元数据透传
 */
public interface BotChannel {
    String getChannelId();

    /**
     * 启动通道 (如建立 WebSocket, 登录账户)
     */
    void start();

    /**
     * 推送 AI 状态块
     * @return 返回消息 ID (用于后续更新状态)
     */
    String push(String targetId, ReActChunk chunk, Map<String, Object> metadata);

    /**
     * 更新已有消息 (实现"⚙️ 运行中"变为"✅ 已完成"的平滑过渡)
     */
    void update(String targetId, String messageId, String newContent);

    /**
     * 推送纯文本 (用于系统提示、报错或 HITL 请求)
     */
    void pushText(String targetId, String text, Map<String, Object> metadata);

    // ==================== 扩展能力 ====================

    /**
     * 推送富文本消息 (支持 Markdown、格式化等)
     */
    String pushRich(String targetId, RichMessage message, Map<String, Object> metadata);

    /**
     * 推送文件附件
     */
    String pushAttachment(String targetId, Attachment attachment, Map<String, Object> metadata);

    /**
     * 添加消息反应 (如 👍、👎、✅ 等)
     */
    void addReaction(String targetId, String messageId, String emoji);

    /**
     * 移除消息反应
     */
    void removeReaction(String targetId, String messageId, String emoji);

    /**
     * 发送打字状态
     */
    void sendTyping(String targetId, boolean isTyping);

    /**
     * 获取通道能力
     */
    ChannelCapabilities getCapabilities();

    /**
     * 验证用户是否有权限 (用于群组场景)
     */
    boolean hasPermission(String targetId, String userId, String permission);

    /**
     * 获取消息线程上下文 (用于回复场景)
     */
    ThreadContext getThreadContext(String targetId, String messageId);

    /**
     * 健康检查
     */
    HealthStatus healthCheck();

    /**
     * 停止通道
     */
    void stop();

    // ==================== 内部类定义 ====================

    /**
     * 富文本消息
     */
    class RichMessage {
        public String text;
        public String format; // markdown, html, plain
        public Map<String, Object> extra;

        public RichMessage(String text, String format) {
            this.text = text;
            this.format = format;
        }
    }

    /**
     * 文件附件
     */
    class Attachment {
        public String name;
        public String mimeType;
        public byte[] data;
        public String url;
        public long size;

        public Attachment(String name, String mimeType, byte[] data) {
            this.name = name;
            this.mimeType = mimeType;
            this.data = data;
            this.size = data != null ? data.length : 0;
        }

        public Attachment(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }

    /**
     * 通道能力
     */
    class ChannelCapabilities {
        public boolean supportsMarkdown = false;
        public boolean supportsAttachments = false;
        public boolean supportsReactions = false;
        public boolean supportsTyping = false;
        public boolean supportsThreads = false;
        public boolean supportsGroups = false;
        public int maxMessageLength = 4000;
        public int maxAttachmentSize = 10 * 1024 * 1024; // 10MB

        public ChannelCapabilities enableMarkdown() {
            this.supportsMarkdown = true;
            return this;
        }

        public ChannelCapabilities enableAttachments() {
            this.supportsAttachments = true;
            return this;
        }

        public ChannelCapabilities enableReactions() {
            this.supportsReactions = true;
            return this;
        }

        public ChannelCapabilities enableTyping() {
            this.supportsTyping = true;
            return this;
        }

        public ChannelCapabilities enableThreads() {
            this.supportsThreads = true;
            return this;
        }

        public ChannelCapabilities enableGroups() {
            this.supportsGroups = true;
            return this;
        }
    }

    /**
     * 线程上下文
     */
    class ThreadContext {
        public String threadId;
        public String parentMessageId;
        public boolean isGroup;
        public String groupId;

        public ThreadContext(String threadId, String parentMessageId) {
            this.threadId = threadId;
            this.parentMessageId = parentMessageId;
        }
    }

    /**
     * 健康状态
     */
    class HealthStatus {
        public boolean healthy;
        public String status;
        public long lastCheck;
        public Map<String, Object> metrics;

        public HealthStatus(boolean healthy, String status) {
            this.healthy = healthy;
            this.status = status;
            this.lastCheck = System.currentTimeMillis();
        }
    }
}