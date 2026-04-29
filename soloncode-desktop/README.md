# SolonCode Desktop

AI 驱动的桌面编程助手，基于 Tauri 2.0 + React 18 构建。前端通过 HTTP/WebSocket 连接后端 CLI 服务，实现 AI 对话、代码编辑、Git 管理等功能。

## 架构

```
┌─────────────────────────────────┐
│  SolonCode Desktop (Tauri)      │
│  ┌───────────────────────────┐  │
│  │  React 前端               │  │
│  │  HTTP REST + WebSocket    │──┼── localhost:4808
│  └───────────────────────────┘  │
│  ┌───────────────────────────┐  │
│  │  Rust 层                  │  │
│  │  文件系统 / 终端 / 进程管理│  │
│  └───────────────────────────┘  │
└─────────────────────────────────┘
               │
               │ java -jar soloncode-cli.jar
               ▼
┌─────────────────────────────────┐
│  soloncode-cli (Java/Solon)     │
│  AI Agent + ChatModel + Harness │
└─────────────────────────────────┘
```

## 技术栈

| 层 | 技术 |
|---|---|
| 桌面框架 | Tauri 2.0 (Rust) |
| 前端 | React 18 + TypeScript + Vite 6 |
| 代码编辑器 | Monaco Editor |
| 后端通信 | HTTP REST + WebSocket |
| 本地存储 | IndexedDB (Dexie) |
| 消息渲染 | markdown-it + react-syntax-highlighter |
| CLI 后端 | Java 8+ / Solon 3.x |
| AI 集成 | Solon AI (ChatModel / HarnessEngine) |

## 功能

- **AI 对话** — 多供应商模型管理，实时流式对话，Markdown 渲染 + 代码高亮
- **模型管理** — 支持智谱/OpenAI/DeepSeek/Claude 等多供应商，模型列表持久化
- **代码编辑器** — Monaco Editor，多文件 Tab、语法高亮、自动补全
- **资源管理器** — 文件树浏览、新建/重命名/删除/复制/剪切/粘贴
- **Git 集成** — 分支切换、暂存/提交/推送/拉取、提交历史
- **工作区管理** — 打开文件夹、配置持久化
- **终端** — 内置终端面板
- **主题** — 深色/浅色主题

## 环境要求

- Node.js >= 18
- pnpm (或 npm)
- Rust + Cargo (通过 rustup 安装)
- Tauri CLI 2.0 (`npm install @tauri-apps/cli`)
- Java >= 8 (运行 CLI 后端)
- Maven (构建 CLI JAR)

## 开发

### 安装前端依赖

```bash
cd soloncode-desktop
npm install
```

### 开发模式

```bash
# 启动前端 + Tauri 窗口（热更新）
npm run tauri:dev
```

前端热更新端口 `5173`，后端默认连接 `ws://localhost:4808`。

如需完整体验，先启动 CLI 后端：

```bash
# 在项目根目录
cd soloncode-cli
mvn clean package -DskipTests
java -jar target/soloncode-cli.jar serve 4808
```

### 仅前端开发

```bash
npm run dev
```

浏览器访问 `http://localhost:5173`，非 Tauri 环境使用 Mock 数据。

## 构建发布

### 完整构建流程

```
步骤 1: 构建 CLI JAR                    步骤 2: 构建桌面安装包
────────────────────────                ─────────────────────────
cd soloncode-cli                        cd soloncode-desktop
mvn clean package -DskipTests           npm run tauri:build
        │                                       │
        ▼                                       ▼
  target/soloncode-cli.jar               beforeBuildCommand:
                                          check-jar.cjs (检查JAR + 复制到src-tauri/)
                                          pnpm build (tsc + vite → dist/)
                                                 │
                                                 ▼
                                          cargo build --release
                                                 │
                                                 ▼
                                          收集 resources:
                                           ├── release/**/*
                                           ├── target/soloncode-cli.jar
                                           └── build/install-cli.*
                                                 │
                                                 ▼
                                          生成安装包:
                                           ├── *.msi / *.exe (Windows)
                                           ├── *.dmg / *.app (macOS)
                                           └── *.deb / *.AppImage (Linux)
```

#### 1. 构建 CLI JAR

```bash
cd soloncode-cli
mvn clean package -DskipTests
```

构建产物：`target/soloncode-cli.jar`

#### 2. 构建桌面安装包

**Windows:**

```bash
cd soloncode-desktop
npm run tauri:build
```

**macOS:**

```bash
# 前置依赖
brew install node rust
npm install -g pnpm

# 构建 CLI JAR
cd soloncode-cli
mvn clean package -DskipTests

# 构建桌面安装包
cd ../soloncode-desktop
pnpm install
pnpm tauri build
```

**Linux:**

```bash
# 前置依赖
sudo apt install libwebkit2gtk-4.1-dev build-essential curl wget file libxdo-dev libssl-dev libayatana-appindicator3-dev librsvg2-dev

# 构建 CLI JAR
cd soloncode-cli
mvn clean package -DskipTests

# 构建桌面安装包
cd ../soloncode-desktop
pnpm install
pnpm tauri build
```

产物位于 `src-tauri/target/release/bundle/`：

| 平台 | 格式 | 文件 |
|------|------|------|
| Windows | MSI | `soloncode-desktop_0.1.0_x64_en-US.msi` |
| Windows | NSIS | `soloncode-desktop_0.1.0_x64-setup.exe` |
| macOS | DMG | `soloncode-desktop_0.1.0_x64.dmg` |
| macOS | App | `soloncode-desktop.app` |
| Linux | Deb | `soloncode-desktop_0.1.0_amd64.deb` |
| Linux | AppImage | `soloncode-desktop_0.1.0_amd64.AppImage` |

> **注意**：Tauri 不支持交叉编译，需要在对应平台上构建。如需自动化多平台构建，建议使用 GitHub Actions。

### 打包机制

`check-jar.cjs` 在构建前将 CLI 产物复制到 `src-tauri/` 下，Tauri 通过 `resources` 配置打包：

// tauri.conf.json
```json
{
  "bundle": {
    "beforeBuildCommand": "node check-jar.cjs && pnpm build",
    "resources": [
      "release/**/*",
      "target/soloncode-cli.jar",
      "build/install-cli.*"
    ]
  }
}
```

**check-jar.cjs 做了什么**：
1. 检查 `../soloncode-cli/target/soloncode-cli.jar` 是否存在（不存在则报错退出）
2. 复制 JAR → `src-tauri/target/soloncode-cli.jar`
3. 复制 release/ → `src-tauri/release/`
4. 复制 build/install-cli.* → `src-tauri/build/`

**resources 说明**：

| 路径 | 内容 |
|------|------|
| `release/**/*` | config.yml、AGENTS.md、skills/、bin/（卸载脚本）、install.ps1/sh |
| `target/soloncode-cli.jar` | CLI JAR（Maven 构建产物） |
| `build/install-cli.*` | 自动安装脚本（.bat / .sh） |

**安装后应用资源结构**：

```
{app_install_dir}/
├── soloncode-desktop.exe
├── target/
│   └── soloncode-cli.jar
├── release/
│   ├── config.yml
│   ├── AGENTS.md
│   ├── install.ps1
│   ├── install.sh
│   ├── bin/
│   └── skills/
└── build/
    ├── install-cli.bat
    └── install-cli.sh
```

### 首次启动流程

```
用户启动桌面端
  │
  ▼
start_backend(workspace, port)
  │
  ▼
find_cli_jar()
  → 查找 ~/.soloncode/bin/soloncode-cli.jar
  │
  ├── 已安装 → java -jar serve <port>
  │
  └── 未安装 → auto_install_cli()
        │
        ├─ 从打包资源复制 JAR → ~/.soloncode/bin/
        ├─ 运行 install-cli 脚本（复制 release/、创建启动器、注册 PATH）
        │
        ▼
      java -jar ~/.soloncode/bin/soloncode-cli.jar serve <port>
        │
        ▼
      前端 HTTP/WS 连接 localhost:<port>
```

### 用户目录结构

```
~/.soloncode/
├── bin/
│   ├── soloncode-cli.jar    # CLI JAR（每次桌面版更新时覆盖）
│   ├── soloncode.ps1        # PowerShell 启动器
│   ├── soloncode.bat        # CMD 启动器
│   └── uninstall.ps1        # 卸载脚本
├── skills/                  # 全局 Skills
├── extensions/              # 扩展
├── config.yml               # 用户配置（首次从 release 复制，后续保留）
└── AGENTS.md                # 系统提示词
```

## 模型管理

### 数据流

```
设置面板 (ProviderModelSelect)
  → 用户填写 API 地址和密钥
  → 点击"获取模型"按钮
  → 调用后端 /chat/models/fetch
  → 模型列表持久化到 IndexedDB (provider.availableModels)

聊天输入 (ChatInput)
  → 从 providers[].availableModels 展开所有模型
  → 用户选择模型 → 通过 REST 注册到后端
  → 发送消息时携带模型名
```

### 支持的供应商

| 供应商 | 类型标识 | API 地址 |
|--------|---------|---------|
| 智谱 AI | `zhipu` | `https://open.bigmodel.cn/api/paas/v4/chat/completions` |
| OpenAI | `openai` | `https://api.openai.com/v1/chat/completions` |
| DeepSeek | `deepseek` | `https://api.deepseek.com/v1/chat/completions` |
| Claude | `claude` | `https://api.anthropic.com/v1/messages` |
| 自定义 | `custom` | 用户自定义 |

### 后端 API

| 端点 | 方法 | 说明 |
|------|------|------|
| `/chat/models` | GET | 获取已注册模型列表 |
| `/chat/models/fetch` | GET | 从供应商 API 获取可用模型 |
| `/chat/models/add` | POST | 动态注册模型 |
| `/chat/models/select` | POST | 选择当前使用的模型 |
| `/chat/input` | POST/Multipart | 发送对话消息（SSE 流式响应） |
| `/chat/sessions` | GET | 获取会话列表 |
| `/ws` | WebSocket | WebSocket 连接 |

## 项目结构

```
soloncode-desktop/
├── src/
│   ├── App.tsx                 # 主应用入口
│   ├── components/
│   │   ├── ChatView.tsx        # AI 对话面板
│   │   ├── ChatInput.tsx       # 消息输入 + 模型选择器
│   │   ├── ChatMessages.tsx    # 消息渲染
│   │   ├── common/             # Icon、ContextMenu、ConfirmDialog
│   │   ├── editor/             # Monaco 编辑器
│   │   ├── layout/             # TitleBar、ActivityBar、StatusBar
│   │   └── sidebar/            # Explorer、Search、Git、Settings、Sessions
│   ├── services/
│   │   ├── fileService.ts      # Tauri 文件操作封装
│   │   ├── gitService.ts       # Git 操作
│   │   ├── settingsService.ts  # 设置 + 模型列表持久化
│   │   └── chatService.ts      # WebSocket 通信
│   ├── hooks/                  # 自定义 Hooks
│   ├── db.ts                   # IndexedDB (Dexie) 表定义
│   └── types.ts                # 类型定义
├── src-tauri/
│   ├── src/lib.rs              # Rust 后端（文件系统、终端、CLI 进程管理）
│   ├── Cargo.toml
│   └── tauri.conf.json         # Tauri 配置
├── build/
│   ├── install-cli.bat         # Windows CLI 自动安装脚本
│   ├── install-cli.sh          # Unix CLI 自动安装脚本
│   ├── start.bat               # 开发模式启动脚本
│   └── config.yml              # 默认配置
└── package.json
```

## 环境变量

`.env.development` 配置后端连接：

```env
VITE_WS_HOST=localhost:4808
VITE_WS_PROTOCOL=ws
```


## Agent 注入流程

Agent 与 Skill 采用完全相同的扫描注入模式。

### 目录约定
- **全局 Agents**: `~/.soloncode/agents/` — 每个 agent 是一个子目录，必须包含 `AGENT.md`
- **工作区 Agents**: `{workspacePath}/.soloncode/agents/` — 同结构

### AGENT.md 格式
```yaml
---
name: my-agent
description: Agent 描述
---

Agent 的具体配置内容（Markdown 正文）
```

### 扫描时机
1. **打开工作区时**（App.tsx `openFolderByPath`）— 调用 `settingsService.scanAgentsDir(workspacePath)` 扫描工作区 `.soloncode/agents/`，去重后合并到 `settings.agents`
2. **恢复上次工作区时**（App.tsx `loadLastFolder`）— 同上逻辑
3. **AgentsPanel 挂载时** — 调用 Tauri 命令 `list_agents` 扫描全局 `~/.soloncode/agents/`，覆盖到 `settings.agents`
4. **手动刷新** — 点击 AgentsPanel 的刷新按钮，重新执行 `list_agents`

### 数据流
```
Tauri 后端 (list_agents)
  → 读取 ~/.soloncode/agents/ 子目录
  → 解析每个子目录的 AGENT.md frontmatter (name, description)
  → 检查 .disabled 标记文件判断 enabled
  → 返回 AgentInfo[]

前端 settingsService.scanAgentsDir(workspacePath)
  → 读取 {workspacePath}/.soloncode/agents/ 子目录
  → 检查每个子目录是否含 AGENT.md
  → 返回 AgentConfig[] (source: 'discovered')
```

### 持久化
- 前端: IndexedDB `agents` 表（v5 migration），字段: id, name, description, path, enabled, source, sortOrder
- 后端: 文件系统 `.disabled` 标记文件控制启用/禁用

### Tauri 命令
| 命令 | 参数 | 说明 |
|------|------|------|
| `list_agents` | 无 | 扫描 `~/.soloncode/agents/` 返回 AgentInfo[] |
| `toggle_agent` | agentPath: string, enabled: bool | 创建/删除 `.disabled` 标记文件 |

### 选择逻辑
- 用户在 AgentsPanel 点击 agent name → `App.tsx` 保存 `activeAgent` 状态
- 当前仅前端选择，尚未传递给后端

### 配置文件
工作区 `.soloncode/config.yml` 中 `agent.maxSteps` 字段通过 `settingsService.loadConfigFile()` 加载，通过文件监听自动热更新。
