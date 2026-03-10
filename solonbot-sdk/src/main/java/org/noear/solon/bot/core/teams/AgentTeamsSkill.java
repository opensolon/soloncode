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
import org.noear.solon.bot.core.subagent.SubAgentMetadata;
import org.noear.solon.bot.core.subagent.Subagent;
import org.noear.solon.bot.core.subagent.SubagentManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
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
        sb.append("4. **动态代理**: 使用 `create_agent()` 创建新的子代理定义\n\n");

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
        sb.append("    teamName=\"myteam\",              # 指定团队名称\n");
        sb.append("    expertise=\"security,auth,encryption\",\n");
        sb.append("    model=\"gpt-4\"\n");
        sb.append(")\n");
        sb.append("# 生成文件: myteam-security-expert.md\n\n");
        sb.append("# 场景2: 创建子代理（不指定团队）\n");
        sb.append("teammate(\n");
        sb.append("    name=\"db-optimizer\",\n");
        sb.append("    role=\"数据库优化专家\",\n");
        sb.append("    description=\"SQL 查询优化\"\n");
        sb.append(")\n");
        sb.append("# 生成文件: db-optimizer.md\n\n");
        sb.append("# 场景3: 查看所有成员\n");
        sb.append("teammates()\n\n");
        sb.append("# 场景4: 启动团队协作任务\n");
        sb.append("team_task(\"实现用户登录功能\")\n\n");
        sb.append("# 场景5: 查看任务状态\n");
        sb.append("team_status()\n");
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
            Path agentsDir = Paths.get(__cwd, ".soloncode", "agents");
            Files.createDirectories(agentsDir);

            // 文件命名：{teamName}-{name}.md 或 {name}.md
            String fileName;
            if (teamName != null && !teamName.isEmpty()) {
                fileName = teamName + "-" + name + ".md";
                LOG.info("创建团队成员: 团队={}, 角色={}, 文件={}", teamName, name, fileName);
            } else {
                fileName = name + ".md";
                LOG.info("创建子代理: 角色={}, 文件={}", name, fileName);
            }

            Path agentFile = agentsDir.resolve(fileName);
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
            }

            if (expertise != null && !expertise.isEmpty()) {
                result.append(String.format("| **专业领域** | %s |\n", expertise));
            }

            if (model != null && !model.isEmpty()) {
                result.append(String.format("| **模型** | %s |\n", model));
            }

            result.append(String.format("| **文件** | `%s` |\n", agentFile));
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
            result.append("| 名称 | 角色 | 描述 | 状态 | 模型 |\n");
            result.append("|------|------|------|------|------|\n");

            for (Subagent agent : agents) {
                String name = String.format("`%s`", agent.getType());
                String role = agent.getClass().getSimpleName().replace("Subagent", "");
                String desc = truncate(agent.getDescription(), 30);
                String status = "🟢 活跃";
                String model = agent.getMetadata().getModel() != null ? agent.getMetadata().getModel()  : "默认";

                result.append(String.format("| %s | %s | %s | %s | %s |\n",
                        name, role, desc, status, model));
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
}
