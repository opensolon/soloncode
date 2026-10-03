package org.noear.solon.codecli.workspace;

/**
 * 工作区查询端口。
 *
 * <p>Web、Desktop 等入口只需要按标识获取工作区，不应依赖工作区生命周期管理器的具体实现。</p>
 */
public interface WorkspaceRegistry {
    /**
     * 获取或创建工作区。
     */
    WorkspaceContext getOrCreate(String workspaceIdOrPath);

    /**
     * 只查询已缓存的工作区，不因查询重新创建或唤醒工作区。
     */
    WorkspaceContext getContextsCached(String workspaceIdOrPath);
}
