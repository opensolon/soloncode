# Portal UI 统一输出与滚动模型

- 状态: Accepted
- 模块: `soloncode-cli`
- 范围: `org.noear.solon.codecli.portal.ui`
- 日期: 2026-04-02

## 1. 背景与目标

当前 CLI 同时存在这些交互特征：

- AI 正文持续流式输出
- 用户可以持续输入，新输入在任务运行时进入队列，等下一轮自动携带
- 底部存在运行态动画
- 底部存在多行输入、列表选择面板、补全面板、队列提示等多种 UI

单个特性单独出现时通常没有问题，但一旦正文输出、底部动画、输入编辑、列表面板、队列提示同时出现，终端极易出现以下问题：

- 正文和底部互相覆盖
- 光标位置错乱
- 列表面板、输入框、状态栏之间相互踩踏
- 历史内容不能正常进入终端自己的 scrollback
- 鼠标滚轮查看历史时被持续重绘拉回底部

本文档固定的结论只有一句话：

**全局只能有一个地方真正向终端输出；这个输出点在真正落屏前，必须先把底部所有状态完整统计成一个快照，然后按统一规则输出。**

## 2. 核心结论

屏幕必须被明确拆成两个区域：

- 顶部内容区
- 底部保留区

这两个区域的渲染语义完全不同：

- 顶部内容区是追加语义。正文一旦产生，就真实写入终端主缓冲区，让历史自然进入终端 scrollback。
- 底部保留区是重绘语义。它不是历史正文的一部分，而是当前状态的可视化结果，每次都根据最新状态快照进行局部重绘。

换句话说：

- 顶部内容区是“不断新增”
- 底部保留区是“按快照重绘”

这不是可选策略，而是必须遵守的基础模型。

## 3. 唯一输出口径

### 3.1 唯一落屏点

全局只允许一个真实写终端的组件：

- `PortalScreenRenderer`

其他组件都不允许直接向终端写屏。它们只能做两件事：

- 持有或更新自己的状态
- 请求 `PortalScreenRenderer` 进行一次统一渲染

职责边界固定如下：

- `CliShellNew`
  - 负责业务事件、AI 正文事件、任务生命周期、待处理输入队列
- `BottomInputController`
  - 负责输入缓冲、光标、补全、选择面板、队列提示文案
- `StatusBar`
  - 负责把底部各类状态拼成一个 `RenderSnapshot`
- `PortalScreenRenderer`
  - 负责唯一真实落屏

### 3.2 统一输出前必须做的状态统计

在任何一次真正写终端之前，必须先完成以下计算：

1. 读取当前终端尺寸
2. 生成底部 `RenderSnapshot`
3. 统计底部总行数 `footerHeight`
4. 计算内容区底边 `contentBottomRow`
5. 计算底部起始行 `footerTopRow`
6. 计算底部光标落点
7. 在同一把终端锁内完成内容输出和底部重绘

推荐公式如下：

```text
terminalHeight = 当前终端总行数
footerHeight = snapshot.lines.size()
contentTopRow = 1
contentBottomRow = terminalHeight - footerHeight
footerTopRow = contentBottomRow + 1
scrollRegion = [contentTopRow, contentBottomRow]
```

额外约束：

- 必须始终至少保留 1 行内容区
- 底部高度超限时，应截断底部附加面板，而不是挤占全部内容区

## 4. 底部状态模型

底部不是一个单独的“状态栏”，而是一整块可重绘区域。按用户看到的自下而上顺序，底部状态层级为：

1. 状态栏
2. 输入框
3. 列表或补全面板
4. 队列提示
5. 运行态

按 `RenderSnapshot` 实际拼装时的自上而下顺序为：

1. 运行态行
2. 空行
3. 队列行
4. 列表或补全面板行
5. 输入行
6. 空行
7. 状态栏行

这两种描述是同一个布局，只是观察方向不同。

### 4.1 运行态

来源：

- `StatusBar.currentStatus`
- `StatusBar.taskStartTime`
- `StatusBar.stateStartTime`
- `StatusBar.animationTick`

作用：

- 表示 `thinking`、`responding`、`tool:*`、`awaiting approval` 等运行态
- 提供动画 spinner 和阶段耗时信息

### 4.2 队列提示

来源：

- `CliShellNew` 内部的待处理输入队列
- 通过 `BottomInputController.Listener.getPendingInputs()` 暴露给 `BottomInputController`

作用：

- 当任务仍在运行时，用户的新输入不立即发送，而是进入队列
- 队列中的内容以预览形式显示在底部输入区上方

### 4.3 列表或补全面板

来源：

- `BottomInputController` 的 `selectionMode`
- `commandCompletionMode`
- `fileCompletionMode`

作用：

- 展示命令补全
- 展示文件补全
- 展示选择列表

### 4.4 输入框

来源：

- `BottomInputController.inputBuffer`
- `BottomInputController.cursor`
- `BottomInputController.footerNotice`

作用：

- 展示当前输入内容
- 展示输入光标
- 展示临时提示，例如“队列已满”

### 4.5 状态栏

来源：

- `StatusBar.enabledFields`
- `modelName`
- `lastTaskDuration`
- `lastTokens`
- `workDir`
- `version`
- `sessionId`
- `turns`
- `compactMode`

作用：

- 展示模型、耗时、tokens、工作目录、版本、会话、轮次、模式等元信息

### 4.6 特殊覆盖态

`StatusBar.configActive` 属于特殊底部态。

当它激活时，普通底部布局可以被配置面板快照整体替代。这仍然属于“底部快照重绘”，不属于正文区。

## 5. 顶部内容区的规则

顶部内容区必须遵守 append-only 原则：

- 正文产生后，真实写入终端主缓冲区
- 不维护应用内“正文视口”
- 不做“只保留最后 N 行”的假窗口渲染
- 不通过内存中的正文列表反复整屏回放

这里的核心判断是：

**正文历史应该由终端自己保存，而不是由应用重新发明一套历史滚动系统。**

因此：

- 顶部内容区必须使用主缓冲区 `main buffer`
- 不能切到 alternate screen buffer
- 不能依赖应用层键盘翻页来模拟历史滚动

## 6. 底部区的渲染规则

底部区是 sticky footer，其核心不是“固定几行文本”，而是“固定一块不参与正文滚动的重绘区域”。

实现规则如下：

1. 每次渲染前先拿到底部快照
2. 根据快照行数计算底部保留高度
3. 用 scroll region 把内容区和底部分离
4. 正文只在内容区滚动
5. 底部只在保留区内重绘
6. 输入光标只在底部区恢复，不得把正文区光标语义搞乱

推荐使用 ANSI scroll region：

```text
CSI top;bottom r
```

内容区使用：

- `top = 1`
- `bottom = contentBottomRow`

底部区使用：

- `footerTopRow = contentBottomRow + 1`
- `footerBottomRow = terminalHeight`

底部重绘时允许做的事情：

- 定位到底部保留区某一行
- 清理该行
- 输出该行最新内容
- 恢复底部输入光标

底部重绘时不允许做的事情：

- 清空整屏
- 重新回放正文区
- 手工发假滚屏指令推进正文历史
- 修改正文区的滚动语义

## 7. 滚动模型

滚动模型已经确定，不能再走别的路线。

### 7.1 必须满足的要求

- 必须使用鼠标滚轮查看历史
- 不允许依赖键盘在应用内实现历史滚动
- 正文历史必须进入终端原生 scrollback

### 7.2 正确方案

正确方案是：

- 主缓冲区 `main buffer`
- 正文真实输出
- 底部 sticky footer
- 使用 scroll region 隔离正文区与底部区
- 鼠标滚轮交给终端原生 scrollback

### 7.3 明确不要做的事

以下方案全部禁止：

- 整屏统一重绘正文区
- 在应用里维护一套正文 viewport 并自己处理滚动
- 用键盘快捷键模拟历史滚动来替代鼠标滚轮
- 通过 JLine 或应用层接管鼠标滚轮来实现正文历史浏览

原因很简单：

- 这些方案会把“正文历史”重新收回应用内部
- 一旦底部动画、输入编辑、列表面板和正文输出并发，就会重新回到渲染打架的问题

## 8. 当前问题为什么会发生

这次讨论已经明确，当前“历史无法正常滚动”的根因，不是正文没有进入 scrollback，而是 footer 机制仍在持续干扰 scrollback。

典型错误做法包括：

- 在 `PortalScreenRenderer` 中继续维护假的 `contentCursor`
- 使用手工滚屏指令，例如 `ESC[nS`
- 底部区高频率反复定位和重绘
- 运行态动画每 100ms 请求一次渲染
- 输入循环前后频繁触发 `renderNow()`

这些行为叠加后，会造成两类问题：

1. 应用还在把自己当成“整屏控制器”
2. 终端滚回历史时，又被新的 footer 写屏强行拉回底部

因此，本次结论不是“去掉动画”或“去掉输入重绘”，而是：

**任何高频刷新都必须被严格限制在底部保留区内，且不能再带着正文区的伪视口逻辑一起运行。**

## 9. 必须长期遵守的约束

后续任何人改 `portal/ui` 时，都必须遵守以下约束：

- 除 `PortalScreenRenderer` 外，其他类不得直接写终端
- 底部所有可见状态都必须先汇总成快照，再统一渲染
- 顶部正文必须真实进入主缓冲区
- 底部必须是 sticky footer，而不是正文的一部分
- 不允许回退到整屏重绘正文的方案
- 不允许引入应用内历史滚动系统替代终端 scrollback
- 不允许把鼠标滚轮改造成应用层事件来接管正文历史滚动

## 10. 验收标准

满足以下条件，才算该架构真正落地：

1. AI 持续输出正文时，历史内容能进入终端原生 scrollback
2. 用户可以在任务运行期间继续输入，新输入进入队列
3. 运行态动画、列表面板、多行输入、队列提示可同时存在且不互相覆盖
4. 鼠标滚轮可以查看历史内容
5. 不需要键盘历史滚动方案
6. 窗口 resize 后，内容区与底部区边界能重新计算
7. 除显式清屏操作外，系统不会主动清空正文历史

## 11. 一句话原则

顶部正文是“真实追加到终端历史”，底部区域是“基于完整状态快照的局部重绘”；两者都只能经过同一个落屏口径统一协调。
