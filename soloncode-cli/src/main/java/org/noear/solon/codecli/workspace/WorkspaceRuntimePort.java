package org.noear.solon.codecli.workspace;

import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.host.SessionAdmin;
import org.noear.solon.core.handle.UploadedFile;

import java.util.Locale;

/**
 * 工作区运行时端口。
 *
 * <p>该端口承接 Web/Desktop 等入口对工作区运行时的适配能力：文件变化广播、
 * Session 恢复、会话管理态与会话繁忙判断。工作区管理器只依赖这些能力，
 * 不持有具体的 WebGate 或其它协议网关。goal / loop 的后台轮次由入口内部的宿主骨架
 * （HarnessHost）在首次取到引擎时自行登记执行器，不再经本端口转发。</p>
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
     * 工作区初始化完成后：登记后台轮次执行器，并恢复持久化的 goal 调度与 Session 队列。
     */
    void onWorkspaceReady(WorkspaceContext workspaceContext);

    /**
     * 会话管理态（队列等管理接口）：对扩展门面的操作统一返回结果码，成功后自动续发积压。
     */
    SessionAdmin sessionAdmin(WorkspaceContext workspaceContext);

    /**
     * 会话是否有运行流（含受理窗口，不含待审批）：插话、界面「运行中」展示用。
     */
    boolean isSessionBusy(HarnessEngine engine, String sessionId);

    /**
     * 会话是否「占着」：有运行流、受理中或有待审批卡。删除、回退等会破坏会话状态的操作用它判断
     * （与桌面端、HarnessHost#closeSession 同一口径；等审批的会话被回退会把挂起的 trace 写坏）。
     */
    boolean isSessionOccupied(HarnessEngine engine, String sessionId);

    /**
     * 中断指定会话的当前 AI 任务。
     *
     * @return true 表示已发起中断；false 表示会话不存在或没有可中断任务
     */
    boolean interruptSession(WorkspaceContext workspaceContext, String sessionId);

    /**
     * 用户聊天输入入口（由 ChatWebController 等 HTTP 接口调用）。
     *
     * @param locale 本轮对话的语言（Web 入口由前端传入当前界面语言）；null 时用引擎默认语言。
     *               审批与续流不是新的一轮，忽略该参数（沿用原来那一轮的语言）
     */
    void onChatInput(WorkspaceContext workspaceContext,
                     String sessionId, String sessionCwd,
                     String input, String selectedModel,
                     UploadedFile[] attachments, String[] attachmentTypes,
                     String hitlAction, String source,
                     String reasoningEffort, String thinkingMode, String selectedAgent,
                     Locale locale);
}
