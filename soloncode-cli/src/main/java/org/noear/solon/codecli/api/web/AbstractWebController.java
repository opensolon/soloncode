/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.codecli.api.web;

import org.noear.solon.codecli.loop.AutomationManager;
import org.noear.solon.codecli.session.SessionManager;
import org.noear.solon.codecli.workspace.WorkspaceContext;
import org.noear.solon.codecli.workspace.WorkspaceManager;
import org.noear.solon.codecli.workspace.WorkspaceRuntimePort;
import org.noear.solon.codecli.workspace.filer.FileService;
import org.noear.solon.codecli.workspace.git.GitService;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.codecli.loop.LoopScheduler;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Web 领域 Controller 共享基类。
 *
 * <p>原 WebController（3000+ 行、70 路由）按领域拆分后，各子 Controller 复用同一组
 * 工作区访问辅助：currentContext / engine / runtimePort / loopScheduler 等。这些辅助
 * 全部依赖「当前请求工作区」语义（多工作区下按请求路由到对应上下文），因此收敛到
 * 本基类统一提供，避免每个领域 Controller 重复持有 WorkspaceManager 并复制取数逻辑。</p>
 *
 * <p>子类只做参数解析、安全校验和结果转发；业务委派给运行时端口 / 领域服务。</p>
 *
 * @see WorkspaceRuntimePort 入口运行时端口（Web/Desktop 等入口的适配能力）
 * @see WorkspaceManager 工作区管理器
 */
public abstract class AbstractWebController {

    /**
     * 工作区管理器：按需创建并缓存各物理工作区的上下文。
     */
    protected final WorkspaceManager workspaceManager;

    protected AbstractWebController(WorkspaceManager workspaceManager) {
        this.workspaceManager = workspaceManager;
    }

    /**
     * 当前请求对应的 WorkspaceContext（由各工作区过滤链/上下文切换器保证）。
     */
    protected WorkspaceContext currentContext() {
        return workspaceManager.currentContext();
    }

    protected HarnessEngine engine() {
        return currentContext().getEngine();
    }

    /**
     * 当前工作区的入口运行时端口（输入受理、会话繁忙判断、中断、原始广播等）。
     *
     * <p>正式 Web 模式下由 WebGate 实现；headless 等无入口模式返回 null。</p>
     */
    protected WorkspaceRuntimePort runtimePort() {
        return currentContext().getRuntimePort();
    }

    protected LoopScheduler loopScheduler() {
        return currentContext().getLoopScheduler();
    }

    protected AutomationManager automationManager() {
        return currentContext().getAutomationManager();
    }

    protected SessionManager sessionManager() {
        return currentContext().getSessionManager();
    }

    protected FileService fileService() {
        return currentContext().getFileService();
    }

    protected GitService gitService() {
        return currentContext().getGitService();
    }

    /**
     * 校验 web 会话 ID 格式（白名单方式，防止路径遍历攻击）。
     *
     * <p>Web 会话以 web 开头；桌面端的持久化会话使用正整数主键；
     * 自动任务专用会话以 auto 开头（UUID 十六进制后缀）。三类均使用严格白名单，
     * 保证后续 resolve 后不会出现路径穿越。</p>
     *
     * @param sessionId 会话 ID
     * @return true 表示合法
     */
    protected static boolean isValidSessionId(String sessionId) {
        return WebSessionIds.isValid(sessionId);
    }

    /**
     * 获取当前用户 ID。
     * 用户认证启用时返回 userId，否则返回 null（使用传统非隔离路径）。
     * 优先从上下文属性获取，若未设置则尝试从 token 中提取。
     */
    protected String getCurrentUserId() {
        // 认证与对话隔离是两个独立开关；关闭隔离时保持所有已登录用户共享对话。
        org.noear.solon.codecli.auth.UserAuthConfig authConfig = currentContext().getSettings().getUserAuth();
        if (authConfig == null || !authConfig.isEnabled() || !authConfig.isConversationIsolationEnabled()) {
            return null;
        }
        org.noear.solon.core.handle.Context ctx = org.noear.solon.core.handle.Context.current();
        if (ctx != null) {
            // 优先从 UserAuthFilter 设置的上下文属性获取
            String userId = ctx.attr("user_id");
            if (userId != null) {
                return userId;
            }
            // 回退：从 token 中提取 userId（用于 UserAuthFilter 未设置属性但有有效 token 的场景）
            try {
                String token = org.noear.solon.codecli.auth.UserLoginController.extractToken(ctx);
                if (token != null) {
                    org.noear.solon.codecli.auth.UserSessionManager sessionMgr =
                            org.noear.solon.Solon.context().getBean(org.noear.solon.codecli.auth.UserSessionManager.class);
                    if (sessionMgr != null) {
                        org.noear.solon.codecli.auth.UserSessionManager.UserSession session = sessionMgr.getSession(token);
                        if (session != null) {
                            return session.getUserId();
                        }
                    }
                }
            } catch (Exception e) {
                // 忽略异常，回退返回 null
            }
        }
        return null;
    }

    /** 认证开启时，会话必须明确归属于当前用户；无 owner 的旧会话不再默认向普通用户暴露。 */
    protected boolean ownsSession(Path sessionPath) {
        String userId = getCurrentUserId();
        if (userId == null) return true;
        if (sessionPath == null || !Files.isDirectory(sessionPath)) return false;
        String ownerId = org.noear.solon.codecli.session.SessionMeta.load(sessionPath.toFile()).getOwnerUserId();
        return ownerId != null && !ownerId.isEmpty() && userId.equals(ownerId);
    }

    /** 输入入口：隔离开启时必须识别用户，已有会话必须属于该用户。 */
    protected boolean canWriteSession(String sessionId) {
        org.noear.solon.codecli.auth.UserAuthConfig auth = currentContext().getSettings().getUserAuth();
        if (auth == null || !auth.isEnabled() || !auth.isConversationIsolationEnabled()) return true;
        String userId = getCurrentUserId();
        if (userId == null || userId.isEmpty()) return false;
        Path path = currentContext().getSessionPath(sessionId);
        return !Files.exists(path) || ownsSession(path);
    }
}
