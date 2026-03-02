
## 一、关于

Solon Code CLI 是基于 Solon AI 框架构建的高性能、自主式 AI 终端助手。

它 100% 对齐了 Claude Code CLI 的核心体验，允许 AI 智能体直接与你的本地开发环境交互：阅读代码、运行测试、修复 Bug 以及执行复杂的重构任务。

**核心特性**

* 极致性能：基于 Solon AI 响应式引擎，支持流式交互，内存占用极低。
* 强大技能库：完全兼容 Claude Code Agent Skills 规范，可无缝接入现有的工具集。
* 环境感知：自动索引项目结构，支持文件读写、Grep 搜索及 Bash 命令执行。
* 安全受控 (HITL)：关键操作（如删除文件、执行写入）支持人工审批（Human-in-the-loop）。
* 广泛兼容：支持 Java 8 ~ 25 全环境运行（即便在老旧企业级项目中也能起飞）。

**多态运行**

* CLI：沉浸式的终端开发体验。
* Web API：标准化的 HTTP/JSON 接口，便于集成。
* ACP 协议：支持 Agent Context Protocol，可作为插件接入各类现代 IDE。


## 二、快速开始

### 1、修改配置（运行前）

* 先修改 cli.yml 的配置。主要修改 chatModel（修改后，就能运行了）
* 再修改 AGENTS.md （设定自己的智能体，比如性格，风格，专业等...）


### 2、运行

运行时，需要 jdk8+（jdk8 ~ jdk25） 的环境

* mac/linux 运行 run.sh
* window 运行 run.cmd

### 3、补充：扩展技能 (Skills) 下载

* https://github.com/zrt-ai-lab/opencode-skills
* https://github.com/openclaw/skills
* https://github.com/ComposioHQ/awesome-claude-skills


## 三、后续补充说明

刚开始不用管这块内容

### 关于 `AGENTS.md` 的存放位置：

* 放在工作区根目录下，表示工作区内有效
* 放在程序目录下，表示默认（工作区内没有时，会被启用）

如何配置参考（不要有让 llm 逻辑矛盾的内容）：https://mp.weixin.qq.com/s/gbxNIHHhLEGfbZQLh0lqWQ

### 关于 `.soloncode` 目录：

* 智能体启动后，工作区根目录会自动创建 `.soloncode` 目录（也可以提前创建）
* `.soloncode/sessoins` 存放会话记录（自动）
* `.soloncode/skills` 存放工作区内技能（手动），技能可以放在此处，也可以外部挂载
* `.soloncode/agents` 预留


## 四、最近的更新说明：


* 添加 skillPools 配置替代 mountPool 配置（仍可用）
* 添加 TodoSkill（独立出来）
* 添加 AGENTS.md 配置支持
* 优化 CliSkill 拆分为：TerminalSkill + ExpertSkill
* 优化 简化系统提示词，拆散到各工具里
* 调整 工件包 `SolonCodeCLI.jar` 改为 `soloncode-cli.jar`
* 调整 系统目录 `.system` 改为 `.soloncode`（后者更有标识性）
* 调整 配置文件 `cli.yml` 改为 `config.yml`（后都更通用）
* 调整 配置项 `config/nickname` 取消（由 AGENTS.md 替代，更自由全面）
* 调整 配置项 `config/instruction` 取消（由 AGENTS.md 替代，更自由全面）


关于 `AGENTS.md` 的存放位置：

* 放在工作区根目录下，表示工作区内有效
* 放在程序目录下，表示默认（工作区内没有时，会被启用）

关于 `.soloncode` 目录：

* 智能体启动后，工作区根目录会自动创建 `.soloncode` 目录（也可以提前创建）
* `.soloncode/sessoins` 存放会话记录（自动）
* `.soloncode/skills` 存放工作区内技能（手动），技能可以放在此处，也可以外部挂载
* `.soloncode/agents` 预留
