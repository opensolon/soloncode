package org.noear.solon.codecli.config;

import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessExtension;
import org.noear.solon.ai.harness.agent.AgentDefinition;
import org.noear.solon.codecli.command.builtin.LoopScheduler;


/**
 *
 * @author noear 2026/6/8 created
 *
 */
public class ManagerExtension implements HarnessExtension {
    private final HarnessEngine engine;
    private final AgentSettings settings;
    private final ManagerTalent managerTalent;

    public ManagerExtension(HarnessEngine engine, AgentSettings settings, LoopScheduler loopScheduler) {
        this.engine = engine;
        this.settings = settings;
        this.managerTalent = new ManagerTalent(engine, settings, loopScheduler);
    }

    @Override
    public void configure(String agentName, ReActAgent.Builder agentBuilder) {
        if (AgentDefinition.AGENT_MAIN.equals(agentName)) {
            agentBuilder.defaultTalentAdd(managerTalent);
        }
    }
}
