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
import org.noear.solon.codecli.command.builtin.LoopScheduler;
import org.noear.solon.codecli.session.SessionManager;

/**
 * Desktop 领域 Controller 共享基类。
 *
 * <p>原 WsController（790 行）按领域拆分后，各子 Controller 复用同一组桌面端
 * 运行时依赖：HarnessEngine / LoopScheduler / SessionManager。Desktop 模式为
 * 单引擎进程（无多工作区路由语义），依赖直接随构造器注入，收敛到本基类统一
 * 持有，避免每个领域 Controller 重复声明与赋值。</p>
 *
 * <p>子类只做参数解析、安全校验和结果转发；会话忙态等判断委派给 WsGate。</p>
 *
 * @see org.noear.solon.codecli.api.desktop.WsGate Desktop WebSocket 流处理网关
 */
public abstract class AbstractDesktopController {

    /**
     * 桌面端唯一引擎实例。
     */
    protected final HarnessEngine engine;

    /**
     * 循环/Goal 调度器；Desktop 未启用 automation 时可能为 null。
     */
    protected final LoopScheduler loopScheduler;

    /**
     * 会话管理器：负责桌面会话的内存态与落盘恢复。
     */
    protected final SessionManager sessionManager;

    protected AbstractDesktopController(HarnessEngine engine, LoopScheduler loopScheduler,
                                        SessionManager sessionManager) {
        this.engine = engine;
        this.loopScheduler = loopScheduler;
        this.sessionManager = sessionManager;
    }
}
