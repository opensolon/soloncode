package org.noear.solon.codecli.workspace;

import org.noear.solon.ai.harness.HarnessEngine;

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
     * 工作区初始化完成后恢复持久化的 Session 队列。
     */
    void recoverSessionQueues(WorkspaceContext workspaceContext);

    /**
     * 判断指定引擎中的 Session 是否正在运行任务。
     */
    boolean isSessionBusy(HarnessEngine engine, String sessionId);

    /**
     * 执行 Loop 专用输入并等待本轮文本结果。
     */
    String runLoop(String workspaceId, String sessionId, String input, String source);
}
