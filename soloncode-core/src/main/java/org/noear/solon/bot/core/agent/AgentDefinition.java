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
        return AgentFactory.create(agentRuntime, this);
    }
}