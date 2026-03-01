
### v0.0.15

* 添加 skillPools 配置替代 mountPool 配置（仍可用）
* 添加 TodoSkill（独立出来）
* 添加 AGENTS.md 配置支持
* 优化 CliSkill 拆分为：TerminalSkill + ExpertSkill
* 优化 简化系统提示词，拆散到各工具里
* 调整 `.system` 改为 `.soloncode`（后者更有标识性）
* 调整 `cli.yml` 改为 `config.yml`（后都更通用）
* 调整 `config/nickname` 取消（由 AGENTS.md 替代，更自由全面）
* 调整 `config/instruction` 取消（由 AGENTS.md 替代，更自由全面）


关于 `AGENTS.md` 的存放位置：

* 放在工作区根目录下，表示工作区内有效
* 放在程序目录下，表示默认（工作区内没有时，会被启用）

关于 `.soloncode` 目录：

* 智能体启动后，工作区根目录会自动创建 `.soloncode` 目录（也可以提前创建）
* `.soloncode/sessoins` 存放会话记录（自动）
* `.soloncode/skills` 存放工作区内技能（手动），技能可以放在此处，也可以外部挂载

### v0.0.14

* 添加 mcpServers 配置支持（支持 mcp 配置）
* 添加 apply_patch 内置工具（支持批量操作文件），替代 diff 工具
* 添加 cli.yaml userAgent 默认配置（用于支持阿里云的 coding plan，它需要 UA） 
* 优化 ssl 处理（方便支持任意证书）
* 优化 codesearch 工具描述（强调是远程查询，避免 llm 错用）
* 优化 init 提示词
* 优化 简化系统提示词
* 优化 取消 ReActAgent 自带的计划模式，改用 TODO.md 纯文件模式（可简化系统提示词）

### v0.0.13

* 添加 codesearch 内置工具
* 添加 websearch 内置工具
* 添加 webfetch 内置工具
* 优化 systemPrompt 引导约束
* 优化 summarizationInterceptor 增加策略机制并内置4个策略
* 修复 ChatModel.stream 过程异常时会破坏流响应的问题
* 修复 ReActAgent.ReasonTask.callWithRetry 网络异常时会中断工作流的问题
* 修复 ReActAgent.stream 流式请求时，可能无法记忆结果内容的问题

### v0.0.12

* 优化 命名（方便画图）
* 修复 HITL 可能会出现2次确认界面的问题

### v0.0.11

* 优化 instruction 机制，开放用户可配置定制