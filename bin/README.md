
## 一、关于

Solon Code CLI 是基于 Solon AI 框架构建的高性能、自主式 AI 终端助手。

它 100% 对齐了 Claude Code CLI 的核心体验，允许 AI 智能体直接与你的本地开发环境交互：阅读代码、运行测试、修复 Bug 以及执行复杂的重构任务。

**核心特性**

* 强大技能：完全兼容 Claude Code Agent Skills 规范，可无缝接入现有的技能集。
* 环境感知：自动索引项目结构，支持文件读写、Grep 搜索及 Bash 命令执行。
* 安全受控：关键操作（如删除文件、执行写入）支持人工审批（Human-in-the-loop）。
* 广泛兼容：支持 Java 8 ~ 25 全环境运行（即便在老旧企业级项目中也能起飞）。
* 网络能力：自带 Webfetch、Websearch 工具，方便感知网络（基于 `mcp.exa.ai/mcp` 封装）
* 开源定制：（MIT 开源协议）方便企业基于 Java 生态去定制自己的 Agent
* 兼容第三方规范：
    * 支持自动加载 `.opencode/skills` 作为 `@opencode_skills` 只读池
    * 支持自动加载 `.claude/skills` 作为 `@claude_skills` 只读池
    * 支持自动加载 `CLAUDE.md` 作为代码项目规范


**多态运行支持**

* CLI：沉浸式的终端开发体验。
* Web API：标准化的 HTTP/JSON 接口，便于集成。
* ACP 协议：支持 Agent Context Protocol，可作为插件接入各类现代 IDE。

## 二、快速开始（重要！）

### 1、修改配置（运行前）

* 先修改 `config.yml` 的配置。主要修改 chatModel（修改后，就能运行了）
* 再修改 `AGENTS.md` （设定自己的智能体，比如性格，风格，专业等...）


### 2、运行

运行时，需要 jdk8+（jdk8 ~ jdk25） 的环境

* mac/linux 运行 run.sh
* window 运行 run.cmd

### 3、补充：扩展技能 (Skills) 下载

* https://github.com/opensolon/skills （内部只有一个 solon 框架开发技能）
* https://github.com/zrt-ai-lab/opencode-skills
* https://github.com/openclaw/skills
* https://github.com/anthropics/skills
* https://github.com/ComposioHQ/awesome-claude-skills


重要提醒：

* 如果有跨技能 `.opencode` 或 `.claude` 开头的脚本调用，要放到工作区的规范目录下
* 如果没有（跨技能），可按普通技能池配置挂载

兼容第三方规范：

* 支持自动加载 `.opencode/skills` 作为 `@opencode_skills` 只读池
* 支持自动加载 `.claude/skills` 作为 `@claude_skills` 只读池



## 三、后续补充说明（刚开始不用管这块内容）



### 关于 `AGENTS.md` 的存放位置：

* 放在工作区根目录下，表示工作区内有效
* 放在程序目录下，表示默认（工作区内没有时，会被启用）

如何配置参考（不要有让 llm 逻辑矛盾的内容）：https://mp.weixin.qq.com/s/gbxNIHHhLEGfbZQLh0lqWQ

### 关于 `.soloncode` 目录：

智能体启动后，工作区根目录会自动创建 `.soloncode` 目录（也可以提前创建）

* `.soloncode/sessoins` 存放会话记录（自动）
* `.soloncode/skills` 存放工作区内技能（手动），技能可以放在此处，也可以外部挂载
* `.soloncode/agents` 子代理配置记录（自动）

### 如何使用 agent-team ？

通过自然语言描述，示例：

```
我想开发一个经典的权限管理系统（项目名为 demo1-web，里面再建两个目录分为前端与后端），后端基于 solon（参考技术开发），java8，前端基于 vue3 开发，界面要清爽好看。先帮我设计一个 agent team （至少要有架构师，前端开发，后端开发，测试员四个角色，如果有需要每个模块可以安排一个角色并行开发，当然架构师要把好关）分工去做这个事，团队设计案保存为 demo1-team.md。然后驱动团队去完成开发，单元测试，界面测试（用无头浏览器去测试）。全好了后，运行起来，让我检查效果。
```