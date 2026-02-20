package org.noear.solon.ai.codecli.portal;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActChunk;
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.ai.agent.react.intercept.HITLTask;
import org.noear.solon.ai.agent.react.task.ActionChunk;
import org.noear.solon.ai.agent.react.task.ReasonChunk;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.codecli.core.CodeAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bot 门户网关 (OpenClaw/Moltbot 对齐版)
 */
public class BotGate {
    private static final Logger log = LoggerFactory.getLogger(BotGate.class);

    private final CodeAgent codeAgent;
    private final Map<String, BotChannel> channels = new ConcurrentHashMap<>();

    // 关键：维护 Session 级别的 HITL 任务状态
    private final Map<String, HITLTask> pendingHitlTasks = new ConcurrentHashMap<>();

    public BotGate(CodeAgent codeAgent) {
        this.codeAgent = codeAgent;
    }

    /**
     * 注册通道 (如 TelegramChannel, MoltbookChannel)
     */
    public BotGate register(BotChannel channel) {
        channels.put(channel.getChannelId(), channel);
        return this;
    }

    /**
     * 统一接收消息入口 (Webhook 或 Bot 监听器调用)
     */
    public void onIncomingMessage(String channelId, String senderId, String message, Map<String, Object> metadata) {
        BotChannel channel = channels.get(channelId);
        if (channel == null) return;

        String sessionId = channelId + ":" + senderId;

        // 1. 拦截并处理审批指令 (y/n)
        if (handleHitlApproval(channel, senderId, sessionId, message, metadata)) {
            return;
        }

        // 2. 正常任务分发
        processTask(channel, senderId, sessionId, message, metadata);
    }

    /**
     * 任务流转处理
     */
    private void processTask(BotChannel channel, String senderId, String sessionId, String input, Map<String, Object> metadata) {
        AgentSession session = codeAgent.getSession(sessionId);

        codeAgent.stream(sessionId, Prompt.of(input))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(chunk -> {
                    if (chunk instanceof ReasonChunk) {
                        // 【对齐核心 1】IM 端不推送碎片化的思考过程，直接静默或记录日志
                        // 除非是特定的提示语，否则 ReasonChunk 在 IM 中不作为消息发送
                    }
                    else if (chunk instanceof ActionChunk) {
                        // 【对齐核心 2】动作阶段只推送极简摘要
                        ActionChunk action = (ActionChunk) chunk;
                        String actionSummary = String.format("⚙️ [%s] %s",
                                action.getToolName(),
                                simplifyArgs(action.getArgs()));
                        channel.pushText(senderId, actionSummary, metadata);
                    }
                    else if (chunk instanceof ReActChunk) {
                        // 【对齐核心 3】最终结果全量输出
                        channel.push(senderId, (ReActChunk)chunk, metadata);
                    }
                })
                .doOnError(e -> channel.pushText(senderId, "❌ System Error: " + e.getMessage(), metadata))
                .doOnComplete(() -> {
                    // 检查是否进入等待授权状态
                    if (HITL.isHitl(session)) {
                        requestHitl(channel, senderId, session, metadata);
                    }
                })
                .subscribe();
    }

    /**
     * 参数简化 (避免 IM 屏占比过大)
     */
    private String simplifyArgs(Map<String, Object> args) {
        if (args == null || args.isEmpty()) return "";
        String s = args.toString();
        return s.length() > 60 ? s.substring(0, 57) + "..." : s;
    }

    /**
     * 处理 HITL 审批逻辑
     */
    private boolean handleHitlApproval(BotChannel channel, String senderId, String sessionId, String text, Map<String, Object> metadata) {
        HITLTask task = pendingHitlTasks.get(sessionId);
        if (task == null) return false;

        String cmd = text.trim().toLowerCase();
        AgentSession session = codeAgent.getSession(sessionId);

        if ("y".equals(cmd) || "yes".equals(cmd)) {
            pendingHitlTasks.remove(sessionId);
            HITL.approve(session, task.getToolName());
            channel.pushText(senderId, "✅ Approved. Proceeding...", metadata);
            // 重新触发 Agent (空输入代表继续执行流)
            processTask(channel, senderId, sessionId, "", metadata);
            return true;
        } else if ("n".equals(cmd) || "no".equals(cmd)) {
            pendingHitlTasks.remove(sessionId);
            HITL.reject(session, task.getToolName());
            channel.pushText(senderId, "🚫 Action rejected by user.", metadata);
            return true;
        }
        return false;
    }

    /**
     * 发起远程审批请求
     */
    private void requestHitl(BotChannel channel, String senderId, AgentSession session, Map<String, Object> metadata) {
        HITLTask task = HITL.getPendingTask(session);
        pendingHitlTasks.put(session.getSessionId(), task);

        StringBuilder sb = new StringBuilder();
        sb.append("⚠️ **Permission Required**\n");
        sb.append("Tool: `").append(task.getToolName()).append("`\n");
        if (task.getArgs().containsKey("command")) {
            sb.append("Cmd: `").append(task.getArgs().get("command")).append("`\n");
        }
        sb.append("\nReply [y] to Approve, [n] to Reject.");

        channel.pushText(senderId, sb.toString(), metadata);
    }

    private String clearThink(String content) {
        return content == null ? "" : content.replaceAll("(?s)<\\s*/?think\\s*>", "");
    }

    public void startAll() {
        channels.values().forEach(BotChannel::start);
    }
}