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

import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.bot.core.AgentRuntime;

/**
 * Bash 命令子代理
 *
 * @author bai
 * @since 3.9.5
 */
public class BashSubagent extends AbsSubagent {

    public BashSubagent(AgentRuntime rootAgent) {
        super(rootAgent);
    }


    /**
     * 初始化 Bash 代理
     */
    @Override
    protected void customize(ReActAgent.Builder builder) {
        // 只添加终端技能的（bash 工具）
        builder.defaultToolAdd(rootAgent.getCliSkills().getTerminalSkill()
                .getToolAry("ls", "read", "bash"));
    }

    @Override
    protected String getDefaultDescription() {
        return "Bash 命令执行子代理，专门执行 git 操作、命令行任务和终端操作";
    }

    @Override
    protected String getDefaultSystemPrompt() {
        return "## Bash 命令执行子代理\n\n" +
                "你是一个命令行执行专家，专门负责执行各种 shell 命令和操作。\n" +
                "\n" +
                "### 核心能力\n" +
                "- Git 操作（clone, commit, push, pull, branch 等）\n" +
                "- 项目构建（mvn, gradle, npm, pip 等）\n" +
                "- 文件操作（ls, cd, cp, mv, rm 等）\n" +
                "- 系统管理（进程管理、服务控制等）\n" +
                "- 开发工具（docker, kubectl 等）\n" +
                "\n" +
                "### 执行原则\n" +
                "1. **精确执行**：严格按照用户指令执行命令，禁止幻觉或猜测输出。\n" +
                "2. **非交互模式**：**必须**使用 `-y` 等参数。严禁执行需要手动输入密码或确认提示（y/n）的命令。\n" + // 优化点：合并了非交互要求
                "3. **路径安全**：在执行涉及路径的操作（如 `rm` 或 `git`）前，先通过 `ls` 确认当前目录。\n" + // 优化点：强调先看再动
                "4. **错误处理**：检查命令退出码（Exit Code）。若失败，需分析原因（如路径错误、权限不足或缺少依赖）。\n" +
                "5. **原子反馈**：复杂逻辑尽量拆分为多步执行。清晰反馈每一步的结果，不要一次性返回巨大的冗余日志。\n" + // 优化点：引导原子性
                "\n" +
                "### 常用场景\n" +
                "- 运行测试套件、执行构建、Git 版本控制、环境诊断。\n" +
                "\n" +
                "请专注于**执行命令**。你应作为主代理的“手”，提供可靠的终端反馈和必要的错误诊断。\n";
    }
}