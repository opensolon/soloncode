package org.noear.solon.bot.core.subagent;

import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActAgentExtension;
import org.noear.solon.bot.core.AgentKernel;
import org.noear.solon.bot.core.teams.TeamReActExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Paths;

/**
 *
 * @author noear 2026/3/19 created
 *
 */
public class SubagentReActExtension implements ReActAgentExtension {
    private static final Logger LOG = LoggerFactory.getLogger(SubagentReActExtension.class);

    private final AgentKernel rootAgent;

    private SubagentManager subagentManager;


    public SubagentManager getSubagentManager() {
        return subagentManager;
    }

    public SubagentReActExtension(AgentKernel rootAgent){
        this.rootAgent = rootAgent;
    }

    @Override
    public void configure(ReActAgent.Builder agentBuilder) {
        subagentManager = new SubagentManager(rootAgent);

        // 注册自定义 agents 池（类似 skillPool）
        // 注册 soloncode agents
        subagentManager.agentPool(Paths.get(rootAgent.getProperties().getWorkDir(), AgentKernel.SOLONCODE_AGENTS));
        // 注册 opencode agents
        subagentManager.agentPool(Paths.get(rootAgent.getProperties().getWorkDir(), AgentKernel.OPENCODE_AGENTS));
        // 注册 claude agents
        subagentManager.agentPool(Paths.get(rootAgent.getProperties().getWorkDir(), AgentKernel.CLAUDE_AGENTS));
        // 注册 soloncode agentsTeams（递归扫描团队成员目录）
        subagentManager.agentPool(Paths.get(rootAgent.getProperties().getWorkDir(), AgentKernel.SOLONCODE_AGENTS_TEAMS), true);

        // SubagentSkill 会通过 @ToolMapping 自动注册为工具
        agentBuilder.defaultSkillAdd(new SubagentSkill(rootAgent, subagentManager));

        LOG.debug("子代理模式已启用");
    }
}
