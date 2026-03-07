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
package org.noear.solon.bot.codecli.portal.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * CLI 命令注册中心
 *
 * <p>
 * 统一管理所有 / 命令的注册、查找和执行
 * </p>
 *
 * @author noear
 * @since 0.0.19
 */
public class CommandRegistry {

    /**
     * 命令定义
     */
    public static class Command {
        private final String name;
        private final String description;
        private final Consumer<CommandContext> handler;

        public Command(String name, String description, Consumer<CommandContext> handler) {
            this.name = name;
            this.description = description;
            this.handler = handler;
        }

        public String getName() {
            return name;
        }

        public String getDescription() {
            return description;
        }

        public Consumer<CommandContext> getHandler() {
            return handler;
        }
    }

    /**
     * 命令执行上下文
     */
    public static class CommandContext {
        private final Object session;
        private final Runnable onExit;

        public CommandContext(Object session, Runnable onExit) {
            this.session = session;
            this.onExit = onExit;
        }

        @SuppressWarnings("unchecked")
        public <T> T getSession() {
            return (T) session;
        }

        public void exit() {
            if (onExit != null) {
                onExit.run();
            }
        }
    }

    private final Map<String, Command> commands = new LinkedHashMap<String, Command>();

    /**
     * 注册命令
     *
     * @param name        命令名称（如 "/exit"）
     * @param description 命令描述
     * @param handler     执行逻辑
     */
    public void register(String name, String description, Consumer<CommandContext> handler) {
        commands.put(name.toLowerCase(), new Command(name, description, handler));
    }

    /**
     * 获取所有已注册命令
     */
    public List<Command> getAllCommands() {
        return Collections.unmodifiableList(new ArrayList<Command>(commands.values()));
    }

    /**
     * 获取所有命令名称
     */
    public List<String> getCommandNames() {
        return new ArrayList<String>(commands.keySet());
    }

    /**
     * 根据前缀查找匹配的命令候选
     *
     * @param prefix 前缀（如 "/" 或 "/he"）
     * @return 匹配的命令列表
     */
    public List<Command> findCandidates(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return getAllCommands();
        }

        String lowerPrefix = prefix.toLowerCase();
        List<Command> result = new ArrayList<Command>();
        for (Command cmd : commands.values()) {
            if (cmd.getName().toLowerCase().startsWith(lowerPrefix)) {
                result.add(cmd);
            }
        }
        return result;
    }

    /**
     * 执行命令
     *
     * @param input   用户输入
     * @param context 命令上下文
     * @return true 如果匹配到了命令并执行
     */
    public boolean execute(String input, CommandContext context) {
        if (input == null || input.isEmpty()) {
            return false;
        }

        String key = input.toLowerCase();
        Command cmd = commands.get(key);
        if (cmd != null) {
            cmd.getHandler().accept(context);
            return true;
        }
        return false;
    }

    /**
     * 检查是否存在指定命令
     */
    public boolean hasCommand(String name) {
        return commands.containsKey(name.toLowerCase());
    }
}
