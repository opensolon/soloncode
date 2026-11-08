package org.noear.solon.codecli.api.web;

import org.noear.solon.codecli.workspace.WorkspaceContext;
import org.noear.solon.codecli.workspace.WorkspaceRegistry;

/**
 * 测试用工作区注册表：只认给定的工作区（按工作区 ID 查缓存，与生产口径一致：只查不建）。
 */
final class TestWorkspaces {
    private TestWorkspaces() {
    }

    static WorkspaceRegistry of(WorkspaceContext ctx) {
        return new WorkspaceRegistry() {
            @Override
            public WorkspaceContext getOrCreate(String workspaceIdOrPath) {
                return getContextsCached(workspaceIdOrPath);
            }

            @Override
            public WorkspaceContext getContextsCached(String workspaceIdOrPath) {
                return ctx != null && ctx.getMeta() != null && ctx.getMeta().getId().equals(workspaceIdOrPath) ? ctx : null;
            }
        };
    }
}
