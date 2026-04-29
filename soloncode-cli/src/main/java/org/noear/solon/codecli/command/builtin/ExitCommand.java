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

import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.ai.harness.command.CommandContext;
import org.noear.solon.ai.harness.command.CommandType;



/**
 * /exit 命令（仅 CLI 环境）
 *
 * @author noear
 * @since 2026.4.28
 */
public class ExitCommand implements Command {
    private static final String DIM = "\033[2m";
    private static final String RESET = "\033[0m";

    @Override
    public String name() {
        return "exit";
    }

    @Override
    public String description() {
        return "退出进程";
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
        ctx.println(ctx.color(DIM + "Exiting..." + RESET));
        System.exit(0);
        return true;
    }
}
