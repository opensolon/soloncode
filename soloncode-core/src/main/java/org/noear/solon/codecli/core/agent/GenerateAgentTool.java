package org.noear.solon.codecli.core.agent;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;
import org.noear.solon.codecli.core.AgentRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/**
 *
 * @author noear 2026/3/21 created
 *
 */
public class GenerateAgentTool {
    private static final Logger LOG = LoggerFactory.getLogger(GenerateAgentTool.class);

    private AgentRuntime agentRuntime;

    public GenerateAgentTool(AgentRuntime agentRuntime) {
        this.agentRuntime = agentRuntime;
    }

    @ToolMapping(name = "generate_agent",
            description = "动态创建一个新的子代理。")
    public String generateAgent(
            @Param(name = "name", description = "子代理的唯一标识码，必须使用英文单词或组合") String name,
            @Param(name = "description", description = "子代理的功能描述") String description,
            @Param(name = "systemPrompt", description = "子代理的系统提示词") String systemPrompt,
            @Param(name = "model", required = false) String model,
            @Param(name = "tools", required = false, description = "多个用英文逗号隔开（可选工具：read，edit，glob，grep，list，bash，skill，todoread，todowrite，webfetch，websearch，codesearch，task，browser，*。* 表示所有工具）") String tools,
            @Param(name = "skills", required = false) String skills,
            @Param(name = "maxTurns", required = false) Integer maxTurns,
            @Param(name = "saveToFile", required = false) Boolean saveToFile,
            String __cwd
    ) {
        try {
            AgentDefinition definition = agentRuntime.getAgentManager()
                    .getAgent(AgentDefinition.AGENT_GENERAL_PURPOSE)
                    .copy();

            definition.getMetadata().setName(name);
            definition.getMetadata().setDescription(description);
            definition.getMetadata().setEnabled(true);

            if (model != null && !model.isEmpty()) {
                definition.getMetadata().setModel(model);
            }
            if (tools != null && !tools.isEmpty()) {
                definition.getMetadata().setTools(Arrays.asList(tools.split(",\\s*")));
            }
            if (skills != null && !skills.isEmpty()) {
                definition.getMetadata().setSkills(Arrays.asList(skills.split(",\\s*")));
            }
            if (maxTurns != null && maxTurns > 0) {
                definition.getMetadata().setMaxTurns(maxTurns);
            }

            definition.setSystemPrompt(systemPrompt);

            boolean shouldSave = saveToFile == null || saveToFile;
            if (shouldSave) {
                Path agentsDir = Paths.get(__cwd, ".soloncode", "agents");
                if (!Files.exists(agentsDir)) {
                    Files.createDirectories(agentsDir);
                }
                Path agentFile = agentsDir.resolve(name + ".md");

                try (BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(
                                Files.newOutputStream(agentFile.toFile().toPath()),
                                StandardCharsets.UTF_8))) {
                    writer.write(definition.toMarkdown());
                }

                LOG.info("Agent 定义已保存到: {}", agentFile);
            }

            agentRuntime.getAgentManager().addAgent(definition);

            return "[OK] 子代理创建成功！\n\n" +
                    String.format("**标识**: %s\n", name) +
                    String.format("**描述**: %s\n", description) +
                    String.format("\n现在可以使用 `task(name=\"%s\", prompt=\"...\")` 来调用。", name);

        } catch (Throwable e) {
            LOG.error("创建子代理失败: name={}, error={}", name, e.getMessage(), e);
            return "ERROR: 创建子代理失败: " + e.getMessage();
        }
    }
}