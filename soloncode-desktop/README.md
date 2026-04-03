# SolonCode Desktop

AI 驱动的桌面编程助手，基于 Tauri 2.0 + React 18 构建。

## 技术栈

| 层 | 技术 |
|---|---|
| 桌面框架 | Tauri 2.0 (Rust) |
| 前端 | React 18 + TypeScript + Vite 6 |
| 代码编辑器 | Monaco Editor |
| 后端通信 | WebSocket (连接 soloncode-cli) |
| 本地存储 | IndexedDB (Dexie) + localStorage |
| 消息渲染 | markdown-it + react-syntax-highlighter |

## 功能

- **AI 对话** — 通过 WebSocket 与后端 AI 服务实时交互，支持 Markdown 渲染和代码高亮
- **代码编辑器** — Monaco Editor 集成，支持多文件 Tab、语法高亮、自动补全、Ctrl+S 保存
- **资源管理器** — 文件树浏览、新建文件/文件夹、右键菜单（重命名、删除、复制、剪切、粘贴）
- **Git 集成** — 分支切换、暂存/取消暂存、提交、推送/拉取、提交历史查看、丢弃更改
- **工作区管理** — 打开文件夹、自动创建 `.soloncode/settings.json` 配置
- **主题切换** — 深色/浅色主题，编辑器和 UI 同步切换
- **会话管理** — 多会话切换，消息持久化到 IndexedDB

## 开发

### 环境要求

- Node.js >= 18
- Rust (通过 rustup 安装)
- Tauri CLI 2.0

### 安装依赖

```bash
cd soloncode-desktop
npm install
```

### 开发模式

```bash
npm run tauri:dev
```

前端热更新端口 `5173`，WebSocket 后端默认连接 `ws://localhost:8080`。

### 构建发布

```bash
npm run tauri:build
```

### 仅前端开发（浏览器模式）

```bash
npm run dev
```

非 Tauri 环境下会自动使用 Mock 数据，无需后端即可预览 UI。

## 环境变量

`.env.development` 配置后端连接：

```env
VITE_WS_HOST=localhost:8080
VITE_WS_PROTOCOL=ws
```

## 项目结构

```
soloncode-desktop/
├── src/                        # React 前端
│   ├── App.tsx                 # 主应用入口
│   ├── components/
│   │   ├── ChatView.tsx        # AI 对话面板
│   │   ├── ChatInput.tsx       # 消息输入框
│   │   ├── ChatMessages.tsx    # 消息列表渲染
│   │   ├── common/             # 通用组件（Icon、ContextMenu、ConfirmDialog）
│   │   ├── editor/             # Monaco 编辑器面板
│   │   ├── layout/             # 布局（ActivityBar、TitleBar、SidePanel、StatusBar）
│   │   └── sidebar/            # 侧边栏（Explorer、Search、Git、Extensions、Sessions、Settings）
│   ├── services/
│   │   ├── fileService.ts      # 文件操作（Tauri invoke 封装）
│   │   ├── gitService.ts       # Git 操作
│   │   ├── settingsService.ts  # 设置持久化
│   │   └── chatService.ts      # WebSocket 通信
│   ├── hooks/                  # 自定义 Hooks
│   └── types.ts                # 类型定义
├── src-tauri/
│   ├── src/lib.rs              # Rust 后端（文件系统操作、Git 命令）
│   └── Cargo.toml
└── package.json
```

## 后端

桌面端通过 WebSocket 连接 [soloncode-cli](../soloncode-cli)（Java/Solon 框架）。后端负责 AI 模型调用、Agent 执行等核心逻辑。

## License

Private
