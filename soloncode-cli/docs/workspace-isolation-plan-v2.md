# 多工作区隔离优化方案 v2（已实施）

## 背景
`web.html?workspaceId=ws-xxx` 部分面板（文件树/审查列表/设置）仍显示默认工作区内容。根因：全局 FILES 挂载泄漏、挂载别名与多工作区 ID 参数同名混淆、共享 GitService 目录切换竞态。

## 已实施内容

### 阶段一：身份误传防护
- `WorkspaceFilter`：只信任 `X-Workspace-Id` 请求头，删除 query 参数 fallback（filer/git 的 query `workspaceId`/`workspace` 现在专指挂载别名 `mount`）。
- `WorkspaceManager.getOrCreate()`：拒绝 `@` 前缀、含 `..`、非绝对路径的参数，回退默认工作区，绝不据此创建目录。
- 挂载参数更名：前端 `app-filer.js`/`app-git.js` 与后端 `WebController` filer/git 接口统一改为 `mount`。

### 阶段二：挂载泄漏修复
- `AgentSettings.loadForWorkspace()`：非默认工作区加载时，过滤全局 settings 中 scope != workspace 的 FILES 类挂载（SKILLS/AGENTS 保留），文件树不再显示默认项目目录。

### 阶段三：并发安全与标题
- `withGitWorkspace()`：使用当前工作区上下文的 GitService（每工作区独立实例）+ 服务实例锁串行化挂载目录切换。
- `web.html` 标题：`workname - <workspaceId短ID>`，不暴露绝对路径。

### 阶段四：hub.md 对齐
- 失效 ws- ID → 404 `WORKSPACE_NOT_FOUND` + redirect `/home.html`；前端 fetch/XHR 劫持层统一识别并跳转。
- 历史 ID 加载沿用原 meta（不再生成重复 ws- ID）；历史路径缺失时回退默认。
- LRU 闲置释放：每 10 分钟扫描，非默认工作区 30 分钟无访问且无 WS 连接则 dispose。

## 验证清单
1. `web.html?workspaceId=ws-xxx`：文件树根节点、审查列表、Git 状态均为目标项目。
2. 请求 `/web/chat/filer/tree?mount=@xxx` 不再创建名为 `@xxx` 的目录。
3. `?workspaceId=ws-notexist` 得 404 并跳 home。
4. 设置面板 workspace 作用域读写落 `<workspaceDir>/.soloncode/settings.json`。
