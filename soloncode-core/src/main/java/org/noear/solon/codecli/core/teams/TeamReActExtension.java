package org.noear.solon.codecli.core.teams;

import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActAgentExtension;
import org.noear.solon.codecli.core.AgentRuntime;
import org.noear.solon.codecli.core.AgentProperties;
import org.noear.solon.codecli.core.agent.AgentDefinition;
import org.noear.solon.codecli.core.teams.event.EventBus;
import org.noear.solon.codecli.core.teams.memory.SharedMemoryManager;
import org.noear.solon.codecli.core.teams.message.MessageChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 *
 * @author noear 2026/3/19 created
 *
 */
public class TeamReActExtension implements ReActAgentExtension {
    private static final Logger LOG = LoggerFactory.getLogger(TeamReActExtension.class);

    private final AgentRuntime agentRuntime;
    private final AgentProperties properties;

    // Agent Teams 相关组件
    private MainAgent mainAgent;
    private EventBus eventBus;
    private SharedTaskList taskList;
    private SharedMemoryManager memoryManager;
    private MessageChannel messageChannel;

    public TeamReActExtension(AgentRuntime agentRuntime) {
        this.agentRuntime = agentRuntime;
        this.properties = agentRuntime.getProperties();
    }

    public String getTeamLeadInstruction() {
        return mainAgent.getTeamLeadInstruction();
    }

    @Override
    public void configure(ReActAgent.Builder agentBuilder) {
        try {
            LOG.info("正在初始化 Agent Teams 模式...");

            // 1. 创建 EventBus（事件总线）- 使用配置
            int eventThreads = properties.getEventBus().asyncThreads;
            int eventHistorySize = properties.getEventBus().maxHistorySize;
            this.eventBus = new EventBus(eventThreads, eventHistorySize);
            LOG.debug("EventBus 已创建 (线程数: {}, 历史大小: {})", eventThreads, eventHistorySize);

            // 2. 创建 SharedTaskList（共享任务列表）- 使用配置
            this.taskList = new SharedTaskList(eventBus, properties.getTeams());
            LOG.debug("SharedTaskList 已创建");

            // 3. 创建 SharedMemoryManager（共享内存管理器）
            Path memoryPath = Paths.get(properties.getWorkDir(), AgentRuntime.SOLONCODE_MEMORY);
            this.memoryManager = new SharedMemoryManager(memoryPath);
            LOG.debug("SharedMemoryManager 已创建，路径: {}", memoryPath);

            // 4. 创建 MessageChannel（消息通道）- 使用配置
            Path messagePath = Paths.get(properties.getWorkDir(), AgentRuntime.SOLONCODE_MEMORY);
            int messageThreads = properties.getMessageChannel().threads != null ?
                    properties.getMessageChannel().threads : 4;
            this.messageChannel = new MessageChannel(messagePath.toString(), messageThreads);
            LOG.debug("MessageChannel 已创建，路径: {}, 线程数: {}", messagePath, messageThreads);

            // 5. 创建 MainAgent 配置
            AgentDefinition agentDefinition = agentRuntime.getAgentManager()
                    .getAgent(AgentDefinition.AGENT_SUPERVISOR);

            // 6. 创建 MainAgent（传入 kernel 和 subagentManager 以支持 subagent 功能）
            this.mainAgent = new MainAgent(
                    agentRuntime,
                    agentDefinition,
                    memoryManager,
                    eventBus,
                    messageChannel,
                    taskList,
                    properties.getWorkDir()
            );



            AgentTeamsSkill agentTeamsSkill = new AgentTeamsSkill(
                    agentRuntime,
                    mainAgent
            );
            SharedMemorySkill sharedMemorySkill =  new SharedMemorySkill(memoryManager, eventBus);

            agentBuilder.defaultSkillAdd(agentTeamsSkill);
            agentBuilder.defaultSkillAdd(sharedMemorySkill);

            LOG.debug("Agent Teams 模式初始化完成 [OK]");

        } catch (Throwable e) {
            LOG.error("Agent Teams 模式初始化失败", e);
            throw new RuntimeException("Failed to initialize Agent Teams mode", e);
        }
    }
}