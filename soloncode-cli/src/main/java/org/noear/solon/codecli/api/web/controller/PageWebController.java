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
package org.noear.solon.codecli.api.web.controller;

import org.noear.solon.Solon;
import org.noear.solon.annotation.Get;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.codecli.api.web.AbstractWebController;
import org.noear.solon.codecli.config.AgentFlags;
import org.noear.solon.codecli.workspace.WorkspaceContext;
import org.noear.solon.codecli.workspace.WorkspaceManager;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Result;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 页面入口与应用元信息 Controller（原 WebController 的 page/meta 域）。
 *
 * <p>职责：首页/登录页/管理页转发、前端扩展脚本清单、应用元信息（标题、版本、
 * 工作区路径、默认模型配置状态等）。</p>
 */
public class PageWebController extends AbstractWebController {

    public PageWebController(WorkspaceManager workspaceManager) {
        super(workspaceManager);
    }

    /**
     * 首页入口：将根路径请求转发到静态页面 web.html。
     *
     * @param ctx Solon 请求上下文
     * @throws Throwable 转发异常
     */
    @Get
    @Mapping("/")
    public void index(Context ctx) throws Throwable {
        ctx.forward("/web.html");
    }

    /**
     * 管理员面板入口：将 /web/admin 请求转发到静态页面 admin.html。
     *
     * <p>准入控制（role=admin）由 {@code UserAuthFilter} 统一负责，本方法不做鉴权判断。</p>
     *
     * @param ctx Solon 请求上下文
     * @throws Throwable 转发异常
     */
    @Get
    @Mapping("/web/admin")
    public void admin(Context ctx) throws Throwable {
        ctx.forward("/admin.html");
    }

    /**
     * 登录页入口：将 /login 请求转发到静态页面 login-page.html。
     *
     * <p>登录页不再以 /login.html 直接暴露，统一通过 /login 访问，
     * 保证入口地址稳定、与静态文件名解耦。</p>
     *
     * @param ctx Solon 请求上下文
     * @throws Throwable 转发异常
     */
    @Get
    @Mapping("/login")
    public void login(Context ctx) throws Throwable {
        ctx.forward("/login-page.html");
    }

    /**
     * 前端脚本清单：返回所有已加载扩展登记的前端脚本 URL，前端据此动态注入。
     * 各扩展在自己的 Plugin.start() 中向系统属性 "soloncode.frontend.scripts" 追加自身脚本地址，
     * 核心对此无感知。
     *
     * @return 脚本 URL 列表
     */
    @Get
    @Mapping("/web/frontend/scripts")
    public Result<List<String>> frontendScripts() {
        String v = System.getProperty("soloncode.frontend.scripts", "");
        List<String> list = new ArrayList<>();
        if (!v.isEmpty()) {
            for (String s : v.split(",")) {
                s = s.trim();
                if (!s.isEmpty()) list.add(s);
            }
        }
        return Result.succeed(list);
    }

    @Get
    @Mapping("/web/chat/meta")
    public Result<Map> meta() {
        HarnessEngine currentEngine = engine();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("appTitle", Solon.cfg().appTitle());
        data.put("appVersion", AgentFlags.getVersion());
        //更新检查（启动时已异步预热，此处仅读缓存比较）
        if (AgentFlags.checkUpdate()) {
            data.put("updateAvailable", true);
            data.put("latestVersion", AgentFlags.getLastVersion());
        }
        data.put("workspace", currentEngine.getWorkspace());
        data.put("workname", getLastSegment(currentEngine.getWorkspace()));
        // 是否「在用户主目录下启动」的默认工作区：把整个 ~ 当工作区代价过高（沙盒范围、
        // 文件监听、检索都会铺满主目录），前端据此默认弹出工作区面板引导选项目目录。
        // 判定放后端（前端拿不到真实的 user.home，也不应自行猜路径）
        WorkspaceContext currentCtx = currentContext();
        boolean homeWorkspace = currentCtx != null
                && WorkspaceManager.isHomeStartupWorkspace(currentCtx.getMeta());
        data.put("isHomeWorkspace", homeWorkspace);
        // 是否已配置至少一个可用模型，供前端首帧渲染引导面板，避免界面闪现
        boolean modelConfigured = false;
        for (ChatConfig config : currentEngine.getModels()) {
            if (config.isEnabled()) {
                modelConfigured = true;
                break;
            }
        }
        data.put("modelConfigured", modelConfigured);
        return Result.succeed(data);
    }

    /**
     * 从文件路径中提取最后一段（即文件名或目录名）。
     *
     * @param pathStr 完整文件路径字符串
     * @return 路径最后一段，若路径为空则返回空字符串
     */
    private static String getLastSegment(String pathStr) {
        Path path = Paths.get(pathStr);
        Path fileName = path.getFileName();
        return fileName == null ? "" : fileName.toString();
    }
}
