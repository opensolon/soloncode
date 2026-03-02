
<p align="center">
<img width="500" src="SHOW.png" />
</p>


# Solon Code（[Solon AI](../../../../opensolon/solon-ai) 框架综合应用展示）

Solon Code 是基于 [Solon AI](../../../../opensolon/solon-ai) （支持 Java8 到 Java25）框架构建的高性能、自主式 AI 终端助手。

它（相当于）是用 Java8 实现的 Claude Code，100% 对齐 Claude Code（或 OpenCode）核心体验，允许 AI 智能体直接与你的本地开发环境交互：阅读代码、运行测试、修复 Bug 以及执行复杂的重构任务。


## 核心特性

* 强大技能：完全兼容 Claude Code Agent Skills 规范，可无缝接入现有的技能集。
* 环境感知：自动索引项目结构，支持文件读写、Grep 搜索及 Bash 命令执行。
* 安全受控：关键操作（如删除文件、执行写入）支持人工审批（Human-in-the-loop）。
* 广泛兼容：支持 Java 8 ~ 25 全环境运行（即便在老旧企业级项目中也能起飞）。
* 网络能力：自带 Webfetch、Websearch 工具，方便感知网络（基于 `mcp.exa.ai/mcp` 封装）
* 开源定制：（MIT 开源协议）方便企业基于 Java 生态去定制自己的 Agent

## 多态运行

* CLI：沉浸式的终端开发体验。
* Web API：标准化的 HTTP/JSON 接口，便于集成。
* ACP 协议：支持 Agent Context Protocol，可作为插件接入各类现代 IDE。


## 模型适配与兼容性

下表仅基于兼容测试词："通过网络调查 ai mcp 协议，然后使用技能生成一个 60 秒的视频文件，要求画面好看吸引人。细节你自己解决。"

| 大语言模型             | 兼容适配 | 效率适配 | 备注                              |
|-------------------|------|------|---------------------------------|
| Claude Opus 4.6   | 已适配  | 高    |                                 |
|                   |      |      |                                 |
| GLM 5.0           | 已适配  | 高    | （建议启用 `enable_thinking: false`） |
| qwen3.5-plus      | 已适配  | 低    |
| DeepSeek-R1 3.2   | 已适配  | 低    |                                 |
| MiniMax-M2.5      | 已适配  | 低    |                                 |
| Kimi 2.5          | /    | /    | 多轮后，会出现重复输出的问题（仍需进一步适配）         |
| DeepSeek-Chat 3.2 | /    | /    | 多轮后，会出现重复输出的问题（仍需进一步适配）         |


各模型如果能关闭思考的，建议关闭。节省 token。


## 下载与入门

* 下载程序包

[发布包](../../../../opensolon/soloncode/releases)

* Bilibili 入门视频

[《Solon Code CLI 如何下载与运行？》](https://www.bilibili.com/video/BV1ArfjBiEU4/)

## 扩展技能 (Skills)：

* https://github.com/zrt-ai-lab/opencode-skills
* https://github.com/openclaw/skills
* https://github.com/ComposioHQ/awesome-claude-skills

