
### v2026.3.26

* 添加 工作区 skills 目录自动索引
* 优化 explore.md 和 TaskSkill
* 优化 cli 打印添加执行秒数（方便看）
* 移除 ApplyPatchTool，LuceneSkill （减少工具上下文，工件包缩小为：27MB）
* 调整 子代理模式改为弱模式（不再强模式使用，否则会让简单的体务变复杂）
* 调整 子代理增加 code skill 支持

### v2026.3.23

* 优化 子代理模式相关细节（子代理模式下，只做计划与调度）
* 优化 项目结构，成熟的技能转为 `solon-ai-skill-*`
* 优化 上下文摘要压缩处理
* 添加 恢复命令（恢复这前的快照并继续执行）
* 添加 并行子代理支持
* 调整 取消 browser 插件（包可以小100多m，以小优先）
* 调整 取消 agent-team 代码实现，改由自然语言驱动 agent-team（以小优先）
* 调整 general-purpose 更名为 general
* 修复 Windows 下创建中文文件，文件夹及执行中文命令 bat 的乱码问题

### v0.0.23

* 添加 TeamAgent 支持

### v0.0.22

* 添加 KeyInfoExtractionStrategy 处理
* 添加 summaryWindowToken 配置
* 优化 TaskSkill 子代理引导指令

### v0.0.21

* 优化 浏览器能力（不需要下载了，改用系统浏览器的能力）

### v0.0.20

* 添加 子智能体（子代理）流式输出
* 添加 BrowserSkill（浏览器能力。支持通过浏览器测试，或者淘宝买东西）

### v0.0.19

* 添加 沙盒模式对 `~/` （用户根目录）的支持，有些 skill 会要求安装在用户目录下
* 添加 子智能体模式支持（默认启用，可通过配置关闭）

### v0.0.18

* 添加 anthropic 接口兼容支持
* 优化 与 api.minimax.io 平台接口的兼容性

### v0.0.17

* 添加 summaryWindowSize 摘要窗口大小（即，工作上下文压缩时保留几条消息），一般 12 或 15（更吃 token，但保留更多最近交互）
* 添加 sandboxMode 配置。`:true`（沙盒模式，只能访问工作区内的相对路径）, `:false`（开放模式，支持工作区外的绝对路径）
* 添加 thinkPrinted 配置（关闭界面更清爽）
* 优化 CodeSkill 增加三级扫描（之前为二级）

### v0.0.16

* 添加 自动加载工作区下的 `.opencode/skills` 作为 `@opencode_skills` 只读池
* 添加 自动加载工作区下的 `.claude/skills` 作为 `@claude_skills` 只读池
* 优化 TerminalSkill 预置环境变量 `$PYTHON`，`$NODE`
* 优化 TerminalSkill bash 添加超时控制（由 llm 控制），之前只能默认（造成有些脚本执行超时）
* 优化 ExpertSkill skillread 时添加文件在`沙盒`内的别名，并引导使用沙盒别名

### v0.0.15

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