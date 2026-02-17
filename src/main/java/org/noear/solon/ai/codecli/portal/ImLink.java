package org.noear.solon.ai.codecli.portal;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActChunk;
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.ai.agent.react.intercept.HITLTask;
import org.noear.solon.ai.agent.react.task.ActionChunk;
import org.noear.solon.ai.agent.react.task.PlanChunk;
import org.noear.solon.ai.agent.react.task.ReasonChunk;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.codecli.core.AgentNexus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IM 门户实现 (Moltbot 风格)
 * 能够处理异步消息推送和远程 HITL 授权
 */
public class ImLink {
    private static final Logger log = LoggerFactory.getLogger(ImLink.class);
    private final AgentNexus codeAgent;

    // 用于记录正在等待 HITL 响应的会话
    private final Map<String, HITLTask> pendingHitlTasks = new ConcurrentHashMap<>();

    public ImLink(AgentNexus codeAgent) {
        this.codeAgent = codeAgent;
    }

    /**
     * 当收到 IM 消息时调用 (例如来自 Webhook 或 Bot 监听器)
     */
    public void onReceive(String userId, String text, ImSender sender) {
        // 1. 尝试处理 HITL 审批指令 (y/n)
        if (handleHitlCommand(userId, text, sender)) {
            return;
        }

        // 2. 正常任务处理
        AgentSession session = codeAgent.getSession(userId);

        sender.send("🚀 Moltbot 开始处理任务...");

        codeAgent.stream(userId, Prompt.of(text))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(chunk -> {
                    // --- 规划阶段 ---
                    if (chunk instanceof PlanChunk) {
                        sender.send("📋 [规划]\n" + chunk.getContent());
                    }
                    // --- 思考阶段 (过滤掉工具调用的中间态) ---
                    else if (chunk instanceof ReasonChunk) {
                        ReasonChunk reason = (ReasonChunk) chunk;
                        if (!reason.isToolCalls() && chunk.hasContent()) {
                            sender.send("🤔 " + reason.getContent());
                        }
                    }
                    // --- 工具执行阶段 ---
                    else if (chunk instanceof ActionChunk) {
                        ActionChunk action = (ActionChunk) chunk;
                        // 模仿 Moltbot 的状态反馈
                        sender.send("⚙️ 正在调用 [" + action.getToolName() + "]...");
                    }
                    // --- 最终回复 ---
                    else if (chunk instanceof ReActChunk) {
                        sender.send("✅ 任务完成:\n" + chunk.getContent());
                    }
                })
                .doOnError(e -> sender.send("❌ 发生错误: " + e.getMessage()))
                .doOnComplete(() -> {
                    // 检查任务结束后是否进入了 HITL 等待状态
                    if (HITL.isHitl(session)) {
                        requestHitlApproval(userId, session, sender);
                    }
                })
                .subscribe();
    }

    /**
     * 处理远程审批指令
     */
    private boolean handleHitlCommand(String userId, String text, ImSender sender) {
        String cmd = text.trim().toLowerCase();
        if (!pendingHitlTasks.containsKey(userId)) return false;

        AgentSession session = codeAgent.getSession(userId);
        HITLTask task = pendingHitlTasks.remove(userId);

        if ("y".equals(cmd) || "yes".equals(cmd)) {
            sender.send("👍 已授权执行: " + task.getToolName());
            HITL.approve(session, task.getToolName());
            // 授权后，我们需要再次触发 Agent 继续后续动作 (传入空 Prompt 触发继续)
            onReceive(userId, "", sender);
            return true;
        } else if ("n".equals(cmd) || "no".equals(cmd)) {
            sender.send("🛑 已拒绝该操作。");
            HITL.reject(session, task.getToolName());
            return true;
        }
        return false;
    }

    /**
     * 发起远程审批请求
     */
    private void requestHitlApproval(String userId, AgentSession session, ImSender sender) {
        HITLTask task = HITL.getPendingTask(session);
        pendingHitlTasks.put(userId, task);

        StringBuilder sb = new StringBuilder();
        sb.append("⚠️ **需要授权**\n");
        sb.append("工具: ").append(task.getToolName()).append("\n");

        if (task.getArgs().containsKey("command")) {
            sb.append("命令: `").append(task.getArgs().get("command")).append("`\n");
        }

        sb.append("\n回复 [y] 批准，[n] 拒绝");
        sender.send(sb.toString());
    }

    /**
     * 适配器接口
     */
    @FunctionalInterface
    public interface ImSender {
        void send(String message);
    }
}