package org.noear.solon.bot.core.agent;

import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.skills.web.CodeSearchTool;
import org.noear.solon.ai.skills.web.WebfetchTool;
import org.noear.solon.ai.skills.web.WebsearchTool;
import org.noear.solon.bot.core.AgentRuntime;
import org.noear.solon.core.util.Assert;

/**
 * 代理工厂
 *
 * @author noear 2026/3/20 created
 */
public class AgentFactory {
    /**
     * 根据定义生成代理
     */
    public static ReActAgent create(AgentRuntime agentRuntime, AgentDefinition agentDefinition) {
        ReActAgent.Builder builder = ReActAgent.of(agentRuntime.getChatModel());

        AgentMetadata metadata = agentDefinition.getMetadata();

        builder.name(agentDefinition.getName());
        builder.systemPrompt(r -> agentDefinition.getSystemPrompt());

        builder.defaultInterceptorAdd(agentRuntime.getSummarizationInterceptor());

        if (metadata.getMaxSteps() != null && metadata.getMaxSteps() > 0) {
            builder.maxSteps(metadata.getMaxSteps());
        } else if (metadata.hasMaxTurns()) {
            builder.maxSteps(metadata.getMaxTurns());
        } else {
            builder.maxSteps(30);
        }

        if (metadata.getMaxStepsAutoExtensible() != null) {
            builder.maxStepsExtensible(metadata.getMaxStepsAutoExtensible());
        } else {
            builder.maxStepsExtensible(true);
        }

        if (Assert.isNotEmpty(metadata.getTools())) {
            //目前参考了： https://opencode.ai/docs/zh-cn/permissions/
            TerminalSkillProxy terminalSkillWrap = new TerminalSkillProxy(agentRuntime.getCliSkills().getTerminalSkill());

            for (String toolName : metadata.getTools()) {
                switch (toolName) {
                    case "read": {
                        terminalSkillWrap.addTools("read");
                        break;
                    }
                    case "edit": {
                        terminalSkillWrap.addTools("read", "write", "edit", "multiedit", "undo");
                        break;
                    }
                    case "glob": {
                        terminalSkillWrap.addTools("glob");
                        break;
                    }
                    case "grep": {
                        terminalSkillWrap.addTools("grep");
                        break;
                    }
                    case "ls":
                    case "list": {
                        terminalSkillWrap.addTools("ls");
                        break;
                    }
                    case "bash": {
                        terminalSkillWrap.addTools("bash");
                        break;
                    }
                    case "task": {
                        break;
                    }
                    case "skill": {
                        builder.defaultSkillAdd(agentRuntime.getCliSkills().getExpertSkill());
                        break;
                    }

                    case "todoread": {
                        builder.defaultToolAdd(agentRuntime.getTodoSkill()
                                .getToolAry("todoread"));
                        break;
                    }

                    case "todowrite": {
                        builder.defaultToolAdd(agentRuntime.getTodoSkill()
                                .getToolAry("todowrite"));
                        break;
                    }

                    case "webfetch": {
                        builder.defaultToolAdd(WebfetchTool.getInstance());
                        break;
                    }

                    case "websearch": {
                        builder.defaultToolAdd(WebsearchTool.getInstance());
                        break;
                    }

                    case "codesearch": {
                        builder.defaultToolAdd(CodeSearchTool.getInstance());
                        break;
                    }

                    case "*": {
                        builder.defaultSkillAdd(agentRuntime.getCliSkills());
                        builder.defaultToolAdd(agentRuntime.getTodoSkill());
                        builder.defaultToolAdd(WebfetchTool.getInstance());
                        builder.defaultToolAdd(WebsearchTool.getInstance());
                        builder.defaultToolAdd(CodeSearchTool.getInstance());
                        break;
                    }
                }
            }

            if (terminalSkillWrap.isEmpty() == false) {
                // terminalSkill / tools 需要通过以 skill 形态加载（getInstruction 里有 SOP）
                builder.defaultSkillAdd(terminalSkillWrap);
            }
        }

        return builder.build();
    }
}