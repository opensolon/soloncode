
## 关于

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


## 快速开始

### 1、修改配置

（运行前）先修改 cli.yml 的配置（修改后，就能运行了）

### 2、运行

运行时，需要 jdk8+（jdk8 ~ jdk25） 的环境

* mac/linux 运行 run.sh
* window 运行 run.cmd

### 3、补充：扩展技能 (Skills) 下载


* https://github.com/zrt-ai-lab/opencode-skills
* https://github.com/openclaw/skills
* https://github.com/ComposioHQ/awesome-claude-skills