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
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActChunk;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.task.ActionChunk;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;
import org.noear.solon.bot.core.AgentKernel;
import org.noear.solon.bot.core.subagent.SubAgentMetadata;
import org.noear.solon.bot.core.subagent.Subagent;
import org.noear.solon.bot.core.subagent.SubagentManager;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
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
        this.mainAgent = mainAgent;
        this.kernel = null;
        this.manager = manager;
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

        sb.append("### 使用场景\n");
        sb.append("```");
        sb.append("# 场景1: 复杂任务需要团队协作\n");
        sb.append("team_task(\"实现用户登录功能，包括探索、设计、开发、测试\")\n\n");
        sb.append("# 场景2: 调用专门子代理\n");
        sb.append("subagent(\n");
        sb.append("    type=\"explore\",\n");
        sb.append("    prompt=\"分析认证相关代码\",\n");
        sb.append("    description=\"认证代码分析\"\n");
        sb.append(")\n\n");
        sb.append("# 场景3: 继续之前的任务会话\n");
        sb.append("subagent(\n");
        sb.append("    type=\"explore\",\n");
        sb.append("    prompt=\"继续深入分析\",\n");
        sb.append("    taskId=\"subagent_explore_1234567890\"  # 使用之前的 task_id\n");
        sb.append(")\n\n");
        sb.append("# 场景4: 查看团队任务状态\n");
        sb.append("team_status()\n\n");
        sb.append("# 场景5: 创建专门的子代理\n");
        sb.append("create_agent(\"sql-expert\", \"SQL优化专家\", ...)\n");
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
}
