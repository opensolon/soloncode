package org.noear.solon.codecli.workspace;

/**
 * 工作区消息入口端口。
 *
 * <p>Channel 只需要把外部消息提交给当前工作区，并在必要时发送一条终止通知，
 * 不应依赖具体的 WebGate 或 WebSocket 实现。</p>
 */
public interface WorkspaceMessageGateway {
    /**
     * 接受一条来自外部通道的输入。
     *
     * @return true 表示输入已进入处理流程；false 表示当前不能接受
     */
    boolean acceptInput(WorkspaceContext workspaceContext,
                        String sessionId,
                        String input,
                        String source,
                        String sourceUserId,
                        String replyTarget,
                        String messageId);

    /**
     * 向客户端发送错误并结束本次通知。
     */
    void emitErrorAndDone(WorkspaceContext workspaceContext, String sessionId, String message);
}
