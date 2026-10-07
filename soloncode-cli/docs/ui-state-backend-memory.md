# 界面状态「后端记忆」改造（Gitee #IKJOCR）

> 一句话：把「刷新后要恢复的界面状态」的真相源从浏览器 localStorage 搬到服务端
> （`~/.soloncode/ui-state.json`），因为 localStorage 按 origin 隔离，而 Web 支持随机端口启动。

## 1. 问题

现象：Web 重启后**不会自动加载之前的对话**（会话列表里有，但打开的是空白新会话），
主题、侧栏宽度、右栏折叠态、活动标签页、界面语言等也一起回到默认。

根因：`localStorage` / `sessionStorage` 的作用域是 **origin = scheme + host + port**。
SolonCode Web 支持随机端口（`--server.port 0` 或端口被占用时自动换端口），
端口一变 origin 就变，浏览器会给出一个**空白的 localStorage**；
`soloncode-active-session*`（上次活动会话指针）读不到 → 启动后无处可恢复 → 落到新建会话。

固定端口只是碰巧躲过了这件事（端口不变 → origin 不变 → 状态还在），所以问题看起来
「有的人有、有的人没有」。会话数据本身一直在磁盘上（`~/.soloncode/workspaces/<id>/sessions/`），
丢的只是**浏览器侧的界面状态**。

## 2. 方案取舍

| 状态 | 原存储 | 现真相源 | 说明 |
| --- | --- | --- | --- |
| 上次活动会话 `soloncode-active-session[-<ws>]` | localStorage | **服务端 ui-state.json** | 本次 issue 的直接原因 |
| 主题 `chat-theme` | localStorage | **服务端 ui-state.json** | 无其它服务端真相源 |
| 侧栏 `sidebar-collapsed/width` | localStorage | **服务端 ui-state.json** | 同上 |
| 右栏 `files-collapsed/width` | localStorage | **服务端 ui-state.json** | 同上 |
| 工作区活动标签 `workspace-active-tab` | localStorage | **服务端 ui-state.json** | 同上 |
| 语言 `sc-locale` | localStorage | **服务端 ui-state.json** | 同上（登录页未认证时降级为系统语言） |
| 配置备份勾选 `soloncode.backup.checkedKeys` | localStorage | **服务端 ui-state.json** | 同上 |
| 字体 `chat-font-*`、皮肤 `chat-skin` | localStorage | settings.json（**已有**） | 启动后本来就用服务端值校准，localStorage 只是首屏缓存，不重复入库 |
| 更新横幅「本次会话已关闭」 | sessionStorage | sessionStorage（**有意保留**） | 语义就是「本次会话」，不该跨启动继承 |

前端读取仍然**同步**：localStorage 保留为「同源首屏镜像」，避免主题/布局等异步回来才应用时的可见闪烁；
真相源是服务端，镜像丢了只是首帧回默认值，随后被水合结果纠正。

## 3. 服务端

- `api/web/service/UiStateStore.java`
  - 落盘 `<userHome>/<harnessHome>/ui-state.json`（即 `~/.soloncode/ui-state.json`）。
    只认用户级目录一处（`getUserHome()` + `getHarnessHome()`），与启动目录、监听端口无关。
  - 格式：`{ "version": 1, "scopes": { "<scope>": { "<key>": "<value>" } } }`，值统一字符串。
  - **作用域**：启用「对话隔离」时按登录用户分片，否则共享默认作用域 `_` ——
    与会话按 `ownerUserId` 过滤的口径一致，多用户不会互相改写对方的界面状态。
  - 校验：键名白名单（字母数字 `. _ @ : -`，≤128）、单值 ≤2048、单作用域 ≤100 键、≤50 作用域；
    越界项丢弃而不是报错（best-effort）。
  - 写入：同目录临时文件 + 原子移动；每次读都从磁盘现读（读-改-写），多进程/多实例不互相覆盖。
  - 损坏文件：只告警并降级为空表（不阻断请求），下次写入即自愈。
- `api/web/controller/UiStateController.java`
  - `GET  /web/ui/state` → 当前作用域全量键值（前端启动水合）。
  - `POST /web/ui/state/save` → 增量合并（`{"key":"value"}`；值为 `null` 表示删除）。
  - 在 `Configurator#addWebBean` 注册，和 `FilerWebController` 同一批。
  - 认证：走既有 `UserAuthFilter`。未认证（登录页）时前端静默降级，不阻断页面。

## 4. 前端

- 新增 `static/js/ui-state.js`（门面，三个入口页面在 `i18n.js` 之前加载）：
  - `get/set/remove`：同步（镜像即 localStorage），写入合并后 300ms 批量提交；
    同一时刻只允许一个在途请求，避免同键写入乱序；
  - `ready(cb)`：水合完成后回调；**已水合则立即执行**，规避「响应早于监听器注册」的竞态
    （这也是页面里不再用 `document.addEventListener('uistate:ready')` 的原因）；
  - 水合时以服务端值为准，并把「本地镜像有、服务端没有」的受管键补推一次（老用户升级不丢设置）；
  - `pagehide`/`beforeunload` 用 `sendBeacon` 兜底提交；写失败回填待写，不无限重试。
- 调用点：`app-history.js`（活动会话 + `uistate:ready` 后再恢复一次）、`app-ui.js`（主题、侧栏）、
  `app-filer.js`（右栏布局）、`app-git.js`（活动标签）、`i18n.js`（语言，水合后校准）、
  `studio.js`、`app-settings-profile.js`；`web.html` / `admin.html` / `login-page.html` 引入门面脚本。

## 5. 验证

- `UiStateWebContractTest`（5 例）：三页加载顺序、受管键已彻底离开 localStorage、
  字体/皮肤仍走 settings.json、门面覆盖竞态与卸载兜底。
- `UiStateStoreTest`（10 例）：路径契约、跨实例落盘、null 删除、非法键/超长值、
  作用域隔离、空作用域清理、损坏文件自愈。

## 6. 已知边界

- 登录页（未认证）拿不到 `/web/ui/state`，语言退回系统语言；登录后即恢复（安全边界内的取舍）。
- 首帧仍是「镜像 → 水合校准」两步：换端口后的第一次访问，主题/布局可能在数百毫秒内
  从默认值切到记忆值。极端要求零闪烁时可考虑把状态内联进 HTML 首屏，当前未做。
