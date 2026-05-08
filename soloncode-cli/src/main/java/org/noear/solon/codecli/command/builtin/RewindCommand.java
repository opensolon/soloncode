package org.noear.solon.codecli.command.builtin;

import io.swagger.models.auth.In;
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.ai.harness.command.CommandContext;
import org.noear.solon.ai.harness.command.CommandType;
import org.noear.solon.core.util.Assert;

/**
 *
 * @author noear 2026/5/8 created
 *
 */
public class RewindCommand implements Command {
    @Override
    public String name() {
        return "rewind";
    }

    @Override
    public String description() {
        return "回退对话记录";
    }

    @Override
    public CommandType type() {
        return CommandType.AGENT;
    }

    @Override
    public boolean execute(CommandContext ctx) throws Exception {
        String flag = ctx.argAt(0);
        if (Assert.isInteger(flag)) {
            ctx.getSession().removeLatestMessage(Integer.parseInt(flag));
        } else {
            ctx.getSession().removeLatestMessage(1);
        }

        return true;
    }
}