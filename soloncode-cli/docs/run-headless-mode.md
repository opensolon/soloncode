# soloncode run — 无头模式用户指南

> 面向 CI/CD 和自动化场景的非交互执行入口。

## 概述

`soloncode run` 是 soloncode-cli 的 Print/Headless 模式。它接收一个提示词（命令行参数或 stdin 管道），运行 Agent 到完成，按指定格式输出结构化结果，并以退出码标识成功或失败。整个过程不启动交互式 UI，适合在 CI 管道、定时任务、脚本编排中无人值守调用。

## 快速开始

```bash
# 基本调用 — 输出纯文本
soloncode run "总结最近一次提交的变更内容"

# JSON 输出 — 适合程序化消费
soloncode run "列出所有公开函数" --output-format json

# 流式输出 — 实时获取事件流
soloncode run "重构日志模块" --output-format stream-json --verbose

# 从 stdin 管道读取提示词
cat build-error.log | soloncode run --output-format json "分析这个错误的根本原因"
```

## 命令格式

```
soloncode run [提示词] [选项...]
```

提示词可以作为第一个位置参数传入，也可以通过 stdin 管道输入。如果两者都提供，命令行参数优先。如果都没有，进程以退出码 3 终止。

## 退出码

| 退出码 | 含义 |
|--------|------|
| 0 | 成功完成 |
| 1 | 运行出错（Agent 异常、API 错误等） |
| 2 | 超过最大轮次限制（`--max-turns`） |
| 3 | 未提供提示词 |
| 4 | 超过费用预算（`--max-budget-usd`） |

CI 管道可以根据退出码决定是否中断流水线或触发告警。

## 全部选项

### 输出控制

#### `--output-format`（默认 text）

| 值 | 说明 |
|----|------|
| `text` | 纯文本输出，直接打印最终答复 |
| `json` | 输出单个 JSON 对象，包含 result / session_id / metrics / 费用等 |
| `stream-json` | 逐行 JSONL 事件流，每个事件占一行 |

```bash
soloncode run "分析代码质量" --output-format json
soloncode run "生成测试" --output-format stream-json --verbose
```

#### `--verbose`

stream-json 模式必须配合 `--verbose` 才会输出流式事件。不带此选项时 stream-json 仅输出最终 result 事件。

```bash
soloncode run "重构代码" --output-format stream-json --verbose
```

### 模型与轮次

#### `--model`

指定使用的模型名称或别名。未指定时使用引擎默认模型。

```bash
soloncode run "生成文档" --model sonnet
soloncode run "快速摘要" --model haiku
```

#### `--max-turns`

限制 Agent 的最大推理-行动轮次。超过限制时以退出码 2 终止。CI 场景建议设置此值防止失控。

```bash
soloncode run "执行重构" --max-turns 15
```

#### `--fallback-model`

主模型不可用时自动回退到指定模型。适合夜间批处理任务中主模型限流时的容错。

```bash
soloncode run "夜间审计" --model opus --fallback-model haiku
```

### 会话管理

#### `--session-id`

为本次执行指定固定的会话 ID。

```bash
soloncode run "第一步" --session-id my-task-001
```

#### `--resume`

恢复指定的已有会话，保留之前的上下文。常用于多阶段任务。

```bash
# 第一阶段：分析
soloncode run "分析这个模块的结构和问题" --output-format json > phase1.json

# 提取 session_id
SESSION=$(jq -r '.session_id' phase1.json)

# 第二阶段：基于分析结果写测试
soloncode run "根据之前的分析为这个模块编写单元测试" --resume "$SESSION"
```

#### `--continue`

继续最近的默认会话（session ID 为 `cli`）。

```bash
soloncode run "先分析代码" 
soloncode run "然后写测试" --continue
```

### 工具控制

#### `--allowedTools`

限制 Agent 只能使用指定的工具。多个工具用逗号分隔。未指定时允许全部工具。

```bash
# 只读审查 — 只允许读取和搜索工具
soloncode run "Review this PR" --allowedTools "Read,Grep,Glob"
```

#### `--disallowedTools`

禁止 Agent 使用指定的工具。

```bash
# 允许除 Bash 外的所有工具
soloncode run "分析并修复" --disallowedTools "Bash"
```

#### 工具规则语法 `ToolName(pattern)`

支持细粒度的工具命令控制，用 glob 模式限定工具的调用范围。

```bash
# 只允许特定的 Bash 命令 + Read 工具
soloncode run "总结变更" \
  --allowedTools "Bash(git log *),Bash(git diff *),Read" \
  --disallowedTools "Bash(rm *)"

# 禁止危险的删除命令
soloncode run "清理代码" --disallowedTools "Bash(rm -rf *)"
```

规则解析说明：
- `Read` — 纯工具名，匹配该工具的所有调用
- `Bash(git log *)` — 工具名 + glob 模式，仅匹配符合模式的调用
- `Bash(rm *)` — 禁止所有 rm 开头的 Bash 命令

### 权限模式

#### `--permission-mode`（默认 default）

| 模式 | 行为 | 适用场景 |
|------|------|---------|
| `default` | 禁用人工审批（HITL），未授权操作自动拒绝 | 通用无人值守 |
| `dontAsk` | 同 default，明确语义化命名 | CI 推荐 |
| `plan` | 仅分析和提议，禁止所有写入类工具（Write/Edit/Bash） | 方案评审 |
| `acceptEdits` | 自动批准文件编辑（Write/Edit/Read/Glob/Grep），其它操作自动拒绝 | 安全的自动修复 |
| `bypassPermissions` | 跳过所有权限检查和沙箱限制 | 沙箱环境内的全权操作 |

```bash
# CI 安全模式 — 只读审查
soloncode run "Review this PR" --output-format json \
  --allowedTools "Read,Grep,Glob" --permission-mode dontAsk

# 方案规划 — 不允许任何修改
soloncode run "制定重构方案" --permission-mode plan

# 自动修复 — 允许文件编辑但不允许执行命令
soloncode run "修复所有 lint 错误" --permission-mode acceptEdits
```

### 环境与发现

#### `--bare`

跳过 skills、agents 挂载、MCP 服务和 memory 的自动发现。适合需要最小化启动开销或确保隔离环境的场景。

```bash
soloncode run "运行测试" --bare
```

效果：
- 移除所有 SKILLS 和 AGENTS 类型挂载
- 移除所有已注册的 MCP 服务
- 禁用 memory（长期记忆）

#### `--add-dir`

注册额外的工作目录，授予 Agent 读写权限。可多次指定以添加多个目录。

```bash
soloncode run "跨仓库分析" \
  --add-dir /path/to/repo-a \
  --add-dir /path/to/repo-b
```

每个目录会以 `@add-dir-0`、`@add-dir-1` 的别名注册到引擎，Agent 可以像访问内置挂载点一样读写这些目录。

### 结构化输出

#### `--json-schema`

约束 Agent 的输出格式为指定的 JSON Schema。引擎会在提示词末尾追加结构化输出指令，并在 JSON 输出中提取 `structured_output` 字段。

```bash
soloncode run "提取所有公开函数及其签名" \
  --output-format json \
  --json-schema '{"type":"object","properties":{"functions":{"type":"array","items":{"type":"object","properties":{"name":{"type":"string"},"signature":{"type":"string"},"file":{"type":"string"}},"required":["name","signature"]}}}}'
```

输出中的 `structured_output` 字段包含符合 schema 的解析结果：

```json
{
  "result": "```json\n{\"functions\":[...]}\n```",
  "structured_output": {
    "functions": [
      {"name": "foo", "signature": "public void foo()", "file": "App.java"}
    ]
  },
  "session_id": "print-a1b2c3d4",
  "is_error": false
}
```

提取逻辑会依次尝试：
1. 直接解析为 JSON
2. 从 ` ```json ... ``` ` 代码块中提取
3. 从裸 `{ ... }` 或 `[ ... ]` 块中提取

### 费用控制

#### `--max-budget-usd`

设置本次执行的费用硬上限（美元）。执行结束后估算总费用，如果超过预算则以退出码 4 终止。

费用基于 token 用量估算（输入 $0.003/1K tokens，输出 $0.015/1K tokens，为近似值）。

```bash
soloncode run "大规模代码迁移" --max-budget-usd 5.0 --max-turns 30
```

JSON 输出中包含费用信息：

```json
{
  "result": "...",
  "total_cost_usd": 0.0312,
  "budget_limit_usd": 5.0,
  "budget_exceeded": false
}
```

> **注意**：当前版本在执行完成后检测预算（事后报警），而非执行中途停止。`--max-turns` 提供运行时的轮次兜底。两者配合使用效果最佳。

## 输出格式详解

### text 模式

直接输出最终答复文本到 stdout，错误信息输出到 stderr。

```bash
$ soloncode run "什么是依赖注入"
依赖注入是一种设计模式...
```

### json 模式

输出单个 JSON 对象到 stdout：

```json
{
  "result": "依赖注入是一种设计模式...",
  "is_error": false,
  "session_id": "print-a1b2c3d4",
  "metrics": {
    "total_tokens": 1250,
    "prompt_tokens": 980,
    "completion_tokens": 270,
    "duration_ms": 3200
  },
  "total_cost_usd": 0.0072
}
```

错误时：

```json
{
  "result": "",
  "is_error": true,
  "error": "API rate limit exceeded",
  "session_id": "print-a1b2c3d4",
  "total_cost_usd": 0.001
}
```

### stream-json 模式

逐行输出 JSONL 事件流，每个事件占一行。事件类型对齐 Claude Code 的 stream-json 格式。

**system/init 事件**（执行开始时）：
```json
{"type":"system","subtype":"init","session_id":"print-a1b2c3d4","model":"sonnet","tools":["Read","Grep","Glob","Write","Edit","Bash"],"mcp_servers":[],"version":"2026.xxx"}
```

**assistant 事件**（Agent 输出文本）：
```json
{"type":"assistant","message":{"content":[{"type":"text","text":"正在分析代码..."}]}}
```

**assistant 事件**（Agent 调用工具）：
```json
{"type":"assistant","message":{"content":[{"type":"tool_use","id":"call_001","name":"Read","input":{"file_path":"src/App.java"}}]}}
```

**user 事件**（工具执行结果）：
```json
{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"call_001","content":"file content here","is_error":false}]}}
```

**result 事件**（执行结束）：
```json
{"type":"result","result":"分析完成。","session_id":"print-a1b2c3d4","is_error":false,"metrics":{"total_tokens":1250,"prompt_tokens":980,"completion_tokens":270,"duration_ms":3200},"total_cost_usd":0.0072}
```

**error 事件**（执行出错）：
```json
{"type":"error","error":"API rate limit exceeded"}
```

stream-json 事件格式与 Claude Code 兼容，可以直接使用 jq 过滤消费：

```bash
# 只提取最终结果文本
soloncode run "分析代码" --output-format stream-json --verbose | jq -r 'select(.type=="result") | .result'

# 审计日志 — 保存完整事件流同时提取结果
soloncode run "重构" --output-format stream-json --verbose \
  | tee logs/run-$(date +%s).jsonl \
  | jq -r 'select(.type=="result") | .result'

# 监控工具调用
soloncode run "修复bug" --output-format stream-json --verbose \
  | jq 'select(.type=="assistant") | .message.content[] | select(.type=="tool_use") | {name: .name, input: .input}'
```

## CI/CD 实践示例

### GitHub Actions — 自动代码审查

```yaml
- name: Code Review
  run: |
    soloncode run "Review the changes in this PR. Focus on security, performance, and correctness." \
      --output-format json \
      --allowedTools "Read,Grep,Glob,Bash(git log *),Bash(git diff *)" \
      --disallowedTools "Bash(rm *)" \
      --permission-mode dontAsk \
      --max-turns 15 \
      --max-budget-usd 2.0 \
      --model sonnet > review.json

    # 检查退出码
    if [ $? -ne 0 ]; then
      echo "Review failed with exit code $?"
      cat review.json | jq '.error // .result'
      exit 1
    fi

    # 提取审查结果
    cat review.json | jq -r '.result' > review-comment.md
```

### 两阶段任务 — 分析后生成测试

```bash
#!/bin/bash
set -euo pipefail

# 阶段 1：分析模块
soloncode run "分析 src/auth 模块的结构和潜在问题" \
  --output-format json --max-turns 10 > phase1.json

SESSION=$(jq -r '.session_id' phase1.json)

# 阶段 2：基于分析结果编写测试
soloncode run "根据之前的分析，为 src/auth 模块编写单元测试" \
  --resume "$SESSION" \
  --output-format json \
  --permission-mode acceptEdits \
  --max-turns 20 > phase2.json

# 检查是否成功
if [ "$(jq -r '.is_error' phase2.json)" = "false" ]; then
  echo "Tests generated successfully"
  jq -r '.result' phase2.json
else
  echo "Failed: $(jq -r '.error' phase2.json)"
  exit 1
fi
```

### 结构化输出 — 提取 API 端点清单

```bash
soloncode run "扫描 src 目录下所有 Controller 类，提取 API 端点信息" \
  --output-format json \
  --allowedTools "Read,Grep,Glob" \
  --permission-mode dontAsk \
  --json-schema '{
    "type": "object",
    "properties": {
      "endpoints": {
        "type": "array",
        "items": {
          "type": "object",
          "properties": {
            "method": {"type": "string"},
            "path": {"type": "string"},
            "controller": {"type": "string"},
            "description": {"type": "string"}
          },
          "required": ["method", "path", "controller"]
        }
      }
    }
  }' | jq '.structured_output.endpoints'
```

### 定时巡检 — 带费用控制

```bash
#!/bin/bash
# 每日代码健康检查
soloncode run "检查项目的代码质量：未使用的导入、潜在 NPE、重复代码" \
  --output-format json \
  --allowedTools "Read,Grep,Glob,Bash(git log *)" \
  --permission-mode dontAsk \
  --max-turns 25 \
  --max-budget-usd 3.0 \
  --fallback-model haiku \
  | jq '.' > reports/health-$(date +%Y%m%d).json

# 退出码 4 表示超预算 — 发送告警但不中断
EXIT_CODE=$?
if [ $EXIT_CODE -eq 4 ]; then
  echo "WARNING: Budget exceeded for daily health check"
  # 发送告警...
elif [ $EXIT_CODE -ne 0 ]; then
  echo "ERROR: Health check failed with exit code $EXIT_CODE"
  exit 1
fi
```

## 与 Claude Code 兼容性

`soloncode run` 的设计对齐 Claude Code `claude -p` 无头模式。以下场景可以直接替换使用：

| 场景 | Claude Code | soloncode run |
|------|-------------|---------------|
| 基本调用 | `claude -p "prompt"` | `soloncode run "prompt"` |
| JSON 输出 | `claude -p "prompt" --output-format json` | `soloncode run "prompt" --output-format json` |
| 流式输出 | `claude -p "prompt" --output-format stream-json --verbose` | `soloncode run "prompt" --output-format stream-json --verbose` |
| 轮次限制 | `claude -p "prompt" --max-turns 10` | `soloncode run "prompt" --max-turns 10` |
| 模型选择 | `claude -p "prompt" --model sonnet` | `soloncode run "prompt" --model sonnet` |
| 会话续接 | `claude -p "prompt" --resume SESSION` | `soloncode run "prompt" --resume SESSION` |
| 工具限制 | `claude -p "prompt" --allowedTools "Read,Grep"` | `soloncode run "prompt" --allowedTools "Read,Grep"` |
| 细粒度控制 | `claude -p "prompt" --allowedTools "Bash(git log *)"` | `soloncode run "prompt" --allowedTools "Bash(git log *)"` |
| 权限模式 | `claude -p "prompt" --permission-mode dontAsk` | `soloncode run "prompt" --permission-mode dontAsk` |
| 结构化输出 | `claude -p "prompt" --json-schema '{...}'` | `soloncode run "prompt" --json-schema '{...}'` |
| 费用控制 | `claude -p "prompt" --max-budget-usd 5.0` | `soloncode run "prompt" --max-budget-usd 5.0` |
| 裸模式 | `claude -p "prompt" --bare` | `soloncode run "prompt" --bare` |
| 多目录 | `claude -p "prompt" --add-dir /repo/a` | `soloncode run "prompt" --add-dir=/repo/a` |
| 回退模型 | `claude -p "prompt" --fallback-model haiku` | `soloncode run "prompt" --fallback-model haiku` |

stream-json 事件格式兼容 Claude Code，jq 过滤器可以直接复用。

## 选项速查表

| 选项 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `--output-format` | text/json/stream-json | text | 输出格式 |
| `--model` | string | 引擎默认 | 模型名称或别名 |
| `--max-turns` | int | 无限制 | 最大推理-行动轮次 |
| `--session-id` | string | 自动生成 | 指定会话 ID |
| `--resume` | string | — | 恢复指定会话 |
| `--continue` | flag | false | 继续最近默认会话 |
| `--allowedTools` | CSV | 全部允许 | 允许的工具列表 |
| `--disallowedTools` | CSV | 空 | 禁止的工具列表 |
| `--permission-mode` | enum | default | 权限模式 |
| `--verbose` | flag | false | 启用流式事件输出 |
| `--bare` | flag | false | 跳过自动发现 |
| `--add-dir` | string (可重复) | — | 额外工作目录 |
| `--fallback-model` | string | — | 回退模型 |
| `--json-schema` | string | — | 结构化输出约束 |
| `--max-budget-usd` | double | 无限制 | 费用硬上限（美元） |

## 相关文件

| 文件 | 说明 |
|------|------|
| `portal/printmode/PrintMode.java` | 核心执行器：提示词解析、Agent 运行、输出格式化、退出码管理 |
| `portal/printmode/PrintModeOptions.java` | 选项解析器：命令行参数解析、工具规则语法、权限模式映射 |
| `Configurator.java` | 入口集成：`run` flag 分发到 PrintMode.execute() |
| `test/.../PrintModeOptionsTest.java` | 选项解析单元测试（59 个） |
| `test/.../PrintModeTest.java` | 执行器逻辑单元测试（26 个） |
