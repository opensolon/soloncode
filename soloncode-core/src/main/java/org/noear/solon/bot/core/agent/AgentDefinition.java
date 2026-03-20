package org.noear.solon.bot.core.agent;

import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.skills.web.CodeSearchTool;
import org.noear.solon.ai.skills.web.WebfetchTool;
import org.noear.solon.ai.skills.web.WebsearchTool;
import org.noear.solon.bot.core.AgentRuntime;
import org.noear.solon.bot.core.util.Markdown;
import org.noear.solon.bot.core.util.MarkdownUtil;
import org.noear.solon.core.util.Assert;

import java.util.Arrays;
import java.util.List;

/**
 * 代理定义
 *
 * @author bai
 * @author noear
 * @since 3.9.5
 */
public class AgentDefinition {
    protected AgentMetadata metadata = new AgentMetadata();
    protected String systemPrompt;

    public AgentMetadata getMetadata() {
        return metadata;
    }

    public String getName() {
        return metadata.getName();
    }

    public String getDescription() {
        return metadata.getDescription();
    }


    public void setMetadata(AgentMetadata metadata) {
        if (metadata == null) {
            this.metadata = new AgentMetadata();
        } else {
            this.metadata = metadata;
        }
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    /**
     * 从系统提示词中解析元数据
     *
     * @param markdownStr 系统提示词
     * @return 解析出的元数据对象
     */
    public static AgentDefinition fromMarkdown(String markdownStr) {
        AgentDefinition definition = new AgentDefinition();

        if (markdownStr == null || markdownStr.isEmpty()) {
            return definition;
        }

        Markdown markdown = MarkdownUtil.resolve(Arrays.asList(markdownStr.split("\n")));

        markdown.getMetadata().bindTo(definition.metadata);
        definition.systemPrompt = markdown.getContent();

        return definition;
    }

    /**
     * 从文件行列表解析子代理元数据和提示词
     *
     * @param lines 文件内容行列表
     * @return 包含元数据和提示词的对象
     */
    public static AgentDefinition fromMarkdown(List<String> lines) {
        AgentDefinition definition = new AgentDefinition();

        if (lines == null || lines.isEmpty()) {
            return definition;
        }

        Markdown markdown = MarkdownUtil.resolve(lines);

        definition.metadata = markdown.getMetadata().toBean(AgentMetadata.class);
        definition.systemPrompt = markdown.getContent();

        return definition;
    }


    public String toMarkdown() {
        StringBuilder buf = new StringBuilder();
        metadata.injectYamlFrontmatter(buf);

        if (Assert.isNotEmpty(systemPrompt)) {
            buf.append(systemPrompt);
        }

        return buf.toString();
    }

    public ReActAgent create(AgentRuntime agentRuntime) {
        ReActAgent.Builder builder = ReActAgent.of(agentRuntime.getChatModel());

        builder.name(metadata.getName());
        builder.systemPrompt(r -> getSystemPrompt());
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