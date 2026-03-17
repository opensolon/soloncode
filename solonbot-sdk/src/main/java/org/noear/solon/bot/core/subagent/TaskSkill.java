/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.bot.core.subagent;

import org.noear.solon.ai.agent.AgentChunk;
import org.noear.solon.ai.agent.AgentResponse;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.task.ActionChunk;
import org.noear.solon.ai.agent.react.task.ReasonChunk;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.annotation.Param;
import org.noear.solon.bot.core.AgentKernel;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.BufferedWriter;
import java.time.Duration;
import java.util.List;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 子代理技能
 *
 * 将子代理能力暴露为可调用的工具（Claude Code Subagent 类似实现）
 *
 * @author bai
 * @since 3.9.5
 */
public class TaskSkill extends AbsSkill {
    private static final Logger LOG = LoggerFactory.getLogger(TaskSkill.class);

    private final AgentKernel agentKernel;
    private final SubagentManager manager;

    // 并发控制：使用 Semaphore 限制同时发起的子代理请求数（从配置读取）
    private final Semaphore concurrencySemaphore;

    // 记录每个子代理类型的调用时间，用于精细控制
    private static final ConcurrentHashMap<String, Long> lastCallTimeByType = new ConcurrentHashMap<>();

    public TaskSkill(AgentKernel agentKernel, SubagentManager manager) {
        this.agentKernel = agentKernel;
        this.manager = manager;

        // 从配置读取并发控制参数
        int maxConcurrent = agentKernel.getProperties().subagentConcurrency.maxConcurrent;
        this.concurrencySemaphore = new Semaphore(maxConcurrent);

        LOG.info("TaskSkill 初始化: maxConcurrent={}, callIntervalMs={}ms",
                 maxConcurrent,
                 agentKernel.getProperties().subagentConcurrency.callIntervalMs);
    }

    @Override
    public String description() {
        return "SubAgent 模式工具：委派任务给专门的子代理（explore、plan、bash等）";
    }

    @Override
    public String getInstruction(Prompt prompt) {
        StringBuilder sb = new StringBuilder();
        sb.append("## SubAgent 模式工具\n\n");

        sb.append("###  核心规则（强制执行）\n\n");
        sb.append("####  禁止行为\n");
        sb.append("1. **禁止模拟工作**：严禁不断更新状态而无实际产出\n");
        sb.append("2. **禁止自己代替子代理完成任务**：必须调用真实的子代理\n\n");

        sb.append("#### 必须行为\n");
        sb.append("1. **强制使用 task() 工具**：所有实际工作必须通过 task() 完成\n");
        sb.append("2. **必须有实际产出**：代码任务必须生成文件，使用 ls/read 验证\n\n");

        sb.append("### 可用的子代理注册表\n");
        sb.append("<available_agents>\n");
        for (Subagent agent : manager.getAgents()) {
            sb.append(String.format("  - \"%s\": %s\n", agent.name(), agent.getDescription()));
        }
        sb.append("</available_agents>\n\n");

        sb.append("### 调用约定\n");
        sb.append("- **上下文对齐**: 子代理看不见当前历史，必须在 prompt 中传入必要的上下文\n");
        sb.append("- **示例**: `task(name=\"explore\", prompt=\"分析项目架构\")`\n");

        return sb.toString();
    }

    @ToolMapping(name = "task",
            description = "派生并分派任务给专项子代理。所有实际开发工作必须使用此工具委派给子代理完成。")
    public String task(
            @Param(name = "name", description = "子代理名称") String name,
            @Param(name = "prompt", description = "具体指令。必须包含任务目标、关键类名或必要的背景上下文。") String prompt,
            @Param(name = "description", required = false, description = "简短的任务描述") String description,
            @Param(name = "taskId", required = false, description = "可选。若要继续之前的任务会话，请传入对应的 task_id") String taskId,
            String __cwd,
            String __sessionId
    ) {
        AgentSession __parentSession = agentKernel.getSession(__sessionId);
        ReActTrace __parentTrace = ReActTrace.getCurrent(__parentSession.getSnapshot());

        try {
            Subagent agent = manager.getAgent(name);
            if (agent == null) {
                return "ERROR: 未知的子代理类型 '" + name + "'。";
            }

            String finalSessionId = Assert.isEmpty(taskId)
                    ? "subagent_" + name
                    : taskId;

            LOG.info("分派任务 -> 类型: {}, 会话: {}, 描述: {}", name, finalSessionId, description);

            if (!acquirePermit(name)) {
                return "ERROR: 等待子代理执行许可超时。当前可能有太多子代理在执行。";
            }

            String result = null;

            try {
                if (__parentTrace.getOptions().getStreamSink() == null) {
                    // 同步模式
                    AgentResponse response = agent.call(__cwd, finalSessionId, Prompt.of(prompt));
                    result = response.getContent();
                    __parentTrace.getMetrics().addMetrics(response.getMetrics());
                } else {
                    // 流式模式
                    result = AgentStreamOutput.executeStream(agent, __cwd, finalSessionId, Prompt.of(prompt),
                            __parentTrace, name);
                }

                LOG.info("子代理任务完成: {}", finalSessionId);

                return String.format(
                        "task_id: %s\n" +
                                "name: %s\n" +
                                "\n" +
                                "<task_result>\n" +
                                "%s\n" +
                                "</task_result>",
                        finalSessionId, name, result != null ? result : "(无输出)"
                );

            } finally {
                releasePermit(name);
            }

        } catch (Throwable e) {
            LOG.error("子代理执行崩溃: type={}, error={}", name, e.getMessage(), e);
            return "ERROR: 子代理执行失败: " + e.getMessage();
        }
    }

    /**
     * 获取执行许可（带限流控制）
     */
    private boolean acquirePermit(String subagentType) throws InterruptedException {
        long waitStart = System.currentTimeMillis();

        // 1. 等待并发许可（从配置读取超时时间）
        long acquireTimeoutMs = agentKernel.getProperties().subagentConcurrency.acquireTimeoutMs;
        boolean acquired = concurrencySemaphore.tryAcquire(acquireTimeoutMs, TimeUnit.MILLISECONDS);
        if (!acquired) {
            LOG.warn("[并发控制] 等待执行许可超时: type={}, timeout={}ms", subagentType, acquireTimeoutMs);
            return false;
        }

        long waitTime = System.currentTimeMillis() - waitStart;
        if (waitTime > 100) {
            LOG.info("[并发控制] 等待执行许可: type={}, wait={}ms", subagentType, waitTime);
        }

        // 2. 控制调用间隔（避免连续调用触发速率限制）
        long callIntervalMs = agentKernel.getProperties().subagentConcurrency.callIntervalMs;
        Long lastCall = lastCallTimeByType.get(subagentType);
        long now = System.currentTimeMillis();
        if (lastCall != null) {
            long elapsed = now - lastCall;
            if (elapsed < callIntervalMs) {
                long sleepTime = callIntervalMs - elapsed;
                LOG.debug("[并发控制] 调用间隔等待: type={}, sleep={}ms", subagentType, sleepTime);
                Thread.sleep(sleepTime);
            }
        }
        lastCallTimeByType.put(subagentType, System.currentTimeMillis());

        return true;
    }

    /**
     * 释放执行许可
     */
    private void releasePermit(String subagentType) {
        concurrencySemaphore.release();
        LOG.debug("[并发控制] 释放执行许可: type={}, available={}",
                subagentType, concurrencySemaphore.availablePermits());
    }


    @ToolMapping(name = "create_agent",
                 description = "动态创建一个新的子代理。")
    public String createAgent(
            @Param(name = "code", description = "子代理的唯一标识码") String code,
            @Param(name = "name", description = "子代理的显示名称") String name,
            @Param(name = "description", description = "子代理的功能描述") String description,
            @Param(name = "systemPrompt", description = "子代理的系统提示词") String systemPrompt,
            @Param(name = "model", required = false) String model,
            @Param(name = "tools", required = false) String tools,
            @Param(name = "skills", required = false) String skills,
            @Param(name = "maxTurns", required = false) Integer maxTurns,
            @Param(name = "saveToFile", required = false) Boolean saveToFile,
            String __cwd
    ) {
        try {
            SubAgentMetadata metadata = new SubAgentMetadata();
            metadata.setName(name);
            metadata.setDescription(description);
            metadata.setEnabled(true);

            if (model != null && !model.isEmpty()) {
                metadata.setModel(model);
            }
            if (tools != null && !tools.isEmpty()) {
                metadata.setTools(Arrays.asList(tools.split(",\\s*")));
            }
            if (skills != null && !skills.isEmpty()) {
                metadata.setSkills(Arrays.asList(skills.split(",\\s*")));
            }
            if (maxTurns != null && maxTurns > 0) {
                metadata.setMaxTurns(maxTurns);
            }

            String agentDefinition = metadata.toYamlFrontmatterWithPrompt(systemPrompt);

            boolean shouldSave = saveToFile == null || saveToFile;
            if (shouldSave) {
                Path agentsDir = Paths.get(__cwd, ".soloncode", "agents");
                if (!Files.exists(agentsDir)) {
                    Files.createDirectories(agentsDir);
                }
                Path agentFile = agentsDir.resolve(code + ".md");

                try (BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(
                                Files.newOutputStream(agentFile.toFile().toPath()),
                                StandardCharsets.UTF_8))) {
                    writer.write(agentDefinition);
                }
                LOG.info("Agent 定义已保存到: {}", agentFile);
            }

            GeneralPurposeSubagent newAgent = new GeneralPurposeSubagent(agentKernel, metadata, agentDefinition);
            newAgent.setDescription(description);
            newAgent.refresh();
            manager.addSubagent(newAgent);

            return "[OK] 子代理创建成功！\n\n" +
                   String.format("**代码**: %s\n", code) +
                   String.format("**名称**: %s\n", name) +
                   String.format("**描述**: %s\n", description) +
                   String.format("\n现在可以使用 `task(name=\"%s\", prompt=\"...\")` 来调用。", code);

        } catch (Throwable e) {
            LOG.error("创建子代理失败: code={}, error={}", code, e.getMessage(), e);
            return "ERROR: 创建子代理失败: " + e.getMessage();
        }
    }
}
