# 飞书/钉钉/微信 IM 通道「网关化」重构方案

> 版本：v1（2026-10 落地版）
> 状态：实施中
> 关联：`docs/package-refactor-plan-v1.md`（包结构重构，已完成的背景工程）

## 一、目标定义

将 IM 通道从「工作区级资源」重构为「进程级网关资源」，从结构上消灭三个问题：

1. 同一 bot 先后绑定不同工作区的不同 session 导致的错乱（多连接随机路由）；
2. 工作区 LRU 释放/重启恢复引起的连接 churn 与消息丢失；
3. 绑定指向已删除会话时的悬挂路由。

约束：对外 HTTP API 契约保持兼容（只增不改），前端与桌面端平滑过渡。

## 二、调研结论（代码事实摘要）

| # | 事实 | 位置 |
|---|---|---|
| 1 | ChannelHub 构造于每个 WorkspaceContext，三 Link 各自持有 connections/bindings/openIdToSession | `WorkspaceContext.java:81`，`ChannelHub.java:70-78` |
| 2 | 连接拉起有两个入口：setRuntimePort（默认工作区）与工作区激活 | `WorkspaceManager.java:130-133, 848` |
| 3 | 工作区关闭（LRU 30 分钟）会 stop 全部 IM 连接 | `WorkspaceContext.java:163-168`，`WorkspaceManager.java:90` |
| 4 | 冲突检查只查内存活跃连接，扫码路径完全绕过 | `WebChannel.java:216, 631-646, 354-359` |
| 5 | 持久化文件是全局单文件，sessionId 为键 + workspaceId 归属字段，合并写回 | `FeishuCredentialStore.java:46, 119-170` |
| 6 | 出站回复走 HTTP API（tenant_access_token + 消息接口），不依赖 WS 连接 | `FeishuLink.java:640-668` |
| 7 | 入站消息投递依赖 `wsContext.getMessageGateway()`，判空防 NPE | `FeishuLink.java:614-621` |
| 8 | 前端轮询 `/web/chat/feishu/status`，弹窗双 Tab（凭据/扫码），i18n 键约 10 个 × 20 余语言 | `app-streaming.js:2654, 2816, 2879`，`i18n/*.json` |
| 9 | 钉钉结构与飞书 1:1 同构（isAppInUse 在 `DingTalkLink.java:426`） | `DingTalkLink.java` |
| 10 | 微信是每绑定一条长轮询线程（botToken 维度），同构问题 | `WeChatLink.java:84, 249-257, 331` |

### 病灶分析（为何绑 session 是错的）

- **错配 1**：appId 连接是进程级独占资源（飞书服务端对同一 appId 多连接随机路由），所有权却挂在工作区级对象上，导致所有补丁式状态同步。
- **错配 2**：工作区生命周期（LRU 30 分钟释放）churn 连接，但用户绑定意图不该随之消亡。
- **错配 3**：session 是可删除的短命对象（SessionJanitor 清僵尸、用户手删），绑定却是长命持久对象，主键锚在 session 上锚会漂移、会消失。

## 三、架构调整

### 3.1 目标架构

```
┌────────────────────────────────────────────────────────────┐
│ ImGateway（进程级单例，归 WorkspaceManager 持有）              │
│                                                              │
│  FeishuTransport    DingTalkTransport    WeChatTransport     │
│  appId→WS连接(唯一)  appKey→WS连接(唯一)  botToken→轮询线程(唯一)│
│                                                              │
│  路由表: openId → Binding{channel, appId, workspaceId,        │
│           sessionId, credentials, lastMessageId, boundAt}    │
│  持久化: im-bindings.json（openId 为键，单一真相源）            │
└──────────────┬───────────────────────────────────────────────┘
               │ 消息到达：查路由 → 校验会话存活 →
               │ workspaceRegistry.getOrCreate(workspaceId)
               │ → wsContext.getMessageGateway().acceptInput(...)
┌──────────────▼───────────────────────────────────────────────┐
│ WorkspaceRegistry / WorkspaceManager（不变，多一处按需激活路径） │
└──────────────┬───────────────────────────────────────────────┘
┌──────────────▼───────────────────────────────────────────────┐
│ WorkspaceContext（不再持有 IM 连接生命周期）                    │
│   └ ChannelHub（退化为无状态外观：getImLinks() 返回委托网关的   │
│      thin link，WebStreamBuilder 四处遍历零改动）              │
└──────────────────────────────────────────────────────────────┘
```

核心决策：

1. **主键换成 openId**（微信为 toUserName/openId）。session 只是绑定上的可变挂点，不再是锚。
2. **appId 连接唯一持有者是网关**。不存在第二条连接的可能，冲突检查概念消失；抢占迁移是模型自然结果。
3. **工作区不再参与连接生命周期**。`WorkspaceManager.java:130-133, 848` 两处 ChannelHub 拉起删除；网关在 `setRuntimePort()` 启动一次。LRU 释放不断连，消息到达时 `getOrCreate` 按需重开工作区。
4. **惰性校验收敛绑定生命周期**：消息到达时校验挂点 session 目录存在，不存在即删条目 + 回引导提示；会话删除 API 加主动清理钩子作为优化。
5. **对外 API 契约不变**（只增不改）。

### 3.2 新增组件

**（a）`ImGateway`**（`org.noear.solon.codecli.channel.ImGateway`）
- 构造：`WorkspaceRegistry`、`ImBindingStore`、三个 Transport。
- 关键方法：
  - `adoptBinding(channel, openId, appId, secret, workspaceId, sessionId, force)`：绑定或迁移；openId 已有挂点不同 → 迁移（删旧条目 + 给旧挂点会话推「绑定已迁移」提示 + 更新条目）；`force=false` 时返回冲突信息供前端确认。
  - `route(channel, openId)`：查表；挂点会话目录不存在 → 视为未绑定，删条目并回引导提示。
  - `isBound(workspaceId, sessionId)` / `snapshot(workspaceId, sessionId)`：供 thin link 实现 `Channel` 接口。
- 线程模型：Transport 连接线程 daemon + 重连退避（与现状一致），仅归属从 Link 移到 Transport。
- 生命周期：`setRuntimePort()` 中 `runtimePort != null` 时启动；acp/cli 无入口端口模式不启动。

**（b）`FeishuTransport` / `DingTalkTransport` / `WeChatTransport`**
- 从 `FeishuLink.StreamConnection`（767-1007 行）与对应类整体平移，剥离路由职责，改为回调 `ImGateway.onEvent(...)`。
- `pendingSessionId` 改为网关级 `Map<appId, PendingBind>`。

**（c）`ImBindingStore`**（替代三个旧 CredentialStore）
- 路径沿用 `engine.getHarnessChannels()` 目录，文件名 `im-bindings.json`。
- 格式（openId 为键，channel 分区）：

```json
{
  "version": 1,
  "feishu": {
    "ou_xxxx": { "appId": "cli_a", "appSecret": "***", "workspaceId": "ws1",
                 "sessionId": "web-abc", "lastMessageId": "", "boundAt": 1735689600000 }
  },
  "dingtalk": { "...": {} },
  "wechat":   { "...": {} }
}
```

- 迁移：网关首次启动读取旧 `feishu-bindings.json`（sessionId 键）转 openId 键；同一 openId 多条目取 `boundAt` 最大者；旧文件改名 `.bak.<timestamp>` 留底。钉钉、微信同样处理。

### 3.3 存量文件改动明细

| 文件 | 改动 | 性质 |
|---|---|---|
| `WorkspaceManager.java` | ①新增 `imGateway` 字段；②`setRuntimePort` 改为启动网关；③848 行激活拉起删除；④新增 `getImGateway()` | 减法为主 |
| `WorkspaceContext.java` | `close()` 删除 `channelHub.stop()`；ChannelHub 字段保留（M4 评估移除） | 减法 |
| `ChannelHub.java` | thin 化：三个 Link 变为委托网关的无状态适配器（保留 `getImLinks()` 签名） | 改造 |
| `FeishuLink.java` | 连接/协议部分移入 `FeishuTransport`；Link 保留 `Channel` 实现（isBound/sendReply/sendStatus），内部查网关路由表 | 拆分 |
| `DingTalkLink.java` | 同飞书对称 | 拆分 |
| `WeChatLink.java` | 长轮询线程移入 `WeChatTransport`，每 botToken 一线程由网关管 | 拆分 |
| `WebChannel.java` | ①`findFeishuAppConflict`/`findDingTalkAppConflict` 删除，改 `imGateway.adoptBinding(..., force)`；②bind 增加 `force` 参数；③扫码路径（354-359 行）同走 adoptBinding；④unbind 走网关 | 改造 |
| `WebStreamBuilder.java` | 无改动（四处 `getImLinks()` 遍历经 thin link 委托网关，语义不变） | 零改动 |
| `SessionJanitor` / 会话删除 API | 删除会话时调 `imGateway.onSessionRemoved(workspaceId, sessionId)` | 增量 |

### 3.4 关键流程走查（重构后）

| 场景 | 行为 |
|---|---|
| 工作区 A 绑 bot X → 重启 → 工作区 B 绑 X → A 激活 | appId 连接进程内唯一且常驻；B 绑定是显式迁移（force 确认），挂点原子更新 |
| 工作区被 LRU 释放后消息到达 | 连接未断；`getOrCreate` 按需重开工作区后投递 |
| 删除已绑定会话 | 钩子即时清理，或下一条消息惰性校验 + 引导提示 |
| 同一 openId 换绑其它会话 | 路由表键唯一，覆盖即迁移 |
| 出站回复 | thin link 查网关快照 → HTTP API 发送（不依赖连接） |

## 四、界面调整

### 4.1 API 契约（只增不改）

| 接口 | 变化 |
|---|---|
| `POST /web/chat/feishu/bind` | 新增可选参数 `force`；冲突且 `force!=true` 时返回 `Result.failure` + `conflict:{workspaceName, sessionId}` |
| `GET /web/chat/feishu/status` | 新增可选字段 `boundElsewhere:boolean`、`boundWorkspaceName:string` |
| `GET /web/chat/feishu/qrcode/status` | 扫码 bindSession 同走 adoptBinding，可能携带 `status:"conflict"` |
| unbind/qrcode/qrcode-cancel/钉钉微信同系 | 签名与既有响应不变 |

### 4.2 前端按钮三态方案

现状（`web.html:116-131`）：三按钮 `.im-btn-header` 基类（飞书/钉钉共用）+ `.wechat-btn-header`，状态机仅 bound/unbound 两态（`updateFeishuUI`，`app-streaming.js:2652-2663`），点击路由 2668-2682，刷新钩子 2496/2511/2666/2675/2825/2896（钉钉镜像 2954/3010/3019/3161/3257）。

**按钮三态定义：**

| 状态 | 触发条件 | 视觉 | 点击行为 |
|---|---|---|---|
| `unbound` | `bound:false, boundElsewhere:false` | 现状灰色 | 弹绑定窗（不变） |
| `bound` | `bound:true` | 现状绿色实线 | 解绑确认（不变） |
| `elsewhere`（新） | `bound:false, boundElsewhere:true` | 黄色虚线边框+黄图标（`--color-warning`），title 显示「已绑定到工作区 X」 | 弹绑定窗并直接进入迁移确认态 |

CSS（app.css）：

```css
.feishu-btn-header.elsewhere  { border: 1px dashed var(--color-warning); color: var(--color-warning); }
.dingtalk-btn-header.elsewhere { border: 1px dashed var(--color-warning); color: var(--color-warning); }
.wechat-btn-header.elsewhere   { border: 1px dashed var(--color-warning); color: var(--color-warning); }
```

JS（每渠道约 10 行）：`updateXxxUI` 内 `toggleClass('bound', bound)` / `toggleClass('elsewhere', !bound && elsewhere)` + title 三分支；点击路由 `showXxxModal(elsewhere ? { conflict: data } : null)`。绑定窗内（凭据提交与扫码成功两出口）收到 conflict 弹 `layer.confirm`，确认后 `force=true` 重发。

边界情况：
1. 同工作区另一会话占用：conflict.workspaceName == 当前工作区时文案显示「本工作区的另一个会话」。
2. 被迁出方按钮回落：陈旧 UI 无害，挂现有钩子即可，不加轮询。
3. 微信状态刷新入口函数名与飞书/钉钉不同（无 `updateWeChatUI`），实施时按实际命名对齐。
4. 桌面端共享静态资源，自动获得三态。

### 4.3 迁移通知与文案

- 迁移成功后被迁出方回落未绑定态（轮询 status 自然收敛）。
- i18n 新增键（约 6 个 × 22 语言）：`im.boundElsewhereTitle`（含 `{workspace}` 占位）、`im.migrate`、`im.feishuMigrateConfirm`、`im.feishuBoundElsewhere`、`im.feishuMigrateSuccess`、（钉钉/微信对应镜像键）。
- 绑定弹窗副标题追加「同一机器人同一时间仅可绑定一个会话，重复绑定将自动迁移」。

## 五、里程碑计划

| 阶段 | 内容 | 涉及文件 | 工作量 |
|---|---|---|---|
| M1 网关核心 + 飞书迁移 | ImGateway、ImBindingStore（含旧数据迁移）、FeishuTransport 拆分、FeishuLink thin 化、生命周期改造 | 约 6 新增 + 4 修改 | 60% |
| M2 Web 层 + 前端 | adoptBinding 接入、force 参数、status 扩展字段、三态按钮 + 迁移确认 + i18n | WebChannel、app-streaming.js、app.css、i18n/* | 20% |
| M3 钉钉 + 微信并入 | DingTalkTransport、WeChatTransport 对称迁移 | 2 拆分 + 2 thin 化 | 10% |
| M4 清理与测试重构 | 删残留（isAppInUse/ownsBinding/findXxxConflict/旧 CredentialStore）；测试改「路由表唯一性」语义 | 清理 + 3 测试类重写 | 10% |

顺序：M1 → M2 串行；M3 可与 M2 并行；M4 收尾。M1 完成后中间态可用。

## 六、验证标准

**M1**：迁移单测（旧文件含跨工作区双条目 → openId 唯一 + .bak 存在；同一 openId adopt 两次路由表仅一条指向后者）；双工作区集成（仅一条 `feishu-stream-*` 线程，重启恢复同）；LRU（调小 IDLE_RELEASE_MS，释放后消息触发重开并收到回复）。

**M2**：双工作区浏览器（B 弹迁移确认 → B 收发正常 → A 轮询回落 bound:false）；旧前端无 JS 报错；扫码冲突场景全通。

**M3/M4**：钉钉复跑 M1 用例（appKey 维度）；全仓编译通过；三个测试类新语义通过；grep 无残留引用。

**回归风险点**：进程退出时网关统一 `gateway.stop()`（挂 shutdown 钩子）；消息路径的 `getOrCreate` 只发生在消息分发线程，不得出现在 close 回调链（`WebGate.resolveConnections` 注释约束）。
