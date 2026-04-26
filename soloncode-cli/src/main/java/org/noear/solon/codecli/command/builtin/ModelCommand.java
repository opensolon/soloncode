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

import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.codecli.command.Command;
import org.noear.solon.codecli.command.CommandContext;
import org.noear.solon.codecli.command.CommandType;
import org.noear.solon.codecli.core.AgentFlags;

/**
 * /model 命令（多子命令）
 *
 * @author noear
 * @since 2026.4.28
 */
public class ModelCommand implements Command {
    private static final String BOLD = "\033[1m";
    private static final String DIM = "\033[2m";
    private static final String GREEN = "\033[32m";
    private static final String RED = "\033[31m";
    private static final String RESET = "\033[0m";

    @Override
    public String name() {
        return "model";
    }

    @Override
    public String description() {
        return "Model management (ls, help, <name>)";
    }

    @Override
    public CommandType type() {
        return CommandType.CONFIG;
    }

    @Override
    public boolean execute(CommandContext ctx) throws Exception {
        String flag = ctx.argAt(0);

        if ("ls".equals(flag) || flag == null || flag.isEmpty()) {
            String currentModel = ctx.getSession().getContext().getAs(AgentFlags.VAR_MODEL_SELECTED);
            currentModel = ctx.getAgentRuntime().getModelOrMain(currentModel).getNameOrModel();

            ctx.println(BOLD + "Models:" + RESET);
            for (ChatConfig m : ctx.getAgentProps().getModels()) {
                String model = m.getNameOrModel();
                String desc = m.getDescriptionOrModel();
                String suffix = model.equals(currentModel) ? " " + GREEN + "(active)" + RESET : "";
                String label = model.equals(desc) ? model : model + DIM + " - " + desc + RESET;
                ctx.println("  " + label + suffix);
            }
            ctx.println(DIM + "\nUsage: /model <name>" + RESET);
        } else if ("help".equals(flag)) {
            ctx.println(BOLD + "/model" + RESET + " - Model management");
            ctx.println(DIM + "  /model" + RESET + "          List all available models");
            ctx.println(DIM + "  /model ls" + RESET + "       List all available models");
            ctx.println(DIM + "  /model <name>" + RESET + "   Switch to the specified model");
        } else {
            if (ctx.getAgentProps().getModelOrNil(flag) == null) {
                ctx.println(RED + "Model not found: " + RESET + BOLD + flag + RESET);
                ctx.println(DIM + "Use '/model' to see available models." + RESET);
            } else {
                ctx.getSession().getContext().put(AgentFlags.VAR_MODEL_SELECTED, flag);
                ctx.getSession().updateSnapshot();
                ctx.println(GREEN + "Model switched to: " + RESET + BOLD + flag + RESET);
            }
        }

        return true;
    }
}
