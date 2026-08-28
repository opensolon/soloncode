package org.noear.solon.codecli.portal.web;

import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Filter;
import org.noear.solon.core.handle.FilterChain;
import org.noear.solon.codecli.workspace.WorkspaceManager;
import org.noear.solon.codecli.workspace.WorkspaceContext;
import org.noear.solon.core.util.Assert;

import org.noear.solon.codecli.workspace.WorkspaceLogRouter;

/**
 * 工作区请求拦截器：只信任 X-Workspace-Id 请求头进行多工作区路由。
 *
 * <p>注意：query 参数中出现的 workspaceId/workspace 在 filer/git 接口里表示的是
 * 挂载点别名（mount），与多工作区 ID 是两套语义，此处绝不从 query 读取，
 * 避免挂载别名被误当成工作区路径处理。</p>
 *
 * <p>若 Header 携带的 ws-xxx ID 在历史记录中不存在，返回 404，
 * 引导前端回退到默认工作区（根路由 /）。</p>
 *
 * @author noear
 */
@Component(index = -90)
public class WorkspaceFilter implements Filter {
    @Inject
    WorkspaceManager workspaceManager;

    @Override
    public void doFilter(Context ctx, FilterChain chain) throws Throwable {
        if (ctx.path().startsWith("/web/")) {
            String wsId = ctx.header("X-Workspace-Id");
            // 防御：若多处代码重复 setRequestHeader，浏览器会合并为 "id, id"，只取第一个合法片段
            if (wsId != null && wsId.contains(",")) {
                wsId = wsId.split(",")[0].trim();
            }

            if (Assert.isEmpty(wsId)) {
                String q = ctx.param("workspaceId");
                if (q != null && workspaceManager.isValidWorkspaceId(q)) {
                    wsId = q;
                }
            }

            if (Assert.isNotEmpty(wsId) && !workspaceManager.isValidWorkspaceId(wsId)) {
                // 场景 C：失效的工作区 ID，返回 404 引导前端回默认工作区
                ctx.status(404);
                ctx.contentType("application/json;charset=utf-8");
                ctx.output("{\"code\":1,\"description\":\"WORKSPACE_NOT_FOUND\",\"redirect\":\"/\"}");
                return;
            }

            WorkspaceContext wctx = workspaceManager.getOrCreate(wsId);
            if (wctx == null) {
                // getOrCreate 收紧语义后可能返回 null（目录不存在/非法 ID/加载失败），
                // 同样返回 404 引导前端回默认工作区，避免后续控制器 NPE 或静默路由到错误工作区
                ctx.status(404);
                ctx.contentType("application/json;charset=utf-8");
                ctx.output("{\"code\":1,\"description\":\"WORKSPACE_NOT_FOUND\",\"redirect\":\"/\"}");
                return;
            }

            ctx.attrSet("WORKSPACE_CTX", wctx);

            //按工作区分流日志：入口打标（MDC + 继承式标记），请求处理中新建的线程也能带上归属
            Object logScope = WorkspaceLogRouter.beginScope(wctx.getMeta().getPath());
            try {
                chain.doFilter(ctx);
            } finally {
                //恢复而非简单 remove：避免吞掉上游（若存在）设置的其它值
                WorkspaceLogRouter.endScope(logScope);
            }
            return;
        }

        chain.doFilter(ctx);
    }
}