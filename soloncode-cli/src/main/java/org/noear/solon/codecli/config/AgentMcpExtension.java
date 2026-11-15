package org.noear.solon.codecli.config;

import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessExtension;
import org.noear.solon.ai.harness.agent.AgentDefinition;
import org.noear.solon.ai.harness.agent.AgentSelections;
import org.noear.solon.ai.talents.gateway.McpGatewayTalent;
import org.noear.solon.annotation.Component;
import org.noear.solon.core.util.Assert;

import java.util.List;

/**
 * 将 Agent 明确选择的 MCP 服务隔离到独立网关，复用已有连接。
 *
 * <p>引擎侧的「智能体声明」只收窄视野（模型仍可经 {@code call_tool} 点名调用范围外的工具），
 * 这里再给非主代理换一个只含声明服务的网关，范围外的工具就真的调不到。</p>
 *
 * <p>用哪些服务与引擎同口径：直接取 {@link AgentSelections#mcpsOf}（已判 tools 分组展开与两级禁用、
 * 已剔除不存在与已停用的服务）。它返回 null 表示「未声明或声明全部失效」——此时不隔离、沿用全局网关。
 * 不能按「声明了列表即隔离」处理：那会让一个写错的服务名把该 Agent 的 MCP 全关掉，与引擎侧
 * 「全部失效视为未声明」相矛盾。</p>
 *
 * <p>只认引擎级定义（含挂载目录）。会话级子代理（{@code generate} 且不持久化）不在 AgentManager 里，
 * 回调又只给名字，取不到它的定义，于是沿用全局网关——它声明的 {@code mcpServers} 仍按引擎的
 * 智能体声明收窄视野（见 {@link AgentSelections}），只是没有硬隔离。</p>
 */
@Component
public class AgentMcpExtension implements HarnessExtension {
    @Override
    public void onAgentBuild(HarnessEngine engine, String agentName, ReActAgent.Builder agentBuilder) {
        if (Assert.isEmpty(agentName) || AgentDefinition.AGENT_MAIN.equals(agentName)) return;
        // 构建任何代理都会回调：名字不在引擎级注册表里时不处理，不能抛出（否则委派会报「定义加载失败」）
        AgentDefinition definition = engine.getAgentManager().getAgentOrNil(agentName);
        if (definition == null) return;

        List<String> selected = AgentSelections.mcpsOf(engine, definition);
        if (selected == null) return;

        McpGatewayTalent scoped = new McpGatewayTalent().retryConfig(engine.getMcpRetries());
        scoped.setEnabled(engine.getMcpGatewayTalent().isEnabled());
        for (String name : selected) {
            scoped.addMcpServer(name, engine.getMcpServer(name));
        }
        // Talent 以名称索引；同名网关替换默认全局网关，不修改或关闭共享连接。
        agentBuilder.defaultTalentAdd(scoped);
    }
}
