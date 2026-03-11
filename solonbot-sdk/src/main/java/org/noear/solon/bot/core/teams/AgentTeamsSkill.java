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

import org.noear.solon.ai.agent.AgentResponse;

import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;
import org.noear.solon.bot.core.AgentKernel;
import org.noear.solon.bot.core.memory.KnowledgeMemory;
import org.noear.solon.bot.core.memory.LongTermMemory;
import org.noear.solon.bot.core.memory.Memory;
import org.noear.solon.bot.core.memory.ShortTermMemory;
import org.noear.solon.bot.core.subagent.SubAgentMetadata;
import org.noear.solon.bot.core.subagent.Subagent;
import org.noear.solon.bot.core.subagent.SubagentManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;

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

    private final MainAgent mainAgent;
    private final AgentKernel kernel;
    private final SubagentManager manager;

    /**
     * 完整构造函数（支持子代理调用）
     */
    public AgentTeamsSkill(MainAgent mainAgent, AgentKernel kernel, SubagentManager manager) {
        this.mainAgent = mainAgent;
        this.kernel = kernel;
        this.manager = manager;
    }

    /**
     * 简化构造函数（兼容性）
     */
    public AgentTeamsSkill(MainAgent mainAgent, SubagentManager manager) {
        this(mainAgent, null, manager);
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
        sb.append("## 工作流程\n" +
                ". 分析任务：识别所需的专业领域。\n" +
                ". 组建团队：自动激活相关领域的专家 Agent。\n" +
                ". 引导讨论：\n" +
                "       - 让专家轮流发表观点。\n" +
                "       - 鼓励专家互相质疑（例如：安全专家挑战开发专家的架构）。\n" +
                "       - 记录争议点并寻求共识。\n" +
                ". 生成报告：汇总讨论结果，去除冗余对话，只保留高质量的最终结论。");

        sb.append("### 核心能力\n");
        sb.append("1. **团队协作任务**: 使用 `team_task()` 启动多代理协作\n");
        sb.append("2. **任务管理**: 查看、创建团队任务\n");
        sb.append("3. **子代理调用**: 使用 `subagent()` 委派专门任务（支持会话续接）\n");
        sb.append("4. **动态代理**: 使用 `create_agent()` 创建新的子代理定义\n");
        sb.append("5. **智能记忆管理**: 使用 `memory_store()` 自动分类存储（推荐）\n");
        sb.append("6. **代理间通信**: 使用 `send_message()` 向其他代理发送消息\n\n");

        sb.append("### 强制委派准则\n");
        sb.append("- **项目认知**: 探索项目、分析架构 → 委派给子代理\n");
        sb.append("- **复杂变更**: 跨文件修复、重构 → 委派给子代理\n");
        sb.append("- **决策量化**: 超过 3 次工具调用 → 改用子代理\n\n");

        sb.append("### 可用的子代理\n");
        sb.append("<available_agents>\n");
        for (Subagent agent : manager.getAgents()) {
            sb.append(String.format("  - `%s`: %s\n",
                    agent.getType(), agent.getDescription()));
        }
        sb.append("</available_agents>\n\n");

        sb.append("### 团队成员管理\n");
        sb.append("1. **创建成员**: 使用 `teammate()` 创建新的团队成员\n");
        sb.append("2. **列出成员**: 使用 `teammates()` 查看所有团队成员（表格格式）\n");
        sb.append("3. **移除成员**: 使用 `remove_teammate()` 移除团队成员\n\n");

        sb.append("### 使用场景\n");
        sb.append("```");
        sb.append("# 场景1: 创建团队成员（指定团队）\n");
        sb.append("teammate(\n");
        sb.append("    name=\"security-expert\",\n");
        sb.append("    role=\"安全专家\",\n");
        sb.append("    description=\"专注于安全审计、漏洞检测\",\n");
        sb.append("    teamName=\"myteam\"              # 指定团队名称\n");
        sb.append(")\n");
        sb.append("# 生成文件: .soloncode/agentsTeams/myteam/security-expert.md\n\n");
        sb.append("# 场景2: 创建子代理（不指定团队）\n");
        sb.append("teammate(\n");
        sb.append("    name=\"db-optimizer\",\n");
        sb.append("    role=\"数据库优化专家\",\n");
        sb.append("    description=\"SQL 查询优化\"\n");
        sb.append(")\n");
        sb.append("# 生成文件: .soloncode/agents/db-optimizer.md\n\n");
        sb.append("# 场景3: 查看所有成员\n");
        sb.append("teammates()\n\n");
        sb.append("# 场景4: 启动团队协作任务\n");
        sb.append("team_task(\"实现用户登录功能\")\n\n");
        sb.append("# 场景5: 查看任务状态\n");
        sb.append("team_status()\n\n");
        sb.append("# 场景6: 智能记忆存储（推荐使用）\n");
        sb.append("# 自动分类存储（系统自动判断存储周期）\n");
        sb.append("memory_store(key=\"项目结构\", value=\"采用三层架构：Controller-Service-Repository\")\n");
        sb.append("# → 系统识别为架构决策，存储到知识记忆（永久）\n\n");
        sb.append("# 手动指定类别\n");
        sb.append("memory_store(key=\"当前步骤\", value=\"正在实现Service层\", category=\"context\")\n");
        sb.append("# → 存储到短期记忆（1小时）\n\n");
        sb.append("# 场景7: 存储任务完成结果\n");
        sb.append("memory_store(key=\"登录功能\", value=\"已完成JWT认证，包括登录、登出、token刷新\", category=\"task\")\n");
        sb.append("# → 存储到长期记忆（7天）\n\n");
        sb.append("# 场景8: 代理间通信\n");
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
        sb.append("list_agents()\n\n");
        sb.append("### 记忆存储说明\n");
        sb.append("- **auto**: 自动分类（推荐），系统根据内容关键词智能判断\n");
        sb.append("- **task**: 任务结果类 → 长期记忆（7天）\n");
        sb.append("- **context**: 临时上下文类 → 短期记忆（1小时）\n");
        sb.append("- **decision/knowledge**: 决策和知识类 → 知识记忆（永久）\n");
        sb.append("```\n");

        return sb.toString();
    }


    /**
     * 启动团队协作任务
     *
     * MainAgent 会分析任务、创建子任务、协调多个 SubAgent 协作完成
     */
    @ToolMapping(name = "team_task",
                 description = "启动团队协作任务。MainAgent 会自动分解任务并协调多个 SubAgent 协作完成。适用于复杂、多步骤的任务。")
    public String teamTask(
            @Param(name = "prompt", description = "任务描述，清晰说明目标和要求") String prompt,
            String __cwd,
            String __sessionId
    ) {
        try {
            if (mainAgent.isRunning()) {
                return "⚠️ 团队任务正在执行中，请等待当前任务完成。";
            }

            LOG.info("启动团队协作任务: {}", prompt);

            // 执行团队任务
            AgentResponse response = mainAgent.execute(Prompt.of(prompt));

            // 获取任务统计
            SharedTaskList.TaskStatistics stats = mainAgent.getTaskList().getStatistics();

            StringBuilder result = new StringBuilder();
            result.append("✅ 团队任务执行完成\n\n");
            result.append("**任务统计**:\n");
            result.append(String.format("- 总任务数: %d\n", stats.totalTasks));
            result.append(String.format("- 已完成: %d\n", stats.completedTasks));
            result.append(String.format("- 失败: %d\n", stats.failedTasks));
            result.append(String.format("- 进行中: %d\n", stats.inProgressTasks));
            result.append(String.format("- 待认领: %d\n\n", stats.pendingTasks));

            // 主 Agent 的回复
            result.append("**主 Agent 回复**:\n");
            result.append(response.getContent());

            return result.toString();

        } catch (Throwable e) {
            LOG.error("团队任务执行失败", e);
            return "❌ 团队任务执行失败: " + e.getMessage();
        }
    }

    /**
     * 查看团队任务状态
     */
    @ToolMapping(name = "team_status",
                 description = "查看当前团队任务状态，包括任务列表、进度统计等")
    public String teamStatus() {
        try {
            SharedTaskList taskList = mainAgent.getTaskList();
            SharedTaskList.TaskStatistics stats = taskList.getStatistics();

            StringBuilder sb = new StringBuilder();
            sb.append("## 团队任务状态\n\n");

            // 统计信息
            sb.append("**统计**:\n");
            sb.append(String.format("- 总任务: %d\n", stats.totalTasks));
            sb.append(String.format("- ✅ 已完成: %d\n", stats.completedTasks));
            sb.append(String.format("- ❌ 失败: %d\n", stats.failedTasks));
            sb.append(String.format("- 🔄 进行中: %d\n", stats.inProgressTasks));
            sb.append(String.format("- ⏳ 待认领: %d\n\n", stats.pendingTasks));

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
                sb.append("**主 Agent 状态**: 🔄 正在运行\n\n");
            } else {
                sb.append("**主 Agent 状态**: ⏸️ 空闲\n\n");
            }

            return sb.toString();

        } catch (Throwable e) {
            LOG.error("获取团队状态失败", e);
            return "❌ 获取团队状态失败: " + e.getMessage();
        }
    }

    /**
     * 创建新任务
     */
    @ToolMapping(name = "create_task",
                 description = "创建新的团队任务。可以设置依赖关系、优先级等。")
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
                    return "❌ 无效的任务类型: " + type;
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

            return String.format("✅ 任务创建成功\n\n" +
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
            return "❌ 创建任务失败: " + e.getMessage();
        }
    }

    /**
     * 获取状态图标
     */
    private String getStatusIcon(TeamTask.Status status) {
        switch (status) {
            case PENDING: return "⏳";
            case IN_PROGRESS: return "🔄";
            case COMPLETED: return "✅";
            case FAILED: return "❌";
            case CANCELLED: return "⏹️";
            default: return "❓";
        }
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
                 description = "创建新的团队成员。可以定义角色、职责、技能集，并立即激活。支持联网搜索相关资料。")
    public String createTeammate(
            @Param(name = "name", description = "团队成员唯一标识（如：security-expert）") String name,
            @Param(name = "role", description = "角色描述（如：安全专家）") String role,
            @Param(name = "description", description = "详细职责描述") String description,
            @Param(name = "teamName", required = false, description = "团队名称（如：myteam）。如果指定，文件命名为 {teamName}-{name}.md") String teamName,
            @Param(name = "systemPrompt", required = false, description = "系统提示词，定义行为模式") String systemPrompt,
            @Param(name = "expertise", required = false, description = "专业领域，逗号分隔（如：security,auth,encryption）") String expertise,
            @Param(name = "model", required = false, description = "使用的模型（如：默认）") String model,
            @Param(name = "searchContext", required = false, description = "是否联网搜索相关上下文（默认false）") Boolean searchContext,
            String __cwd
    ) {
        try {
            // 如果需要联网搜索上下文
            if (searchContext != null && searchContext && kernel != null) {
                LOG.info("为 teammate {} 搜索相关上下文...", name);
                LOG.info("联网搜索功能需要进一步集成 WebSearch 工具");
            }

            // 构建子代理元数据
            SubAgentMetadata metadata = new SubAgentMetadata();
            metadata.setCode(name);
            metadata.setName(role);
            metadata.setDescription(description);
            metadata.setEnabled(true);

            // 设置专业领域
            if (expertise != null && !expertise.isEmpty()) {
                metadata.setSkills(Arrays.asList(expertise.split(",\\s*")));
            }

            // 设置模型
            if (model != null && !model.isEmpty()) {
                metadata.setModel(model);
            }

            // 生成系统提示词（如果没有提供）
            String finalPrompt = systemPrompt;
            if (finalPrompt == null || finalPrompt.isEmpty()) {
                finalPrompt = generateDefaultSystemPrompt(name, role, description, expertise);
            }

            // 生成完整的代理定义
            String agentDefinition = metadata.toYamlFrontmatterWithPrompt(finalPrompt);

            // 保存到文件
            Path agentFile;
            if (teamName != null && !teamName.isEmpty()) {
                // 团队成员：保存到 .soloncode/agentsTeams/{teamName}/{name}.md
                Path teamsDir = Paths.get(__cwd, ".soloncode", "agentsTeams", teamName);
                Files.createDirectories(teamsDir);
                agentFile = teamsDir.resolve(name + ".md");
                LOG.info("创建团队成员: 团队={}, 角色={}, 文件={}", teamName, name, agentFile);
            } else {
                // 普通子代理：保存到 .soloncode/agents/{name}.md
                Path agentsDir = Paths.get(__cwd, ".soloncode", "agents");
                Files.createDirectories(agentsDir);
                agentFile = agentsDir.resolve(name + ".md");
                LOG.info("创建子代理: 角色={}, 文件={}", name, agentFile);
            }

            Files.write(agentFile, agentDefinition.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            // 返回结果（使用表格格式）
            StringBuilder result = new StringBuilder();
            result.append("✅ 团队成员创建成功\n\n");

            // 表格格式的成员信息
            result.append("## 成员信息\n\n");
            result.append("| 属性 | 值 |\n");
            result.append("|------|------|\n");
            result.append(String.format("| **名称** | `%s` |\n", name));
            result.append(String.format("| **角色** | %s |\n", role));
            result.append(String.format("| **描述** | %s |\n", description));

            if (teamName != null && !teamName.isEmpty()) {
                result.append(String.format("| **所属团队** | %s |\n", teamName));
                result.append(String.format("| **文件路径** | `.soloncode/agentsTeams/%s/%s.md` |\n", teamName, name));
            } else {
                result.append(String.format("| **文件路径** | `.soloncode/agents/%s.md` |\n", name));
            }

            if (expertise != null && !expertise.isEmpty()) {
                result.append(String.format("| **专业领域** | %s |\n", expertise));
            }

            if (model != null && !model.isEmpty()) {
                result.append(String.format("| **模型** | %s |\n", model));
            }

            result.append(String.format("| **状态** | 🟢 已激活 |\n"));

            result.append("\n**使用方法**:\n");
            result.append("```bash\n");
            result.append(String.format("subagent(type=\"%s\", prompt=\"你的任务描述\")\n", name));
            result.append("```\n");

            return result.toString();

        } catch (Throwable e) {
            LOG.error("创建团队成员失败", e);
            return "❌ 创建团队成员失败: " + e.getMessage();
        }
    }

    /**
     * 列出所有团队成员
     *
     * 类似 Claude Code 的 /teammates 命令
     * 使用表格格式输出
     */
    @ToolMapping(name = "teammates",
                 description = "列出所有团队成员，以表格格式显示。包括名称、角色、状态、模型等信息。")
    public String listTeammates() {
        try {
            Collection<Subagent> agents = manager.getAgents();

            StringBuilder result = new StringBuilder();
            result.append("## 团队成员\n\n");

            if (agents.isEmpty()) {
                result.append("⚠️ 当前没有团队成员。\n\n");
                result.append("使用 `teammate()` 命令创建新成员。\n");
                return result.toString();
            }

            // 表格格式的成员列表
            result.append("| 名称 | 角色 | 描述 | 团队 | 状态 | 模型 |\n");
            result.append("|------|------|------|------|------|------|\n");

            for (Subagent agent : agents) {
                String name = String.format("`%s`", agent.getType());
                String role = agent.getClass().getSimpleName().replace("Subagent", "");
                String desc = truncate(agent.getDescription(), 30);
                String team = agent.getMetadata().hasTeamName() ? agent.getMetadata().getTeamName() : "-";
                String status = "🟢 活跃";
                String model = agent.getMetadata().getModel() != null ? agent.getMetadata().getModel()  : "默认";

                result.append(String.format("| %s | %s | %s | %s | %s | %s |\n",
                        name, role, desc, team, status, model));
            }

            result.append("\n**总计**: " + agents.size() + " 位活跃成员\n\n");

            // 添加使用提示
            result.append("**快速操作**:\n");
            result.append("```bash\n");
            result.append("# 创建新成员\n");
            result.append("teammate(name=\"expert\", role=\"专家\", description=\"...\")\n\n");
            result.append("# 调用成员\n");
            result.append("subagent(type=\"explore\", prompt=\"任务描述\")\n\n");
            result.append("# 查看任务状态\n");
            result.append("team_status()\n");
            result.append("```\n");

            return result.toString();

        } catch (Throwable e) {
            LOG.error("列出团队成员失败", e);
            return "❌ 列出团队成员失败: " + e.getMessage();
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
                return String.format("❌ 未找到团队成员: `%s`\n\n可用的成员:\n%s",
                        name, listTeammates());
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

            return String.format("✅ 团队成员已禁用: `%s`\n\n" +
                    "**提示**: 配置文件已保留，如需重新激活，请编辑 `.soloncode/agents/%s.md` 并设置 `enabled: true`。",
                    name, name);

        } catch (Throwable e) {
            LOG.error("移除团队成员失败", e);
            return "❌ 移除团队成员失败: " + e.getMessage();
        }
    }

    @ToolMapping(name = "isTeamsEnabled",
            description = "检查是否已开启团队功能")
    public String isTeamsEnabled() {
        return kernel.getProperties().isTeamsEnabled() ? "团队功能已启用" : "⚠️ 团队功能未启用。请先启用团队功能。";
    }

    /**
     * 生成默认系统提示词
     */
    private String generateDefaultSystemPrompt(String name, String role, String description, String expertise) {
        StringBuilder prompt = new StringBuilder();
        prompt.append(String.format("# %s\n\n", role));
        prompt.append(String.format("你是 %s，专门负责 %s。\n\n", role, description));
        prompt.append("## 工作原则\n\n");
        prompt.append("1. **专业专注**: 始终在你的专业领域内提供建议和解决方案\n");
        prompt.append("2. **质量优先**: 注重代码质量和最佳实践\n");
        prompt.append("3. **协作配合**: 与其他团队成员保持良好沟通\n");
        prompt.append("4. **持续学习**: 不断更新知识，掌握最新技术趋势\n\n");

        if (expertise != null && !expertise.isEmpty()) {
            prompt.append("## 专业领域\n\n");
            String[] areas = expertise.split(",\\s*");
            for (String area : areas) {
                prompt.append(String.format("- %s\n", area));
            }
            prompt.append("\n");
        }

        prompt.append("## 沟通风格\n\n");
        prompt.append("- 使用清晰、简洁的语言\n");
        prompt.append("- 提供具体的代码示例\n");
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

    // ==================== 记忆管理工具 ====================

    /**
     * 存储任务结果到记忆
     */
    @ToolMapping(name = "memory_store_task_result",
                 description = "存储任务结果到共享记忆（长期记忆，7天TTL）。用于保存重要任务的执行结果。")
    public String memoryStoreTaskResult(
            @Param(name = "taskTitle", description = "任务标题") String taskTitle,
            @Param(name = "result", description = "任务结果（JSON格式或文本）") String result) {
        try {
            if (mainAgent == null || mainAgent.getSharedMemoryManager() == null) {
                return "⚠️ 共享记忆未初始化";
            }

            // 存储到长期记忆（7天TTL）
            String key = "task-result:" + taskTitle + ":" + System.currentTimeMillis();
            mainAgent.getSharedMemoryManager().putLongTerm(key, result, 604800);

            LOG.info("存储任务结果: taskTitle={}, key={}", taskTitle, key);
            return "✅ 任务结果已存储: " + taskTitle;
        } catch (Exception e) {
            LOG.error("存储任务结果失败", e);
            return "❌ 存储失败: " + e.getMessage();
        }
    }

    /**
     * 搜索任务结果
     */
    @ToolMapping(name = "memory_search_task_results",
                 description = "搜索历史任务结果（从共享记忆中检索）")
    public String memorySearchTaskResults(
            @Param(name = "query", description = "搜索查询（任务标题关键词）") String query,
            @Param(name = "limit", description = "返回结果数量限制，默认10") Integer limit) {
        try {
            if (mainAgent == null || mainAgent.getSharedMemoryManager() == null) {
                return "⚠️ 共享记忆未初始化";
            }

            int actualLimit = limit != null && limit > 0 ? limit : 10;
            List<Memory> results =
                mainAgent.getSharedMemoryManager().search(query, actualLimit);

            if (results.isEmpty()) {
                return "⚠️ 未找到相关任务结果";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("找到 ").append(results.size()).append(" 条相关任务结果:\n\n");

            for (int i = 0; i < results.size(); i++) {
                Memory mem = results.get(i);
                String content = getMemoryContent(mem);
                sb.append(i + 1).append(". ").append(content).append("\n");
            }

            return sb.toString();
        } catch (Exception e) {
            LOG.error("搜索任务结果失败", e);
            return "❌ 搜索失败: " + e.getMessage();
        }
    }

    /**
     * 存储决策到记忆
     */
    @ToolMapping(name = "memory_store_decision",
                 description = "存储重要决策到知识记忆（永久保存）。用于保存架构决策、技术选型等。")
    public String memoryStoreDecision(
            @Param(name = "decisionTitle", description = "决策标题") String decisionTitle,
            @Param(name = "decision", description = "决策内容（包括理由、影响等）") String decision) {
        try {
            if (mainAgent == null || mainAgent.getSharedMemoryManager() == null) {
                return "⚠️ 共享记忆未初始化";
            }

            // 存储到知识记忆（永久）
            String key = "decision:" + decisionTitle;
            mainAgent.getSharedMemoryManager().putKnowledge(key, decision);

            LOG.info("存储决策: decisionTitle={}", decisionTitle);
            return "✅ 决策已存储: " + decisionTitle;
        } catch (Exception e) {
            LOG.error("存储决策失败", e);
            return "❌ 存储失败: " + e.getMessage();
        }
    }

    /**
     * 存储临时上下文
     */
    @ToolMapping(name = "memory_store_context",
                 description = "存储临时上下文到短期记忆（会话级别，1小时TTL）。用于传递信息给其他代理。")
    public String memoryStoreContext(
            @Param(name = "key", description = "上下文键") String key,
            @Param(name = "value", description = "上下文值") String value) {
        try {
            if (mainAgent == null || mainAgent.getSharedMemoryManager() == null) {
                return "⚠️ 共享记忆未初始化";
            }

            // 存储到短期记忆（1小时TTL）
            mainAgent.getSharedMemoryManager().putShortTerm(key, value, 3600);

            LOG.debug("存储临时上下文: key={}", key);
            return "✅ 临时上下文已存储: " + key;
        } catch (Exception e) {
            LOG.error("存储临时上下文失败", e);
            return "❌ 存储失败: " + e.getMessage();
        }
    }

    /**
     * 智能存储记忆（统一接口）
     *
     * 自动根据内容类型选择合适的存储周期：
     * - 决策类 → 知识记忆（永久）
     * - 任务结果 → 长期记忆（7天）
     * - 临时上下文 → 短期记忆（1小时）
     * - 默认 → 长期记忆（7天）
     */
    @ToolMapping(name = "memory_store",
                 description = "智能存储记忆（自动判断存储周期）。根据内容自动选择合适的存储类型。")
    public String memoryStore(
            @Param(name = "key", description = "记忆键") String key,
            @Param(name = "value", description = "记忆值") String value,
            @Param(name = "category", description = "可选：指定类别（auto/task/context/decision/knowledge），默认auto") String category) {
        try {
            if (mainAgent == null || mainAgent.getSharedMemoryManager() == null) {
                return "⚠️ 共享记忆未初始化";
            }

            String actualCategory = (category != null && !category.isEmpty()) ? category : "auto";
            String result;

            switch (actualCategory) {
                case "task":
                    mainAgent.getSharedMemoryManager().putLongTerm(key, value, 604800);
                    result = "✅ 任务结果已存储（7天）";
                    break;

                case "context":
                    mainAgent.getSharedMemoryManager().putShortTerm(key, value, 3600);
                    result = "✅ 上下文已存储（1小时）";
                    break;

                case "decision":
                case "knowledge":
                    mainAgent.getSharedMemoryManager().putKnowledge(key, value);
                    result = "✅ 知识已存储（永久）";
                    break;

                case "auto":
                default:
                    result = autoClassifyAndStore(key, value);
                    break;
            }

            LOG.info("智能存储记忆: key={}, category={}", key, actualCategory);
            return result;
        } catch (Exception e) {
            LOG.error("存储记忆失败", e);
            return "❌ 存储失败: " + e.getMessage();
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
                return "⚠️ 共享记忆未初始化";
            }

            // 使用默认的 taskId "main-agent"
            String taskId = "main-agent";
            org.noear.solon.bot.core.memory.WorkingMemory workingMemory =
                mainAgent.getSharedMemoryManager().getWorking(taskId);

            if (workingMemory == null) {
                return "⚠️ 没有工作记忆";
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
            return "❌ 获取失败: " + e.getMessage();
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
                return "⚠️ 共享记忆未初始化";
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
                    return "❌ 无效的字段名: " + field + "。支持的字段：taskDescription、status、step、currentAgent";
            }

            // 保存更新后的工作记忆
            mainAgent.getSharedMemoryManager().storeWorking(workingMemory);

            LOG.debug("更新工作记忆: {}={}", field, value);
            return "✅ 工作记忆已更新: " + field + " = " + value;
        } catch (NumberFormatException e) {
            return "❌ 步骤必须是数字: " + value;
        } catch (Exception e) {
            LOG.error("更新工作记忆失败", e);
            return "❌ 更新失败: " + e.getMessage();
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
                return "⚠️ 事件总线未初始化";
            }

            org.noear.solon.bot.core.event.AgentEventType type;
            try {
                type = org.noear.solon.bot.core.event.AgentEventType.valueOf(eventType.toUpperCase());
            } catch (IllegalArgumentException e) {
                return "❌ 无效的事件类型: " + eventType;
            }

            org.noear.solon.bot.core.event.AgentEvent event =
                new org.noear.solon.bot.core.event.AgentEvent(type, data, null);
            mainAgent.getEventBus().publish(event);

            LOG.debug("发布团队事件: type={}, data={}", type, data);
            return "✅ 团队事件已发布: " + type;
        } catch (Exception e) {
            LOG.error("发布团队事件失败", e);
            return "❌ 发布失败: " + e.getMessage();
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
                return "⚠️ MainAgent 未初始化";
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
            return "❌ 获取失败: " + e.getMessage();
        }
    }

    // ==================== 任务管理工具 ====================

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
                return "⚠️ MainAgent 未初始化";
            }

            SharedTaskList taskList = mainAgent.getTaskList();
            TeamTask task = taskList.getTask(taskId);

            if (task == null) {
                return "❌ 任务不存在: " + taskId;
            }

            if (task.getStatus() != TeamTask.Status.PENDING) {
                return "⚠️ 任务状态不是待认领: " + task.getStatus();
            }

            taskList.claimTask(taskId, agentName);

            LOG.info("任务已认领: taskId={}, agent={}", taskId, agentName);
            return "✅ 任务已认领: " + taskId + " 由 " + agentName;
        } catch (Exception e) {
            LOG.error("认领任务失败", e);
            return "❌ 认领失败: " + e.getMessage();
        }
    }

    /**
     * 完成任务
     */
    @ToolMapping(name = "complete_task",
                 description = "标记任务为已完成（将任务状态改为COMPLETED）")
    public String completeTask(
            @Param(name = "taskId", description = "任务ID") String taskId,
            @Param(name = "result", description = "任务执行结果（可选）") String result) {
        try {
            if (mainAgent == null) {
                return "⚠️ MainAgent 未初始化";
            }

            SharedTaskList taskList = mainAgent.getTaskList();
            TeamTask task = taskList.getTask(taskId);

            if (task == null) {
                return "❌ 任务不存在: " + taskId;
            }

            if (task.getStatus() != TeamTask.Status.IN_PROGRESS) {
                return "⚠️ 任务状态不是进行中: " + task.getStatus();
            }

            taskList.completeTask(taskId, result);

            // 如果提供了结果，额外存储到记忆
            if (result != null && !result.isEmpty()) {
                String key = "task-result:" + task.getTitle() + ":" + System.currentTimeMillis();
                mainAgent.getSharedMemoryManager().putLongTerm(key, result.toString(), 604800);
            }

            LOG.info("任务已完成: taskId={}", taskId);
            return "✅ 任务已完成: " + taskId;
        } catch (Exception e) {
            LOG.error("完成任务失败", e);
            return "❌ 完成失败: " + e.getMessage();
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
                return "⚠️ MainAgent 未初始化";
            }

            SharedTaskList taskList = mainAgent.getTaskList();
            TeamTask task = taskList.getTask(taskId);

            if (task == null) {
                return "❌ 任务不存在: " + taskId;
            }

            taskList.failTask(taskId, errorMessage);

            LOG.error("任务失败: taskId={}, error={}", taskId, errorMessage);
            return "❌ 任务已标记为失败: " + taskId;
        } catch (Exception e) {
            LOG.error("标记任务失败", e);
            return "❌ 操作失败: " + e.getMessage();
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
                return "⚠️ MainAgent 未初始化";
            }

            SharedTaskList taskList = mainAgent.getTaskList();
            TeamTask task = taskList.getTask(taskId);

            if (task == null) {
                return "❌ 任务不存在: " + taskId;
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
            return "❌ 获取失败: " + e.getMessage();
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
                return "⚠️ MainAgent 未初始化";
            }

            SharedTaskList taskList = mainAgent.getTaskList();
            List<TeamTask> tasks;

            if (status != null && !status.isEmpty()) {
                TeamTask.Status taskStatus;
                try {
                    taskStatus = TeamTask.Status.valueOf(status.toUpperCase());
                } catch (IllegalArgumentException e) {
                    return "❌ 无效的状态: " + status;
                }
                tasks = taskList.getTasksByStatus(taskStatus);
            } else {
                tasks = taskList.getAllTasks();
            }

            if (tasks.isEmpty()) {
                return "⚠️ 没有任务";
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
            return "❌ 列出失败: " + e.getMessage();
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
                return "⚠️ MainAgent 未初始化";
            }

            SharedTaskList taskList = mainAgent.getTaskList();
            TeamTask task = taskList.getTask(taskId);

            if (task == null) {
                return "❌ 任务不存在: " + taskId;
            }

            TeamTask dependsOnTask = taskList.getTask(dependsOnTaskId);
            if (dependsOnTask == null) {
                return "❌ 依赖任务不存在: " + dependsOnTaskId;
            }

            // 添加依赖到任务的依赖列表
            if (task.getDependencies() == null) {
                task.setDependencies(new java.util.ArrayList<>());
            }
            task.getDependencies().add(dependsOnTaskId);

            LOG.info("添加任务依赖: {} depends on {}", taskId, dependsOnTaskId);
            return "✅ 依赖关系已添加: " + taskId + " 依赖于 " + dependsOnTaskId;
        } catch (Exception e) {
            LOG.error("添加任务依赖失败", e);
            return "❌ 添加失败: " + e.getMessage();
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
                return "⚠️ MainAgent 未初始化";
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
                return "⚠️ 没有可认领的任务";
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
            return "❌ 获取失败: " + e.getMessage();
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
                return "⚠️ MainAgent 未初始化";
            }

            SharedTaskList taskList = mainAgent.getTaskList();
            TeamTask task = taskList.getTask(taskId);

            if (task == null) {
                return "❌ 任务不存在: " + taskId;
            }

            task.setResult(result);

            LOG.info("任务结果已更新: taskId={}", taskId);
            return "✅ 任务结果已更新: " + taskId;
        } catch (Exception e) {
            LOG.error("更新任务结果失败", e);
            return "❌ 更新失败: " + e.getMessage();
        }
    }

    /**
     * 获取记忆内容（辅助方法）
     */
    private String getMemoryContent(Memory memory) {
        if (memory instanceof ShortTermMemory) {
            return ((ShortTermMemory) memory).getContext();
        } else if (memory instanceof LongTermMemory) {
            return ((LongTermMemory) memory).getSummary();
        } else if (memory instanceof KnowledgeMemory) {
            return ((KnowledgeMemory) memory).getContent();
        } else {
            return memory.getId();
        }
    }

    /**
     * 自动分类并存储记忆
     */
    private String autoClassifyAndStore(String key, String value) {
        String lowerValue = value.toLowerCase();

        // 1. 检查是否是决策类内容 → 知识记忆（永久）
        if (isDecisionContent(lowerValue)) {
            mainAgent.getSharedMemoryManager().putKnowledge(key, value);
            return "✅ 决策已存储（永久）[自动分类: 决策类]";
        }

        // 2. 检查是否是任务结果 → 长期记忆（7天）
        if (isTaskResult(lowerValue)) {
            mainAgent.getSharedMemoryManager().putLongTerm(key, value, 604800);
            return "✅ 任务结果已存储（7天）[自动分类: 任务结果]";
        }

        // 3. 检查是否是临时上下文 → 短期记忆（1小时）
        if (isTemporaryContext(lowerValue)) {
            mainAgent.getSharedMemoryManager().putShortTerm(key, value, 3600);
            return "✅ 上下文已存储（1小时）[自动分类: 临时上下文]";
        }

        // 4. 检查是否是知识经验 → 知识记忆（永久）
        if (isKnowledgeContent(lowerValue)) {
            mainAgent.getSharedMemoryManager().putKnowledge(key, value);
            return "✅ 知识已存储（永久）[自动分类: 知识经验]";
        }

        // 5. 默认使用长期记忆（7天）
        mainAgent.getSharedMemoryManager().putLongTerm(key, value, 604800);
        return "✅ 记忆已存储（7天）[自动分类: 默认长期]";
    }

    /**
     * 判断是否是决策类内容
     */
    private boolean isDecisionContent(String lowerValue) {
        return lowerValue.contains("决策") || lowerValue.contains("决定")
            || lowerValue.contains("采用") || lowerValue.contains("选择")
            || lowerValue.contains("技术选型") || lowerValue.contains("架构")
            || lowerValue.contains("方案");
    }

    /**
     * 判断是否是任务结果
     */
    private boolean isTaskResult(String lowerValue) {
        return lowerValue.contains("已完成") || lowerValue.contains("完成")
            || lowerValue.contains("结果") || lowerValue.contains("实现")
            || lowerValue.contains("成功") || lowerValue.contains("done");
    }

    /**
     * 判断是否是临时上下文
     */
    private boolean isTemporaryContext(String lowerValue) {
        return lowerValue.contains("临时") || lowerValue.contains("当前")
            || lowerValue.contains("待办") || lowerValue.contains("进行中")
            || lowerValue.contains("步骤") || lowerValue.contains("中间");
    }

    /**
     * 判断是否是知识经验
     */
    private boolean isKnowledgeContent(String lowerValue) {
        return lowerValue.contains("最佳实践") || lowerValue.contains("经验")
            || lowerValue.contains("教训") || lowerValue.contains("总结")
            || lowerValue.contains("技巧") || lowerValue.contains("注意")
            || lowerValue.contains("建议");
    }

    // ==================== 代理间通信工具 ====================

    /**
     * 发送消息给其他代理
     */
    @ToolMapping(name = "send_message",
                 description = "发送消息给其他代理（点对点通信）。可用代理：explore、plan、bash、general-purpose、solon-code-guide 等。")
    public String sendMessage(
            @Param(name = "targetAgent", description = "目标代理名称（如：explore、plan、bash、general-purpose）") String targetAgent,
            @Param(name = "message", description = "消息内容") String message) {
        try {
            if (mainAgent == null || mainAgent.getMessageChannel() == null) {
                return "⚠️ 消息通道未启用";
            }

            // 使用 Builder 创建消息
            org.noear.solon.bot.core.message.AgentMessage<String> agentMessage =
                org.noear.solon.bot.core.message.AgentMessage.<String>of(message)
                    .from("main-agent")
                    .to(targetAgent)
                    .type("command")
                    .build();

            // 发送消息并等待完成
            java.util.concurrent.CompletableFuture<org.noear.solon.bot.core.message.MessageAck> ackFuture =
                mainAgent.getMessageChannel().send(agentMessage);

            // 获取结果（超时5秒）
            org.noear.solon.bot.core.message.MessageAck ack =
                ackFuture.get(5, java.util.concurrent.TimeUnit.SECONDS);

            if (ack.isSuccess()) {
                LOG.debug("消息已发送: to={}, msg={}", targetAgent, message);
                return "✅ 消息已发送给 " + targetAgent;
            } else {
                return "❌ 消息发送失败: " + ack.getMessage();
            }
        } catch (java.util.concurrent.TimeoutException e) {
            LOG.error("发送消息超时", e);
            return "⚠️ 发送超时: 消息可能还在处理中";
        } catch (Exception e) {
            LOG.error("发送消息失败", e);
            return "❌ 发送失败: " + e.getMessage();
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
                if (!agent.getType().equals("explore")
                    && !agent.getType().equals("plan")
                    && !agent.getType().equals("bash")
                    && !agent.getType().equals("general-purpose")
                    && !agent.getType().equals("solon-code-guide")) {
                    sb.append(String.format("- `%s`: %s\n",
                            agent.getType(), agent.getDescription()));
                }
            }

            sb.append("\n**提示**:\n");
            sb.append("- 使用 `send_message(targetAgent, message)` 向指定代理发送消息\n");
            sb.append("- 使用 `subagent(type, prompt)` 调用代理并获取响应\n");
            sb.append("- 使用 `teammates()` 查看所有团队成员\n");

            return sb.toString();
        } catch (Exception e) {
            LOG.error("列出代理失败", e);
            return "❌ 列出失败: " + e.getMessage();
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
                return "⚠️ 消息通道未启用";
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
            return "❌ 获取失败: " + e.getMessage();
        }
    }
}
