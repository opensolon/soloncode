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
package org.noear.solon.codecli.api.desktop;

/**
 * 桌面端运行模式的纯函数规则集。
 *
 * <p>从原 {@code WsGate} 中拆出：模式归一化与“是否需要桌面审批”的判断
 * 不持有任何状态，独立成类便于单测与复用（例如 HITL 审批理由构造）。</p>
 *
 * @author bai
 * @since 3.9.1
 */
final class DesktopRunModes {
    private DesktopRunModes() {
    }

    static String normalize(String mode) {
        if ("auto".equals(mode) || "plan".equals(mode) || "goal".equals(mode) || "full".equals(mode)) {
            return mode;
        }
        // 未知或缺失模式按最严格的审批执行处理，避免客户端字段异常导致静默放行。
        return "default";
    }

    static boolean requiresApproval(String mode, String toolName) {
        String normalizedMode = normalize(mode);
        if ("auto".equals(normalizedMode) || "full".equals(normalizedMode)) {
            // 自动编辑/完全访问模式：文件修改与命令执行均无需审批
            return false;
        }
        if ("bash".equals(toolName)) {
            return "default".equals(normalizedMode);
        }
        return "default".equals(normalizedMode)
                && ("write".equals(toolName) || "edit".equals(toolName));
    }
}
