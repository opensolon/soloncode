# Portal UI 主题自定义

- 状态: Accepted
- 模块: `soloncode-cli`
- 范围: `org.noear.solon.codecli.portal.ui`
- 日期: 2026-04-03

## 1. 目标

Portal UI 的主题不再只靠内置常量切换，而是支持：

- 通过 `soloncode.uiTheme` 指定当前主题
- 通过 `soloncode.uiThemes` 定义用户自己的主题
- 基于内置主题或其他自定义主题做局部覆盖
- 在运行时通过 `/theme` 预览和切换已加载主题

这套机制只作用于 `uiType: new` 的 Portal UI。

## 2. 配置位置

配置沿用现有的 `config.yml` 体系，不新开主题文件：

- 工作区配置：`.soloncode/config.yml`
- 用户全局配置：`~/.soloncode/config.yml`

加载优先级仍然是工作区优先于用户目录。

## 3. 基本配置

```yaml
soloncode:
  uiType: new
  uiTheme: solon
```

内置主题当前包括：

- `solon`
- `opencode`
- `ocean`
- `forest`
- `graphite`
- `sakura`

运行时可用：

- `/theme`
  打开主题选择面板
- `/theme <name>`
  直接切换到指定主题

注意：

- `/theme` 的切换是当前进程内生效，不会自动回写到配置文件
- 自定义主题与默认主题都应该直接写在 `.soloncode/config.yml`
- 如果运行时改了配置文件，需要重新启动 CLI 才会重新加载主题定义

## 4. 自定义主题

```yaml
soloncode:
  uiType: new
  uiTheme: my-theme
  uiThemes:
    my-theme:
      extends: solon
      accent: "#ff7d90"
      accentStrong: "#ff9aa8"
      userTitle: "#ffcad4"
      assistantTitle: "#ff8fa3"
      thinkingTitle: "#c4cddf"
      thinkingBorder: "#a0a8b8"
      toolTitle: "#ff7d90"
      toolMeta: "#a0a8b8"
      toolValue: "#f3f5f7"
      toolResult: "#e7f1fa"
      toolPreview: "#727b89"
      blockTime: "#727b89"
```

语义说明：

- `uiTheme`
  指定启动后默认应用哪个主题
- `uiThemes`
  定义用户主题表，键名就是主题名
- `extends`
  指定继承的基础主题；可继承内置主题，也可继承另一个自定义主题

如果某个 token 没写，会自动回退到 `extends` 指向的基础主题；如果没有写 `extends`，默认回退到 `solon`。

## 5. 支持的 Token

### 5.1 基础色

- `accent`
- `accentStrong`
- `textPrimary`
- `textMuted`
- `textSoft`
- `success`
- `warning`
- `error`
- `separator`

### 5.2 对话语义色

- `userTitle`
- `assistantTitle`
- `thinkingTitle`
- `thinkingBorder`
- `toolTitle`
- `toolMeta`
- `toolValue`
- `toolResult`
- `toolPreview`
- `blockTime`

### 5.3 Markdown 与表格

- `markdownHeader`
- `markdownBold`
- `markdownInlineCode`
- `markdownCodeText`
- `markdownCodeBorder`
- `markdownListBullet`
- `markdownListNumber`
- `markdownBlockquote`
- `markdownRule`
- `tableBorder`
- `tableHeader`

这些 token 会同时影响：

- 顶部正文区
- Markdown 渲染
- 底部输入区
- 列表面板
- 状态栏

其中底部区域仍然遵守统一快照重绘模型，只是样式由同一份主题提供。

## 6. 颜色格式

支持以下格式：

- `#RRGGBB`
- `#RGB`
- `0xRRGGBB`
- `rgb(r,g,b)`
- `r,g,b`

例如：

```yaml
accent: "#ff7d90"
warning: "#fc3"
toolMeta: "148,163,184"
toolResult: "rgb(231,241,250)"
```

无效颜色会被忽略，并回退到继承主题的原值。

## 7. 继承规则

支持三种等价写法：

- `extends`
- `base`
- `parent`

例如：

```yaml
soloncode:
  uiThemes:
    team-base:
      extends: ocean
      accent: "#4da3ff"

    team-review:
      parent: team-base
      toolTitle: "#9ad1ff"
      toolResult: "#d8ecff"
```

规则说明：

- 主题名匹配大小写不敏感
- 自定义主题可以继承自定义主题
- 如果存在循环继承，主题加载会失败并回退为空的自定义主题集

## 8. 实际建议

- 先选一个接近目标风格的内置主题做 `extends`
- 第一轮优先只改语义色，不要一次把所有 token 都改掉
- 如果主要诉求是角色区分，优先调 `userTitle`、`assistantTitle`、`thinkingTitle`、`toolTitle`
- 如果主要诉求是可读性，优先调 `textPrimary`、`textMuted`、`markdownCodeText`、`tableHeader`
- 如果主要诉求是底部状态层次，优先调 `accent`、`accentStrong`、`separator`、`textSoft`

## 9. 与渲染架构的关系

主题系统只负责“颜色语义”，不改变 Portal UI 的统一输出架构：

- 顶部内容区继续走追加写入
- 底部保留区继续走快照重绘
- 真正落屏的唯一出口仍然是 `PortalScreenRenderer`

也就是说，主题自定义是样式层能力，不会破坏你们已经固定下来的统一输出口径。
