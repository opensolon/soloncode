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
package org.noear.solon.codecli.command.builtin;

import org.noear.solon.codecli.command.Command;
import org.noear.solon.codecli.command.CommandContext;
import org.noear.solon.codecli.command.CommandRegistry;
import org.noear.solon.codecli.command.CommandType;

/**
 * /help 命令
 *
 * @author noear
 * @since 2026.4.28
 */
public class HelpCommand implements Command {
    private static final String BOLD = "\033[1m";
    private static final String DIM = "\033[2m";
    private static final String CYAN = "\033[36m";
    private static final String RESET = "\033[0m";

    private final CommandRegistry registry;

    public HelpCommand(CommandRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String name() {
        return "help";
    }

    @Override
    public String description() {
        return "Show available commands";
    }

    @Override
    public CommandType type() {
        return CommandType.SYSTEM;
    }

    @Override
    public boolean cliOnly() {
        return true;
    }

    @Override
    public boolean execute(CommandContext ctx) {
        ctx.println(BOLD + "Available Commands:" + RESET);
        for (Command cmd : registry.all()) {
            String suffix = cmd.cliOnly() ? DIM + " (cli-only)" + RESET : "";
            ctx.println("  " + CYAN + "/" + cmd.name() + RESET + " - " + cmd.description() + suffix);
        }
        ctx.println(DIM + "\nType /<command> to execute" + RESET);
        return true;
    }
}
