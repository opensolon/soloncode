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

import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.ai.harness.command.CommandContext;

/**
 * /goal 命令，是 /loop goal 的快捷别名。
 *
 * @author noear
 */
public class GoalCommand implements Command {
    private final LoopCommand loopCommand;

    public GoalCommand(LoopCommand loopCommand) {
        this.loopCommand = loopCommand;
    }

    @Override
    public String name() {
        return "goal";
    }

    @Override
    public String description() {
        return "创建目标任务（/loop goal 的快捷别名）";
    }

    @Override
    public String[] examples() {
        return new String[]{
                "/goal <objective>",
                "/goal --max-tokens:2000 <objective>",
                "/goal --max-duration:30 <objective>"
        };
    }

    @Override
    public void execute(HarnessEngine engine, CommandContext ctx) throws Exception {
        loopCommand.executeGoal(ctx);
    }
}
