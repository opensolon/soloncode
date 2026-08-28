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
               │ soloncode serve 4808
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

如需完整体验，先安装并启动 CLI 后端：

```bash
# 在项目根目录
cd soloncode-cli
mvn clean package -DskipTests

# 安装到 ~/.soloncode/bin/
cd release
# Windows
powershell -File install.ps1
# Linux/macOS
bash install.sh
```

### 仅前端开发

```bash
npm run dev
```

浏览器访问 `http://localhost:5173`，非 Tauri 环境使用 Mock 数据。

## 构建发布

### 推荐：统一打包脚本

在对应操作系统上执行：

```bash
cd soloncode-desktop
npm install
npm run package -- windows  # Windows，生成 MSI
npm run package -- mac      # macOS，生成 APP 和 DMG
npm run package -- linux    # Linux，生成 DEB 和 AppImage
```

不传平台时自动选择当前系统。脚本拒绝跨系统打包，并依次完成以下工作：

1. 从当前源码构建 `soloncode-cli/target/soloncode-cli.jar`。
2. 将 JAR 复制到 Tauri 打包资源目录。
3. 构建前端、Rust 应用和当前平台安装包。

使用 `npm run package:plan -- windows` 可只查看步骤而不执行构建；将 `windows` 换为当前平台即可。

### 打包原理

```
步骤 1: 构建 CLI JAR                    步骤 2: 构建桌面安装包
────────────────────────                ─────────────────────────
cd soloncode-cli                        cd soloncode-desktop
mvn clean package -DskipTests           npm run tauri:build
        │                                       │
        ▼                                       ▼
  target/soloncode-cli.jar               pnpm build (tsc + vite → dist/)
                                               │
                                               ▼
                                         cargo build --release
                                               │
                                               ▼
                                         生成安装包:
                                          ├── *.msi / *.exe (Windows)
                                          ├── *.dmg / *.app (macOS)
                                          └── *.deb / *.AppImage (Linux)
```

#### 1. 手动构建 CLI JAR

```bash
cd soloncode-cli
mvn clean package -DskipTests
```

构建产物：`target/soloncode-cli.jar`

#### 2. 可选：单独安装 CLI

统一打包脚本会把当前 JAR 放入桌面安装包。只有需要在终端中单独使用 CLI 时，才需要安装到用户目录：

```bash
cd soloncode-cli/release
# Windows
powershell -File install.ps1
# Linux/macOS
bash install.sh
```

安装后文件位于 `~/.soloncode/bin/`。

#### 3. 构建桌面安装包

**Windows:**

```bash
cd soloncode-desktop
npm run package -- windows
```

**macOS:**

```bash
# 前置依赖
brew install node rust

# 构建当前 CLI JAR 并生成 APP/DMG
cd soloncode-desktop
npm install
npm run package -- mac
```

**macOS Universal Binary（Apple Silicon + Intel）：**

Tauri 可以把 `arm64` 与 `x86_64` 两种架构合并到同一个 `.app` 中，并继续生成对应的通用 `.dmg`。该构建必须在 macOS 上执行。

```bash
# 同时安装 Apple Silicon 与 Intel Rust 目标
rustup target add aarch64-apple-darwin x86_64-apple-darwin

cd soloncode-desktop
pnpm install
pnpm tauri build --target universal-apple-darwin
```

通用构建产物位于：

```text
src-tauri/target/universal-apple-darwin/release/bundle/macos/soloncode-desktop.app
src-tauri/target/universal-apple-darwin/release/bundle/dmg/*.dmg
```

使用 `lipo` 验证应用主程序是否同时包含两种架构：

```bash
lipo -archs \
  src-tauri/target/universal-apple-darwin/release/bundle/macos/soloncode-desktop.app/Contents/MacOS/soloncode-desktop
```

正常输出应同时包含：

```text
x86_64 arm64
```

> 发布到其他 Mac 前仍需使用 Apple Developer 证书完成代码签名与公证。若以后增加原生 sidecar 或架构相关资源，也必须同时提供 `arm64` 和 `x86_64` 版本或先合并为 Universal Binary。

**Linux:**

```bash
# 前置依赖
sudo apt install libwebkit2gtk-4.1-dev build-essential curl wget file libxdo-dev libssl-dev libayatana-appindicator3-dev librsvg2-dev

# 构建当前 CLI JAR 并生成 DEB/AppImage
cd soloncode-desktop
npm install
npm run package -- linux
```

默认架构的产物位于 `src-tauri/target/release/bundle/`；指定 `--target` 时，产物位于 `src-tauri/target/<target>/release/bundle/`：

| 平台 | 格式 | 文件 |
|------|------|------|
| Windows | MSI | `soloncode-desktop_<version>_x64_zh-CN.msi` |
| macOS | DMG | `soloncode-desktop_<version>_*.dmg` |
| macOS | App | `soloncode-desktop.app` |
| macOS Universal | DMG / App | `src-tauri/target/universal-apple-darwin/release/bundle/` |
| Linux | Deb | `soloncode-desktop_<version>_amd64.deb` |
| Linux | AppImage | `soloncode-desktop_<version>_amd64.AppImage` |

> **注意**：各操作系统的正式安装包应在对应系统上构建；macOS Universal Binary 必须在 macOS 上生成。如需自动化多平台构建，建议使用对应系统的 CI Runner。

### 后端启动流程

桌面版启动后端时，按以下优先级查找启动方式：

```
detect_launch_method()
  │
  ├── 1. 检查安装包内置的 soloncode-cli.jar
  │     → 找到：java -jar <资源目录>/soloncode-cli.jar serve <port>
  │
  └── 2. 内置 JAR 不存在时，使用 PATH 或 ~/.soloncode/bin/ 中已安装的 CLI
```

### 用户目录结构

```
~/.soloncode/
├── bin/
│   ├── soloncode-cli.jar    # CLI JAR
│   ├── soloncode.ps1        # PowerShell 启动器
│   ├── soloncode.bat        # CMD 启动器
│   └── uninstall.ps1        # 卸载脚本
├── skills/                  # 全局 Skills
├── agents/                  # 全局 Agents
├── extensions/              # 扩展
├── config.yml               # 用户配置
└── AGENTS.md                # 系统提示词
```

## 模型管理

### 数据流

```
设置面板 (ProviderModelSelect)
  → 用户填写 API 地址和密钥
  → 点击"获取模型"按钮
  → 调用后端 /desktop/chat/models/fetch
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
| `/desktop/version` | GET | 获取桌面后端版本与工作区信息 |
| `/desktop/chat/models/fetch` | GET | 从供应商 API 获取可用模型 |
| `/desktop/chat/models/add` | POST | 动态注册模型 |
| `/desktop/chat/models/select` | POST | 选择桌面端默认模型 |
| `/desktop/chat/models/remove` | POST | 移除动态模型 |
| `/desktop/ws` | WebSocket | 桌面端 WebSocket 连接 |
| `/web/chat/*` | HTTP | WebController/WebChannel 提供的共享 Web 接口 |

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
│   │   ├── backendService.ts   # 后端启动/停止/就绪检测
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
