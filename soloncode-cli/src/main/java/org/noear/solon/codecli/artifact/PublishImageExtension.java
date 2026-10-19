package org.noear.solon.codecli.artifact;

import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessExtension;
import org.noear.solon.ai.harness.agent.AgentDefinition;

/** 图片登记工具仅提供给主代理，子代理不能登记待发送图片。 */
public class PublishImageExtension implements HarnessExtension {
    @Override
    public void configure(HarnessEngine engine, String agentName, ReActAgent.Builder agentBuilder) {
        if (AgentDefinition.AGENT_MAIN.equals(agentName)) {
            agentBuilder.defaultTalentAdd(new PublishImageTalent(engine));
        }
    }
}
