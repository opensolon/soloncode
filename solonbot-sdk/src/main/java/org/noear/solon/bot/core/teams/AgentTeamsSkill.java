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
package org.noear.solon.bot.core.teams;

import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.AgentChunk;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamProtocols;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;
import org.noear.solon.bot.core.AgentKernel;
import org.noear.solon.bot.core.memory.smart.IntelligentMemoryManager;
import org.noear.solon.bot.core.subagent.AbsSubagent;
import org.noear.solon.bot.core.subagent.SubAgentMetadata;
import org.noear.solon.bot.core.subagent.Subagent;
import org.noear.solon.bot.core.subagent.SubagentManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Agent Teams 技能
 *
 * 将 Agent Teams 的协调能力暴露给主 Agent，包括：
 * - 启动团队协作任务
 * - 查看和管理共享任务列表
 * - 监控团队任务状态
 * - 调用专门的子代理（使用 TaskSkill 的实现）
 * - 动态创建新的子代理
 *
 * @author bai
 * @since 3.9.5
 */
public class AgentTeamsSkill extends AbsSkill {

    private static final Logger LOG = LoggerFactory.getLogger(AgentTeamsSkill.class);
    private static final String CURRENT_TEAM_KEY = "current_team_name"; // 当前团队名的内存键

    private final MainAgent mainAgent;
    private final AgentKernel kernel;
    private final SubagentManager manager;
    private final IntelligentMemoryManager intelligentMemoryManager;

    /**
     * 完整构造函数（支持子代理调用）
     */
    public AgentTeamsSkill(MainAgent mainAgent, AgentKernel kernel, SubagentManager manager) {
        this.mainAgent = mainAgent;
        this.kernel = kernel;
        this.manager = manager;

        // 初始化智能记忆管理器
        if (mainAgent != null && mainAgent.getSharedMemoryManager() != null) {
            this.intelligentMemoryManager = new IntelligentMemoryManager(Paths.get(mainAgent.getWorkDir(), AgentKernel.SOLONCODE_MEMORY));
        } else {
            this.intelligentMemoryManager = null;
        }
    }


    @Override
    public String description() {
        return "Agent Teams 协调专家：支持团队协作任务、任务管理、子代理调用";
    }

    @Override
    public String getInstruction(Prompt prompt) {
        StringBuilder sb = new StringBuilder();

        sb.append("## Agent Teams 协调能力\n\n");
        sb.append("你是一个团队协调器，可以启动和管理多代理协作任务。\n\n");

        sb.append("### 核心规则（强制执行）\n\n");
        sb.append("#### 禁止行为（绝对不可违反）\n");
        sb.append("1. **禁止模拟工作**：\n");
        sb.append("   - 严禁不断更新 `update_working_memory`、`step`、`currentAgent` 而无实际产出\n");
        sb.append("   - 不得使用 `memory_store` 存储虚假的\"已完成\"状态\n");
        sb.append("   - 不得声称\"需求分析已完成\"、\"代码已编写\"等虚假结论\n");
        sb.append("   - 不断更新状态而无实际文件产出是**严重违规**\n\n");
        sb.append("2. **必须有实际产出**：\n");
        sb.append("   - 代码任务必须生成 `.java`、`.py` 等文件\n");
        sb.append("   - 文档任务必须生成 `.md`、`.txt` 等文件\n");
        sb.append("   - 使用 `ls`、`read` 工具验证文件已真实创建\n");
        sb.append("   - 确认文件内容符合要求后才可宣称任务完成\n\n");
        sb.append("3. **禁止循环操作**：\n");
        sb.append("   - 不得重复调用相同工具而不产生新进展\n");
        sb.append("   - 检测到循环时必须立即停止并使用 `task()` 工具\n\n");
        sb.append("#### 必须行为（强制执行）\n");
        sb.append("1. **必须使用 task 工具**：\n");
        sb.append("   - 所有实际工作必须通过 `task(subagent_type, prompt)` 委派给子代理\n");
        sb.append("   - 可用类型：explore、plan、bash、general-purpose、solon-code-guide\n");
        sb.append("   - 例如：`task(subagent_type='bash', prompt='创建文件并编写代码')`\n\n");
        sb.append("2. **必须验证产出**：\n");
        sb.append("   - 使用 `ls(path='.')` 列出创建的文件\n");
        sb.append("   - 使用 `read(file_path='xxx')` 验证文件内容\n");
        sb.append("   - 只有确认真实产出后才可完成\n\n");
        sb.append("3. **token 使用警告**：\n");
        sb.append("   - 每个任务建议不超过 10,000 tokens\n");
        sb.append("   - 超过 5,000 tokens 无产出时必须改变策略\n");
        sb.append("   - 禁止无限循环调用工具\n\n");

        // ========== 工作流程 ==========
        sb.append("### 工作流程\n");
        sb.append("1. 分析任务：识别所需的专业领域。\n");
        sb.append("2. 组建团队：自动激活相关领域的专家 Agent。\n");
        sb.append("3. 引导讨论：\n");
        sb.append("   - 让专家轮流发表观点。\n");
        sb.append("   - 鼓励专家互相质疑（例如：安全专家挑战开发专家的架构）。\n");
        sb.append("   - 记录争议点并寻求共识。\n");
        sb.append("4. 生成报告：汇总讨论结果，去除冗余对话，只保留高质量的最终结论。\n\n");

        // ========== 核心能力 ==========
        sb.append("### 核心能力\n");
        sb.append("1. **团队协作任务**: 使用 `team_task()` 启动多代理协作\n");
        sb.append("2. **任务管理**: \n");
        sb.append("   - `analyze_tasks()` 分析任务（返回分解建议，JSON格式）\n");
        sb.append("   - `create_task()` 单个创建，`create_tasks()` 批量创建，`remove_task()` 删除\n");
        sb.append("   - `team_status()` 查看状态，`claim_task()` 认领，`release_task()` 释放\n");
        sb.append("   - `complete_task()` 完成，`fail_task()` 失败\n");
        sb.append("   - `list_all_tasks()` 列出所有，`get_claimable_tasks()` 获取可认领\n");
        sb.append("3. **子代理调用**: 使用 `task()` 委派专门任务（支持会话续接）【强制使用】\n");
        sb.append("4. **团队成员管理**: 使用 `teammate()` 创建，`teammates()` 列出，`remove_teammate()` 移除\n");
        sb.append("5. **智能记忆管理**: 使用 `memory_store()` 自动分类存储（推荐）\n");
        sb.append("   - 自动分类：决策→永久、任务→7天、临时→10分钟\n");
        sb.append("   - 自动评分：多维度评估记忆重要性（0-10分）\n");
        sb.append("   - 智能检索：`memory_recall()` 按相关性排序\n");
        sb.append("6. **工作记忆**: `get_working_memory()` 查看，`update_working_memory()` 更新\n");
        sb.append("7. **代理间通信**: 使用 `send_message()` 发送消息，`list_agents()` 查看可用代理\n");
        sb.append("8. **团队命名**: 使用 `suggest_team_name()` 获取命名建议\n\n");

        sb.append("### 强制委派准则\n");
        sb.append("- **项目认知**: 探索项目、分析架构 → 委派给子代理\n");
        sb.append("- **复杂变更**: 跨文件修复、重构 → 委派给子代理\n");
        sb.append("- **决策量化**: 超过 3 次工具调用 → 改用子代理\n");
        sb.append("- **所有开发任务**: 必须使用 `task(subagent_type='bash', ...)` 实际创建文件\n\n");

        sb.append("### 可用的子代理\n");
        sb.append("<available_agents>\n");
        // 只显示自定义团队成员（有 teamName 的）
        for (Subagent agent : manager.getAgents()) {
            if (agent.getMetadata().hasTeamName()) {
                sb.append(String.format("  - `%s`: %s (团队: %s)\n",
                        agent.name(),
                        agent.getDescription(),
                        agent.getMetadata().getTeamName()));
            }
        }
        sb.append("</available_agents>\n\n");

        // ========== 团队成员管理 ==========
        sb.append("### 团队成员管理\n");
        sb.append("1. **创建成员**: 使用 `teammate()` 创建新的团队成员\n");
        sb.append("2. **列出成员**: 使用 `teammates()` 查看所有团队成员（表格格式）\n");
        sb.append("3. **移除成员**: 使用 `remove_teammate()` 移除团队成员\n\n");
        sb.append("### 团队成员配置选项\n\n");
        sb.append("**基本配置**:\n");
        sb.append("- `name`: 成员唯一标识（如：security-expert）\n");
        sb.append("- `role`: 角色描述（如：安全专家）\n");
        sb.append("- `description`: 详细职责描述\n");
        sb.append("- `teamName`: 团队名称（可选，自动生成）\n\n");
        sb.append("**技能与工具配置**:\n");
        sb.append("- `expertise`: 专业领域（如：security,auth,encryption）\n");
        sb.append("- `skills`: 启用的技能列表（如：expert,terminal,lucene）\n");
        sb.append("- `tools`: 启用的工具列表（如：read,write,edit,browser）\n");
        sb.append("- `disallowedTools`: 禁用的工具列表\n");
        sb.append("- `mcpServers`: 启用的 MCP 服务器\n");
        sb.append("- `includeAgentTeamsTools`: 引入记忆管理工具（默认true）\n\n");
        sb.append("**可用工具参考**:\n");
        sb.append("- **文件操作**: `read`, `write`, `edit`, `ls`, `find`（由子代理提供）\n");
        sb.append("- **浏览器**: `browser_screenshot`, `browser_interact`, `browser_navigate`（由子代理提供）\n");
        sb.append("- **记忆管理**: `memory_store`, `memory_recall`, `memory_stats`\n");
        sb.append("- **工作记忆**: `get_working_memory`, `update_working_memory`\n");
        sb.append("- **任务管理**: `analyze_tasks`, `create_task`, `create_tasks`, `remove_task`, `team_status`, `claim_task`, `release_task`, `complete_task`, `fail_task`, `list_all_tasks`\n");
        sb.append("- **团队协作**: `task`（子代理调用）, `teammate`, `teammates`, `remove_teammate`\n");
        sb.append("- **代理通信**: `send_message`, `list_agents`, `get_message_stats`\n");
        sb.append("- **团队命名**: `suggest_team_name`\n\n");
        sb.append("**使用示例**:\n");
        sb.append("```bash\n");
        sb.append("# 创建带记忆管理工具的成员\n");
        sb.append("teammate(\n");
        sb.append("    name=\"analyst\",\n");
        sb.append("    role=\"数据分析师\",\n");
        sb.append("    description=\"负责数据分析和报告生成\",\n");
        sb.append("    skills=\"expert,lucene\",\n");
        sb.append("    tools=\"read,write,ls\"\n");
        sb.append(")\n\n");
        sb.append("# 创建带浏览器工具的成员\n");
        sb.append("teammate(\n");
        sb.append("    name=\"browser-bot\",\n");
        sb.append("    role=\"浏览器自动化专家\",\n");
        sb.append("    tools=\"browser_screenshot,browser_interact,browser_navigate\"\n");
        sb.append(")\n\n");
        sb.append("# 创建禁用某些工具的成员\n");
        sb.append("teammate(\n");
        sb.append("    name=\"read-only-expert\",\n");
        sb.append("    role=\"只读代码审查专家\",\n");
        sb.append("    disallowedTools=\"write,edit,bash\"\n");
        sb.append(")\n");
        sb.append("```\n\n");

        sb.append("### 任务链协调：如何将结果传递给下一个 subagent\n\n");
        sb.append("**⚠️ 重要：完成任务链协调的三种方法**\n\n");
        sb.append("**方法 1：在 prompt 中传递上下文（推荐）**\n");
        sb.append("```\n");
        sb.append("# 第一步：plan 完成设计\n");
        sb.append("result1 = task(subagent_type='plan', prompt='设计用户登录模块')\n");
        sb.append("\n");
        sb.append("# 第二步：传递给 bash\n");
        sb.append("task(\n");
        sb.append("    subagent_type='bash',\n");
        sb.append("    prompt='<context>基于以下设计：' + result1 + '</context>创建代码'\n");
        sb.append(")\n");
        sb.append("```\n\n");
        sb.append("**方法 2：使用共享记忆**\n");
        sb.append("```\n");
        sb.append("result1 = task(subagent_type='plan', prompt='设计...')\n");
        sb.append("memory_store(content=result1, key='design')\n");
        sb.append("design = memory_recall(query='设计', limit=1)\n");
        sb.append("task(subagent_type='bash', prompt='<context>' + design + '</context>创建代码')\n");
        sb.append("```\n\n");
        sb.append("**方法 3：使用 taskId 续接会话**\n");
        sb.append("```\n");
        sb.append("result1 = task(subagent_type='explore', prompt='分析项目')\n");
        sb.append("# 返回 task_id: explore_12345\n");
        sb.append("result2 = task(\n");
        sb.append("    subagent_type='explore',\n");
        sb.append("    prompt='深入分析 Controller 层',\n");
        sb.append("    taskId='explore_12345'  # 续接之前的会话\n");
        sb.append(")\n");
        sb.append("```\n\n");
        sb.append("**详细指南**：参考项目文档 `docs/TASK_CHAIN_GUIDE.md`\n\n");

        // ========== 使用场景 ==========
        sb.append("### 使用场景\n");
        sb.append("```\n");
        sb.append("# 场景1: 创建团队成员（指定团队）\n");
        sb.append("teammate(\n");
        sb.append("    name=\"security-expert\",\n");
        sb.append("    role=\"security-expert\",\n");
        sb.append("    description=\"专注于安全审计、漏洞检测\",\n");
        sb.append("    teamName=\"myteam\"              # 指定团队名称\n");
        sb.append(")\n");
        sb.append("# 生成文件: .soloncode/agentsTeams/myteam/security-expert.md\n\n");
        sb.append("# 场景2: 创建成员（不指定团队，智能生成）\n");
        sb.append("teammate(\n");
        sb.append("    name=\"db-optimizer\",\n");
        sb.append("    role=\"db-optimizer\",\n");
        sb.append("    description=\"SQL 查询优化\"\n");
        sb.append(")\n");
        sb.append("# 智能生成团队名: database-team (根据关键词自动识别)\n");
        sb.append("# 生成文件: .soloncode/agentsTeams/database-team/db-optimizer.md\n");
        sb.append("# 团队名会保存到内存，后续创建会自动使用同一团队\n\n");
        sb.append("# 场景3: 查看所有成员\n");
        sb.append("teammates()\n\n");
        sb.append("# 场景4: 启动团队协作任务\n");
        sb.append("team_task(\"实现用户登录功能\")\n\n");
        sb.append("# 场景5: 查看任务状态\n");
        sb.append("team_status()\n\n");
        sb.append("# 场景6: 智能记忆存储（推荐使用）\n");
        sb.append("# 自动分类存储（系统自动判断存储类型和周期）\n");
        sb.append("memory_store(content=\"采用三层架构：Controller-Service-Repository\", key=\"架构决策\")\n");
        sb.append("# → 系统识别为架构决策，自动分类到永久记忆（重要性评分: 8.5/10）\n\n");
        sb.append("# 存储任务完成结果\n");
        sb.append("memory_store(content=\"已完成JWT认证，包括登录、登出、token刷新\", key=\"登录功能\")\n");
        sb.append("# → 系统识别为任务结果，自动分类到长期记忆（7天，重要性评分: 7.2/10）\n\n");
        sb.append("# 存储临时上下文\n");
        sb.append("memory_store(content=\"正在实现Service层\", key=\"当前步骤\")\n");
        sb.append("# → 系统识别为临时上下文，自动分类到工作记忆（10分钟，重要性评分: 3.5/10）\n\n");
        sb.append("# 场景7: 智能检索记忆\n");
        sb.append("memory_recall(query=\"登录功能\", limit=5)\n");
        sb.append("# → 返回最相关的 5 条记忆，按相关性排序\n\n");
        sb.append("# 场景8: 查看记忆统计\n");
        sb.append("memory_stats()\n");
        sb.append("# → 显示各层级记忆数量、智能层状态、内存使用情况\n\n");
        sb.append("# 场景9: 代理间通信\n");
        sb.append("# 向 explore 代理发送消息\n");
        sb.append("send_message(\n");
        sb.append("    targetAgent=\"explore\",\n");
        sb.append("    message=\"请探索项目的目录结构，重点关注 src/main/java 目录\"\n");
        sb.append(")\n\n");
        sb.append("# 向 plan 代理发送消息\n");
        sb.append("send_message(\n");
        sb.append("    targetAgent=\"plan\",\n");
        sb.append("    message=\"需要设计一个用户认证模块，请提供实现方案\"\n");
        sb.append(")\n\n");
        sb.append("# 查看可用代理列表\n");
        sb.append("list_agents()\n");
        sb.append("```\n\n");

        // ========== 记忆管理说明 ==========
        sb.append("### 记忆管理说明\n");
        sb.append("**智能记忆系统**（推荐使用）:\n");
        sb.append("- **memory_store(content, key?)**: 自动分类存储\n");
        sb.append("  - 决策类（\"决策\"、\"架构\"、\"采用\"）→ 永久记忆\n");
        sb.append("  - 任务类（\"完成\"、\"实现\"、\"修复\"）→ 长期记忆（7天）\n");
        sb.append("  - 临时类（\"正在\"、\"尝试\"、\"临时\"）→ 工作记忆（10分钟）\n");
        sb.append("  - 知识类（\"API\"、\"设计\"、\"配置\"）→ 永久记忆\n");
        sb.append("  - 自动评分：基于内容、类型、关键词、时间、访问频率\n");
        sb.append("- **memory_recall(query, limit?)**: 智能检索（按相关性排序）\n");
        sb.append("- **memory_stats()**: 查看统计信息\n\n");
        sb.append("**记忆数量优化**:\n");
        sb.append("- 从 15+ 个工具简化到 3 个核心工具\n");
        sb.append("- 自动分类：无需手动选择记忆类型\n");
        sb.append("- 智能检索：只返回最相关的记忆，减少 token 消耗\n");
        sb.append("```\n");

        return sb.toString();
    }


    /**
     * 启动团队协作任务
     *
     * MainAgent 会分析任务、创建子任务、协调多个 SubAgent 协作完成
     */
    // 超时配置（单位：毫秒）
    private static final long TEAM_TASK_TIMEOUT_MS = 300_000; // 5分钟超时

    @ToolMapping(name = "team_task",
                 description = "启动团队协作任务。MainAgent 会自动分解任务并协调多个 SubAgent 协作完成。适用于复杂、多步骤的任务。注意：简单任务请直接回答，无需调用此工具。返回 Mono<String> 用于异步处理。")
    public Mono<String> teamTask(
            @Param(name = "prompt", description = "任务描述，清晰说明目标和要求") String prompt,
            String __cwd,
            String __sessionId
    ) {
        // 检查是否已有任务在执行
        if (mainAgent.isRunning()) {
            return Mono.just("[WARN] 团队任务正在执行中，请等待当前任务完成。");
        }

        LOG.info("启动团队协作任务: {}", prompt);

        // 使用流式执行并转换为 Mono
        return Mono.fromCallable(() -> {
            // 使用流式执行（实时输出）
            StringBuilder streamingOutput = new StringBuilder();

            try {
                // 获取流式响应（传递 __cwd 以支持文件操作）
                Flux<AgentChunk> responseStream =
                    mainAgent.executeStream(Prompt.of(prompt), __cwd);

                // 收集流式输出（带超时）
                List<AgentChunk> chunks = responseStream
                    .doOnNext(chunk -> {
                        String content = chunk.getContent();
                        if (content != null && !content.isEmpty()) {
                            streamingOutput.append(content);
                        }
                    })
                    .doOnComplete(() -> {
                        LOG.info("\n[OK] 团队任务流式执行完成");
                    })
                    .doOnError(error -> {
                        LOG.error("团队任务流式执行出错", error);
                    })
                    .collectList()
                    .block(Duration.ofMillis(TEAM_TASK_TIMEOUT_MS));

                if (chunks != null) {
                    LOG.debug("流式响应收集完成，收到 {} 个 chunk", chunks.size());
                }

            } catch (Exception e) {
                LOG.error("团队任务执行失败", e);
                throw new RuntimeException("团队任务执行失败: " + e.getMessage(), e);
            }

            // 获取任务统计
            SharedTaskList.TaskStatistics stats = mainAgent.getTaskList().getStatistics();

            StringBuilder result = new StringBuilder();
            result.append("[OK] 团队任务执行完成\n\n");
            result.append("**任务统计**:\n");
            result.append(String.format("- 总任务数: %d\n", stats.totalTasks));
            result.append(String.format("- 已完成: %d\n", stats.completedTasks));
            result.append(String.format("- 失败: %d\n", stats.failedTasks));
            result.append(String.format("- 进行中: %d\n", stats.inProgressTasks));
            result.append(String.format("- 待认领: %d\n\n", stats.pendingTasks));

            // 主 Agent 的回复（流式输出已实时打印）
            result.append("**主 Agent 回复**:\n");

            // 如果流式输出有内容，添加到结果中
            String agentResponse = streamingOutput.toString();
            if (!agentResponse.isEmpty()) {
                // 限制显示长度，避免过长
                if (agentResponse.length() > 2000) {
                    agentResponse = agentResponse.substring(0, 2000) + "\n...(内容过长，已截断)";
                }
                result.append(agentResponse);
            } else {
                result.append("(流式输出已完成，请查看上方控制台输出)");
            }

            return result.toString();
        })
        .subscribeOn(Schedulers.boundedElastic()) // 在弹性线程池中执行
        .doOnSubscribe(subscription -> LOG.info("开始异步执行团队任务: {}", prompt))
        .doOnError(error -> LOG.error("团队任务执行失败: prompt={}", prompt, error))
        .onErrorResume(throwable -> {
            LOG.error("团队任务执行失败: prompt={}", prompt, throwable);
            return Mono.just("[ERROR] 团队任务执行失败: " + throwable.getMessage());
        });
    }

    /**
     * 查看团队任务状态
     */
    @ToolMapping(name = "team_status",
                 description = "查看团队任务状态。返回任务统计和当前任务列表。")
    public String teamStatus() {
        try {
            SharedTaskList taskList = mainAgent.getTaskList();
            SharedTaskList.TaskStatistics stats = taskList.getStatistics();

            StringBuilder sb = new StringBuilder();
            sb.append("## 团队任务状态\n\n");

            // 统计信息
            sb.append("**统计**:\n");
            sb.append(String.format("- 总任务: %d\n", stats.totalTasks));
            sb.append(String.format("- [OK] 已完成: %d\n", stats.completedTasks));
            sb.append(String.format("- [ERROR] 失败: %d\n", stats.failedTasks));
            sb.append(String.format("- [PROCESS] 进行中: %d\n", stats.inProgressTasks));
            sb.append(String.format("- [WAIT] 待认领: %d\n\n", stats.pendingTasks));

            // 任务列表
            List<TeamTask> allTasks = taskList.getAllTasks();
            if (!allTasks.isEmpty()) {
                sb.append("**任务列表**:\n\n");

                for (TeamTask task : allTasks) {
                    String statusIcon = getStatusIcon(task.getStatus());
                    sb.append(String.format("%s **%s** (优先级: %d)\n",
                            statusIcon, task.getTitle(), task.getPriority()));
                    sb.append(String.format("  - 类型: %s\n", task.getType()));
                    sb.append(String.format("  - 状态: %s\n", task.getStatus()));

                    if (task.getClaimedBy() != null) {
                        sb.append(String.format("  - 认领者: %s\n", task.getClaimedBy()));
                    }

                    if (task.getDependencies() != null && !task.getDependencies().isEmpty()) {
                        sb.append(String.format("  - 依赖: %d 个任务\n", task.getDependencies().size()));
                    }

                    if (task.isCompleted() && task.getResult() != null) {
                        String result = task.getResult().toString();
                        if (result.length() > 100) {
                            result = result.substring(0, 100) + "...";
                        }
                        sb.append(String.format("  - 结果: %s\n", result));
                    }

                    if (task.isFailed() && task.getErrorMessage() != null) {
                        sb.append(String.format("  - 错误: %s\n", task.getErrorMessage()));
                    }

                    sb.append("\n");
                }
            }

            // 正在运行状态
            if (mainAgent.isRunning()) {
                sb.append("**主 Agent 状态**: [PROCESS] 正在运行\n\n");
            } else {
                sb.append("**主 Agent 状态**: [PAUSED] 空闲\n\n");
            }

            return sb.toString();

        } catch (Throwable e) {
            LOG.error("获取团队状态失败", e);
            return "[ERROR] 获取团队状态失败: " + e.getMessage();
        }
    }

    /**
     * 创建新任务
     */
    @ToolMapping(name = "create_task",
                 description = "创建任务（完整版）。支持设置依赖、优先级等。简单场景请使用 task_add() 或 tasks_add()。")
    public String createTask(
            @Param(name = "title", description = "任务标题") String title,
            @Param(name = "description", required = false, description = "任务描述") String description,
            @Param(name = "type", required = false, description = "任务类型 (DEVELOPMENT, EXPLORATION, TESTING, ANALYSIS, DOCUMENTATION)") String type,
            @Param(name = "priority", required = false, description = "优先级 (0-10, 默认5)") Integer priority,
            @Param(name = "dependencies", required = false, description = "依赖的任务ID列表，逗号分隔") String dependencies
    ) {
        try {
            SharedTaskList taskList = mainAgent.getTaskList();

            // 构建任务（使用手动创建而不是 Builder，避免 Lombok 问题）
            TeamTask task = new TeamTask();
            task.setTitle(title);
            task.setDescription(description != null ? description : "");

            // 设置类型
            if (type != null && !type.isEmpty()) {
                try {
                    task.setType(TeamTask.TaskType.valueOf(type.toUpperCase()));
                } catch (IllegalArgumentException e) {
                    return "[ERROR] 无效的任务类型: " + type;
                }
            } else {
                task.setType(TeamTask.TaskType.DEVELOPMENT);
            }

            // 设置优先级
            if (priority != null) {
                task.setPriority(Math.max(0, Math.min(10, priority)));
            } else {
                task.setPriority(5);
            }

            // 设置依赖
            if (dependencies != null && !dependencies.isEmpty()) {
                String[] depIds = dependencies.split(",\\s*");
                task.setDependencies(Arrays.asList(depIds));
            } else {
                task.setDependencies(new ArrayList<>());
            }

            // 添加到任务列表
            CompletableFuture<TeamTask> future = taskList.addTask(task);
            TeamTask addedTask = future.join();

            return String.format("[OK] 任务创建成功\n\n" +
                    "**任务ID**: %s\n" +
                    "**标题**: %s\n" +
                    "**类型**: %s\n" +
                    "**优先级**: %d\n" +
                    "**状态**: %s",
                    addedTask.getId(),
                    addedTask.getTitle(),
                    addedTask.getType(),
                    addedTask.getPriority(),
                    addedTask.getStatus());

        } catch (Throwable e) {
            LOG.error("创建任务失败", e);
            return "[ERROR] 创建任务失败: " + e.getMessage();
        }
    }


    /**
     * 快速添加任务（简化版）
     *
     * 只需要标题，其他参数使用智能默认值。
     * 适合快速创建任务的场景。
     */
    @ToolMapping(name = "task_add",
                 description = "快速添加任务。只需提供标题，其他参数使用智能默认值。适合快速创建任务。")
    public String taskAdd(
            @Param(name = "title", description = "任务标题") String title,
            @Param(name = "description", required = false, description = "任务描述（可选）") String description
    ) {
        // 使用默认值调用完整版 createTask
        return createTask(title, description, null, null, null);
    }

    /**
     * 快速添加多个任务（简化版）
     *
     * 接受标题列表，批量创建任务。
     */
    @ToolMapping(name = "tasks_add",
                 description = "批量添加任务。接受标题列表，一次性创建多个任务。")
    public String tasksAdd(
            @Param(name = "titles", description = "任务标题列表，逗号分隔（如：任务1,任务2,任务3）") String titles
    ) {
        try {
            if (mainAgent == null) {
                return "[WARN] MainAgent 未初始化";
            }

            SharedTaskList taskList = mainAgent.getTaskList();
            String[] titleArray = titles.split(",\\s*");
            List<TeamTask> tasks = new ArrayList<>();

            for (String title : titleArray) {
                if (!title.isEmpty()) {
                    TeamTask task = new TeamTask();
                    task.setTitle(title.trim());
                    task.setDescription("");
                    task.setType(TeamTask.TaskType.DEVELOPMENT);
                    task.setPriority(5);
                    task.setDependencies(new ArrayList<>());
                    tasks.add(task);
                }
            }

            if (tasks.isEmpty()) {
                return "[WARN] 没有有效的任务可创建";
            }

            // 批量添加任务
            List<TeamTask> added = taskList.addTasks(tasks).join();

            StringBuilder sb = new StringBuilder();
            sb.append("[OK] 批量创建任务完成\n\n");
            sb.append("**成功创建**: ").append(added.size()).append(" 个任务\n\n");

            for (int i = 0; i < Math.min(added.size(), 10); i++) {
                TeamTask task = added.get(i);
                sb.append(String.format("%d. `%s` (ID: %s)\n",
                        i + 1, task.getTitle(), task.getId()));
            }

            if (added.size() > 10) {
                sb.append(String.format("... 还有 %d 个任务\n", added.size() - 10));
            }

            return sb.toString();

        } catch (Exception e) {
            LOG.error("批量创建任务失败", e);
            return "[ERROR] 批量创建失败: " + e.getMessage();
        }
    }
    /**
     * 分析任务（使用 LLM 分解任务）
     *
     * 将用户请求分解为多个子任务，返回 JSON 格式的任务建议
     * Agent 可以根据建议逐个调用 create_task 创建任务
     */
    @ToolMapping(name = "analyze_tasks",
                 description = "针对团队协作任务，分析用户请求，将其分解为多个子任务。返回任务分解建议（JSON格式），Agent可以根据建议使用create_task创建任务。")
    public String analyzeTasks(
            @Param(name = "request", description = "用户请求或任务描述") String request) {
        try {
            if (mainAgent == null) {
                return "[WARN] MainAgent 未初始化";
            }

            // 构建 LLM 分析提示词
            String prompt = buildTaskAnalysisPrompt(request);

            // 调用 LLM 分析
            Prompt llmPrompt = Prompt.of(prompt);

            // 通过 kernel 获取 ChatModel
            if (kernel == null) {
                return "[WARN] Kernel 未初始化";
            }

            ChatModel chatModel = kernel.getChatModel();
            if (chatModel == null) {
                return "[WARN] ChatModel 未初始化";
            }

            String response = chatModel.prompt(llmPrompt).call().getContent();

            // 解析并格式化响应
            return formatAnalysisResponse(response);

        } catch (Exception e) {
            LOG.error("任务分析失败", e);
            return "[ERROR] 任务分析失败: " + e.getMessage();
        }
    }

    /**
     * 构建任务分析提示词
     */
    private String buildTaskAnalysisPrompt(String request) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 任务分解分析\n\n");
        sb.append("你是一个任务规划专家。请分析用户请求，将其分解为具体的子任务。\n\n");
        sb.append("## 用户请求\n").append(request).append("\n\n");
        sb.append("## 输出要求\n");
        sb.append("请以 JSON 格式返回任务列表，格式如下：\n");
        sb.append("```json\n");
        sb.append("[\n");
        sb.append("  {\n");
        sb.append("    \"title\": \"任务标题\",\n");
        sb.append("    \"description\": \"任务详细描述\",\n");
        sb.append("    \"type\": \"DEVELOPMENT|EXPLORATION|TESTING|ANALYSIS|DOCUMENTATION\",\n");
        sb.append("    \"priority\": 1-10\n");
        sb.append("  }\n");
        sb.append("]\n");
        sb.append("```\n\n");
        sb.append("## 任务类型\n");
        sb.append("- DEVELOPMENT: 代码开发\n");
        sb.append("- EXPLORATION: 代码探索\n");
        sb.append("- TESTING: 测试相关\n");
        sb.append("- ANALYSIS: 分析诊断\n");
        sb.append("- DOCUMENTATION: 文档编写\n\n");
        sb.append("只返回 JSON，不要其他解释。");
        return sb.toString();
    }

    /**
     * 格式化分析响应
     */
    private String formatAnalysisResponse(String response) {
        StringBuilder sb = new StringBuilder();
        sb.append("[OK] 任务分析完成\n\n");
        sb.append("**任务分解建议**（JSON格式）：\n\n");
        sb.append("```json\n");
        sb.append(response);
        sb.append("\n```\n\n");
        sb.append("**使用方法**：\n");
        sb.append("根据上述建议，使用 create_task() 逐个创建任务。\n");
        sb.append("例如：\n");
        sb.append("```bash\n");
        sb.append("create_task(\n");
        sb.append("    title=\"探索代码库\",\n");
        sb.append("    description=\"分析项目结构\",\n");
        sb.append("    type=\"EXPLORATION\",\n");
        sb.append("    priority=8\n");
        sb.append(")\n");
        sb.append("```\n");
        return sb.toString();
    }

    /**
     * 批量创建任务
     *
     * 根据 JSON 格式的任务列表批量创建任务
     */
    @ToolMapping(name = "create_tasks",
                 description = "批量创建任务。接受 JSON 格式的任务列表，一次性创建多个任务。可以与 analyze_tasks 配合使用。")
    public String createTasks(
            @Param(name = "tasksJson", description = "JSON格式的任务列表，例如：[{\"title\":\"任务1\",\"description\":\"描述\",\"type\":\"DEVELOPMENT\",\"priority\":8}]") String tasksJson) {
        try {
            if (mainAgent == null) {
                return "[WARN] MainAgent 未初始化";
            }

            SharedTaskList taskList = mainAgent.getTaskList();
            List<TeamTask> tasks = parseTasksJson(tasksJson);

            if (tasks.isEmpty()) {
                return "[WARN] 没有有效的任务可创建";
            }

            // 批量添加任务
            List<TeamTask> added = taskList.addTasks(tasks).join();

            StringBuilder sb = new StringBuilder();
            sb.append("[OK] 批量创建任务完成\n\n");
            sb.append("**成功创建**: ").append(added.size()).append(" 个任务\n\n");

            for (int i = 0; i < added.size(); i++) {
                TeamTask task = added.get(i);
                sb.append(String.format("%d. `%s` (ID: %s, 类型: %s, 优先级: %d)\n",
                        i + 1, task.getTitle(), task.getId(), task.getType(), task.getPriority()));
            }

            return sb.toString();

        } catch (Exception e) {
            LOG.error("批量创建任务失败", e);
            return "[ERROR] 批量创建失败: " + e.getMessage();
        }
    }

    /**
     * 解析 JSON 格式的任务列表
     */
    private List<TeamTask> parseTasksJson(String json) {
        List<TeamTask> tasks = new ArrayList<>();

        try {
            ONode root = ONode.deserialize(json);

            // 检查是否为数组
            if (root.isArray()) {
                for (int i = 0; i < root.size(); i++) {
                    ONode taskNode = root.get(i);
                    TeamTask task = parseTaskNode(taskNode, i);
                    if (task != null) {
                        tasks.add(task);
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("解析任务 JSON 失败: {}", e.getMessage());
        }

        return tasks;
    }

    /**
     * 从 ONode 解析单个任务
     */
    private TeamTask parseTaskNode(ONode node, int index) {
        try {
            String title = node.get("title").getString();
            String description = node.get("description").getString();
            String typeStr = node.get("type").getString();
            int priority = node.get("priority").getInt();

            if (title == null || title.isEmpty()) {
                title = "任务 " + (index + 1);
            }
            if (description == null || description.isEmpty()) {
                description = title;
            }

            TeamTask.TaskType type = TeamTask.TaskType.DEVELOPMENT;
            if (typeStr != null && !typeStr.isEmpty()) {
                try {
                    type = TeamTask.TaskType.valueOf(typeStr.toUpperCase());
                } catch (IllegalArgumentException e) {
                    // 使用默认类型
                }
            }

            if (priority < 1 || priority > 10) {
                priority = 7;
            }

            String taskId = "task-" + System.currentTimeMillis() + "-" + index;
            return TeamTask.builder()
                    .id(taskId)
                    .title(title)
                    .description(description)
                    .type(type)
                    .priority(priority)
                    .dependencies(new ArrayList<>())
                    .build();
        } catch (Exception e) {
            LOG.warn("解析任务节点失败: {}, error: {}", node, e.getMessage());
            return null;
        }
    }

    /**
     * 获取状态图标
     */
    private String getStatusIcon(TeamTask.Status status) {
        switch (status) {
            case PENDING: return "[WAIT]";
            case IN_PROGRESS: return "[PROCESS]";
            case COMPLETED: return "[OK]";
            case FAILED: return "[ERROR]";
            case CANCELLED: return "[STOPPED]";
            default: return "[UNKNOWN]";
        }
    }

    /**
     * 快速创建团队成员（简化版）
     *
     * 只需提供 name 和 role，其他参数使用智能默认值。
     * 适合快速创建团队成员的场景。
     */
    @ToolMapping(name = "teammate_quick",
                 description = "快速创建团队成员。只需 name + role，其他全部智能默认（自动开启记忆管理、智能团队名生成）。适合快速创建场景。")
    public String createTeammateQuick(
            @Param(name = "name", description = "团队成员唯一标识（如：security-expert）") String name,
            @Param(name = "role", description = "角色描述（如：安全专家）") String role,
            @Param(name = "description", required = false, description = "详细职责描述（可选，默认使用角色描述）") String description,
            String __cwd
    ) {
        // 使用默认值调用完整版 createTeammate
        return createTeammate(
                name,                           // name
                role,                           // role
                description != null ? description : role,  // description
                null,                           // teamName - 智能生成
                null,                           // systemPrompt - 自动生成
                null,                           // model - 使用默认
                null,                           // tools - 使用默认记忆工具
                null,                           // disallowedTools - 空
                null,                           // skills - 空
                __cwd
        );
    }
    /**
     * 创建团队成员
     *
     * 类似 Claude Code 的 /teammate 命令
     * 可以创建新的团队成员定义，并立即激活
     *
     * 文件命名格式：{teamName}-{roleName}.md（如果指定 teamName）
     *              或者 {roleName}.md（如果未指定 teamName）
     */
    @ToolMapping(name = "teammate",
                 description = "创建团队成员（完整版）。支持完整自定义配置。快速创建请使用 teammate_quick() 或 teammate_template()。")
    public String createTeammate(
            @Param(name = "name", description = "团队成员唯一标识（如：security-expert）") String name,
            @Param(name = "role", description = "角色描述（如：安全专家）") String role,
            @Param(name = "description", description = "详细职责描述") String description,
            @Param(name = "teamName", required = false, description = "团队名称（如：myteam）。如果不指定，将根据角色和描述智能生成语义化名称（如：database-team、security-squad）") String teamName,
            @Param(name = "systemPrompt", required = false, description = "系统提示词，定义行为模式") String systemPrompt,
            @Param(name = "model", required = false, description = "使用的模型（如：默认）") String model,
            @Param(name = "tools", required = false, description = "启用的工具列表，逗号分隔（如：read,write,edit,browser）") String tools,
            @Param(name = "disallowedTools", required = false, description = "禁用的工具列表，逗号分隔") String disallowedTools,
            @Param(name = "skills", required = false, description = "启用的技能列表，逗号分隔（如：expert,terminal,lucene）") String skills,
            String __cwd
    ) {
        try {
            // 自动生成团队名称（如果未提供）
            if (teamName == null || teamName.isEmpty()) {
                // 1. 优先从共享内存中读取当前团队名
                if (mainAgent != null && mainAgent.getSharedMemoryManager() != null) {
                    String currentTeam = mainAgent.getSharedMemoryManager().get(CURRENT_TEAM_KEY);
                    if (currentTeam != null && !currentTeam.isEmpty()) {
                        teamName = currentTeam;
                        LOG.info("从内存中读取到当前团队名称: {}", teamName);
                    }
                }

                // 2. 如果内存中也没有，智能生成新的团队名
                if (teamName == null || teamName.isEmpty()) {
                    // 使用 TeamNameGenerator 根据角色和描述生成有意义的团队名
                    String taskGoal = description != null ? description : role;
                    teamName = TeamNameGenerator.generateTeamName(name, role, taskGoal);

                    LOG.info("智能生成新的团队名称: role={}, description={}, teamName={}",
                            role, description, teamName);
                }
            }

            // 3. 将使用的团队名保存到共享内存（短期记忆，1小时TTL）
            if (mainAgent != null && mainAgent.getSharedMemoryManager() != null) {
                mainAgent.getSharedMemoryManager().putShortTerm(CURRENT_TEAM_KEY, teamName, 3600); // 1小时
                LOG.info("已将团队名称保存到内存: {} (TTL: 1小时)", teamName);
            }

            // 4. 检查是否已存在相同 role 的 agent（避免重复覆盖）
            if (manager.hasAgent(role)) {
                LOG.warn("警告：已存在 role='{}' 的子代理，新成员可能会覆盖它。建议使用唯一的 role 名称。", role);
            }

            // 构建子代理元数据
            SubAgentMetadata metadata = new SubAgentMetadata();
            metadata.setName(name);
            metadata.setDescription(description);
            metadata.setEnabled(true);
            metadata.setTeamName(teamName);

            // 设置启用的工具
            if (tools != null && !tools.isEmpty()) {
                metadata.getTools().addAll(Arrays.asList(tools.split(",\\s*")));
            }

            // 设置禁用的工具
            if (disallowedTools != null && !disallowedTools.isEmpty()) {
                metadata.setDisallowedTools(Arrays.asList(disallowedTools.split(",\\s*")));
            }

            // 设置启用的技能
            if (skills != null && !skills.isEmpty()) {
                // 合并 expertise 定义的技能和 skills 参数定义的技能
                List<String> allSkills = new ArrayList<>();
                if (metadata.getSkills() != null) {
                    allSkills.addAll(metadata.getSkills());
                }
                allSkills.addAll(Arrays.asList(skills.split(",\\s*")));
                metadata.setSkills(allSkills);
            }


            // 设置模型
            if (model != null && !model.isEmpty()) {
                metadata.setModel(model);
            }

            // 自动添加记忆管理相关工具
            List<String> defaultTools = Arrays.asList(
                "memory_store",
                "memory_recall",
                "memory_stats",
                "working_memory_set",
                "working_memory_get"
            );
            metadata.setTools(defaultTools);
            LOG.info("已自动引入 AgentTeamsTool 记忆管理工具");

            // 生成系统提示词（如果没有提供）
            String finalPrompt = systemPrompt;
            if (finalPrompt == null || finalPrompt.isEmpty()) {
                finalPrompt = generateDefaultSystemPrompt(name, role, description);
            }

            // 生成完整的代理定义
            String agentDefinition = metadata.toYamlFrontmatterWithPrompt(finalPrompt);

            // 保存到文件（所有成员都归属于某个团队）
            Path teamsDir = Paths.get(__cwd, ".soloncode", "agentsTeams", teamName);
            Files.createDirectories(teamsDir);
            Path agentFile = teamsDir.resolve(name + ".md");
            LOG.info("创建团队成员: 团队={}, 角色={}, 文件={}", teamName, name, agentFile);

            Files.write(agentFile, agentDefinition.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            LOG.info("Agent 定义已保存到: {}", agentFile);

            // 重新扫描目录以加载新创建的 agent（使用 putIfAbsent 避免重复）
            try {
                Path parentDir = agentFile.getParent();
                manager.agentPool(parentDir, false);
                LOG.info("已重新扫描目录并加载新团队成员: {}", name);
            } catch (Throwable ex) {
                LOG.warn("重新扫描目录失败（文件已保存）: {}", ex.getMessage());
            }

            // 返回结果（使用表格格式）
            StringBuilder result = new StringBuilder();
            result.append("[OK] 团队成员创建成功\n\n");

            // 表格格式的成员信息
            result.append("## 成员信息\n\n");
            result.append("| 属性 | 值 |\n");
            result.append("|------|------|\n");
            result.append(String.format("| **名称** | `%s` |\n", name));
            result.append(String.format("| **角色** | %s |\n", role));
            result.append(String.format("| **描述** | %s |\n", description));
            result.append(String.format("| **所属团队** | %s |\n", teamName));
            result.append(String.format("| **文件路径** | `.soloncode/agentsTeams/%s/%s.md` |\n", teamName, name));


            if (model != null && !model.isEmpty()) {
                result.append(String.format("| **模型** | %s |\n", model));
            }

            // 显示启用的技能
            if (metadata.getSkills() != null && !metadata.getSkills().isEmpty()) {
                result.append(String.format("| **技能** | %s |\n",
                        String.join(", ", metadata.getSkills())));
            }

            // 显示启用的工具
            if (metadata.getTools() != null && !metadata.getTools().isEmpty()) {
                result.append(String.format("| **工具** | %s |\n",
                        String.join(", ", metadata.getTools())));
            }

            // 显示禁用的工具
            if (metadata.getDisallowedTools() != null && !metadata.getDisallowedTools().isEmpty()) {
                result.append(String.format("| **禁用工具** | %s |\n",
                        String.join(", ", metadata.getDisallowedTools())));
            }

            // 显示 MCP 服务器
            if (metadata.getMcpServers() != null && !metadata.getMcpServers().isEmpty()) {
                result.append(String.format("| **MCP服务** | %s |\n",
                        String.join(", ", metadata.getMcpServers())));
            }

            result.append("| **状态** | 🟢 已激活 |\n");

            result.append("\n**使用方法**:\n");
            result.append("```bash\n");
            result.append(String.format("task(subagent_type=\"%s\", prompt=\"你的任务描述\")\n", name));
            result.append("```\n");

            return result.toString();

        } catch (Throwable e) {
            LOG.error("创建团队成员失败", e);
            return "[ERROR] 创建团队成员失败: " + e.getMessage();
        }
    }

    /**
     * 列出团队成员（支持按团队筛选）
     *
     * 类似 Claude Code 的 /teammates 命令
     * 使用表格格式输出
     *
     * @param teamName 可选。指定团队名称，只显示该团队的成员
     */
    @ToolMapping(name = "teammates",
                 description = "列出团队成员，支持按团队筛选。只显示自定义创建的团队成员，不包括内置子代理。")
    public String listTeammates(
            @Param(name = "teamName", required = false, description = "可选。团队名称（如 'test'），只显示该团队的成员") String teamName,
            @Param(name = "includeBuiltIn", required = false, description = "可选。是否包括内置子代理（explore、bash、plan等），默认false") Boolean includeBuiltIn
    ) {
        try {
            List<Subagent> allAgents = new ArrayList<>(manager.getAgents());

            // 根据团队名称筛选
            List<Subagent> agents;
            if (teamName != null && !teamName.isEmpty()) {
                // 指定团队名：只显示该团队的成员
                agents = allAgents.stream()
                        .filter(agent -> agent.getMetadata().hasTeamName() &&
                                       agent.getMetadata().getTeamName().equals(teamName))
                        .collect(java.util.stream.Collectors.toList());
            } else if (includeBuiltIn != null && includeBuiltIn) {
                // 显式要求包括内置agents：显示所有
                agents = allAgents;
            } else {
                // 默认：只显示自定义团队成员（有 teamName 的）
                agents = allAgents.stream()
                        .filter(agent -> agent.getMetadata().hasTeamName())
                        .collect(java.util.stream.Collectors.toList());
            }

            StringBuilder result = new StringBuilder();

            // 标题：显示是否筛选
            if (teamName != null && !teamName.isEmpty()) {
                result.append(String.format("## 团队成员: %s\n\n", teamName));
            } else if (includeBuiltIn != null && includeBuiltIn) {
                result.append("## 所有子代理（包括内置）\n\n");
            } else {
                result.append("## 团队成员（自定义）\n\n");
            }

            if (agents.isEmpty()) {
                if (teamName != null && !teamName.isEmpty()) {
                    result.append(String.format("[WARN] 团队 '%s' 中没有成员。\n\n", teamName));
                    result.append("使用 `teammate()` 命令创建新成员。\n");
                } else {
                    result.append("[WARN] 当前没有团队成员。\n\n");
                    result.append("使用 `teammate()` 命令创建新成员。\n");
                }
                return result.toString();
            }

            // 表格格式的成员列表
            result.append("| 名称 | 角色 | 描述 | 团队 | 模型 |\n");
            result.append("|------|------|------|------|------|------|\n");

            for (Subagent agent : agents) {
                String name = String.format("`%s`", agent.name());
                String role = agent.getClass().getSimpleName().replace("Subagent", "");
                String desc = truncate(agent.getDescription(), 30);
                String team = agent.getMetadata().hasTeamName() ? agent.getMetadata().getTeamName() : "-";
                String model = agent.getMetadata().getModel() != null ? agent.getMetadata().getModel()  : "默认";

                result.append(String.format("| %s | %s | %s | %s | %s |\n",
                        name, role, desc, team, model));
            }

            if (teamName != null && !teamName.isEmpty()) {
                result.append(String.format("\n**总计**: %d 位成员（团队: %s）\n\n", agents.size(), teamName));
            } else {
                result.append(String.format("\n**总计**: %d 位活跃成员\n\n", agents.size()));
            }

            // 添加使用提示
            result.append("**快速操作**:\n");
            result.append("```bash\n");
            result.append("# 创建新成员\n");
            result.append("teammate(name=\"expert\", role=\"专家\", description=\"...\")\n\n");
            result.append("# 调用成员\n");
            result.append("task(subagent_type=\"explore\", prompt=\"任务描述\")\n\n");
            result.append("# 查看任务状态\n");
            result.append("team_status()\n");
            result.append("```\n");

            return result.toString();

        } catch (Throwable e) {
            LOG.error("列出团队成员失败", e);
            return "[ERROR] 列出团队成员失败: " + e.getMessage();
        }
    }

    /**
     * 移除团队成员
     */
    @ToolMapping(name = "remove_teammate",
                 description = "移除指定的团队成员。注意：这只是禁用成员，不会删除配置文件。")
    public String removeTeammate(
            @Param(name = "name", description = "要移除的团队成员名称") String name,
            String __cwd
    ) {
        try {
            // 查找成员
            Subagent agent = manager.getAgent(name);
            if (agent == null) {
                return String.format("[ERROR] 未找到团队成员: `%s`\n\n可用的成员:\n%s",
                        name, listTeammates(null, null));
            }

            // 禁用成员（通过修改配置文件）
            Path agentsDir = Paths.get(__cwd, ".soloncode", "agents");
            Path agentFile = agentsDir.resolve(name + ".md");

            if (Files.exists(agentFile)) {
                String content = new String(Files.readAllBytes(agentFile), java.nio.charset.StandardCharsets.UTF_8);

                // 将 enabled: true 改为 enabled: false
                content = content.replace("enabled: true", "enabled: false");

                Files.write(agentFile, content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }

            return String.format("[OK] 团队成员已禁用: `%s`\n\n" +
                    "**提示**: 配置文件已保留，如需重新激活，请编辑 `.soloncode/agents/%s.md` 并设置 `enabled: true`。",
                    name, name);

        } catch (Throwable e) {
            LOG.error("移除团队成员失败", e);
            return "[ERROR] 移除团队成员失败: " + e.getMessage();
        }
    }

    @ToolMapping(name = "isTeamsEnabled",
            description = "检查是否已开启团队功能")
    public String isTeamsEnabled() {
        return kernel.getProperties().isTeamsEnabled() ? "团队功能已启用" : "[WARN] 团队功能未启用。请先启用团队功能。";
    }

    /**
     * 生成默认系统提示词
     */
    private String generateDefaultSystemPrompt(String name, String role, String description) {
        StringBuilder prompt = new StringBuilder();
        prompt.append(String.format("# %s\n\n", role));
        prompt.append(String.format("你是 %s，专门负责 %s。\n\n", role, description));
        prompt.append("## 工作原则\n\n");
        prompt.append("1. **专业专注**: 始终在你的专业领域内提供建议和解决方案\n");
        prompt.append("2. **平衡优先**: 同时看效率比\n");
        prompt.append("3. **协作配合**: 与其他团队成员保持良好沟通\n");

        prompt.append("## 沟通风格\n\n");
        prompt.append("- 使用清晰、简洁的语言\n");
        prompt.append("- 解释技术决策的理由\n");
        prompt.append("- 在不确定时主动寻求帮助\n");

        return prompt.toString();
    }

    /**
     * 截断文本
     */
    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength - 3) + "...";
    }


    /**
     * 工具 1：智能存储记忆
     *
     * <p>自动分类和评分，无需用户选择记忆类型
     *
     * <p>**自动分类规则**：
     * - 决策类（"决策"、"架构"、"采用"）→ 永久记忆
     * - 任务结果类（"完成"、"实现"、"修复"）→ 长期记忆
     * - 临时上下文（"正在"、"尝试"、"临时"）→ 工作记忆
     * - 知识类（"API"、"设计"、"配置"）→ 永久记忆
     *
     * <p>**重要性评分**（0-10分）：
     * - 内容特征（30%）：代码、数据、文件路径
     * - 观察类型（25%）：决策=2.5, 架构=2.5
     * - 关键词（25%）：关键关键词=+4.0
     * - 时间新鲜度（10%）：30分钟内=+0.5
     * - 访问热度（10%）：对数增长
     *
     * @param content 记忆内容（必填）
     * @param key 记忆键（可选，不填则自动生成 UUID）
     * @return 存储结果
     *
     * @sample
     * memory_store(content="决定使用 MemoryBank 架构", key="architecture-decision")
     * → "[OK] 已存储到 永久记忆 (重要性: 8.5/10, TTL: 永久)"
     *
     * @sample
     * memory_store(content="正在分析代码结构")
     * → "[OK] 已存储到 工作记忆 (重要性: 3.2/10, TTL: 10分钟)"
     */
    @ToolMapping(name = "memory_store",
                 description = "智能存储记忆（自动分类和评分）。根据内容自动判断存储类型（永久/7天/1小时/10分钟），无需手动选择。")
    public String memoryStore(
            @Param(name = "content", description = "记忆内容（必填）") String content,
            @Param(name = "key", description = "记忆键（可选，不填则自动生成）", required = false) String key) {
        if (intelligentMemoryManager == null) {
            return "[WARN] 智能记忆管理器未初始化";
        }
        // 使用智能记忆管理器
        return intelligentMemoryManager.store(key, content);
    }

    /**
     * 工具 2：智能检索记忆
     *
     * <p>基于相关性排序，只返回最相关的记忆
     *
     * <p>**相关性算法**：
     * - 文本匹配（40%）：关键词重叠度
     * - 重要性（30%）：重要性分数
     * - 时间新鲜度（20%）：1小时内=+2.0
     * - 访问热度（10%）：对数增长
     *
     * @param query 查询内容（空则返回所有记忆）
     * @param limit 返回数量限制（默认 10）
     * @return 检索结果
     *
     * @sample
     * memory_recall(query="登录功能", limit=5)
     * → "找到 5 条记忆..."
     */
    @ToolMapping(name = "memory_recall",
                 description = "智能检索记忆（按相关性排序）。基于文本匹配、重要性、时间新鲜度等多维度排序，只返回最相关的记忆。")
    public String memoryRecall(
            @Param(name = "query", description = "查询内容（空则返回所有记忆）", required = false) String query,
            @Param(name = "limit", description = "返回数量限制（默认 10）", required = false) Integer limit) {
        if (intelligentMemoryManager == null) {
            return "[WARN] 智能记忆管理器未初始化";
        }
        return intelligentMemoryManager.retrieve(query, limit != null ? limit : 10);
    }

    /**
     * 工具 3：获取记忆统计信息
     *
     * @return 统计信息
     */
    @ToolMapping(name = "memory_stats",
                 description = "获取记忆统计信息。包括各层级记忆数量、智能层状态、内存使用情况等。")
    public String memoryStats() {
        if (intelligentMemoryManager == null) {
            return "[WARN] 智能记忆管理器未初始化";
        }

        try {
            Map<String, Object> stats = intelligentMemoryManager.getStats();

            // 格式化输出
            StringBuilder sb = new StringBuilder();
            sb.append("[STATS] 记忆系统统计:\n\n");

            // 各层级记忆数量
            sb.append("工作记忆: ").append(stats.getOrDefault("workingCount", 0)).append(" 条\n");
            sb.append("短期记忆: ").append(stats.getOrDefault("shortTermCount", 0)).append(" 条\n");
            sb.append("长期记忆: ").append(stats.getOrDefault("longTermCount", 0)).append(" 条\n");
            sb.append("知识记忆: ").append(stats.getOrDefault("knowledgeCount", 0)).append(" 条\n");

            sb.append("\n");

            // 智能层状态
            sb.append("智能层: ").append(stats.getOrDefault("intelligentLayer", "未启用")).append("\n");
            sb.append("自动合并: ").append(stats.getOrDefault("autoConsolidate", false)).append("\n");

            if (stats.containsKey("consolidationThreshold")) {
                sb.append("合并阈值: ").append(stats.get("consolidationThreshold")).append("\n");
            }
            if (stats.containsKey("consolidationInterval")) {
                sb.append("合并间隔: ").append(stats.get("consolidationInterval")).append("ms\n");
            }

            sb.append("\n");

            // 内存使用情况
            if (stats.containsKey("memoryUsed")) {
                Object usedObj = stats.get("memoryUsed");
                Object maxObj = stats.getOrDefault("memoryMax", "100MB");

                // 处理可能的字符串或数字类型
                long used = 0;
                long max = 100 * 1024 * 1024;

                if (usedObj instanceof Number) {
                    used = ((Number) usedObj).longValue();
                }
                if (maxObj instanceof Number) {
                    max = ((Number) maxObj).longValue();
                }

                double ratio = (double) used / max * 100;

                sb.append(String.format("内存使用: %.2fMB / %.2fMB (%.1f%%)\n",
                        used / (1024.0 * 1024),
                        max / (1024.0 * 1024),
                        ratio));
            }

            return sb.toString();

        } catch (Exception e) {
            LOG.error("获取记忆统计失败", e);
            return "[ERROR] 获取记忆统计失败: " + e.getMessage();
        }
    }

    /**
     * 获取工作记忆状态
     */
    @ToolMapping(name = "get_working_memory",
                 description = "获取当前工作记忆状态（包括当前任务、状态、步骤等）")
    public String getWorkingMemory() {
        try {
            if (mainAgent == null || mainAgent.getSharedMemoryManager() == null) {
                return "[WARN] 共享记忆未初始化";
            }

            // 使用默认的 taskId "main-agent"
            String taskId = "main-agent";
            org.noear.solon.bot.core.memory.WorkingMemory workingMemory =
                mainAgent.getSharedMemoryManager().getWorking(taskId);

            if (workingMemory == null) {
                return "[WARN] 没有工作记忆";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("## 工作记忆状态\n\n");

            if (workingMemory.getTaskDescription() != null) {
                sb.append("**当前任务**: ").append(workingMemory.getTaskDescription()).append("\n");
            }
            if (workingMemory.getStatus() != null) {
                sb.append("**状态**: ").append(workingMemory.getStatus()).append("\n");
            }
            sb.append("**步骤**: ").append(workingMemory.getStep()).append("\n");
            if (workingMemory.getCurrentAgent() != null) {
                sb.append("**当前代理**: ").append(workingMemory.getCurrentAgent()).append("\n");
            }

            return sb.toString();
        } catch (Exception e) {
            LOG.error("获取工作记忆失败", e);
            return "[ERROR] 获取失败: " + e.getMessage();
        }
    }

    /**
     * 更新工作记忆状态
     */
    @ToolMapping(name = "update_working_memory",
                 description = "更新工作记忆状态（设置当前任务、状态、步骤等）")
    public String updateWorkingMemory(
            @Param(name = "field", description = "字段名称（taskDescription/status/step/currentAgent）") String field,
            @Param(name = "value", description = "字段值") String value) {
        try {
            if (mainAgent == null || mainAgent.getSharedMemoryManager() == null) {
                return "[WARN] 共享记忆未初始化";
            }

            // 使用默认的 taskId "main-agent"
            String taskId = "main-agent";
            org.noear.solon.bot.core.memory.WorkingMemory workingMemory =
                mainAgent.getSharedMemoryManager().getWorking(taskId);

            // 如果不存在，创建一个新的
            if (workingMemory == null) {
                workingMemory = new org.noear.solon.bot.core.memory.WorkingMemory(taskId);
            }

            switch (field.toLowerCase()) {
                case "taskdescription":
                case "currenttask":
                    workingMemory.setTaskDescription(value);
                    break;
                case "status":
                    workingMemory.setStatus(value);
                    break;
                case "step":
                    workingMemory.setStep(Integer.parseInt(value));
                    break;
                case "currentagent":
                    workingMemory.setCurrentAgent(value);
                    break;
                default:
                    return "[ERROR] 无效的字段名: " + field + "。支持的字段：taskDescription、status、step、currentAgent";
            }

            // 保存更新后的工作记忆
            mainAgent.getSharedMemoryManager().storeWorking(workingMemory);

            LOG.debug("更新工作记忆: {}={}", field, value);
            return "[OK] 工作记忆已更新: " + field + " = " + value;
        } catch (NumberFormatException e) {
            return "[ERROR] 步骤必须是数字: " + value;
        } catch (Exception e) {
            LOG.error("更新工作记忆失败", e);
            return "[ERROR] 更新失败: " + e.getMessage();
        }
    }

    /**
     * 发布团队事件
     */
    @ToolMapping(name = "publish_team_event",
                 description = "发布团队事件（通知其他代理任务状态变化）")
    public String publishTeamEvent(
            @Param(name = "eventType", description = "事件类型（TASK_COMPLETED/TASK_FAILED/INFO等）") String eventType,
            @Param(name = "data", description = "事件数据") String data) {
        try {
            if (mainAgent == null || mainAgent.getEventBus() == null) {
                return "[WARN] 事件总线未初始化";
            }

            org.noear.solon.bot.core.event.AgentEventType type;
            try {
                type = org.noear.solon.bot.core.event.AgentEventType.valueOf(eventType.toUpperCase());
            } catch (IllegalArgumentException e) {
                return "[ERROR] 无效的事件类型: " + eventType;
            }

            org.noear.solon.bot.core.event.AgentEvent event =
                new org.noear.solon.bot.core.event.AgentEvent(type, data, null);
            mainAgent.getEventBus().publish(event);

            LOG.debug("发布团队事件: type={}, data={}", type, data);
            return "[OK] 团队事件已发布: " + type;
        } catch (Exception e) {
            LOG.error("发布团队事件失败", e);
            return "[ERROR] 发布失败: " + e.getMessage();
        }
    }

    /**
     * 获取任务统计信息
     */
    @ToolMapping(name = "get_task_statistics",
                 description = "获取任务统计信息（总任务数、已完成、失败、进行中、待认领等）")
    public String getTaskStatistics() {
        try {
            if (mainAgent == null) {
                return "[WARN] MainAgent 未初始化";
            }

            SharedTaskList.TaskStatistics stats = mainAgent.getTaskList().getStatistics();

            StringBuilder sb = new StringBuilder();
            sb.append("## 任务统计\n\n");
            sb.append("**总任务数**: ").append(stats.totalTasks).append("\n");
            sb.append("**已完成**: ").append(stats.completedTasks).append("\n");
            sb.append("**失败**: ").append(stats.failedTasks).append("\n");
            sb.append("**进行中**: ").append(stats.inProgressTasks).append("\n");
            sb.append("**待认领**: ").append(stats.pendingTasks).append("\n");

            return sb.toString();
        } catch (Exception e) {
            LOG.error("获取任务统计失败", e);
            return "[ERROR] 获取失败: " + e.getMessage();
        }
    }


    /**
     * 认领任务
     */
    @ToolMapping(name = "claim_task",
                 description = "认领待处理的任务（将任务状态从PENDING改为IN_PROGRESS）")
    public String claimTask(
            @Param(name = "taskId", description = "任务ID") String taskId,
            @Param(name = "agentName", description = "认领任务的代理名称（如：explore、plan、bash）") String agentName) {
        try {
            if (mainAgent == null) {
                return "[WARN] MainAgent 未初始化";
            }

            SharedTaskList taskList = mainAgent.getTaskList();
            TeamTask task = taskList.getTask(taskId);

            if (task == null) {
                return "[ERROR] 任务不存在: " + taskId;
            }

            if (task.getStatus() != TeamTask.Status.PENDING) {
                return "[WARN] 任务状态不是待认领: " + task.getStatus();
            }

            taskList.claimTask(taskId, agentName);

            LOG.info("任务已认领: taskId={}, agent={}", taskId, agentName);
            return "[OK] 任务已认领: " + taskId + " 由 " + agentName;
        } catch (Exception e) {
            LOG.error("认领任务失败", e);
            return "[ERROR] 认领失败: " + e.getMessage();
        }
    }

    /**
     * 完成任务
     */
    @ToolMapping(name = "complete_task",
                 description = "标记任务为已完成（将任务状态改为COMPLETED）。如果任务是PENDING状态，会自动先认领再完成。")
    public String completeTask(
            @Param(name = "taskId", description = "任务ID") String taskId,
            @Param(name = "result", description = "任务执行结果（可选）") String result) {
        try {
            if (mainAgent == null) {
                return "[WARN] MainAgent 未初始化";
            }

            SharedTaskList taskList = mainAgent.getTaskList();
            TeamTask task = taskList.getTask(taskId);

            if (task == null) {
                return "[ERROR] 任务不存在: " + taskId;
            }

            // 智能处理：如果任务是 PENDING，自动先认领
            if (task.getStatus() == TeamTask.Status.PENDING) {
                LOG.info("任务处于PENDING状态，自动认领: taskId={}", taskId);
                taskList.claimTask(taskId, "auto-complete");
            }

            // 检查任务状态是否可以完成
            if (task.getStatus() != TeamTask.Status.IN_PROGRESS) {
                return "[WARN] 任务状态不是进行中: " + task.getStatus();
            }

            taskList.completeTask(taskId, result);

            // 如果提供了结果，额外存储到记忆
            if (result != null && !result.isEmpty()) {
                String key = "task-result:" + task.getTitle() + ":" + System.currentTimeMillis();
                mainAgent.getSharedMemoryManager().putLongTerm(key, result.toString(), 604800);
            }

            LOG.info("任务已完成: taskId={}", taskId);
            return "[OK] 任务已完成: " + taskId;
        } catch (Exception e) {
            LOG.error("完成任务失败", e);
            return "[ERROR] 完成失败: " + e.getMessage();
        }
    }

    /**
     * 标记任务失败
     */
    @ToolMapping(name = "fail_task",
                 description = "标记任务为失败（将任务状态改为FAILED）")
    public String failTask(
            @Param(name = "taskId", description = "任务ID") String taskId,
            @Param(name = "errorMessage", description = "错误消息（说明失败原因）") String errorMessage) {
        try {
            if (mainAgent == null) {
                return "[WARN] MainAgent 未初始化";
            }

            SharedTaskList taskList = mainAgent.getTaskList();
            TeamTask task = taskList.getTask(taskId);

            if (task == null) {
                return "[ERROR] 任务不存在: " + taskId;
            }

            taskList.failTask(taskId, errorMessage);

            LOG.error("任务失败: taskId={}, error={}", taskId, errorMessage);
            return "[ERROR] 任务已标记为失败: " + taskId;
        } catch (Exception e) {
            LOG.error("标记任务失败", e);
            return "[ERROR] 操作失败: " + e.getMessage();
        }
    }

    /**
     * 删除任务
     */
    @ToolMapping(name = "remove_task",
                 description = "删除指定的任务。注意：只能删除状态为 PENDING 的任务，已认领的任务需要先释放才能删除。")
    public String removeTask(
            @Param(name = "taskId", description = "任务ID") String taskId) {
        try {
            if (mainAgent == null) {
                return "[WARN] MainAgent 未初始化";
            }

            SharedTaskList taskList = mainAgent.getTaskList();
            TeamTask task = taskList.getTask(taskId);

            if (task == null) {
                return "[ERROR] 任务不存在: " + taskId;
            }

            // 检查任务状态
            if (task.getStatus() == TeamTask.Status.IN_PROGRESS) {
                return "[WARN] 任务正在执行中，无法删除。请先使用 release_task 释放任务。";
            }

            boolean removed = taskList.removeTask(taskId);

            if (removed) {
                LOG.info("任务已删除: taskId={}", taskId);
                return "[OK] 任务已删除: " + taskId;
            } else {
                return "[ERROR] 删除任务失败: " + taskId;
            }
        } catch (Exception e) {
            LOG.error("删除任务失败", e);
            return "[ERROR] 删除失败: " + e.getMessage();
        }
    }

    /**
     * 释放任务
     */
    @ToolMapping(name = "release_task",
                 description = "释放已认领的任务（将任务状态重新改为PENDING，其他代理可以认领）。用于取消当前认领或让其他代理接管。")
    public String releaseTask(
            @Param(name = "taskId", description = "任务ID") String taskId,
            @Param(name = "agentName", description = "当前认领该任务的代理名称") String agentName) {
        try {
            if (mainAgent == null) {
                return "[WARN] MainAgent 未初始化";
            }

            SharedTaskList taskList = mainAgent.getTaskList();
            TeamTask task = taskList.getTask(taskId);

            if (task == null) {
                return "[ERROR] 任务不存在: " + taskId;
            }

            // 检查任务状态
            if (task.getStatus() != TeamTask.Status.IN_PROGRESS) {
                return "[WARN] 任务状态不是进行中，无法释放: " + task.getStatus();
            }

            // 检查认领者
            if (!agentName.equals(task.getClaimedBy())) {
                return "[WARN] 不是任务的认领者。当前认领者: " + task.getClaimedBy();
            }

            boolean released = taskList.releaseTask(taskId, agentName);

            if (released) {
                LOG.info("任务已释放: taskId={}, agent={}", taskId, agentName);
                return "[OK] 任务已释放: " + taskId + "，其他代理现在可以认领";
            } else {
                return "[ERROR] 释放任务失败: " + taskId;
            }
        } catch (Exception e) {
            LOG.error("释放任务失败", e);
            return "[ERROR] 释放失败: " + e.getMessage();
        }
    }

    /**
     * 获取任务详情
     */
    @ToolMapping(name = "get_task_details",
                 description = "获取任务的详细信息（包括ID、标题、描述、状态、依赖等）")
    public String getTaskDetails(
            @Param(name = "taskId", description = "任务ID") String taskId) {
        try {
            if (mainAgent == null) {
                return "[WARN] MainAgent 未初始化";
            }

            SharedTaskList taskList = mainAgent.getTaskList();
            TeamTask task = taskList.getTask(taskId);

            if (task == null) {
                return "[ERROR] 任务不存在: " + taskId;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("## 任务详情\n\n");
            sb.append("**ID**: ").append(task.getId()).append("\n");
            sb.append("**标题**: ").append(task.getTitle()).append("\n");
            sb.append("**描述**: ").append(task.getDescription()).append("\n");
            sb.append("**类型**: ").append(task.getType()).append("\n");
            sb.append("**状态**: ").append(task.getStatus()).append("\n");
            sb.append("**优先级**: ").append(task.getPriority()).append("\n");

            if (task.getClaimedBy() != null) {
                sb.append("**认领者**: ").append(task.getClaimedBy()).append("\n");
            }

            if (task.getDependencies() != null && !task.getDependencies().isEmpty()) {
                sb.append("**依赖任务**: ");
                sb.append(String.join(", ", task.getDependencies())).append("\n");
            }

            if (task.getResult() != null) {
                sb.append("**结果**: ").append(task.getResult()).append("\n");
            }

            return sb.toString();
        } catch (Exception e) {
            LOG.error("获取任务详情失败", e);
            return "[ERROR] 获取失败: " + e.getMessage();
        }
    }

    /**
     * 列出所有任务
     */
    @ToolMapping(name = "list_all_tasks",
                 description = "列出所有任务（包括待认领、进行中、已完成、失败的任务）")
    public String listAllTasks(
            @Param(name = "status", description = "可选：按状态过滤（PENDING/IN_PROGRESS/COMPLETED/FAILED）") String status) {
        try {
            if (mainAgent == null) {
                return "[WARN] MainAgent 未初始化";
            }

            SharedTaskList taskList = mainAgent.getTaskList();
            List<TeamTask> tasks;

            if (status != null && !status.isEmpty()) {
                TeamTask.Status taskStatus;
                try {
                    taskStatus = TeamTask.Status.valueOf(status.toUpperCase());
                } catch (IllegalArgumentException e) {
                    return "[ERROR] 无效的状态: " + status;
                }
                tasks = taskList.getTasksByStatus(taskStatus);
            } else {
                tasks = taskList.getAllTasks();
            }

            if (tasks.isEmpty()) {
                return "[WARN] 没有任务";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("## 任务列表 (").append(tasks.size()).append(" 个任务)\n\n");

            for (TeamTask task : tasks) {
                String statusIcon = getStatusIcon(task.getStatus());
                sb.append(String.format("%s **%s** (ID: %s)\n",
                        statusIcon, task.getTitle(), task.getId()));
                sb.append(String.format("  - 类型: %s\n", task.getType()));
                sb.append(String.format("  - 优先级: %d\n", task.getPriority()));
                sb.append(String.format("  - 状态: %s\n", task.getStatus()));

                if (task.getClaimedBy() != null) {
                    sb.append(String.format("  - 认领者: %s\n", task.getClaimedBy()));
                }

                sb.append("\n");
            }

            return sb.toString();
        } catch (Exception e) {
            LOG.error("列出任务失败", e);
            return "[ERROR] 列出失败: " + e.getMessage();
        }
    }

    /**
     * 添加任务依赖
     */
    @ToolMapping(name = "add_task_dependency",
                 description = "为任务添加依赖关系（任务将等待依赖任务完成后才能开始）")
    public String addTaskDependency(
            @Param(name = "taskId", description = "任务ID") String taskId,
            @Param(name = "dependsOnTaskId", description = "依赖的任务ID") String dependsOnTaskId) {
        try {
            if (mainAgent == null) {
                return "[WARN] MainAgent 未初始化";
            }

            SharedTaskList taskList = mainAgent.getTaskList();
            TeamTask task = taskList.getTask(taskId);

            if (task == null) {
                return "[ERROR] 任务不存在: " + taskId;
            }

            TeamTask dependsOnTask = taskList.getTask(dependsOnTaskId);
            if (dependsOnTask == null) {
                return "[ERROR] 依赖任务不存在: " + dependsOnTaskId;
            }

            // 添加依赖到任务的依赖列表
            if (task.getDependencies() == null) {
                task.setDependencies(new java.util.ArrayList<>());
            }
            task.getDependencies().add(dependsOnTaskId);

            LOG.info("添加任务依赖: {} depends on {}", taskId, dependsOnTaskId);
            return "[OK] 依赖关系已添加: " + taskId + " 依赖于 " + dependsOnTaskId;
        } catch (Exception e) {
            LOG.error("添加任务依赖失败", e);
            return "[ERROR] 添加失败: " + e.getMessage();
        }
    }

    /**
     * 获取可认领的任务
     */
    @ToolMapping(name = "get_claimable_tasks",
                 description = "获取当前可以认领的任务（所有依赖已完成的状态为PENDING的任务）")
    public String getClaimableTasks(
            @Param(name = "limit", description = "返回结果数量限制，默认10") Integer limit) {
        try {
            if (mainAgent == null) {
                return "[WARN] MainAgent 未初始化";
            }

            SharedTaskList taskList = mainAgent.getTaskList();
            List<TeamTask> pendingTasks = taskList.getTasksByStatus(TeamTask.Status.PENDING);

            // 过滤出依赖已完成的任务
            List<TeamTask> claimableTasks = new java.util.ArrayList<>();
            for (TeamTask task : pendingTasks) {
                // 手动检查依赖是否都已完成
                boolean allDependenciesCompleted = true;
                if (task.getDependencies() != null && !task.getDependencies().isEmpty()) {
                    for (String depId : task.getDependencies()) {
                        TeamTask depTask = taskList.getTask(depId);
                        if (depTask == null || !depTask.isCompleted()) {
                            allDependenciesCompleted = false;
                            break;
                        }
                    }
                }

                if (allDependenciesCompleted) {
                    claimableTasks.add(task);
                }
            }

            int actualLimit = limit != null && limit > 0 ? limit : 10;
            if (claimableTasks.size() > actualLimit) {
                claimableTasks = claimableTasks.subList(0, actualLimit);
            }

            if (claimableTasks.isEmpty()) {
                return "[WARN] 没有可认领的任务";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("## 可认领任务 (").append(claimableTasks.size()).append(" 个任务)\n\n");

            for (TeamTask task : claimableTasks) {
                sb.append(String.format("- **%s** (ID: %s)\n", task.getTitle(), task.getId()));
                sb.append(String.format("  - 类型: %s\n", task.getType()));
                sb.append(String.format("  - 优先级: %d\n", task.getPriority()));
                sb.append(String.format("  - 描述: %s\n\n",
                        truncate(task.getDescription(), 100)));
            }

            return sb.toString();
        } catch (Exception e) {
            LOG.error("获取可认领任务失败", e);
            return "[ERROR] 获取失败: " + e.getMessage();
        }
    }

    /**
     * 更新任务结果
     */
    @ToolMapping(name = "update_task_result",
                 description = "更新任务的执行结果（用于部分完成或阶段性成果）")
    public String updateTaskResult(
            @Param(name = "taskId", description = "任务ID") String taskId,
            @Param(name = "result", description = "任务结果（可以是进度报告、部分成果等）") String result) {
        try {
            if (mainAgent == null) {
                return "[WARN] MainAgent 未初始化";
            }

            SharedTaskList taskList = mainAgent.getTaskList();
            TeamTask task = taskList.getTask(taskId);

            if (task == null) {
                return "[ERROR] 任务不存在: " + taskId;
            }

            task.setResult(result);

            LOG.info("任务结果已更新: taskId={}", taskId);
            return "[OK] 任务结果已更新: " + taskId;
        } catch (Exception e) {
            LOG.error("更新任务结果失败", e);
            return "[ERROR] 更新失败: " + e.getMessage();
        }
    }


    /**
     * 发送消息给其他代理
     */
    @ToolMapping(name = "send_message",
                 description = "发送消息给其他代理（点对点通信）。注意：避免相互发送消息导致死锁。")
    public String sendMessage(
            @Param(name = "targetAgent", description = "目标代理名称（如：explore、plan、bash、general-purpose）") String targetAgent,
            @Param(name = "message", description = "消息内容") String message) {
        try {
            if (mainAgent == null || mainAgent.getMessageChannel() == null) {
                return "[WARN] 消息通道未启用";
            }

            // 检查是否会导致死锁（main-agent 发送给自己）
            if ("main-agent".equals(targetAgent)) {
                return "[ERROR] 不能发送消息给自己，这会导致死锁";
            }

            // 使用 Builder 创建消息
            org.noear.solon.bot.core.message.AgentMessage<String> agentMessage =
                org.noear.solon.bot.core.message.AgentMessage.<String>of(message)
                    .from("main-agent")
                    .to(targetAgent)
                    .type("command")
                    .build();

            // 发送消息并等待完成（超时5秒）
            java.util.concurrent.CompletableFuture<org.noear.solon.bot.core.message.MessageAck> ackFuture =
                mainAgent.getMessageChannel().send(agentMessage);

            // 获取结果（超时5秒）
            org.noear.solon.bot.core.message.MessageAck ack =
                ackFuture.get(5, java.util.concurrent.TimeUnit.SECONDS);

            if (ack.isSuccess()) {
                LOG.debug("消息已发送: to={}, msg={}", targetAgent, message);
                return "[OK] 消息已发送给 " + targetAgent;
            } else {
                LOG.warn("消息发送失败: to={}, error={}", targetAgent, ack.getMessage());
                return "[ERROR] 消息发送失败: " + ack.getMessage();
            }
        } catch (java.util.concurrent.TimeoutException e) {
            LOG.error("发送消息超时（可能死锁）: to={}", targetAgent);
            return "[WARN] 发送超时（可能死锁）: 目标代理可能在等待当前代理的响应";
        } catch (Exception e) {
            LOG.error("发送消息失败", e);
            return "[ERROR] 发送失败: " + e.getMessage();
        }
    }

    /**
     * 获取可用代理列表
     */
    @ToolMapping(name = "list_agents",
                 description = "列出所有可用的子代理（用于查看可以向哪些代理发送消息）")
    public String listAgents() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("## 可用的子代理\n\n");

            sb.append("**内置子代理**:\n");
            sb.append("- `explore`: 代码库探索专家（快速查找文件和理解结构）\n");
            sb.append("- `plan`: 软件架构师（设计实现方案和执行计划）\n");
            sb.append("- `bash`: 命令执行专家（处理 git、构建等终端任务）\n");
            sb.append("- `general-purpose`: 通用代理（处理复杂的多步骤任务）\n");
            sb.append("- `solon-code-guide`: Solon 框架文档指南专家\n\n");

            sb.append("**团队成员**:\n");
            // 从 SubagentManager 获取自定义团队成员
            for (Subagent agent : manager.getAgents()) {
                if (!agent.name().equals("explore")
                    && !agent.name().equals("plan")
                    && !agent.name().equals("bash")
                    && !agent.name().equals("general-purpose")) {
                    sb.append(String.format("- `%s`: %s\n",
                            agent.name(), agent.getDescription()));
                }
            }

            sb.append("\n**提示**:\n");
            sb.append("- 使用 `send_message(targetAgent, message)` 向指定代理发送消息\n");
            sb.append("- 使用 `task(subagent_type, prompt)` 调用代理并获取响应\n");
            sb.append("- 使用 `teammates()` 查看所有团队成员\n");

            return sb.toString();
        } catch (Exception e) {
            LOG.error("列出代理失败", e);
            return "[ERROR] 列出失败: " + e.getMessage();
        }
    }

    /**
     * 查看消息统计信息
     */
    @ToolMapping(name = "get_message_stats",
                 description = "查看消息统计信息（待处理消息数量等）")
    public String getMessageStats() {
        try {
            if (mainAgent == null || mainAgent.getMessageChannel() == null) {
                return "[WARN] 消息通道未启用";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("## 消息统计\n\n");

            // 获取 main-agent 的待处理消息数量
            int pendingCount = mainAgent.getMessageChannel().getPendingMessageCount("main-agent");
            sb.append("**待处理消息数**: ").append(pendingCount).append("\n");

            sb.append("\n提示: 使用 send_message 发送消息给其他代理");
            return sb.toString();
        } catch (Exception e) {
            LOG.error("获取消息统计失败", e);
            return "[ERROR] 获取失败: " + e.getMessage();
        }
    }

    /**
     * 为团队提供命名建议
     */
    @ToolMapping(name = "suggest_team_name",
            description = "为现有团队或即将创建的团队提供更好的命名建议。分析团队成员的角色和职责，生成语义化的团队名。")
    public String suggestTeamName(
            @Param(name = "oldTeamName", required = false,
                    description = "现有团队名（可选）。如果提供，将分析该团队的成员并给出改进建议") String oldTeamName,
            @Param(name = "role", required = false,
                    description = "主要角色（可选）。如：security-expert") String role,
            @Param(name = "description", required = false,
                    description = "团队描述（可选）。如：专注于系统安全") String description
    ) {
        StringBuilder result = new StringBuilder();

        if (oldTeamName != null && !oldTeamName.isEmpty()) {
            // 分析现有团队
            result.append(analyzeExistingTeam(oldTeamName));
        } else {
            // 为新团队生成建议
            result.append(generateSuggestions(role, description));
        }

        return result.toString();
    }

    /**
     * 分析现有团队并提供建议
     */
    private String analyzeExistingTeam(String oldTeamName) {
        StringBuilder sb = new StringBuilder();

        sb.append("## 团队名称分析\n\n");

        // 检查是否是旧的时间戳格式（如 team-1736640123456）
        // 注意：新版本已改用智能生成，此处仅用于迁移旧数据
        if (oldTeamName.matches("^team-\\d+$")) {
            sb.append("**当前团队名**: `").append(oldTeamName).append("`\n\n");
            sb.append("**问题**: 检测到旧版本时间戳格式，已不够语义化。\n");
            sb.append("**说明**: 新版本已改用智能语义化生成（如 database-team、security-squad）。\n\n");

            // 获取该团队的成员
            List<String> memberNames = getTeamMembers(oldTeamName);
            if (!memberNames.isEmpty()) {
                sb.append("**当前成员**:\n");
                for (String member : memberNames) {
                    sb.append("  - ").append(member).append("\n");
                }
                sb.append("\n");

                // 生成建议
                String suggestedName = TeamNameGenerator.suggestBetterName(oldTeamName, memberNames);
                if (suggestedName != null) {
                    sb.append("**建议团队名**: `").append(suggestedName).append("`\n\n");
                    sb.append("**建议描述**: ")
                            .append(TeamNameGenerator.getTeamDescription(suggestedName))
                            .append("\n\n");
                }
            }

            sb.append("**如何重命名**:\n");
            sb.append("使用 `create_team` 工具创建新团队，并指定语义化的 teamName。\n");
        } else {
            sb.append("**当前团队名**: `").append(oldTeamName).append("`\n\n");

            // 检查团队名是否有效
            if (!TeamNameGenerator.isValidTeamName(oldTeamName)) {
                sb.append("**问题**: 团队名格式不符合规范（只允许小写字母、数字和连字符）\n\n");
                String normalized = TeamNameGenerator.normalizeTeamName(oldTeamName);
                sb.append("**规范化建议**: `").append(normalized).append("`\n\n");
            } else {
                sb.append("团队名格式正确！\n\n");

                // 提取领域
                String domain = TeamNameGenerator.extractDomainFromTeamName(oldTeamName);
                if (domain != null) {
                    sb.append("**识别的领域**: ").append(domain).append("\n\n");
                    sb.append("**团队描述**: ")
                            .append(TeamNameGenerator.getTeamDescription(oldTeamName))
                            .append("\n\n");
                }
            }
        }

        return sb.toString();
    }

    /**
     * 为新团队生成命名建议
     */
    private String generateSuggestions(String role, String description) {
        StringBuilder sb = new StringBuilder();

        sb.append("## 团队命名建议\n\n");

        if (role == null && description == null) {
            sb.append("请提供角色或描述信息，以便生成更准确的团队名建议。\n\n");
            sb.append("**示例**:\n");
            sb.append("```\n");
            sb.append("suggest_team_name(role=\"security-expert\", description=\"专注于安全审计\")\n");
            sb.append("```\n\n");

            sb.append("**常见领域示例**:\n");
            sb.append("- `security-squad` - 安全专家团队\n");
            sb.append("- `database-team` - 数据库专家团队\n");
            sb.append("- `frontend-experts` - 前端开发团队\n");
            sb.append("- `backend-force` - 后端开发团队\n");
            sb.append("- `devops-alliance` - 运维自动化团队\n");
            sb.append("- `testing-guild` - 质量保证团队\n");
            sb.append("- `architecture-lab` - 架构设计团队\n");
            sb.append("- `ai-collective` - 人工智能团队\n");

            return sb.toString();
        }

        // 生成建议
        String teamName = TeamNameGenerator.generateTeamName(
                role != null ? role : "expert",
                description != null ? description : "",
                null
        );

        sb.append("**基于输入生成的建议**:\n\n");
        sb.append("```\n");
        sb.append("团队名: ").append(teamName).append("\n");
        sb.append("描述: ").append(TeamNameGenerator.getTeamDescription(teamName)).append("\n");
        sb.append("```\n\n");

        // 生成多个备选方案
        sb.append("**其他备选方案**:\n\n");
        String taskGoal = description != null ? description : role;
        for (int i = 0; i < 3; i++) {
            String alternative = TeamNameGenerator.generateTeamName(
                    role + "-" + i,
                    description,
                    taskGoal
            );
            sb.append((i + 1)).append(". `").append(alternative).append("` - ")
                    .append(TeamNameGenerator.getTeamDescription(alternative))
                    .append("\n");
        }

        sb.append("\n**使用方法**:\n");
        sb.append("在创建团队成员时使用 `teamName=\"").append(teamName).append("\"` 参数。\n");

        return sb.toString();
    }

    /**
     * 获取团队成员
     */
    private List<String> getTeamMembers(String teamName) {
        return manager.getAgents().stream()
                .filter(agent -> agent.getMetadata().hasTeamName() &&
                        agent.getMetadata().getTeamName().equals(teamName))
                .map(agent -> agent.getMetadata().getName())
                .collect(Collectors.toList());
    }


    /**
     * 执行团队任务（同步）
     *
     * 根据已保存的团队配置，动态构建 TeamAgent 并执行任务
     *
     * @param teamName 团队名称
     * @param task 任务描述
     * @return 同步执行结果（String），包含完整的团队协作输出
     */
    @ToolMapping(name = "run_team_task",
                 description = "简易版本，使用指定的团队执行任务。根据团队配置自动协调多个代理协作完成任务。返回 String 同步结果。")
    public String runTeamTask(
            @Param(name = "teamName", description = "团队名称（使用 teammates 查看）") String teamName,
            @Param(name = "task", description = "任务描述") String task) {

        try {
            String description = "";
            String protocol = "sequential";
            List<Subagent> validAgents = new ArrayList<>();

            // 1. 尝试从配置文件读取
            String teamsDir = mainAgent.getWorkDir() + File.separator + ".soloncode" + File.separator + "agentsTeams";
            File configFile = new File(teamsDir + File.separator + teamName + ".md");

            if (configFile.exists()) {
                // 从配置文件解析
                String content = new String(Files.readAllBytes(configFile.toPath()), StandardCharsets.UTF_8);
                List<String> agentNames = new ArrayList<>();

                String[] lines = content.split("\n");
                boolean inFrontMatter = false;
                for (String line : lines) {
                    if (line.trim().equals("---")) {
                        inFrontMatter = !inFrontMatter;
                        continue;
                    }

                    if (inFrontMatter) {
                        if (line.startsWith("description:")) {
                            description = line.substring(12).trim();
                        } else if (line.startsWith("protocol:")) {
                            protocol = line.substring(9).trim();
                        }
                    } else {
                        if (line.startsWith("- **")) {
                            String agentName = line.substring(4).replace("**", "").trim();
                            if (!agentName.isEmpty()) {
                                agentNames.add(agentName);
                            }
                        }
                    }
                }

                // 验证代理是否可用
                List<String> missingAgents = new ArrayList<>();
                for (String agentName : agentNames) {
                    Subagent agent = manager.getAgent(agentName);
                    if (agent != null) {
                        validAgents.add(agent);
                    } else {
                        missingAgents.add(agentName);
                    }
                }

                if (validAgents.isEmpty()) {
                    return "[ERROR] 团队中没有可用的代理\n\n" +
                           "缺少的代理: " + String.join(", ", missingAgents) + "\n" +
                           "请确保这些代理已在 SubagentManager 中注册。";
                }

                LOG.info("从配置文件加载团队: team={}, members={}", teamName, validAgents.size());
            } else {
                // 2. 配置文件不存在，动态从 SubagentManager 获取该团队的成员
                LOG.info("配置文件不存在，尝试从 SubagentManager 动态获取团队成员: team={}", teamName);

                validAgents = manager.getAgents().stream()
                        .filter(agent -> agent.getMetadata().hasTeamName() &&
                                     teamName.equals(agent.getMetadata().getTeamName()))
                        .collect(java.util.stream.Collectors.toList());

                if (validAgents.isEmpty()) {
                    return "[ERROR] 团队 '" + teamName + "' 中没有可用的代理\n\n" +
                           "提示：\n" +
                           "- 使用 `teammates()` 查看可用的团队成员和团队\n" +
                           "- 使用 `teammate()` 创建新的团队成员（可指定 teamName）\n" +
                           "- 确保创建成员时指定了正确的团队名称";
                }

                // 动态生成团队描述
                description = "团队 " + teamName + "，由 " + validAgents.size() + " 位成员组成";
                protocol = "sequential"; // 默认使用顺序执行

                LOG.info("动态加载团队成员: team={}, members={}", teamName, validAgents.size());
            }

            // 4. 构建 TeamAgent
            ChatModel chatModel = mainAgent.getChatModel();

                TeamAgent.Builder teamBuilder = TeamAgent.of(chatModel)
                        .name(teamName)
                        .role(description.isEmpty() ? teamName : description)
                        .instruction("团队协作完成用户指定的任务。根据任务特点和各成员的能力，合理分配和协调工作。");

            // 5. 添加成员（从 Subagent 中提取底层的 ReActAgent）
            for (Subagent agent : validAgents) {
                if (agent instanceof AbsSubagent) {
                    AbsSubagent absSubagent = (AbsSubagent) agent;
                    teamBuilder.agentAdd(absSubagent.getCachedAgent());
                } else {
                    LOG.warn("代理 {} 不是 AbsSubagent 类型，跳过", agent.name());
                }
            }

            // 6. 配置协议
            if ("hierarchical".equalsIgnoreCase(protocol)) {
                teamBuilder.protocol(TeamProtocols.HIERARCHICAL);
            } else {
                teamBuilder.protocol(TeamProtocols.SEQUENTIAL);
            }

            // 7. 配置运行参数
            teamBuilder.maxTurns(15); // 最多协作 15 轮

            // 8. 构建并执行
            TeamAgent teamAgent = teamBuilder.build();

            LOG.info("启动团队任务: team={}, members={}, protocol={}",
                     teamName, validAgents.size(), protocol);

            // 9. 同步执行任务（收集所有输出）
            StringBuilder result = new StringBuilder();

            // 添加头部信息
            result.append(String.format(
                    "[OK] 团队任务执行\n\n" +
                    "**团队**: %s\n" +
                    "**协议**: %s\n" +
                    "**参与成员**: %d\n" +
                    "\n--- 执行结果 ---\n\n",
                    teamName, protocol, validAgents.size()));

            // 执行并收集流式输出
            try {
                List<AgentChunk> chunks = teamAgent.prompt(task)
                        .stream()
                        .doOnNext(chunk -> {
                            String content = chunk.getContent();
                            if (content != null && !content.isEmpty()) {
                                result.append(content);
                            }
                        })
                        .collectList()
                        .block(Duration.ofMillis(300000)); // 5分钟超时

                if (chunks != null) {
                    LOG.info("团队任务执行完成: team={}, chunks={}", teamName, chunks.size());
                }

            } catch (Exception e) {
                LOG.error("团队任务执行异常: team={}, task={}", teamName, task, e);
                result.append("\n[ERROR] 团队任务执行异常: ").append(e.getMessage());
            }

            return result.toString();

        } catch (Exception e) {
            LOG.error("执行团队任务失败: team={}, task={}", teamName, task, e);
            return "[ERROR] 执行团队任务失败: " + e.getMessage() + "\n\n" +
                   "团队: " + teamName + "\n" +
                   "任务: " + task;
        }
    }
}
