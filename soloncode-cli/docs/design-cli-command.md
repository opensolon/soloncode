# SolonCode CLI Command 系统设计方案

## 一、Claude Code 的 Command 机制调研

### 1.1 架构概览

Claude Code 的 slash-command 系统**不是简单的快捷方式集合，而是一个结构化的"操作系统"级控制面板**。核心注册中心位于 `src/commands.ts`（约 754 行），管理着 100+ 个命令目录。

关键架构图：

```
用户输入 (/command arg)
    │
    ▼
 processUserInput()         ← 解析 /command, 构建 UserMessage
    │
    ├─→ 匹配内置 Command ──→ 执行对应逻辑
    │
    └─→ 匹配自定义 Command (.md) ──→ 替换 $ARGUMENTS ──→ 作为 prompt 发给 LLM
```

### 1.2 三种命令类型

| 类型 | 说明 | 典型示例 |
|------|------|----------|
| **PromptCommand** | 生成格式化 prompt 发给 LLM，可注入 allowedTools | `/review`, `/commit` |
| **LocalCommand** | 本地执行，返回纯文本 | `/cost`, `/version`, `/model ls` |
| **LocalJSXCommand** | 本地执行，返回 React JSX 交互界面 | `/install`, `/doctor` |

每种类型对应不同的执行模式，命令不是扁平的快捷方式，而是**带类型接口的执行通道**。

### 1.3 命令对象结构

```typescript
const command = {
  type: 'prompt',           // 'prompt' | 'local' | 'local-jsx'
  name: 'my-command',       // 命令名（不含 /）
  description: '描述',      // 帮助文本
  progressMessage: 'working...',  // 执行进度提示
  allowedTools: ['Bash(git *)', 'FileRead(*)'],  // prompt 类型可限制工具
  source: 'builtin',        // 'builtin' | 'skill' | 'plugin' | 'project' | 'user'
  async getPromptForCommand(args, context) {
    return [{ type: 'text', text: '...' }]
  },
} satisfies Command
```

### 1.4 动态注册机制（核心设计亮点）

`commands.ts` 不仅返回内置命令，它的注册表是**动态的**：
- **skill-directory commands** — 从技能目录发现的命令
- **plugin commands** — 插件注册的命令
- **bundled skills** — 内置技能命令
- **workflow commands** — 工作流命令
- **MCP skill commands** — MCP 服务器提供的技能

```
命令发现优先级:
  local (.claude/commands/) → global (~/.claude/commands/) → plugin → builtin
```

### 1.5 自定义命令（Custom Commands）

- 在 `.claude/commands/` 目录放 `.md` 文件即自动注册
- 文件内容即 prompt 模板
- 使用 `$ARGUMENTS` 占位符接收动态输入
- 例：`.claude/commands/review.md` → 用户输入 `/review` → 内容变成 prompt

### 1.6 核心设计理念

> **slash-command 是多种可扩展性的收敛点**。Claude Code 用一个命令底层服务同时支撑人类控制和模型控制。人类通过 `/` 前缀调用，模型通过 Skill tool 调用。这减少了概念膨胀——不需要一套人宏、一套 prompt 片段、一套插件、一套技能。

---

## 二、soloncode-cli CliShell 现状分析

### 2.1 当前架构

CliShell 的命令处理集中在 `isSystemCommand()` 方法（第 185-248 行），采用**硬编码 if-else** 模式：

```java
private boolean isSystemCommand(AgentSession session, String input) throws Exception {
    String cmd = input.trim().toLowerCase();
    if ("/exit".equals(cmd)) { ... }
    if ("/resume".equals(cmd)) { ... }
    if ("/clear".equals(cmd)) { ... }
    if (cmd.startsWith("/model")) { ... }  // 多子命令逻辑也内联
    return false;
}
```

### 2.2 当前命令清单

| 命令 | 类型 | 实现 |
|------|------|------|
| `/exit` | 系统 | System.exit(0) |
| `/resume` | Agent | performAgentTask(null) |
| `/clear` | 会话 | session.clear() |
| `/model [ls\|help\|<name>]` | 配置 | 内联多分支 |

### 2.3 问题

1. **不可扩展**：新增命令必须修改 CliShell 源码
2. **无命令发现**：不支持从外部目录/文件加载命令
3. **无命令注册表**：无法列出所有可用命令
4. **类型不区分**：所有命令混在一个方法里
5. **无自定义命令支持**：用户/项目无法定义自己的命令
6. **无 Tab 补全**：JLine completer 只配了文件名

### 2.4 已有扩展机制

项目已有 `HarnessExtension`（扩展点接口），以及 Solon IoC 容器的插件机制（`Plugin` 接口）。这些可以作为命令注册的基础设施。

---

## 三、设计方案：CliCommand 系统架构

### 3.1 设计原则

1. **对齐 Claude Code 架构**：采用三种命令类型 + 动态注册
2. **零侵入集成**：在现有 `isSystemCommand()` 处注入，不改主循环
3. **利用 Solon 生态**：通过 IoC 自动发现 `CliCommand` 实现
4. **支持自定义命令**：从 `.soloncode/commands/` 目录加载 `.md` 文件
5. **Tab 补全**：注册命令名到 JLine completer

### 3.2 核心接口设计

```java
package org.noear.solon.codecli.command;

/**
 * CLI 命令接口
 */
public interface CliCommand {
    
    /** 命令名（不含 /），如 "model"、"exit" */
    String name();
    
    /** 命令描述，用于帮助文本 */
    String description();
    
    /** 命令类型 */
    CliCommandType type();
    
    /** 命令来源 */
    default CliCommandSource source() {
        return CliCommandSource.BUILTIN;
    }
    
    /**
     * 执行命令
     * @param context 命令上下文（session, terminal, args 等）
     * @return true 表示命令已处理，false 表示未处理
     */
    boolean execute(CliCommandContext context) throws Exception;
}
```

```java
package org.noear.solon.codecli.command;

/**
 * 命令类型枚举
 */
public enum CliCommandType {
    /** 系统级：直接操作 CLI 状态，如 /exit, /clear */
    SYSTEM,
    /** 配置级：查询或修改运行配置，如 /model, /config */
    CONFIG,  
    /** Agent 级：触发 Agent 执行，如 /resume, /compact */
    AGENT
}
```

```java
package org.noear.solon.codecli.command;

/**
 * 命令来源枚举
 */
public enum CliCommandSource {
    /** 内置命令 */
    BUILTIN,
    /** 用户自定义命令（~/.soloncode/commands/） */
    USER,
    /** 项目自定义命令（.soloncode/commands/） */
    PROJECT,
    /** 扩展命令（通过 HarnessExtension 注册） */
    EXTENSION
}
```

### 3.3 命令上下文

```java
package org.noear.solon.codecli.command;

import org.jline.terminal.Terminal;
import org.jline.reader.LineReader;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.codecli.core.AgentProperties;

import java.util.List;

/**
 * 命令执行上下文
 */
public class CliCommandContext {
    private final AgentSession session;
    private final Terminal terminal;
    private final LineReader reader;
    private final HarnessEngine agentRuntime;
    private final AgentProperties agentProps;
    private final String rawInput;       // 原始完整输入 "/model ls"
    private final String commandName;     // 命令名 "model"
    private final List<String> args;      // 参数 ["ls"]
    
    // constructor, getters...
    
    /** 打印到终端 */
    public void println(String text) {
        terminal.writer().println(text);
        terminal.flush();
    }
    
    /** 获取第一个参数，若无则返回 null */
    public String argAt(int index) {
        return args.size() > index ? args.get(index) : null;
    }
    
    /** 获取参数数量 */
    public int argCount() {
        return args.size();
    }
}
```

### 3.4 命令注册表

```java
package org.noear.solon.codecli.command;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CLI 命令注册表
 */
public class CliCommandRegistry {
    private static final Logger LOG = LoggerFactory.getLogger(CliCommandRegistry.class);
    
    private final Map<String, CliCommand> commands = new ConcurrentHashMap<>();
    
    /** 注册命令 */
    public void register(CliCommand command) {
        CliCommand existing = commands.putIfAbsent(command.name(), command);
        if (existing != null) {
            LOG.warn("Command '{}' already registered by {}, skip: {}", 
                command.name(), existing.getClass().getSimpleName(), command.getClass().getSimpleName());
        }
    }
    
    /** 查找命令 */
    public CliCommand find(String name) {
        return commands.get(name);
    }
    
    /** 获取所有命令（排序后） */
    public List<CliCommand> all() {
        return commands.values().stream()
                .sorted(Comparator.comparing(CliCommand::name))
                .toList();
    }
    
    /** 获取命令名列表（用于 Tab 补全） */
    public List<String> names() {
        return new ArrayList<>(commands.keySet()).stream().sorted().toList();
    }
}
```

### 3.5 内置命令实现示例

将现有的硬编码命令逐一迁移为独立类：

```java
package org.noear.solon.codecli.command.builtin;

/**
 * /exit 命令
 */
public class ExitCommand implements CliCommand {
    @Override public String name() { return "exit"; }
    @Override public String description() { return "Exit the CLI"; }
    @Override public CliCommandType type() { return CliCommandType.SYSTEM; }
    
    @Override
    public boolean execute(CliCommandContext ctx) {
        ctx.println("\033[2mExiting...\033[0m");
        System.exit(0);
        return true;
    }
}
```

```java
package org.noear.solon.codecli.command.builtin;

/**
 * /model 命令（多子命令）
 */
public class ModelCommand implements CliCommand {
    @Override public String name() { return "model"; }
    @Override public String description() { return "Model management"; }
    @Override public CliCommandType type() { return CliCommandType.CONFIG; }
    
    @Override
    public boolean execute(CliCommandContext ctx) throws Exception {
        String flag = ctx.argAt(0);
        String currentModel = ctx.getSession().getContext().getAs(AgentFlags.VAR_MODEL_SELECTED);
        // ... 现有 /model 的全部逻辑迁移过来 ...
        return true;
    }
}
```

```java
package org.noear.solon.codecli.command.builtin;

/**
 * /help 命令（新增！）
 */
public class HelpCommand implements CliCommand {
    private final CliCommandRegistry registry;
    
    public HelpCommand(CliCommandRegistry registry) {
        this.registry = registry;
    }
    
    @Override public String name() { return "help"; }
    @Override public String description() { return "Show available commands"; }
    @Override public CliCommandType type() { return CliCommandType.SYSTEM; }
    
    @Override
    public boolean execute(CliCommandContext ctx) {
        ctx.println("\033[1mAvailable Commands:\033[0m");
        for (CliCommand cmd : registry.all()) {
            ctx.println("  \033[36m/" + cmd.name() + "\033[0m" + " - " + cmd.description());
        }
        ctx.println("\n\033[2mType /<command> to execute\033[0m");
        return true;
    }
}
```

### 3.6 自定义命令加载（Markdown 文件）

这是对标 Claude Code `.claude/commands/*.md` 的核心扩展能力：

```java
package org.noear.solon.codecli.command;

import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Stream;

/**
 * 从 .soloncode/commands/ 目录加载 Markdown 自定义命令
 */
public class CustomCommandLoader {
    private static final Logger LOG = LoggerFactory.getLogger(CustomCommandLoader.class);
    
    /**
     * 扫描目录，注册 .md 文件为命令
     * @param dirPath 命令目录（如 .soloncode/commands/ 或 ~/.soloncode/commands/）
     * @param registry 注册表
     * @param source 命令来源
     */
    public static void loadFromDirectory(String dirPath, CliCommandRegistry registry, CliCommandSource source) {
        Path dir = Paths.get(dirPath);
        if (!Files.isDirectory(dir)) {
            return;
        }
        
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(".md"))
                 .forEach(p -> registerMarkdownCommand(p, registry, source));
        } catch (IOException e) {
            LOG.warn("Failed to load commands from {}: {}", dirPath, e.getMessage());
        }
    }
    
    private static void registerMarkdownCommand(Path mdFile, CliCommandRegistry registry, CliCommandSource source) {
        String fileName = mdFile.getFileName().toString();
        String cmdName = fileName.substring(0, fileName.length() - 3); // 去掉 .md
        
        try {
            String template = Files.readString(mdFile);
            registry.register(new MarkdownCommand(cmdName, template, source));
        } catch (IOException e) {
            LOG.warn("Failed to read command file {}: {}", mdFile, e.getMessage());
        }
    }
}
```

```java
package org.noear.solon.codecli.command;

import org.noear.solon.core.util.Assert;

/**
 * 基于 Markdown 模板的自定义命令（对标 Claude Code 的 Custom Commands）
 * 
 * .md 文件内容即为 prompt 模板，支持 $ARGUMENTS 占位符
 */
public class MarkdownCommand implements CliCommand {
    private final String name;
    private final String template;
    private final CliCommandSource source;
    
    public MarkdownCommand(String name, String template, CliCommandSource source) {
        this.name = name;
        this.template = template;
        this.source = source;
    }
    
    @Override public String name() { return name; }
    @Override public String description() { return "Custom command: " + name; }
    @Override public CliCommandType type() { return CliCommandType.AGENT; }
    @Override public CliCommandSource source() { return source; }
    
    /**
     * 获取替换 $ARGUMENTS 后的 prompt 文本
     */
    public String getResolvedPrompt(String arguments) {
        String args = Assert.isEmpty(arguments) ? "" : arguments;
        return template.replace("$ARGUMENTS", args);
    }
    
    @Override
    public boolean execute(CliCommandContext ctx) throws Exception {
        // 拼接参数
        String arguments = String.join(" ", ctx.getArgs());
        String prompt = getResolvedPrompt(arguments);
        
        // 作为 Agent 任务执行
        // 通过 CliShell 回调 performAgentTask
        return false; // 返回 false，由 CliShell 继续处理为 agent task
    }
}
```

### 3.7 CliShell 集成改造

```java
// CliShell.java 改造要点

public class CliShell implements Runnable {
    private final CliCommandRegistry commandRegistry = new CliCommandRegistry();
    
    public CliShell(HarnessEngine agentRuntime, AgentProperties agentProps) {
        // ... 现有初始化 ...
        
        // 注册内置命令
        registerBuiltinCommands();
        
        // 加载自定义命令
        loadCustomCommands();
        
        // 更新 JLine completer（支持命令补全）
        updateCompleter();
    }
    
    private void registerBuiltinCommands() {
        commandRegistry.register(new ExitCommand());
        commandRegistry.register(new ClearCommand());
        commandRegistry.register(new ResumeCommand());
        commandRegistry.register(new ModelCommand());
        commandRegistry.register(new HelpCommand(commandRegistry));
        // 未来可轻松新增...
        // commandRegistry.register(new CompactCommand());
        // commandRegistry.register(new CostCommand());
        // commandRegistry.register(new ConfigCommand());
    }
    
    private void loadCustomCommands() {
        // 1. 用户级命令：~/.soloncode/commands/
        CustomCommandLoader.loadFromDirectory(
            Paths.get(AgentProperties.getUserHome(), ".soloncode", "commands").toString(),
            commandRegistry, CliCommandSource.USER);
        
        // 2. 项目级命令：.soloncode/commands/
        CustomCommandLoader.loadFromDirectory(
            Paths.get(agentProps.getWorkspace(), ".soloncode", "commands").toString(),
            commandRegistry, CliCommandSource.PROJECT);
    }
    
    private void updateCompleter() {
        // 替换 FileNameCompleter 为复合 Completer
        this.reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .completer(new CliCommandCompleter(commandRegistry))
                .build();
    }
    
    // ---- 核心改造：替换原 isSystemCommand ----
    
    private boolean isSystemCommand(AgentSession session, String input) throws Exception {
        if (!input.startsWith("/")) {
            return false;
        }
        
        // 解析命令名和参数
        String[] parts = input.trim().substring(1).split("\\s+");
        String cmdName = parts[0].toLowerCase();
        List<String> args = parts.length > 1 
                ? List.of(Arrays.copyOfRange(parts, 1, parts.length)) 
                : List.of();
        
        // 查找命令
        CliCommand command = commandRegistry.find(cmdName);
        if (command == null) {
            terminal.writer().println("\033[31mUnknown command: /" + cmdName + "\033[0m");
            terminal.writer().println("\033[2mType /help for available commands.\033[0m");
            terminal.flush();
            return true;
        }
        
        // 构建 context
        CliCommandContext ctx = new CliCommandContext(session, terminal, reader, 
                agentRuntime, agentProps, input, cmdName, args);
        
        // MarkdownCommand 类型 → 作为 prompt 执行
        if (command instanceof MarkdownCommand mdCmd) {
            String prompt = mdCmd.getResolvedPrompt(String.join(" ", args));
            performAgentTask(session, prompt);
            return true;
        }
        
        // 其他命令直接执行
        return command.execute(ctx);
    }
}
```

### 3.8 JLine Tab 补全

```java
package org.noear.solon.codecli.command;

import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;
import java.util.List;

/**
 * 命令名 Tab 补全
 */
public class CliCommandCompleter implements Completer {
    private final CliCommandRegistry registry;
    
    public CliCommandCompleter(CliCommandRegistry registry) {
        this.registry = registry;
    }
    
    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        if (line.word().startsWith("/")) {
            String prefix = line.word().substring(1).toLowerCase();
            for (String name : registry.names()) {
                if (name.startsWith(prefix)) {
                    CliCommand cmd = registry.find(name);
                    candidates.add(new Candidate("/" + name, "/" + name, null, cmd.description(), null, null, true));
                }
            }
        }
    }
}
```

---

## 四、文件结构规划

```
src/main/java/org/noear/solon/codecli/
├── command/                          # 新增包
│   ├── CliCommand.java               # 命令接口
│   ├── CliCommandType.java           # 命令类型枚举
│   ├── CliCommandSource.java         # 命令来源枚举
│   ├── CliCommandContext.java         # 命令上下文
│   ├── CliCommandRegistry.java        # 命令注册表
│   ├── CliCommandCompleter.java       # Tab 补全
│   ├── MarkdownCommand.java           # Markdown 自定义命令
│   ├── CustomCommandLoader.java       # 文件命令加载器
│   └── builtin/                       # 内置命令
│       ├── ExitCommand.java
│       ├── ClearCommand.java
│       ├── ResumeCommand.java
│       ├── ModelCommand.java
│       └── HelpCommand.java
├── portal/
│   └── CliShell.java                  # 改造（注入 registry）
└── ...
```

---

## 五、执行路线

### Phase 1：基础框架（核心）
1. 创建 `CliCommand` 接口和相关枚举
2. 创建 `CliCommandRegistry`
3. 创建 `CliCommandContext`
4. 迁移现有 4 个命令为内置命令类
5. 改造 `CliShell.isSystemCommand()` 使用注册表
6. 新增 `/help` 命令

### Phase 2：自定义命令
7. 实现 `MarkdownCommand` 和 `CustomCommandLoader`
8. 在 `CliShell` 构造器中加载自定义命令目录
9. 支持 `$ARGUMENTS` 占位符替换

### Phase 3：Tab 补全
10. 实现 `CliCommandCompleter`
11. 替换 JLine 的 `FileNameCompleter`

### Phase 4：扩展能力
12. 通过 `HarnessExtension` 支持插件注册命令
13. 动态命令发现（文件系统 watch 或按需重载）

---

## 六、Claude Code Markdown 命令格式兼容规范

### 6.1 完整格式示例

```markdown
---
description: Create a git commit with conventional commit message
argument-hint: [message]
allowed-tools: Bash(git add:*), Bash(git status:*), Bash(git commit:*)
---

Please create a git commit with the following message format:

$ARGUMENTS

If no message is provided, analyze the staged changes and generate one.
Changed files: $1
Commit type: $2
```

### 6.2 Frontmatter 字段说明

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `description` | string | 否 | 命令描述，用于 `/help` 和 Tab 补全提示。默认 `"Custom command: <name>"` |
| `argument-hint` | string | 否 | 参数提示，用于 Tab 补全。如 `[message]`、`<file>` |
| `allowed-tools` | string | 否 | 允许的工具列表，逗号分隔。支持括号内逗号。空值表示不限制 |

### 6.3 变量占位符

| 变量 | 说明 | 示例 |
|------|------|------|
| `$ARGUMENTS` | 所有参数作为单个字符串拼接 | `/review src/App.java` → `$ARGUMENTS` = `"src/App.java"` |
| `$1`, `$2`, `$3` ... | 按位置取单个参数（1-based） | `/find bug fix` → `$1`=`"bug"`, `$2`=`"fix"` |

> **替换顺序**：先替换 `$1`, `$2`...，再替换 `$ARGUMENTS`，避免 `$1` 的内容被 `$ARGUMENTS` 误替换。

### 6.4 子目录命名空间

文件路径映射为命令名时，使用冒号 `:` 作为命名空间分隔符：

| 文件路径 | 命令名 |
|---------|--------|
| `review.md` | `/review` |
| `deploy/staging.md` | `/deploy:staging` |
| `ci/docker/build.md` | `/ci:docker:build` |

### 6.5 向后兼容

无 Frontmatter 的 `.md` 文件仍然支持：

```markdown
<!-- Review the code for bugs -->
Please review the following code for bugs and suggest fixes: $ARGUMENTS
```

- 第一行 `<!-- -->` 注释提取为 description（旧格式兼容）
- 无注释则 description 为 `"Custom command: <name>"`
- 只支持 `$ARGUMENTS`，不支持位置参数

---

## 七、设计对照表

| Claude Code 概念 | SolonCode 对应 |
|---|---|
| `src/commands.ts` 注册表 | `CliCommandRegistry` |
| PromptCommand | `MarkdownCommand`（type=AGENT） |
| LocalCommand | 内置命令类（type=CONFIG） |
| LocalJSXCommand | 暂不需要（CLI 无 React） |
| `commands/*.ts` 目录 | `command/builtin/*.java` |
| `.claude/commands/*.md` | `.soloncode/commands/*.md` |
| `~/.claude/commands/*.md` | `~/.soloncode/commands/*.md` |
| `$ARGUMENTS` 占位符 | `MarkdownCommand.getResolvedPrompt()` |
| `$1`, `$2` 位置参数 | `MarkdownCommand.getResolvedPrompt()` |
| YAML Frontmatter | `CustomCommandLoader.parseFrontmatter()` |
| `description` 元数据 | `CliCommand.description()` |
| `argument-hint` 元数据 | `CliCommand.argumentHint()` |
| `allowed-tools` 元数据 | `CliCommand.allowedTools()` |
| 子目录命名空间 `deploy:staging` | `CustomCommandLoader.buildCommandName()` |
| source: builtin/skill/plugin/project/user | `CliCommandSource` 枚举 |
| 动态注册 + 过滤 | `CliCommandRegistry.register()` |
