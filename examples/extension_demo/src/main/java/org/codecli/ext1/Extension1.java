package org.codecli.ext1;

import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.harness.HarnessExtension;

/**
 *
 * @author noear 2026/4/21 created
 *
 */
public class Extension1 implements HarnessExtension {
    @Override
    public void configure(String agentName, ReActAgent.Builder agentBuilder) {
        System.out.println("进来了...");
    }
}
