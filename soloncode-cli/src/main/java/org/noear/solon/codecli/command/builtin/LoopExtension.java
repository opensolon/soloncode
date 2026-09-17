package org.noear.solon.codecli.command.builtin;

import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessExtension;
import org.noear.solon.ai.harness.agent.AgentDefinition;
import org.noear.solon.codecli.config.AgentSettings;

/**
 * Loop 工具扩展 — 注册 LoopTalent 到主 Agent
 *
 * @author noear
 * @since 3.9.4
 */
public class LoopExtension implements HarnessExtension {
    private final LoopTalent loopTalent;

    public LoopExtension(LoopScheduler loopScheduler, AgentSettings settings) {
        this.loopTalent = new LoopTalent(loopScheduler, settings);
    }

    public LoopTalent getLoopTalent() {
        return loopTalent;
    }

    @Override
    public void configure(HarnessEngine engine, String agentName, ReActAgent.Builder agentBuilder) {
        if (AgentDefinition.AGENT_MAIN.equals(agentName)) {
            agentBuilder.defaultTalentAdd(loopTalent);
        }
    }
}
