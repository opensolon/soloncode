package org.noear.solon.codecli.config;

import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessExtension;
import org.noear.solon.ai.harness.agent.AgentDefinition;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.ai.talents.gateway.McpGatewayTalent;
import org.noear.solon.annotation.Component;

import java.util.LinkedHashSet;
import java.util.List;

/** 将 Agent 明确选择的 MCP 服务隔离到独立网关，复用已有连接。 */
@Component
public class AgentMcpExtension implements HarnessExtension {
    @Override
    public void configure(HarnessEngine engine, String agentName, ReActAgent.Builder agentBuilder) {
        if (AgentDefinition.AGENT_MAIN.equals(agentName)) return;
        AgentDefinition.Metadata metadata = engine.getAgentManager().getAgent(agentName).getMetadata();
        List<String> selected = metadata.getMcpServers();
        // 未声明列表的旧 Agent 保持原有权限。
        if (selected == null || !metadata.getTools().contains("mcp")) return;
        if (metadata.getDisallowedTools().contains("mcp") || engine.getDisallowedTools().contains("mcp")) return;

        McpGatewayTalent scoped = new McpGatewayTalent().retryConfig(engine.getMcpRetries());
        scoped.setEnabled(engine.getMcpGatewayTalent().isEnabled());
        for (String name : new LinkedHashSet<>(selected)) {
            McpClientProvider provider = engine.getMcpServer(name);
            if (provider != null && provider.isEnabled()) scoped.addMcpServer(name, provider);
        }
        // Talent 以名称索引；同名网关替换默认全局网关，不修改或关闭共享连接。
        agentBuilder.defaultTalentAdd(scoped);
    }
}
