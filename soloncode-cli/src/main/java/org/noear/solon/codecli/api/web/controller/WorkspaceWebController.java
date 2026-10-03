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

import org.noear.solon.annotation.Get;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Post;
import org.noear.solon.codecli.api.web.AbstractWebController;
import org.noear.solon.codecli.workspace.WorkspaceContext;
import org.noear.solon.codecli.workspace.WorkspaceManager;
import org.noear.solon.codecli.workspace.WorkspaceMeta;
import org.noear.solon.ai.talents.mount.Mount;
import org.noear.solon.ai.talents.mount.MountType;
import org.noear.solon.ai.talents.mount.source.FileMountSource;
import org.noear.solon.codecli.config.entity.MountDo;
import org.noear.solon.codecli.util.DirectoryPickerUtil;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 工作区管理 Controller（原 WebController 的 workspace 域）。
 *
 * <p>职责：工作区列表（含启动目录与文件挂载）、打开/移除工作区、宿主机目录选择对话框。</p>
 */
public class WorkspaceWebController extends AbstractWebController {
    private static final Logger LOG = LoggerFactory.getLogger(WorkspaceWebController.class);

    /**
     * 目录选择对话框并发锁：同一时刻只允许一个原生对话框（避免多个请求叠加弹框）
     */
    private static final AtomicBoolean PICK_DIR_LOCK = new AtomicBoolean(false);

    public WorkspaceWebController(WorkspaceManager workspaceManager) {
        super(workspaceManager);
    }

    @Get
    @Mapping("/web/workspace/list")
    public Result<Map<String, Object>> listWorkspaces() {
        Map<String, Object> data = new LinkedHashMap<>();

        // 1. 启动目录（默认工作区，虚拟条目：随 user.dir 变化，不落 workspaces.json）
        try {
            WorkspaceContext defCtx = workspaceManager.getOrCreate(null);
            if (defCtx != null && defCtx.getMeta() != null) {
                WorkspaceMeta dm = defCtx.getMeta();
                Map<String, Object> launch = new LinkedHashMap<>();
                launch.put("id", dm.getId());
                launch.put("name", dm.getName());
                launch.put("path", dm.getPath());
                data.put("launch", launch);
            }
        } catch (Exception e) {
            LOG.warn("[Workspace] Failed to resolve launch workspace", e);
        }

        // 2. 最近的工作区（历史列表，不含 default）
        data.put("workspaces", workspaceManager.listWorkspaces());

        // 3. 文件挂载（仅启用的 FILES 类型，每项：别名 + realPath）
        List<Map<String, Object>> mounts = new ArrayList<>();
        try {
            for (Mount entry : engine().getMounts()) {
                if (entry.getType() != MountType.FILES || !(entry.getSource() instanceof FileMountSource)) continue;
                if (!entry.isEnabled()) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("alias", entry.getAlias());
                Path root = ((FileMountSource) entry.getSource()).getRootPath();
                MountDo configured = currentContext().getSettings().getMountPools().get(entry.getAlias());
                item.put("path", configured != null && configured.getPath() != null
                        ? configured.getPath() : (root != null ? root.toString() : ""));
                item.put("realPath", root != null ? root.toString() : "");
                mounts.add(item);
            }
        } catch (Exception e) {
            LOG.warn("[Workspace] Failed to collect file mounts", e);
        }
        data.put("mounts", mounts);

        return Result.succeed(data);
    }

    @Post
    @Mapping("/web/workspace/open")
    public Result<WorkspaceMeta> openWorkspace(String path) {
        if (path == null || path.isEmpty()) {
            return Result.failure("Path is required");
        }

        // 挂载别名转真实路径：@alias/sub → 解析到挂载 realPath，后续走工作区同等链路（打开过即落历史）
        if (path.startsWith("@")) {
            try {
                int slash = path.indexOf('/');
                String alias = slash < 0 ? path : path.substring(0, slash);
                Mount mount = engine().getMount(alias);
                if (mount == null || mount.getType() != MountType.FILES || !(mount.getSource() instanceof FileMountSource)) {
                    return Result.failure("挂载不存在或不是本地文件挂载: " + alias);
                }
                Path realBase = ((FileMountSource) mount.getSource()).getRootPath();
                Path real = slash < 0 ? realBase : realBase.resolve(path.substring(slash + 1));
                real = real.toAbsolutePath().normalize();
                // 防越权：解析后路径必须仍在挂载目录下
                if (!real.startsWith(realBase.toAbsolutePath().normalize())) {
                    return Result.failure("非法路径: " + path);
                }
                path = real.toString();
            } catch (Exception e) {
                return Result.failure("挂载路径解析失败: " + e.getMessage());
            }
        }

        try {
            WorkspaceContext wctx = workspaceManager.getOrCreate(path);
            if (wctx != null) {
                return Result.succeed(wctx.getMeta());
            }
            // getOrCreate 已收紧：目录不存在/非法路径返回 null
            return Result.failure("目录不存在: " + path);
        } catch (Exception e) {
            return Result.failure(e.getMessage());
        }
    }

    /**
     * 目录选择能力探测（无副作用）：返回当前环境能否弹原生目录框。
     *
     * <p>前端初始化时探测一次，据此决定按钮可用态；真正的弹框由 POST /web/workspace/pick-directory 触发。</p>
     */
    @Get
    @Mapping("/web/workspace/pick-directory")
    public Result<Map<String, Object>> pickDirectoryCapability() {
        Map<String, Object> data = new LinkedHashMap<>();
        boolean available = DirectoryPickerUtil.isAvailable() && isLoopbackRequest(Context.current());
        data.put("available", available);
        data.put("headless", !available);
        return Result.succeed(data);
    }

    /**
     * 调起宿主机的系统目录选择框，返回用户选中的绝对路径。
     *
     * <p>soloncode web 运行在用户桌面，CLI 进程可直接弹原生对话框，从根本上绕开浏览器
     * “拿不到本地绝对路径”的安全限制；返回的路径由前端回填后走既有 /web/workspace/open 链路。</p>
     *
     * <h3>安全约束</h3>
     * <ul>
     *   <li><b>仅限本机调用</b>：非 loopback 来源直接拒绝（弹框出现在服务器屏幕而非访问者屏幕，毫无意义且危险）</li>
     *   <li><b>单对话框并发锁</b>：第二个请求直接拒绝，避免叠加弹框</li>
     *   <li><b>headless 拒绝</b>：无桌面环境时静默降级，前端隐藏目录选择入口</li>
     *   <li><b>超时自动关闭</b>：见 {@link DirectoryPickerUtil#DEFAULT_TIMEOUT_MS}</li>
     * </ul>
     *
     * @param ctx Solon 请求上下文（用于来源 IP 校验）
     * @return 选中结果：{path}；用户取消时 path 为 null（code=200）
     */
    @Post
    @Mapping("/web/workspace/pick-directory")
    public Result<Map<String, Object>> pickDirectory(Context ctx) {
        // 1. 仅限本机：非 loopback 来源拒绝（服务未做 host 绑定且开了跨域，弹框不能出现在陌生访问者的请求里）
        if (isLoopbackRequest(ctx) == false) {
            return Result.failure("仅限本机调用（loopback）");
        }

        // 2. 无交互桌面时静默降级，前端隐藏入口并继续支持手工输入
        if (DirectoryPickerUtil.isAvailable() == false) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("path", null);
            data.put("headless", true);
            data.put("available", false);
            return Result.succeed(data);
        }

        // 3. 并发锁：同一时刻只允许一个对话框
        if (PICK_DIR_LOCK.compareAndSet(false, true) == false) {
            return Result.failure("目录选择框已打开，请先完成或取消当前选择");
        }

        try {
            String path = DirectoryPickerUtil.pick("选择工作区目录");

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("path", path);
            data.put("headless", false);
            data.put("available", true);
            return Result.succeed(data);
        } catch (Exception e) {
            // 显示可能在启动后消失（容器、SSH 转发、桌面注销等）。这属于能力降级，
            // 不向前端抛系统错误；记录日志并让前端隐藏入口、继续支持手工输入路径。
            LOG.warn("[Workspace] Directory picker unavailable", e);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("path", null);
            data.put("headless", true);
            data.put("available", false);
            return Result.succeed(data);
        } finally {
            PICK_DIR_LOCK.set(false);
        }
    }

    /**
     * 判断请求是否来自本机（loopback）
     */
    private boolean isLoopbackRequest(Context ctx) {
        try {
            String ip = ctx.realIp();
            if (ip == null) {
                return false;
            }
            return "127.0.0.1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip) || "localhost".equals(ip);
        } catch (Exception e) {
            return false;
        }
    }

    @Post
    @Mapping("/web/workspace/remove")
    public Result<Void> removeWorkspace(String id) {
        if (id == null || id.trim().isEmpty()) {
            return Result.failure("Id is required");
        }

        // 只有历史文件确实完成持久化删除后才报告成功，避免前端刷新后条目又出现。
        if (workspaceManager.removeFromHistory(id.trim()) == false) {
            return Result.failure("Workspace not found or could not be removed");
        }
        return Result.succeed();
    }

    @Get
    @Mapping("/web/workspace/current")
    public Result<WorkspaceMeta> currentWorkspace() {
        WorkspaceContext wctx = currentContext();
        if (wctx != null) {
            return Result.succeed(wctx.getMeta());
        }
        return Result.failure("No current workspace");
    }
}
