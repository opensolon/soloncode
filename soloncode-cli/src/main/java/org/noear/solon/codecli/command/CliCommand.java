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
package org.noear.solon.codecli.command;

import java.util.Collections;
import java.util.List;

/**
 * CLI 命令接口
 * <p>
 * 兼容 Claude Code 的 Custom Command 规范（YAML Frontmatter + Markdown body）
 *
 * @author noear
 * @since 2026.4.28
 */
public interface CliCommand {

    /**
     * 命令名（不含 /），如 "model"、"exit"、"deploy:staging"
     */
    String name();

    /**
     * 命令描述，用于帮助文本
     */
    String description();

    /**
     * 命令类型
     */
    CliCommandType type();

    /**
     * 参数提示（对标 Claude Code 的 argument-hint）
     * <p>
     * 用于 Tab 补全和 /help 输出，如 "[message]"、"&lt;file&gt;"
     */
    default String argumentHint() {
        return "";
    }

    /**
     * 允许使用的工具列表（对标 Claude Code 的 allowed-tools）
     * <p>
     * 空列表表示不限制（使用默认工具集）
     */
    default List<String> allowedTools() {
        return Collections.emptyList();
    }

    /**
     * 执行命令
     *
     * @param context 命令上下文（session, terminal, args 等）
     * @return true 表示命令已处理，false 表示未处理
     */
    boolean execute(CliCommandContext context) throws Exception;
}
