# 飞书/钉钉/微信 IM 通道「网关化」重构方案

> 版本：v1.8（2026-10 落地版）
> 状态：M1/M2/M3a/M3b/M4 已全部落地；真机端到端验证已执行，并据此修复<b>本次重构自身引入</b>的三处缺陷（飞书扫码轮询 400、钉钉多 bot 绑定互顶、落盘位置取错根目录，见 5.6、5.7、5.8）；**三渠道持久化已统一**（微信独立 store 已删除，见 5.9）
> 遗留：微信不支持「多 bot 并存」（多账号已支持）——不是缺陷，见 5.6.3；本类改造仍需真机复测（见 5.9.4）
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
| 5 | 持久化文件是全局单文件，sessionId 为键 + workspaceId 归属字段，合并写回 | `FeishuCredentialStore.java:46, 119-170`（已于 M4 删除，由 `ImBindingStore` 承接） |
| 6 | 出站回复走 HTTP API（tenant_access_token + 消息接口），不依赖 WS 连接 | `FeishuLink.java:640-668` |
| 7 | 入站消息投递依赖 `wsContext.getMessageGateway()`，判空防 NPE | `FeishuLink.java:614-621` |
| 8 | 前端轮询 `/web/chat/feishu/status`，弹窗双 Tab（凭据/扫码），i18n 键约 10 个 × 20 余语言 | `app-streaming.js:2654, 2816, 2879`，`i18n/*.json` |
| 9 | 钉钉结构与飞书 1:1 同构（isAppInUse 在 `DingTalkLink.java:426`） | `DingTalkLink.java` |
| 10 | 微信是每绑定一条长轮询线程（botToken 维度），同构问题 | `WeChatLink.java:84, 249-257, 331` |
| 11 | 登记主键最初只有 channel + userKey，未含 bot 身份；钉钉 staffId 是<b>组织维度</b>的，导致多 bot 互顶（见 5.6.2） | `ImBindingRegistry.java:27`（修复前） |

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

**（c）`ImBindingStore`**（飞书 / 钉钉的绑定登记表；微信另见下方说明）
- 路径：`<用户主目录>/<getHarnessChannels()>/im-bindings.json`，即 `~/.soloncode/channels/im-bindings.json`。
  登记表是「进程级、跨工作区」的单一真相源，**必须锚定用户级目录**，不能取 `engine.getUserDir()`
  ——它返回 `System.getProperty("user.dir")`（进程启动目录），会让同一进程随启动位置读写不同文件（见 5.7.1）。
- 格式（channel 分区，条目键为 `identity + userKey`，即 v2；节点内另写权威 `userKey`）：

```json
{
  "version": 2,
  "feishu": {
    "cli_a␟ou_xxxx": { "channel": "feishu", "userKey": "ou_xxxx",
                          "appId": "cli_a", "appKey": null, "botToken": null,
                          "workspaceId": "ws1", "sessionId": "web-abc",
                          "secret": "***", "lastMessageId": "", "updatedAt": 1735689600000 }
  },
  "dingtalk": { "...": {} }
}
```

- 迁移（实现见 `ImBindingStore.migrate()`，仅登记表缺失时触发）：只扫描**登记表自身所在目录**
  （`~/.soloncode/channels/`），不另加任何目录（见 5.7.2）。按来源分类回收：
  ① 旧格式 `feishu-bindings.json`（sessionId 键，节点含 openId/appId/appSecret）；
  ② 旧格式 `dingtalk-bindings.json`（sessionId 键，节点含 userId/appKey，appKey 缺失时回退 robotCode）。
  合并按 `channel + identity + userKey` 重算键，同键取 `updatedAt` 大者（旧格式无该字段，视为 0，
  故新格式总能胜过旧格式）；每个来源消费后改名 `.bak.<timestamp>` 留底，有结果即落盘。
  `.bak.*` 不参与扫描，因此可安全重启。

- **微信不走本类**：微信绑定仍由 `WeChatCredentialStore`（`wechat-bindings.json`，sessionId 为键）
  承载。因此本节原标题所指的「替代三个旧 CredentialStore」，**实际只完成了飞书 / 钉钉两支**；
  差异成因与统一取舍见 5.8。
  > **已补齐（见 5.9）**：微信现已并入本类，`WeChatCredentialStore` 已删除。
  > 本节下列描述保留为当时的时间线记录，**当前事实以 5.9 为准**。

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
| `SessionWebController` 的会话删除接口 | 删除会话时调 `imGateway.onSessionRemoved(workspaceId, sessionId)`；`SessionJanitor` 与桌面端入口未接入（见 5.5(3)） | 增量 |

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
| `POST /web/chat/feishu/bind` | 新增可选参数 `force`；冲突且 `force!=true` 时返回 `Result.failure` + `conflict:{workspaceId, sessionId}` |
| `GET /web/chat/feishu/status` | 新增可选字段 `boundElsewhere:boolean`、`boundWorkspaceId:string`（实现落地的是 Id 而非 Name） |
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

### 5.1 实施进展（2026-10）

**M1 已完成：**

- 新增 `channel/ImGateway`：openId 为主键的绑定登记表 + 持久化（`im-bindings.json`，旧 `feishu-bindings.json` 自动迁移 + `.bak` 备份）+ 连接租约 + 待绑定挂点 + 消息投递（实现 `FeishuTransport.Sink`）。
- 新增 `channel/feishu/FeishuTransport`：进程级持有 `appId -> WS 连接`，含协议编解码、心跳、重连；**连接不再随工作区 `WorkspaceContext` 关闭而断开**。
- `FeishuLink` 由 1133 行瘦身为约 395 行薄适配层：不再持有连接/协议/本地绑定表，全部委托网关与传输层；`stop()` 变为空实现（工作区关闭不断开飞书连接）。
- `WorkspaceManager`：注入 `WorkspaceRegistry` + `workspacePathOf`（连接线程日志归属）；`setRuntimePort`/工作区激活时拉起进程级传输层（幂等，每 appId 至多一条连接）。
- 消息投递时按绑定挂点 `getOrCreate(workspaceId)` 动态唤醒工作区（含 LRU 已释放的），并做会话存活惰性校验（会话目录不存在即解除悬挂绑定 + 回引导提示）。
- `ImBindingRegistry`/`ImBindingStore`/`FeishuAppLeaseRegistry` + 单元测试。

**验证：**`mvn -o compile` 干净；渠道/绑定/网关相关 55 例通过（1 例 `WebStreamBuilderReplyRouteTest.messagesResolveByLocale` 为既有法语文案失败，与本次无关）；全量 993 例仅 2 例既有失败（法语文案 + `AgentSettingsControllerTest` settings 域）。

**尚未完成：**

- 未做真机/双工作区集成验证（需真实飞书 bot）；「单连接 + LRU 释放后消息触发重开」缺专门集成测试。
- 微信仍为工作区级连接模型（M3b）；`ChannelHub` 仍按工作区构造（M4）。
- `FeishuCredentialStore`/`DingTalkCredentialStore` 已无生产引用（仅测试引用），待 M4 清理。

### 5.2 实施进展（2026-10，续）

**M3a（钉钉网关化）已完成：**

- 新增 `channel/dingtalk/DingTalkTransport`：进程级持有 `appKey -> WS 连接`，含协议解析、ACK/pong、重连；连接不再随工作区关闭而断开。
- `DingTalkLink` 由 967 行瘦身为约 350 行薄适配层：不再持有连接/协议/本地绑定表，全部委托网关与传输层；`stop()` 为空实现。
- `ImGateway` 扩展为双渠道：新增 `CHANNEL_DINGTALK`、钉钉传输层、pending 挂点、`adoptDingTalk/removeDingTalk/claimDingTalk/find*/list*/dingtalkStatus`，并实现 `DingTalkTransport.Sink`（含会话存活惰性校验与 `getOrCreate` 按需唤醒工作区）。
- `ImBindingRegistry` 新增 `findByAppKey`（钉钉身份用 appKey，与飞书 appId 对称）。
- `WebChannel`：钉钉 bind 增加 `force` 参数 + 网关冲突预检，扫码路径同样走网关（关闭「扫码绕过冲突检查」漏洞）；删除 `findDingTalkAppConflict`。

**验证：**`mvn -o compile` 干净；钉钉/飞书/网关定向 68 例全绿；全量 993 例仅 2 例既有失败（法语文案 + settings 域），无新增回归。新增 `ImGatewayTest` 钉钉用例 4 条（跨工作区冲突/force 迁移、状态精确性、双渠道独立性、pending/删除工作区隔离）。

**仍待完成：**

- **M3b（微信）**：微信的长轮询状态（cursor/replyTarget(context_token)/typingTicket）与回复路径强耦合，迁移到进程级需先确定运行时状态的存放位置（绑定上的可变挂点 or 传输层按会话持有），属设计决策而非机械对称，未动。
- **M4（清理）**：删除已无生产引用的 `FeishuCredentialStore`/`DingTalkCredentialStore`（含其测试）、`ChannelHub` 工作区级构造与 `workspaceId` 过滤残留；测试语义改为「路由表唯一性」。
- 真机双工作区端到端验证（需真实飞书/钉钉应用）。

### 5.3 实施进展（2026-10，续二）

**M3b（微信网关化）已完成：**

- 新增 `channel/wechat/WeChatTransport`：进程级持有 `ilinkUserId -> 长轮询连接`，把「每会话一条长轮询」包装成与飞书/钉钉 WebSocket 同形的连接。每条连接由一个 `WeChatLink` 引擎实例承载（游标 / 回复目标 context_token / typing 全在引擎内），投递回调改由传输层 `Sink` 承接。
- 引擎使用**空存储**（`NOOP_STORE`）与可注入的 `Transport` 接缝：微信绑定的落盘由网关单点负责，避免双写漂移；测试可注入假传输层。
- 新增 `channel/WeChatGatewayLink`：工作区级薄外观，方法名与 `WeChatLink` 对齐，使 `ChannelHub`/`WebChannel`/`WebStreamBuilder` 的调用点无需感知改造；**`stop()` 为空实现**——微信长轮询不再随工作区 LRU 回收而断开（本次重构对微信的核心落点）。
- `ImGateway` 扩展为三渠道：新增 `CHANNEL_WECHAT`、微信传输层与全局存储（`WeChatCredentialStore`，null workspaceId 全量管理）、`adoptWeChat/removeWeChat/isWeChatBound/sendWeChatReply/sendWeChatStatus/startWeChat/stopWeChat`，并实现 `WeChatTransport.Sink`（`deliverWeChat` 含 `getOrCreate` 按需唤醒工作区 + 会话存活惰性校验）。
- **语义对齐**：同一 `ilinkUserId` 跨工作区占用时 `force=false` 返回冲突（与飞书/钉钉一致）；同一工作区内换会话沿用旧有的自动迁移语义；**重复确认（同会话同凭据）原样保留游标**，不重建连接。
- `ChannelHub`：微信槽位由 `WeChatLink` 换为 `WeChatGatewayLink`（构造时绑定 `ImGateway` 单例 + workspaceId）。

**验证：**`mvn -o compile` 干净；微信相关 41 例全绿（`WeChatLinkBindingTest` 18 / `WeChatCredentialStoreTest` 6 / `WeChatBindingOwnershipTest` 3 / `WeChatClientParseTest` 14），**零测试改动**（引擎类原样保留，测试仍直接验证引擎）；`ImGatewayTest` 8 例 + 新增 `WeChatTransportTest` 6 例全绿；全量 997 例仅 2 例既有失败（法语文案 + settings 域），无新增回归。

**`WeChatTransportTest` 锁定的核心不变量：**

- 同一会话 + 同一用户重复 `ensureConnected` → 连接复用（游标不重置）；
- 同一用户换会话 → 全局仍只有一条连接；
- **同一用户跨工作区 → 旧工作区连接被断开**（本次重构要消灭的核心症状）；
- 断开后可重新绑定；出站路径对未知会话静默忽略；null 参数不建连接。

**仍待完成：**

- 真机双工作区端到端验证（需真实飞书/钉钉/微信应用）。
- 历史无归属条目：微信绑定文件中 `workspaceId == null` 的遗留条目不再自动认领（旧实现按会话目录探测认领），需用户重新绑定一次。

### 5.4 实施进展（2026-10，续三）—— M4 清理与微信三态

**M4（清理）已完成：**

- 删除 `channel/dingtalk/DingTalkCredentialStore.java`：全仓零引用（生产与测试皆无），纯历史残留。
- 删除 `channel/feishu/FeishuCredentialStore.java` 及其测试 `FeishuBindingOwnershipTest`：绑定归属职能已由 `ImBindingRegistry`/`ImBindingStore` 承接，旧「按 workspaceId 过滤 + 合并写」机制随之淘汰（该测试断言的正是被淘汰的语义）。
- 保留 `WeChatCredentialStore`：它仍是**生产在用**的微信全局绑定存储（`ImGateway` 的落盘唯一出口）；`WeChatTransport` 用其 `NOOP_STORE` 空存储实例作引擎基类，不构成残留。
- `isAppInUse` 保留于 `FeishuTransport`/`DingTalkTransport`：语义已从「跨工作区找冲突」变为传输层自身的连接租约查询，是网关的合法组成部分。
- `WeChatLink.ownsBinding` 保留：引擎在空存储模式下的本地状态过滤，非跨工作区归属判断。

**微信三态按钮（前端）已完成：**

- 后端：`ImGateway` 新增 `wechatStatus(workspaceId, sessionId)` 与 `WeChatStatus`（bound / boundElsewhere / 归属工作区），`WeChatGatewayLink.status(sessionId)` 转发；`GET /web/chat/wechat/status` 响应新增 `boundElsewhere`、`boundWorkspaceId` 字段（只增不改）。
- 前端：`updateWechatUI` 改为三态（`bound` / `elsewhere` / 未绑定）；`updateDingTalkUI` 补齐 `elsewhere` 分支（此前飞书已有、钉钉缺失）。
- CSS：新增 `.wechat-btn-header.elsewhere`、`.dingtalk-btn-header.elsewhere`、`.im-btn-header.elsewhere`（黄色虚线，复用 `--color-warning`）。
- i18n：新增 `im.boundElsewhereTitle`（含 `{workspaceId}` 占位），补齐全部 22 个语言文件。

**顺手修复（计划外，已附考证）：**

- `i18n/im-messages_fr.properties` 的 `im.rejected` 使用了 MessageFormat 风格的 `''` 转义（`n''a` / `l''ai`），但 Solon `I18nBundle` 做的是纯属性查表、**不跑 MessageFormat**，导致字面双引号泄到文案里，`WebStreamBuilderReplyRouteTest.messagesResolveByLocale` 因此失败（断言 `n'a` 而实到 `n''a`）。改为单引号后该用例转绿。该文件与 IM 网关改造无关，属诊断后的一行数据修正，若认为超出范围可单独回退。
- 同时校正了上一轮 M2 遗留的一处 i18n 数据问题：9 个语言（bn/br/bs/de/es/ko/ru/vi/zh-TW）的 `im.feishuBoundUnbind` 被误写成英文原文，已从基线恢复为各自的本土译文（另有校验脚本确认 22 个语言文件除预期的 5 个新增键外，无任何值变更或键丢失）。

**验证：**`mvn -o compile` 干净；`node --check` 前端语法通过；渠道定向 123 例全绿；全量 **1000 例，仅 1 例既有失败**（`AgentSettingsControllerTest.builtinStillReadableAfterOverrideFileExists`，settings 域，已核对：该测试对本次改动的任何文件零引用），无新增回归。用例总数 1003 → 1000 即为删除 `FeishuBindingOwnershipTest` 的 3 例。

### 5.5 收尾补齐（2026-10，续四）—— 生命周期 / 删除钩子 / 历史条目

回补四组「方案已写明、但代码未真正落地」的收尾项（对齐 3.3 与第六节验证标准）：

**（1）进程退出统一 stop + ShutdownHook**

- `ImGateway.stop()` 统一停止飞书/钉钉 WS 与微信长轮询；`setWorkspaces(...)` 内幂等注册 `im-gateway-shutdown` 钩子（`AtomicBoolean` 保证只注册一次）。
- 时机选择：`setWorkspaces` 只在入口运行时就绪时被调用（`WorkspaceManager:133/874`），acp/cli 无端口模式不会走到，因此不会在无 IM 连接的场景注册钩子。
- `/exit` 命令先 `System.exit(0)`（会触发钩子）再于 5 秒后用 `halt(0)` 兜底，钩子有足够时间优雅关闭连接。

**（2）工作区关闭不再触碰 IM 连接（方案 3.3 的减法）**

- `WorkspaceContext.close()` 删除 `channelHub.stop()` 调用。三个 Link 的 `stop()` 本就是空实现，但保留调用会掩盖「连接归进程」这一事实，属误导性残留。
- `ChannelHub.stop()` 保留（`Lifecycle` 接口要求），语义已在注释中声明。

**（3）会话删除主动清理（方案 3.3 的 `onSessionRemoved` 钩子）**

- 新增 `ImGateway.onSessionRemoved(workspaceId, sessionId)`：一次性清理该会话在三个渠道上的绑定（微信同时断开长轮询；飞书/钉钉连接是 appId/appKey 维度，不随会话消亡，仅解除绑定）。归属限定 + null 安全。
- 接入 `SessionWebController` 两处：`clearUnpinnedSessions`（取 `currentContext()`）与 `deleteSession`（按目标工作区路径反查 `getContextsCached`，支持跨工作区删除；内存未命中则跳过，由消息到达时的惰性校验兜底）。
- 桌面端删除入口未接入：遵循项目约定，不主动改动仅服务桌面端的实现。

**（4）微信历史无归属条目（5.3「仍待完成」项）**

- 修复 `adoptWeChat` 冲突误判：`workspaceId == null` 的历史条目此前会在重绑时被判为「跨工作区冲突」，与 `wechatStatus` 报出的「未绑定」自相矛盾。现在此类条目不构成冲突，重绑即认领（与 5.3 记载的「需用户重绑一次」语义一致）。
- 修复 `weChatStore()`：此前只在 `harnessEngine != null` 时返回装配好的存储，导致注入式装配（测试/定制）下 `adoptWeChat` 一律返回未受理；改为优先返回已装配实例。该缺陷由新增测试直接查出。

**验证：**`mvn -o compile` 干净；渠道定向 55 例全绿（新增 `ImGatewayTest` 会话删除 2 例 + `WeChatLegacyBindingTest` 2 例）；全量 **1004 例，仍仅 1 例既有失败**（`AgentSettingsControllerTest`，settings 域，与本次改动零引用）。

### 5.6 真机验证缺陷修复（2026-10，续五）

真机端到端验证暴露出**三处本次重构自身引入**的缺陷（本节 5.6.1、5.6.2，以及 5.7.1 的落盘位置，后者直到 5.8 才真正修掉），均已修复并补了守卫测试；5.6.3 是随之进行的核验结论（无需修改）。

#### 5.6.1 飞书扫码后状态不刷新、发消息回「未绑定」

**根因**：`WebChannel.feishuQrcodeStatus(...)` 在重构中被加上 `@Param("force") Boolean force`。
Solon 自 3.2 起 `@Param.required()` **默认 true**，而前端轮询不带 `force`，于是每 2 秒一次
HTTP 400（`Missing required parameter 'force'`）；jQuery `$.get(...).done()` 永不触发，状态
文本不更新，`startStream` / `bindSession` 从未执行 → 无 pending、无绑定。

**修复**：三个端点（`feishuQrcodeStatus` / `feishuBind` / `dingtalkBind`）的 `force` 改为
`@Param(value = "force", required = false)`；前端轮询补 `.fail()` 兜底（4xx 立即停轮询并提示），
不再静默空转。新增守卫测试 `WebChannelParamContractTest`（反射断言 `WebChannel` 中装箱类型的
`@Param` 不得必填）。注意 `api/web/settings` 下 6 个 toggle 接口的 `@Param("enabled") Boolean`
是<b>有意必填</b>，不可误改。

#### 5.6.2 钉钉多 bot 绑定互相顶掉

**问题**：用 bot1 绑定「对话1」，再用 bot2 绑定「对话2」，**对话1 的绑定消失**。

**根因**：登记主键只有 `channel + userKey`，没有 bot 身份。钉钉的 userKey 取
`senderStaffId`，这是<b>组织维度</b>的工号——同一企业内，不同 bot 收到的同一个人
staffId 完全相同。于是 bot2 的绑定主键与 bot1 撞车，`adopt` 命中 `current != null`
走「删旧写新」，把「对话1」的绑定顶掉。

飞书未暴露此缺陷属侥幸：`open_id` 是 app 维度的，同一个人扫不同 bot 会拿到不同
openId，主键天然不冲突。这也说明旧设计并未真正表达「一个 bot 一个对话」，只是被
飞书的 ID 特性掩盖了。

**修复**：

- `ImBindingRegistry.compositeKey(channel, identity, userKey)`：主键加入 bot 身份维。
  `Identity.key()` 取 appId / appKey / botToken 中首个非空者。
- 归属约束明确为**「一个 bot（同一 identity）同时只能绑定一个对话」**，由 `findOwner`
  执行；不同 identity 之间互不可见，因此天然支持多 bot 并存。
- 所有按 userKey 的查找/更新/解绑改为按 identity 精确命中：
  `find` / `remove` / `removeByUserKey` / `updateLastMessageId` / `claim`。
- `ImGateway` 新增精确重载 `findFeishuByOpenId(appId, openId)`、
  `findDingTalkByUserId(appKey, userId)`；原 1 参版本退化为 `findAny` 扫描，仅用于
  拿不到 identity 的场景（如兼容旧签名 `DingTalkLink.bindSession`）。
- 消息入口 `onFeishuText` / `onDingTalkText` / `onDingTalkNonText` 改用精确查找；
  `onFeishuConnected` / `onDingTalkConnected` 清去重缓存时带上 identity。

**提示语义细化**：此前只要查不到就回「还没有绑定对话」，现在区分两种情况——该 bot
尚未绑定（引导扫码）与**该 bot 已被别的对话占用**（新增 `im.hint.botTaken`，24 个语言
文件齐备）。否则第二个用户发消息会得到误导性提示。

**持久化升级（version 1 → 2）**：

- JSON 条目键改为 `identity + userKey`，并在节点内写入权威的 `userKey` 字段；读取时
  优先采用节点内 `userKey`（旧实现直接用 JSON 条目键当 userKey，加入身份维后会导致
  键错位，此缺陷由 `saveAndLoadRoundTripsGroupedBindings` 当场捕获）。
- 旧文件可**无损升级**：v1 节点内本就存了 `appId`/`appKey`，重算键
  `compositeKey(channel, identity, userKey)` 即可，无需丢弃数据。
- 旧文件里「同一 openId + 不同 appId」的多条目，在新模型下是**两个 bot 的绑定，应当
  并存**（旧模型会互相覆盖）——`migratesLegacyFeishuFileAndBacksItUp` 的期望值已相应
  从 1 条改为 2 条。

**验证**：`mvn -o clean test` 全量 **1011 例，仅 1 例既有失败**（`AgentSettingsControllerTest`，
settings 域，与本次改动零引用）。新增回归测试 6 例，锁死缺陷不再复现：

| 测试 | 层次 | 锁定的不变量 |
| --- | --- | --- |
| `ImBindingRegistryTest.sameUserKeyAcrossDifferentBotsCoexist` | 登记表 | 同 userKey + 不同 bot → 两条绑定并存 |
| `ImBindingRegistryTest.oneBotRejectsSecondUserWithoutForce` | 登记表 | 同 bot 被第二个用户抢绑 → 非 force 被拒 |
| `ImBindingStoreTest.sameUserKeyAcrossBotsRoundTripsWithoutOverwrite` | 持久化 | 落盘后两条绑定互不覆盖 |
| `ImGatewayTest.dingtalkTwoBotsWithSameStaffIdCoexist` | 网关 | 真机场景复刻（bot1→对话1、bot2→对话2） |
| `ImGatewayTest.dingtalkOneBotStaysWithSingleSession` | 网关 | 一个 bot 只服务一个对话 |
| `ImGatewayTest.dingtalkTwoBotsSurviveReload` | 网关 | 重启后仍能按 identity 区分 |

#### 5.6.3 微信为何不受同一缺陷影响（已核验，无需修改）

排查「钉钉多 bot 互相顶掉」时同步核验了微信，结论是**微信不存在该缺陷**，且其约束粒度
与飞书/钉钉**有意不同**。此处记录核验依据，以免后人照搬 5.6.2 的身份维改造而引入回归。

**微信的绑定粒度是「每个微信账号」，而非「每个 bot」。**

- `botToken` 由 ilink 平台**按账号签发**：绑定流程从授权结果里取，见
  `WebChannel:120` 的 `statusResult.get("bot_token")` / `get("ilink_bot_id")`。
  因此每绑一个微信账号，就会得到一个独立的 `bot_token`。
- 多个手机 = 多个微信账号 = 多个 `ilinkUserId` + 多个 `botToken`，**各自独立成条**，
  互不覆盖；进程内也各自持有独立长轮询连接（`WeChatTransport` 按 `ilinkUserId` 分桶）。
  也就是说「多账号并存」在微信上**本就成立且已在运行**。
- 微信的存储与冲突判定（`ImGateway.adoptWeChat`）**只看 `ilinkUserId`，不看 `botToken`**：
  存储以 `sessionId` 为键（`WeChatCredentialStore`），冲突是「同一 `ilinkUserId` 在别的
  工作区已被占用」，清理是「同 `ilinkUserId` 的旧条目」。

**为什么不能把 `botToken` 也纳入冲突判定**：同一微信账号重新授权/重新扫码会**换发新的
`botToken`**，但 `ilinkUserId` 不变。若照搬 5.6.2 按 (identity, userKey) 比对，重复授权
就会认不出旧条目，留下悬挂绑定与僵尸连接。这里按 `ilinkUserId` 归一，正是有意为之。

**微信也不参与登记表的 bot 级归属约束**：`ImBindingRegistry.adopt` 只被
`CHANNEL_FEISHU` / `CHANNEL_DINGTALK` 调用，微信不走 `findOwner`；`toWeChatBinding`
仅把微信条目适配成 `Binding` 供列表展示。因此 5.6.2 的「一个 bot 一个对话」规则不会
误伤微信的多账号并存。

**对比总结**：

| 通道 | 归属粒度 | 约束表达 |
| --- | --- | --- |
| 飞书 / 钉钉 | 每个 bot（appId / appKey） | 一个 bot 一个对话；换人需 force 迁移 |
| 微信 | 每个微信账号（ilinkUserId） | 一个账号一个对话；不同账号天然并存 |

**一处旧断言与修复语义正好相反，已改写**：`differentIdentitySameUserKeyRejectsAndForceReplaces`
原断言「同 userKey + 不同 identity 应被拒」，而这正是缺陷本身；改写为
`sameUserKeyAcrossDifferentBotsCoexist`。

**踩坑（工具链，务必记住）**：`mvn -o test-compile` 会因 Maven 增量编译的陈旧 class
给出**假通过**（改完公共 API 后仍报 BUILD SUCCESS）。因此改动公共 API 后必须用
`mvn -o clean test-compile` 验证，否则编译错误会被掩盖到测试运行阶段才暴露。

### 5.7 登记表落盘位置修正与存量数据迁移（2026-10，续六）

#### 5.7.1 缺陷：登记表锚定在「进程启动目录」

`ImGateway.getInstance(engine)` 原先取 `engine.getUserDir()` 作为根目录，而该方法
（`HarnessEngine:287`）返回的是**进程启动目录**，不是用户主目录：

```java
public String getUserDir()  { return System.getProperty("user.dir"); }   // 启动目录
public String getUserHome() { return System.getProperty("user.home"); }  // 用户主目录
```

后果：登记表落在 `<启动目录>/.soloncode/channels/im-bindings.json`。同一份「进程级、跨工作区」的
单一真相源，会随用户从哪个目录启动 soloncode 而读写不同文件——从 `~` 启动与从工程目录启动，看到的
绑定完全不同。`WeChatCredentialStore` 有同样的取错。

**修复**：`ImGateway` 改用 `getUserHome()`，锚定 `~/.soloncode/channels/`。路径组装抽为可测接缝
`ImGateway.storePath(harnessRoot, harnessChannels)`，并加测试固定该约定。

> **订正（见 5.8）**：本节原写「两处均改用 `getUserHome()`」，但 `WeChatCredentialStore`
> 当时**漏改**，一直取 `getUserDir()`，直到 5.8 才真正修掉。保留原始记述以存时间线，事实以 5.8 为准。

#### 5.7.2 存量数据迁移（已修正：只认用户级目录一处）

**决策背景——撤销一次错误设计。** 修完位置后曾一度把「启动目录下的 channels 目录」
当作历史来源，想把「被该缺陷写错位置的同格式 `im-bindings.json`」当旧数据回收。
**这是错的，已全部撤销**（连同 `legacyDirs` 机制、`ImGateway.getInstance(Path, List<Path>)`
重载）。理由：

1. 登记表是本次改造**新增**的规范，此前不存在任何位置的同格式 `im-bindings.json`，
   所谓「写错位置的历史登记表」并不构成需要回收的真实数据；
2. 再引入一个由 `getUserDir()` 推导的路径，等于把刚拆掉的「位置取决于从哪儿启动」
   重新装回去——同一个缺陷换了个名字。

**准则：进程级共享资源（登记表、微信凭据）只认 `~/.soloncode/` 一处；任何新增路径分量前
先问「它是否由 `user.dir` 推导」。**

**最终行为**：扫描目录 = 登记表**自身所在目录**（`~/.soloncode/channels/`），不另加任何目录：

| 来源 | 形态 | 处理 |
| --- | --- | --- |
| `feishu-bindings.json`（登记表同目录） | sessionId 键：openId/appId/appSecret/workspaceId | 转新键 |
| `dingtalk-bindings.json`（登记表同目录） | sessionId 键：userId/appKey/appSecret | 转新键，appKey 缺失回退 robotCode |

迁移只在登记表文件**缺失**时触发；成功来源改名 `.bak.<timestamp>` 留底。
`wechat-bindings.json` 是 `WeChatCredentialStore` 自己的存储，不归 `ImBindingStore` 迁移。

**运维动作：无需人工干预**。旧格式绑定会保留；此后重新绑定落到 `~/.soloncode/channels/`。

#### 5.7.3 顺带修复的静默失败

`ImBindingStore.load()` 原先把异常 `ignored` 掉且**不打日志**，文件损坏或半写会让绑定**无声消失**；
`save()` 亦然。现均补 `LOG.warn`，与 `WeChatCredentialStore` 的日志行为保持一致。

#### 5.7.4 无归属僵尸条目：明确不处理（已决策）

无归属条目（`workspaceId == null`）若其 `sessions/<sessionId>` 目录已被清理，将永久无法认领并滞留在
表中；且 `statusForSession` 按同名 sessionId 匹配时会把新对话判成 `boundElsewhere`，干扰绑定状态显示。

**决策：不处理，保持现状。** 理由是这类条目只可能来自「IM 与 session 直绑」时代的极早期数据，
且需要「会话目录已被手工清理」这一额外条件叠加才会出现；误删正常绑定的风险大于收益。
若日后确有用户反馈「某对话莫名其妙显示已绑定到别处」，排查入口即：检查
`~/.soloncode/channels/im-bindings.json` 中该通道下是否存在 `workspaceId` 为 null 的条目，
确认后手工删条目并重启即可，无需为此引入自动清理逻辑。

**验证**：`ImBindingStoreTest` 用例（钉钉旧文件迁移 + robotCode 回退；登记表路径锚定传入 harness 根）
连同原有用例全绿。已删除断言「按启动目录回收错位登记表」的用例——它固化的正是上面被撤销的行为。

### 5.8 三渠道持久化尚未统一（2026-10，续七）

#### 5.8.1 缺陷：微信凭据存储漏改根目录

5.7.1 声称「两处均改用 `getUserHome()`」，但 `WeChatCredentialStore` 的构造实际仍取
`engine.getUserDir()`（进程启动目录），**只有 `ImGateway` 改了**。后果：微信绑定落在
`<启动目录>/.soloncode/channels/wechat-bindings.json`，与登记表不在同一处，且随启动位置漂移
——同一进程从 `~` 启动与从工程目录启动，看到的微信绑定不同。

**修复**：

- `WeChatCredentialStore` 改用 `engine.getUserHome()`，与登记表同锚 `~/.soloncode/channels/`；
  路径组装抽为可测接缝 `WeChatCredentialStore.storePath(harnessRoot, harnessChannels)`。
- 同步订正 `ImGateway.weChatStore()` 中「微信存储从历史位置（进程启动目录）回收一次」的过时注释。
- 新增路径契约测试 `WeChatCredentialStoreTest.storePathIsRootedAtGivenHarnessRoot`。
- **不做**按启动目录回收旧文件：遵循 5.7.2 准则（进程级共享资源只认 `~/.soloncode/` 一处），
  否则等于把刚拆掉的「位置取决于从哪儿启动」装回去。若此前确从非 `~` 目录启动过，微信需重绑一次。

#### 5.8.2 为什么三渠道的存储处理不一样（成因）

修复位置后的现状对比：

| 维度 | 飞书 / 钉钉 | 微信 |
|---|---|---|
| 存储类 | `ImBindingStore`（统一） | `WeChatCredentialStore`（独立） |
| 文件 | `im-bindings.json` | `wechat-bindings.json` |
| 目录 | `~/.soloncode/channels/` | 同左（5.8.1 修复后一致） |
| 主键 | `channel + identity + userKey` | `sessionId` |
| 运行时状态 | 仅 `lastMessageId`（去重缓存） | `cursor` / `lastContextToken` / `lastFromUserId` / `baseUrl` / `ilinkBotId` |
| 写策略 | 全量重写 | `saveThrottled`（游标每轮推进，2s 节流） |
| 归属约束 | `ImBindingRegistry.findOwner` | `adoptWeChat` 内手写扫描 |

成因有三层：

1. **原计划本就没做完**：3.2(c) 写的是「`ImBindingStore` 替代三个旧 CredentialStore」。
   M3b 只把微信的**连接**提到了进程级（5.3），持久化沿用了旧的 `WeChatCredentialStore`；
   M4 清理按「是否仍被生产引用」判定，微信存储确被引用，故保留（5.4）。结果只剩飞书/钉钉两支。
2. **微信不能机械照搬 identity 维**：5.6.2 给飞书/钉钉的主键加了 bot 身份维，但微信的
   `botToken` 会**随重新授权换发**（5.6.3），把它并进主键会导致重新授权后认不出旧条目、
   留下悬挂绑定与僵尸连接。微信若要并入，identity 必须取**稳定的** `ilinkBotId`，
   而 `botToken` 只能当可变的凭据字段（等价于 `Binding.secret`）。
3. **绑定记录含高频运行时状态**：`cursor` 每轮长轮询都可能推进，这正是 `saveThrottled` 存在
   的原因；`ImBindingStore.Binding` 目前没有承载这些字段的位置。

#### 5.8.3 统一方案（已于 5.9 执行）

**方向**：把微信并入 `ImBindingStore`，使之成为三渠道唯一的绑定登记表。做法：

1. 微信条目的 identity 取 `ilinkBotId`（稳定），`botToken` 存入 `Binding.secret`（可变凭据）；
   `ilinkUserId` 入 `userKey`；`sessionId` 保留为挂点。
2. 为 `Binding` 增加微信所需的运行时字段（`cursor` / `lastContextToken` / `lastFromUserId` /
   `baseUrl`），或加一个通用扩展段，避免污染飞书/钉钉语义。
3. `adoptWeChat` 改走 `ImBindingRegistry.adopt` + `findOwner`，删除手写冲突扫描。
4. 保留节流写（游标高频），或在登记表内为微信单独节流。
5. 迁移：把 `wechat-bindings.json` 纳入 `ImBindingStore.migrate()`（sessionId 键 → 重算新键），
   这是 5.7.2 表格里目前刻意排除的一支。

**影响面**：`ImBindingRegistry.Binding`、`ImBindingStore`（读写 + 迁移）、`ImGateway` 的微信方法组
（约 250 行），以及直接构造 `WeChatCredentialStore` 的测试（`WeChatCredentialStoreTest` /
`WeChatLinkBindingTest` / `WeChatBindingOwnershipTest`，共约 27 例）。另需注意 `WeChatLink` 引擎
内部仍以 `WeChatCredentialStore` 承载自身运行时状态（`NOOP_STORE`），该用途要单独保留或换成
专用的本地容器——这是本方案最容易踩空的一处。

**结论**：这是**格式变更 + 数据迁移 + 测试改造**的成规模重构，与前三处缺陷（一行/一处修正）不同量级。
经决策后已按此方向落地，执行记录见 5.9（其中第 2 条改为通用 `runtime` 扩展位，第 3 条保留了微信自有的归属粒度，第 4 条选择「不持久化游标」）。

### 5.9 三渠道持久化统一（2026-10，续八）—— 删除微信独立 store

#### 5.9.1 目标

用户明确要求：`WeChatCredentialStore` 应删除，微信绑定「全在 `ImBindingStore` 里搞定」；
三个 IM 通道必须用**同一套持久化策略**。项目尚未发布，格式可自由重新设计。

#### 5.9.2 设计（含三个决策点）

| 决策点 | 选择 | 理由 |
|---|---|---|
| 微信主键 | `userKey = ilinkUserId`，`identity = ilinkBotId` | `ilinkUserId` 由服务端按账号签发，天然按账号唯一；`ilinkBotId` 稳定，不像 `botToken` 会随重新授权换发（拿 `botToken` 当 identity 会在重授权后认不出旧条目，留下悬挂绑定 + 僵尸长轮询） |
| 通道私有状态 | `Binding` 新增通用 `runtime` 扩展位（`Map<String,String>`） | 不再为每个渠道新建 store；微信的 `baseUrl` / 回复目标存这里 |
| 落盘出口 | **只有一处**：`ImGateway.save()` 写 `registry.snapshot()` | 彻底避免「同一份绑定两个写入者互相漂移」——旧实现正是引擎写一个文件、网关另写一份 |
| `cursor` | **不持久化** | 已核实：生产中引擎用的是空存储，游标从未真正落盘；空游标语义就是「从当前 seq 开始」。持久化一个永不推进的游标只会误导 |

另：微信**不并入** `ImBindingRegistry.adopt` 的 bot 级归属约束（`findOwner` 只对飞书/钉钉生效）。
微信的归属粒度是**账号**而非 bot，沿用「同一账号在别的**工作区**被占用则冲突」的原语义，
由 `adoptWeChat` 自行判定后经 `registry.put(...)` 写入。

#### 5.9.3 改动明细

| 文件 | 改动 |
|---|---|
| `ImBindingRegistry` | `Binding` 新增 `runtime` + `getRuntime/putRuntime`（`claim` 同步拷贝）；新增 `findAllByUserKey` / `removeAllByUserKey` / `put` |
| `ImBindingStore` | `VERSION` 2→3；读写 `runtime` 子对象；新增 `readLegacyWeChat` 并接入 `migrate()`（`cursor` 不迁移）；新增 `RT_WECHAT_*` 常量 |
| `WeChatBindingSink`（新） | 引擎的工作集持久化接缝；`NOOP` 常量。生产装配只用 `NOOP`，落盘归 ImGateway |
| `WeChatLink` | 字段/构造参数 `WeChatCredentialStore` → `WeChatBindingSink` |
| `WeChatTransport` | `NOOP_STORE` → `WeChatBindingSink.NOOP`（去掉匿名子类） |
| `WeChatClient` | `normalizeBaseUrl` 改 public（ImGateway 需复用信任过滤） |
| `ImGateway` | 删除 `weChatStore` / `harnessEngine` 字段与 `weChatStore()`；`ctor` 去掉微信存储参数；`startWeChat` / `adoptWeChat` / `removeWeChat` / `wechatStatus` 全部改读内存 `registry`；`toWeChatBinding` 改为 `Binding → WeChatBinding`（并对 `baseUrl` 做信任过滤） |
| 删除 | `WeChatCredentialStore`（零残留引用） |

**统一后三渠道对照**：

| 维度 | 飞书 / 钉钉 / 微信（统一后） |
|---|---|
| 存储 | `ImBindingStore` |
| 文件 | `~/.soloncode/channels/im-bindings.json`（单文件） |
| 形状 | 统一 `Binding`（channel + identity + userKey + secret + runtime） |
| 写入出口 | `ImGateway.save()` 一处 |
| 归属约束 | 飞书/钉钉：一个 bot 一个对话（`findOwner`）；微信：一个账号一个对话 |

#### 5.9.4 验证与遗留

**验证**（本地）：`mvn -o clean test-compile` 通过；全量 `mvn -o test` 为 **1006 例，1 例失败**
（`AgentSettingsControllerTest.builtinStillReadableAfterOverrideFileExists`，settings 域，与 IM 无关，历轮一致）；
渠道定向 **72 例全绿**。新增 `ImBindingStoreWeChatTest`（runtime 往返 + v3 版本号 + 旧微信文件迁移留底）。

**测试账目**：移除 `WeChatCredentialStoreTest`（7 例）与 `WeChatBindingOwnershipTest`（3 例）——
两者的被测对象（微信独立 store 的**按工作区合并持久化**语义）已随统一设计消失，属**立意已废**而非为通过而删；
`WeChatLinkBindingTest` 改用内存接缝 `InMemoryWeChatSink`（18 例保留，其被测对象是引擎行为而非持久化）；
`WeChatLegacyBindingTest` 改为写旧版 `wechat-bindings.json` 并由 `ImBindingStore` 迁移后验证（2 例）。

**遗留 / 需真机复测**：
1. 微信重新绑定后长轮询、首条消息应答、重启后恢复（游标不持久化，重启从当前 seq 起）。
2. 旧 `wechat-bindings.json` 迁移路径已在单测覆盖，但**真机升级**需确认留底文件生成且新文件包含微信条目。
3. `im-bindings.json` 升到 v3，旧 v2 文件读取兼容（节点内本就含 appId/appKey/botToken，可无损升级）。

## 六、验证标准

**M1**：迁移单测（旧文件含跨工作区双条目 → openId 唯一 + .bak 存在；同一 openId adopt 两次路由表仅一条指向后者）；双工作区集成（仅一条 `feishu-stream-*` 线程，重启恢复同）；LRU（调小 IDLE_RELEASE_MS，释放后消息触发重开并收到回复）。

**M2**：双工作区浏览器（B 弹迁移确认 → B 收发正常 → A 轮询回落 bound:false）；旧前端无 JS 报错；扫码冲突场景全通。

**M3/M4**：钉钉复跑 M1 用例（appKey 维度）；全仓编译通过；三个测试类新语义通过；grep 无残留引用。

**M5（多 bot 并存）**：同企业同一人用 bot1 / bot2 分别绑定两个对话，两条绑定并存且进程重启后仍在；同一 bot 被第二个用户抢绑时非 force 被拒；持久化后按 identity 精确可查；第二个用户发消息收到「该 bot 已被别的对话占用」而非「还没绑定对话」。

**M6（三渠道持久化统一）**：`grep -r WeChatCredentialStore src/` 为空；仅 `im-bindings.json` 一个文件承载三渠道；
微信重新绑定后收发正常、重启后连接自动恢复；旧 `wechat-bindings.json` 被迁入并留 `.bak`；
同一微信账号在另一工作区绑定 → 非 force 冲突、force 迁移。

**回归风险点**：进程退出时网关统一 `gateway.stop()`（挂 shutdown 钩子）；消息路径的 `getOrCreate` 只发生在消息分发线程，不得出现在 close 回调链（`WebGate.resolveConnections` 注释约束）。
