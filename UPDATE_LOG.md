
### v2026.6.24

* 新增 soloncode /interpret 命令（支持跨会话中断）
* 添加 soloncode /continue 命令 sessionId 参数支持（支持跨会话继续）
* 添加 soloncode StopLoop 拦截器
* 添加 soloncode web 输入面板的模型选择添加搜索功能
* 添加 soloncode web 对话消息 mermaid 渲染支持
* 添加 soloncode web WWW-Authenticate 认证
* 添加 soloncode web 技能市场重新安装支持（达到更新升级效果）
* 优化 soloncode WebGate 允许中断命令通过忙碌检查
* 优化 soloncode web 面板拖拽性能；延迟消息流代码高亮；为滚动区域和面板添加contain布局
* 优化 soloncode web 任务面板数据显示
* 优化 soloncode web mac 组合输入法的兼容性
* 重构 soloncode loop goal 实现
* 细节优化

### v2026.6.21

* 修复 soloncode web 设置/默认模型后没有马上生效的问题
* 添加 soloncode web 设置/供应商管理面板
* 添加 soloncode anthropic 上下文缓存显示支持
* 优化 soloncode web 对话切换性能
* 优化 soloncode web 关闭浏览器密码填充提示
* 细节优化

### v2026.6.20

* 添加 soloncode web 设置 mcp 导入功能
* 添加 soloncode web 设置 供应商功能
* 优化 soloncode web 右侧面板的 emoji 图标改为 svg 图标（跨系统更稳定）
* 优化 soloncode edit 工具匹配算法（同行号可以乎略缩进）
* 细节优化

### v2026.6.18

* 添加 soloncode web 设置/通用/子代理开发
* 添加 soloncode web 设置/工具权限
* 添加 soloncode 命令 `/rerun`
* 优化 soloncode 心智记忆处理
* 优化 soloncode 任务闭合管理
* 优化 soloncode 代码环境版本识别
* 调整 soloncode `/resume` 命令更名为 `/continue`
* 修复 soloncode `/loop goal` 可能重复执行、不能停止等问题
* 细节优化


### v2026.6.17

* 修复 soloncode 安装时，会清掉 `~/.soloncode/skills/` 技能的问题
* 添加 soloncode `/resume` 命令实现，可恢复任何任务（手动中断，异常结束）
* 添加 soloncode web 对话的最后一条消息：重新运行、继续运行按钮
* 细节优化

### v2026.6.16

* 添加 soloncode bash 输出大小限制机制
* 添加 soloncode web 界面效果设置块（支持工具显示展开或收起）
* 添加 soloncode loop 立好执行机制
* 优化 soloncode 上下文压缩算法
* 优化 soloncode edit 匹配算法
* 优化 soloncode web tool 调用显示效果
* 优化 soloncode web edit（git diff） 和 bash（terminal） 显示效果
* 优化 soloncode web 消息列表的复制按钮效果
* 调整 soloncode loop 机制
* 细节优化

### v2026.6.15

* 添加 soloncode web edit 工具（gitdiff 显示）
* 添加 soloncode web write 工具语法亮显显示
* 调整 soloncode loop 任务列表与编辑简化
* 修复 soloncode web 左侧面板 todo 进度（非当前对话）不更新的问题
* 细节优化

### v2026.6.14

* 调整 soloncode 向去 config.yml 过度（当产生 setting.json 后移除 config.yml）
* 修复 soloncode web 新对话发送 command 对话记录没名字的问题
* 修复 soloncode loop maker/checker 模式下无法停止的问题
* 细节优化

### v2026.6.13

* 添加 soloncode web 左侧面板拖动大小的功能（同时取消展开与收起的动画）
* 添加 soloncode loop goal 模式支持
* 优化 soloncode web loop 管理面板
* 修复 soloncode 在用户根目录启动时，配置错乱问题
* 修复 soloncode web 默认模型禁用后，对话列表没有更新的问题
* 细节优化

### v2026.6.12

* 新增 soloncode Loop Engineering 概念支持
* 添加 soloncode web loop 管理面板
* 优化 soloncode web highlight.js 由 cdn 引用，改为本地引用（快多了）
* 优化 soloncode 启动时文件发现扫描改为异步（快多了）
* 细节优化

### v2026.6.11

* 修复 soloncode web 发送消息时，格式被清的问题
* 修复 soloncode web 复制“历史消息”时，格式被清的问题
* 修复 soloncode web 历史记录选对时，格式被清的问题
* 细节优化


### v2026.6.10

* 添加 soloncode web 任务面板（待办事清单）
* 添加 soloncode web 上下文使用情况实时展示
* 添加 soloncode web 大语言模型默认设置
* 添加 soloncode 消盒模式系统内核级支持
* 优化 soloncode ConfigTalent 引导词和参数校验
* 优化 soloncode 上下文压缩算法（增加 tool 计数）
* 细节优化


### v2026.6.9

* 优化 soloncode web json 输出时间段格式
* 优化 soloncode 沙盒模式（减少误伤）
* 修复 soloncode web 设置/大模型检测失败的问题（有些模型需要有 user-agent）
* 修复 soloncode 首次自动添加 npx mcp 后，工具加载失败的问题（超时了）
* 细节优化

### v2026.6.8

* 新增 soloncode 通过提示词自动添加 config 的支持
* 优化 soloncode web 设置体验细节
* 重构 soloncode 沙盒模式
* 修复 soloncode 因 mcp, openapi 配置错误而无法启动的问题
* 细节优化

### v2026.6.6

* 添加 soloncode web 文件树 tooltip
* 优化 soloncode web 设置添加模型时，对话框架模型同步刷新
* 优化 soloncode web 设置作用域选择框样式
* 优化 soloncode web 设置 LSP 样式
* 优化 soloncode web 设置 挂截 样式
* 优化 soloncode OpenAI Responses 接口兼容性
* 优化 soloncode 压缩算法
* 细节优化

### v2026.6.5

* 添加 soloncode web 专门的 trace 显示块
* 添加 soloncode web lsp 管理能力
* 添加 soloncode web 更多通用设置
* 添加 soloncode web 设置 “作用域” 权念（有效结合，全局设置和工作区设置）
* 优化 soloncode web 代码语法高亮看不清的问题（改成了浅底色）
* 优化 soloncode web 挂载点编辑逻辑
* 修复 soloncode web 历史对话里没有代码语法高亮的问题
* 修复 soloncode web 模型列表为空时仍尝试获取选中模型导致的逻辑问题
* 细节优化

### v2026.6.3

* 添加 soloncode web 通用设置“重试策略”功能
* 添加 soloncode web 技能市场“安装到”功能（可选择技能池）
* 添加 soloncode web 技能市场点击查看详情的功能
* 添加 soloncode web 挂载点击内容查看目录的功能
* 添加 soloncode web 挂载启用开关
* 添加 soloncode mcp 服务器权限控制（禁用工具）
* 添加 soloncode openapi 服务器权限控制（禁用工具）
* 优化 soloncode mcp 协议兼容性（兼容更多非规范协议）
* 优化 soloncode 上下文压缩算法
* 细节优化

### v2026.5.31

* 添加 soloncode web 挂载池管理
* 添加 soloncode web 技能市场安装到（挂载池）选择功能
* 优化 soloncode web 设置面板样式
* 调整 soloncode 技能标识由 aliasPath 统一改为 name，并优化技能池加载逻辑及代码格式
* 修复 soloncode 压缩后可能会引起 ToolMessage 无法对齐的问题
* 细节优化

### v2026.5.30

* 添加 soloncode web 对话 “历史” 功能（复用，定位）
* 添加 soloncode web 审查 “生成摘要” 功能
* 添加 soloncode web 设置/大语言模型 配置功能
* 添加 soloncode web 设置/技能市场 功能（查找、安装）
* 添加 soloncode web 设置/MCP 服务器 配置功能
* 添加 soloncode web 设置/OpenApi 服务器 配置功能
* 细节优化

### v2026.5.26

* 优化 soloncode ActionTask 增加原子性（避免单工具失败时，影响整个工作记忆）
* 优化 soloncode skill 提示加去重处理
* 细节优化

### v2026.5.22

* 优化 soloncode mcp 兼容性（有些 mcp server 不完全按 mcp 规范来）
* 升级 mcp sdk 到 v1.1.3

### v2026.5.21

* 添加 soloncode web 文件查看与复制功能
* 添加 soloncode web 文件搜索功能
* 添加 soloncode web 右侧面板的拖动功能
* 添加 soloncode web 技能提示
* 添加 soloncode cli 技能提示

### v2026.5.20

* 添加 soloncode web 文件树功能（方便知道有哪些文件）
* 添加 soloncode web git diff 功能

### v2026.5.19

* 优化 soloncode 压缩时机和压缩算法
* 优化 soloncode edit 组合权限控制
* 细节优化


### v2026.5.15

* 修复 soloncode llm 返空重试的可能卡死问题
* 细节优化

### v2026.5.13

* 修复 soloncode WebfetchTool 超时失效造成卡死的问题
* 细节优化

### v2026.5.12

* 优化 soloncode web 飞书绑定
* 优化 soloncode web 钉钉绑定
* 优化 soloncode ReActAgent 架构（由计算图改为更简单的 while）
* 细节优化

### v2026.5.11

* 新增 soloncode web 飞书链接
* 新增 soloncode web 钉钉链接
* 添加 soloncode 时间显示
* 优化 soloncode web 微信链接体验
* 修复 soloncode web loop 任务失效的问题（只运行了一次）
* 细节优化

### v2026.5.9

* 添加 soloncode `/rewind` 命令（对话回退）
* 添加 soloncode web （输入框空时）上下键选历史任务功能
* 添加 soloncode web 微信通道命令支持
* 添加 soloncode web icon
* 调整 soloncode 心智记忆改为 md 文件保存（之前的会失效）
* 调整 soloncode web 改用 websocket 统一输出
* 修复 soloncode web `/loop` 任务没有用会话选中模型的问题
* 细节优化

### v2026.5.6

* 新增 soloncode web 微信接入
* 添加 soloncode `/loop` cron 表达式支持
* 优化 soloncode acp 输出

```
/loop cron:'0 */5 * * * ?' check status
```

### v2026.5.5

* 优化 soloncode 启动打印（非 cli 模式，改用 err 通道打印）
* acp-sdk 升为 0.11.0

### v2026.5.4

* 优化 soloncode cli 思考转为灰色
* 优化 soloncode web 取消 thymeleaf 依赖包（缩减 2.5Mb）
* 修复 soloncode mac 安装时，会不断添加新的环境配置记录
* 修复 soloncode 执行恢复命令会出错的问题（v2026.5.3 时出现）

### v2026.5.3

* 添加 soloncode `@agent` 功能

### v2026.5.2

* 优化 soloncode 内置 skill.sh 的 find-skills 专家技能（可以自然语言查找 skill 和安装 skill。先查找再安装）
* 优化 soloncode web 附件上传功能（原附件分为：普通文件，多模态图片文件）
* 优化 soloncode web 语音输入体验，改成微信风格（按住说话，松开结束）
* 优化 soloncode 大模型兼容性（原上下文摘要由系统消息，改为用户消息）
* 调整 soloncode cli 取消回车中断（容易误输）


### v2026.5.1

* 添加 soloncode 心智记忆功能（越用越聪明）

### v2026.4.30

* 新增 soloncode `/loop` 命令支持
* 优化 soloncode `code` skill （增加更多编程语言支持）
* 优化 soloncode 可能意外退出的提示输出

### v2026.4.29

* 添加 soloncode openai baseUrl 配置支持（其它的仍要配置全地址）
* 添加 soloncode commands 命令支持
* 添加 soloncode web 会话缩进支持

### v2026.4.28

* 优化 soloncode web tool-call 打印
* 优化 soloncode cli 安全引导词
* 修复 soloncode 多模态格式拼接错误问题

### v2026.4.27

* 添加 soloncode userAgent 缺省配置（不需要每个 llm 都配置了）
* 优化 soloncode deepseek-v4 适配

### v2026.4.26

* 新增 soloncode web 附件上传功能
* 修复 sololcode 没有加载工作区的 skills 问题（25 引起的）

### v2026.4.25

* 新增 soloncode web 交互界面多模型选择功能
* 新增 soloncode cli 交互界面多模型选择功能

### v2026.4.24

* 新增 soloncode 扩展机制（支持手动配置，与自动装配）
* 其它细节优化

### v2026.4.22

* 新增 soloncode lsp 功能
* 添加 soloncode agentPools（子代理池） 配置支持
* 添加 soloncode chatModel.internalStream 添加流接收超时处理（避免因 llm 服务原因一直卡着）
* 优化 soloncode ExpertSkill 引导词

### v2026.4.20

* 新增 soloncode web 交互模式
* 优化 soloncode-cli 启动时打印主代理模型
* 添加 soloncode 重试次数配置支持
* 添加 新指令 `soloncode web`（默认端口） `soloncode web 1212`（指定端口）
* 调整 soloncode 取消 isCliEnabled, isWebEnabled, isWsEnabled, isAcpEnabled 配置。统一由命令控制：`soloncode server`（启动后端服务，cli 会自动关闭）, 统一由命令控制：`soloncode web`（启动 web 服务）, `soloncode acp`（启动 acp 服务）
* 调整 soloncode-web 合并到 soloncode-cli （作为一个发布包发布）

### v2026.4.18

* 优化 soloncode-cli anthropic, openai-response 方言的异常兼容处理
* 优化 solon-development-skill

### v2026.4.16

* 修复 soloncode-cli 因 snackjson 自动解包引起的部分 llm 不兼容问题

### v2026.4.15

* 优化 soloncode-cli chunk 的打印，不再需要 isNormal 判断了（内部溶进了 ReasonChunk）
* 修复 soloncode-cli 在某些 llm 工具调用时会失败的问题（v2026.4.14 引起的）

### v2026.4.14

* 添加 soloncode-cli 更新检测与提示
* 优化 soloncode-cli agent 日志打印级别
* 修复 soloncode-cli 在 window git bash 环境下 backspace 删除输入文字乱的问题
* 修复 soloncode-cli 因 llm 参数格式问题造成 multitask 工具失败的问题（加了自动修正）
* 其它细节优化

### v2026.4.11

* 添加 soloncode-core subagent 切换模型的能力; 
* 添加 soloncode-core models 配置属性（用于替代 chatModel 单配置）;
* 优化 soloncode-core model 重试条件（改用新的空判断）
* 优化 soloncode-cli ws 通道的输出打印
* 调整 soloncode-core CLAUDE.md 更名为 CODE.md（内部自动生成）
* 调整 soloncode-core restApis 更名为 apiServers；取消 chatModel（由 models 替代）
* 修复 soloncode-core CodeSearchTool 失效的问题（mcp.exa.ai 变了地址）

### v2026.4.6

* 添加 `soloncode --session=test` 指令（启动时指定默认会话id）
* 添加 `soloncode serve` 指令或 `soloncode serve -server.port=4808` 启动无界面服务（作为 http 和 ws 服务）
* 优化 `soloncode-cli` websocket 的消息处理
* 调整 `soloncode-desktop` 更名为 `soloncode-ide`
* 完成 `soloncode-ide` 的上下文功能，通过文件树双击附加上下文
* 取消 `soloncode-ide` 输入框的“#”（由双击取代）和“@”功能（通过自然语言按需调用）
* 取消 `soloncode-ide` 底部栏的 llm 名字显示（由对话框的显示替代）
* 调整 `soloncode` http 端口默认改为：4808（之前是 8080）

### v2026.4.5

* 添加 soloncode-cli pid 打印
* 添加 soloncode-cli skillhub 自动索引
* 优化 soloncode-cli edit 失败时的提示细节
* 优化 java21+ 环境，去除启动时的 System.load() 警告
* 调整 soloncode-cli 日志输出位置到 `.soloncode` 下面（这样，不会产生多余的目录）
* 调整 soloncode-cli TODO 机制，主代理用文件模式（避免开发时冲突，或产生文件太多），次代理用内存模式。次代理定位偏临时性
* 调整 soloncode-core CLAUDE.md 文件位置到 `.soloncode` 下面（这样，不会产生多余的文件）
* 调整 soloncode-core config.yml 和 AGENTS.md 安装位置（从 bin 移到上一级）//与工作区位置相同

### v2026.4.1

* 完成 soloncode-cli “系统命令”化改造（重要）
* 添加 soloncode-code AgentRuntime.createSubagent 方法
* 添加 soloncode-cli `soloncode run xxx` 脚本命令运行支持
* 优化 soloncode-code cwd 动态传递
* 优化 soloncode-code 引入工具网关，解决 mcp 过多时的问题
* 优化 soloncode-web ReActChunk 输出(token 与 sec)
* 调整 soloncode-core 进一步简化代码（合并一部分类）
* 调整 soloncode-cli 取消 init 初始化命令（改为自动了）
* 调整 soloncode-cli 默认会话改为 'default'
* 调整 `solon.code.cli` 配置节，更名为 `soloncode`
* 修复 soloncode-cli 缩放窗口时，User 提示词会多次渲染的问题

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