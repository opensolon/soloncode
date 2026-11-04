/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.codecli.session;

/**
 * 宿主侧会话属性私有键（唯一登记处）。
 *
 * <p>命名空间规约见 {@code org.noear.solon.ai.harness.session.HarnessAttrs}：
 * {@code session.*} 归引擎契约，宿主自有键一律使用 {@code cli.*} / {@code im.*} / {@code _} 前缀。
 * 此前 {@code session.replyRoute}、{@code session.queue.executing}、{@code session.input.admitting}
 * 等宿主私货占用了引擎命名空间，升级时无法区分契约与私有键，故在此集中并改正前缀。</p>
 *
 * <p><b>为何放在 {@code codecli.session}</b>：这些键被 {@code api.web} 与 {@code artifact}
 * 等多个包共用。若登记在 {@code api.web}，则 {@code artifact} 必须反向依赖 {@code api.web}
 * ——用一条包间反向依赖换掉一个字符串字面量，得不偿失（已由
 * {@code PackageBoundaryTest#artifactMustNotDependOnApiWeb} 锁定）。
 * {@code codecli.session} 是宿主会话状态的中立归属，各包单向依赖它即可。</p>
 */
public final class HostAttrs {
    private HostAttrs() {
    }

    /** IM 定向回复路由（来源端 map），跨轮保留至下一次输入受理时重置 */
    public static final String ATTR_REPLY_ROUTE = "cli.replyRoute";

    /** 长任务心跳定时器句柄（终态时取消） */
    public static final String ATTR_IM_HEARTBEAT = "im.heartbeat";

    /** 本轮忙态命令引导是否已提示过（同一轮只教学一次） */
    public static final String ATTR_IM_CMD_HINT_SHOWN = "im.cmdHint.shown";

    /**
     * 本轮 IM 终态是否已投递（去重门）。
     *
     * <p>置为 {@code true} 后同一轮不再发送终态回执，防止「取消终态 + 流终态」双发。
     * 定位于此是因为 {@code artifact.ImageReplyTurn} 与 {@code api.web.WebStreamBuilder}
     * 都要读写它。</p>
     */
    public static final String ATTR_IM_TERMINAL_SENT = "im.terminal.sent";
}
