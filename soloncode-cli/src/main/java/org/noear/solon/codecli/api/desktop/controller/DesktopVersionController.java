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
package org.noear.solon.codecli.api.desktop.controller;

import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.talents.mount.Mount;
import org.noear.solon.annotation.Get;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Param;
import org.noear.solon.annotation.Post;
import org.noear.solon.codecli.command.builtin.LoopScheduler;
import org.noear.solon.codecli.config.AgentFlags;
import org.noear.solon.codecli.session.SessionManager;
import org.noear.solon.core.handle.Result;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Desktop 版本与挂载池 Controller。
 *
 * <p>承载桌面端启动握手（version：进程 PID / 版本 / 工作区路径）与
 * Skill/Agent 挂载池的即时刷新。原 WsController 中的 version / mountsRefresh
 * 随领域拆分迁入，路由与返回结构逐字保留。</p>
 *
 * @author bai
 */
public class DesktopVersionController extends AbstractDesktopController {
    private static final Logger LOG = LoggerFactory.getLogger(DesktopVersionController.class);

    public DesktopVersionController(HarnessEngine engine, LoopScheduler loopScheduler,
                                    SessionManager sessionManager) {
        super(engine, loopScheduler, sessionManager);
    }

    /**
     * 获取消息详细记录信息
     */
    @Get
    @Mapping("/desktop/version")
    public Result<Map> version() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", AgentFlags.getVersion());
        data.put("workspace", engine.getWorkspace());
        data.put("pid", currentProcessId());
        data.put("desktopManaged", "1".equals(System.getenv("SOLONCODE_DESKTOP_MANAGED")));
        return Result.succeed(data);
    }

    private long currentProcessId() {
        try {
            String runtimeName = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            return Long.parseLong(runtimeName.split("@", 2)[0]);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /**
     * 重新扫描桌面端指定的挂载池，使新创建的 Skill/Agent 立即进入运行时。
     */
    @Post
    @Mapping("/desktop/settings/mounts/refresh")
    public Result mountsRefresh(@Param("alias") String alias) {
        if (Assert.isEmpty(alias)) {
            return Result.failure("alias is required");
        }

        Mount mountDir = engine.getMount(alias);
        if (mountDir == null) {
            return Result.failure("挂载池不存在: " + alias);
        }
        if (!mountDir.isEnabled()) {
            return Result.failure("挂载池未启用: " + alias);
        }

        try {
            engine.refreshMount(alias);
            return Result.succeed("刷新成功");
        } catch (Exception e) {
            LOG.warn("[Desktop] Failed to refresh mount {}: {}", alias, e.getMessage());
            return Result.failure("刷新挂载池失败");
        }
    }
}
