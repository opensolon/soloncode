package org.noear.solon.codecli.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.talent.Talent;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.agent.AgentDefinition;
import org.noear.solon.ai.harness.agent.SessionAgents;
import org.noear.solon.ai.mcp.client.McpServerParameters;
import org.noear.solon.ai.talents.gateway.McpGatewayTalent;
import org.noear.solon.core.util.RankEntity;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 智能体级 MCP 硬隔离：声明了可用 mcpServers 的非主代理换成只含这些服务的独立网关。
 *
 * <p>是否隔离与引擎的「智能体声明」同口径（{@code AgentSelections#mcpsOf}）：声明全部失效时视为未声明，
 * 沿用全局网关——否则一个写错的服务名就能把该 Agent 的 MCP 全关掉。</p>
 */
class AgentMcpExtensionTest {
    @TempDir
    Path workspace;

    @Test
    void sessionAgentBuildDoesNotThrow() {
        HarnessEngine engine = engine();
        AgentSession session = InMemoryAgentSession.of("s1");
        AgentDefinition temp = agent("temp", Arrays.asList("read", "mcp"), Arrays.asList("m-off"));
        SessionAgents.putIfAbsent(session, temp, SessionAgents.MAX_PER_SESSION);

        // 会话级子代理不在 AgentManager 里：构建时扩展照样回调，不能抛出（否则委派报「定义加载失败」）
        ReActAgent agent = assertDoesNotThrow(() -> engine.getAgentBuilder(temp).build());
        // 取不到定义就不隔离，沿用全局网关
        assertSame(engine.getMcpGatewayTalent(), gatewayOf(agent));
    }

    @Test
    void fullToolGroupGetsIsolatedGateway() {
        HarnessEngine engine = engine();
        AgentDefinition full = agent("full", Arrays.asList("**"), Arrays.asList("m1", "m-off"));
        // 测试引擎没配记忆提供者，挂 memory 会失败
        full.getMetadata().getDisallowedTools().add("memory");
        engine.getAgentManager().addAgent(full);

        McpGatewayTalent gateway = gatewayOf(engine.getAgentBuilder(full).build());
        assertNotSame(engine.getMcpGatewayTalent(), gateway, "** 含 mcp，应隔离");
        // 只含可用的声明服务（已停用的 m-off 被剔除）
        assertEquals(new LinkedHashSet<>(Arrays.asList("m1")), gateway.getMcpServerNames());
    }

    @Test
    void allDeclaredUnavailableKeepsGlobalGateway() {
        HarnessEngine engine = engine();
        // 全部失效（已停用 / 不存在）：与引擎的「全部失效视为未声明」对齐，不能隔离成空
        AgentDefinition ghosts = agent("ghosts", Arrays.asList("read", "mcp"), Arrays.asList("m-off", "nope"));
        engine.getAgentManager().addAgent(ghosts);

        assertSame(engine.getMcpGatewayTalent(), gatewayOf(engine.getAgentBuilder(ghosts).build()));
    }

    @Test
    void undeclaredOrDisallowedKeepsGlobalGateway() {
        HarnessEngine engine = engine();
        AgentDefinition undeclared = agent("undeclared", Arrays.asList("read", "mcp"), null);
        AgentDefinition denied = agent("denied", Arrays.asList("read", "mcp"), Arrays.asList("m-off"));
        denied.getMetadata().getDisallowedTools().add("mcp");
        engine.getAgentManager().addAgent(undeclared);
        engine.getAgentManager().addAgent(denied);

        assertSame(engine.getMcpGatewayTalent(), gatewayOf(engine.getAgentBuilder(undeclared).build()));
        // 禁用了 mcp：不挂任何 MCP 网关
        assertTrue(findGateway(engine.getAgentBuilder(denied).build()) == null);
    }

    // ---------------- fixtures ----------------

    private HarnessEngine engine() {
        // 假模型（不可达地址）：只构建代理，不实际调用
        ChatConfig model = new ChatConfig();
        model.setName("fake");
        model.setModel("fake-model");
        model.setApiUrl("http://localhost:1/v1");
        model.setApiKey("test");

        HarnessEngine engine = HarnessEngine.of(workspace.toString(), ".soloncode/")
                .sessionProvider((id, create) -> InMemoryAgentSession.of(id))
                .modelAdd(model)
                .defaultModel("fake")
                .extensionAdd(new AgentMcpExtension())
                .build();

        // 以停用状态注册（只进管理表、不建连）：一个后来打开（m1），一个保持停用（m-off）
        engine.getMcpGatewayTalent().addMcpServer("m1", stdio());
        engine.getMcpGatewayTalent().addMcpServer("m-off", stdio());
        engine.getMcpServer("m1").setEnabled(true);
        return engine;
    }

    private static McpServerParameters stdio() {
        McpServerParameters p = new McpServerParameters();
        p.setType("stdio");
        p.setCommand("soloncode-test-no-such-command");
        p.setEnabled(false);
        return p;
    }

    private static AgentDefinition agent(String name, List<String> tools, List<String> mcps) {
        return AgentDefinition.builder()
                .name(name)
                .description(name)
                .toolsAdd(tools)
                .systemPrompt("You are " + name)
                .metadata(md -> md.setMcpServers(mcps))
                .build();
    }

    private static McpGatewayTalent gatewayOf(ReActAgent agent) {
        McpGatewayTalent gateway = findGateway(agent);
        assertNotNull(gateway, "mcp gateway");
        return gateway;
    }

    private static McpGatewayTalent findGateway(ReActAgent agent) {
        for (RankEntity<Talent> entity : agent.getConfig().getDefaultOptions().getModelOptions().talents()) {
            if (entity.target instanceof McpGatewayTalent) {
                return (McpGatewayTalent) entity.target;
            }
        }
        return null;
    }
}
