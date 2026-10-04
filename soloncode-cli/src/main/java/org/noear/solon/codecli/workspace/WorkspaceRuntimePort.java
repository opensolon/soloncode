package org.noear.solon.codecli.workspace;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.core.handle.UploadedFile;

/**
 * 工作区运行时端口。
 *
 * <p>该端口承接 Web/Desktop 等入口对工作区运行时的适配能力：文件变化广播、
 * Session 队列恢复、Loop 执行和会话繁忙判断。工作区管理器只依赖这些能力，
 * 不持有具体的 WebGate 或其它协议网关。</p>
 */
public interface WorkspaceRuntimePort extends WorkspaceMessageGateway {
    /**
     * 向指定工作区广播原始协议消息。
     */
    void broadcastRaw(String workspaceId, String json);

    /**
     * 向指定工作区的全部在线连接广播原始协议消息（文件变化等系统级事件）。
     */
    void broadcastRaw(WorkspaceContext workspaceContext, String json);

    /**
     * 工作区初始化完成后恢复持久化的 Session 队列。
     */
    void recoverSessionQueues(WorkspaceContext workspaceContext);

    /**
     * 从队头领取队列任务并派发给输入处理（队列 HTTP 接口取消/确认后触发）。
     */
    void drainSessionQueue(WorkspaceContext workspaceContext, AgentSession session);

    /**
     * 判断指定引擎中的 Session 是否正在运行任务。
     */
    boolean isSessionBusy(HarnessEngine engine, String sessionId);

    /**
     * 中断指定会话的当前 AI 任务。
     *
     * @return true 表示已发起中断；false 表示会话不存在或没有可中断任务
     */
    boolean interruptSession(WorkspaceContext workspaceContext, String sessionId);

    /**
     * 用户聊天输入入口（由 ChatWebController 等 HTTP 接口调用）。
     */
    void onChatInput(WorkspaceContext workspaceContext,
                     String sessionId, String sessionCwd,
                     String input, String selectedModel,
                     UploadedFile[] attachments, String[] attachmentTypes,
                     String hitlAction, String source,
                     String reasoningEffort, String thinkingMode, String selectedAgent);

    /**
     * 执行 Loop 专用输入并等待本轮文本结果。
     */
    String runLoop(String workspaceId, String sessionId, String input, String source);
}
