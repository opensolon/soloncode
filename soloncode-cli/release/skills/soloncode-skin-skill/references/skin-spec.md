### 文档定位

本文面向两类读者：

1. **Agent**：根据用户自然语言需求，自动生成可安装的 SolonCode 皮肤 Zip 包
2. **开发者**：手工制作、调试、分发皮肤

生成目标始终是：**一个可直接上传安装的 `.zip` 皮肤包**，不是只输出零散 CSS 片段。

| 项 | 值 |
|---|---|
| 产品 | SolonCode（Web UI） |
| 默认 zip 路径 | `.uploads/{name}-yyyyMMddHH.zip`（与 Web 附件目录一致，gitignore；时戳防冲突） |
| 一键安装 | Markdown：`[⬇️ 点击安装皮肤](/web/settings/skins/install?file=.uploads/{name}-yyyyMMddHH.zip)`（前端渲染主按钮并 POST 安装启用） |
| 手动安装 | 设置 → 通用 → 皮肤选择 → 上传皮肤 |
| 安装目录 | `~/.soloncode/skins/{name}/` |
| 预置皮肤 | `default` / `eyecare` / `contrast` |
| 作用范围 | 仅 Web 静态资源体系；**不要**改 `soloncode-desktop` |

与工程内规范文档的关系：

- 本文件：`references/skin-spec.md`（skill 内完整规范；内容与官网 `skin.md` 对齐）
- 官网 / 对外说明：仓库根或站点上的 `skin.md`（若有）
- 工程补充说明：`docs/skin-package.md`（若有）
- 以**运行时实现**为准：`SkinService` + `theme.css` + `app-settings-skin.js`

---

### Agent 总则

当用户说「做个皮肤 / 生成 skin / 换个极光风格 / 给设置面板加背景」时，按本文件执行：

1. **先解析需求** → 主题、主色、目标区域、是否要背景图、明暗偏好
2. **再定皮肤 ID** → 合法 `name`（非保留名）
3. **再写文件** → 至少 `skin.json` + `skin.css`；有图则加 `assets/`
4. **最后打包** → 输出 `{name}-yyyyMMddHH.zip`（默认）或用户指定文件名
5. **自检** → 对照文末清单，确保能安装、light/dark 可读

禁止：

- 生成 JS / HTML / SVG / 可执行文件
- 使用预置保留名：`default` / `eyecare` / `contrast`
- 只改颜色却不写 `[data-skin]` 选择器
- 背景图很花却不配合适 `overlay`
- 臆造官方 token（如 `--bg-welcome-image`，当前不存在）
- 把桌面端 `soloncode-desktop` 当作皮肤目标

优先原则：

1. **编码可读性 > 装饰感**
2. **能用渐变就不要硬塞大图**
3. **用户点名的区域重点做，其它区域克制**
4. **light / dark 必须成对**

---

### 用户需求如何映射到皮肤能力

| 用户说法 | Agent 应生成的能力 | 推荐配方 |
|---|---|---|
| 换个蓝/绿/紫风格 | 覆盖 `--accent` 及相关语义色 | A |
| 护眼、柔和、低刺激 | 低饱和主色 + 淡渐变，避免大图 | B |
| 高对比、清晰、无障碍 | 强化文字/边框，**不要装饰大图** | E |
| 主界面有氛围/背景 | `--bg-main-image` + overlay | B / D |
| 侧栏纹理/侧栏背景 | `--bg-sidebar-image` + overlay | D |
| 设置面板单独好看 | `--bg-settings-image` + tabs/卡片半透明 | C |
| 右栏文件树背景 | `--bg-filer-image` | D |
| 只要设置变、聊天不变 | 只覆盖 settings 槽位 | C |
| 只要配色不要图 | 只写颜色；`*-image: none` 或不写 image | A |
| 暗色更好看 / 亮色更好看 | **仍必须同时提供 light 与 dark** | 全部 |
| 像 ddd / 设置极光那样 | 设置区位图 + 薄遮罩 + Tab/卡片半透明 | C |
| 新对话欢迎页别太空 | 改 `.welcome-view` 布局（非正式槽位） | F |
| 没说清楚 | 默认：完整 light+dark + 主区淡渐变 + 强调色 | B |

---

### 需求决策树（Agent 快速分支）

```text
用户要皮肤？
├─ 只提颜色/风格词 ──────────────► 配方 A（可加轻微表面色）
├─ 提氛围/渐变/清新/海洋/森林 ───► 配方 B（纯 CSS 渐变，无 assets）
├─ 点名设置面板 / 参考 ddd ─────► 配方 C（settings 独立图或强渐变）
├─ 要整站换肤 / 完整主题 ───────► 配方 D
├─ 高对比/无障碍/清晰 ──────────► 配方 E（禁用装饰图）
├─ 欢迎页留白/头像/标题 ────────► 配方 F + 主区背景
└─ 超出现有能力（视频背景等） ──► 用现有槽位近似，并明确说明限制
```

信息不足时的默认值：

- `name`：从主题英文短词生成（如 `aurora`、`ink-blue`）
- 同时生成 light + dark
- 不改功能色（success/danger/git 等）
- 无可靠位图生成能力时，**只用 CSS 渐变**
- 输出文件名：`{name}-yyyyMMddHH.zip`（默认落 `.uploads/`，时戳防冲突）

---

### 包结构（必须遵守）

支持两种 Zip 布局。

#### 扁平（推荐）

```text
my-skin.zip
├── skin.json          # 必需
├── skin.css           # 必需
├── preview.png        # 强烈建议（列表预览；见下方注意）
├── README.md          # 可选（安装器允许）
├── LICENSE            # 可选（安装器允许）
└── assets/            # 可选，背景图等
    ├── main-light.webp
    ├── main-dark.webp
    ├── sidebar-light.webp
    ├── settings-light.webp
    └── settings-dark.webp
```

#### 单层目录包装（也支持）

```text
my-skin.zip
└── my-skin/
    ├── skin.json
    ├── skin.css
    ├── preview.png
    └── assets/
```

Agent 打包时优先用**扁平结构**。

安装器还会忽略 `__MACOSX` / 点开头目录；不要依赖多层嵌套。

---

### 限制与校验规则

安装器（`SkinService`）会强制校验，不满足会失败：

| 规则 | 值 | 说明 |
|---|---|---|
| Zip 总大小 | ≤ 8MB | 写入阶段拦截 |
| 解压后总体积 | ≤ 32MB | 防 zip bomb（约 8MB×4） |
| 单资源文件 | ≤ 2MB | **`skin.css` 除外**；`skin.json` 也计入 2MB |
| 允许图片 | `png` / `jpg` / `jpeg` / `webp` / `gif` | 仅这些扩展名可被代理读取 |
| 根目录允许文件 | `skin.css` / `skin.json` / `preview.png\|webp\|jpg\|jpeg` | 其它根文件除 README/LICENSE 外尽量不要放 |
| `assets/` | 仅图片 | 子路径禁止 `..` |
| 禁止文件 | `js` / `html` / `htm` / `svg` / `exe` / `sh` 等 | 安装直接失败 |
| 路径 | 禁止 `..` | 资源必须在皮肤目录内 |
| `name` 格式 | `^[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}$` | 与安装目录名一致 |
| 保留名 | `default` / `eyecare` / `contrast` | 不可安装同名覆盖 |
| 同名重装 | 允许 | 会删除旧目录再拷贝新包 |

背景图建议：

- 优先 **WebP** 或压缩 PNG
- 单图建议 **100KB~800KB**，必须 < 2MB
- 装饰图要**有可见结构，但低细节**（编码场景不是壁纸秀）
- 没有合适位图时，用 `linear-gradient(...)` 代替 `url(...)`

#### preview 特别注意（容易踩坑）

- 元数据检测支持：`preview.png` / `preview.webp` / `preview.jpg`
- **当前设置列表 UI 实际只加载 `preview.png`**
- 因此 Agent **应固定输出 `preview.png`**，不要只给 webp/jpg

---

### skin.json 规范

```json
{
  "name": "aurora",
  "displayName": "极光",
  "description": "紫色主色与淡背景，适合夜间编码氛围",
  "author": "your-name",
  "version": "1.0.0"
}
```

| 字段 | 必需 | 说明 |
|---|---|---|
| `name` | 是 | 唯一 ID；写入 `data-skin`；也是安装目录名 |
| `displayName` | 建议 | UI 展示名，可中文 |
| `description` | 建议 | 一句话说明风格与适用场景 |
| `author` | 可选 | 作者 |
| `version` | 建议 | 语义化版本，如 `1.0.0` |

命名建议：

- 英文短名：`ocean` / `forest` / `aurora` / `graphite`
- 全小写，单词用 `-`：`settings-aurora`
- 避免与预置/常见包冲突
- **`skin.css` 里所有 `data-skin="..."` 必须与 `name` 完全一致**

`name` 非法时，安装器会尝试回退到解压目录名或 zip 文件名；Agent 不要依赖回退，必须写合法 `name`。

---

### skin.css 规范

#### 选择器约定

皮肤挂载方式：

- 启用非默认皮肤时：`body[data-skin="{name}"]`
- 启用默认皮肤时：**移除** `data-skin` 属性
- 明暗：`body[data-theme="light|dark"]`（与皮肤正交）

必须使用：

```css
[data-skin="{name}"][data-theme="light"] { ... }
[data-skin="{name}"][data-theme="dark"]  { ... }
```

可选细节（非官方槽位，可做增强，但要克制）：

```css
[data-skin="{name}"] .settings-panel { ... }
[data-skin="{name}"] .settings-tabs { ... }
[data-skin="{name}"] .settings-body { ... }
[data-skin="{name}"] .settings-content { ... }
[data-skin="{name}"] .settings-tab.active { ... }
[data-skin="{name}"] .welcome-view { ... }
[data-skin="{name}"] .welcome-title { ... }
[data-skin="{name}"] .welcome-avatar { ... }
```

规则：

- **同一皮肤必须同时定义 light / dark**
- 只覆盖需要的变量，未覆盖的沿用 `theme.css`
- 相对路径图片写：`url("./assets/xxx.webp")`
- 服务端会把相对 `url()` 改写为：  
  `/web/settings/skins/file?name={name}&file=assets/xxx.webp`

#### CSS `url()` 改写规则（实现细节）

| 原始写法 | 结果 |
|---|---|
| `url("./assets/a.webp")` | 改写为皮肤文件代理地址 |
| `url("assets/a.webp")` | 同上 |
| `url("data:...")` | 不改写 |
| `url("https://...")` / `http://...` | 不改写（**不推荐**，不稳定） |
| `url("/web/settings/skins/file?...")` | 不改写 |
| `url("none")` / 非 url 的 `linear-gradient(...)` | 不受影响 |
| 含 `..` 的相对路径 | 被替换为 `about:blank`（等于失效） |

结论：Agent **只写皮肤包内相对路径**；不要外链，不要 `..`。

#### 最小可安装模板（纯配色）

```css
/* skin: aurora */
[data-skin="aurora"][data-theme="light"] {
  --accent: #7c5cff;
  --accent-hover: #6b4de6;
  --accent-light: #f0ebff;
  --bg-user-msg: #efe9ff;
  --thinking-dot: #7c5cff;
  --text-user-msg-link: #7c5cff;
  --text-user-msg-link-hover: #6b4de6;
}

[data-skin="aurora"][data-theme="dark"] {
  --accent: #b39cff;
  --accent-hover: #9f86ff;
  --accent-light: #2a2040;
  --bg-user-msg: #2c2448;
  --thinking-dot: #b39cff;
  --text-user-msg-link: #b39cff;
  --text-user-msg-link-hover: #d0c4ff;
}
```

#### 推荐完整模板（配色 + 分区背景）

```css
[data-skin="aurora"][data-theme="light"] {
  /* 强调色 */
  --accent: #7c5cff;
  --accent-hover: #6b4de6;
  --accent-light: #f0ebff;
  --bg-user-msg: #efe9ff;
  --thinking-dot: #7c5cff;
  --text-user-msg-link: #7c5cff;
  --text-user-msg-link-hover: #6b4de6;

  /* 基础表面 */
  --bg-body: #f5f6fb;
  --bg-main: #f7f8fc;
  --bg-sidebar: #f3f4fa;
  --bg-input-box: rgba(255, 255, 255, 0.92);
  --bg-hover: rgba(124, 92, 255, 0.08);
  --border-color: rgba(124, 92, 255, 0.16);

  /* 主区氛围：可用渐变或图片 */
  --bg-main-image: linear-gradient(165deg, #f3f0ff 0%, #f7f8fc 48%, #eef6ff 100%);
  --bg-main-overlay: rgba(255, 255, 255, 0.30);
  --bg-main-surface: transparent;

  /* 侧栏 */
  --bg-sidebar-image: linear-gradient(180deg, #f6f4ff, #f3f4fa);
  --bg-sidebar-overlay: transparent;
  --bg-sidebar-surface: transparent;

  /* 设置弹层（可独立） */
  --bg-settings: rgba(248, 247, 255, 0.66);
  --bg-settings-image: url("./assets/settings-light.webp");
  --bg-settings-overlay: rgba(255, 255, 255, 0.22);
  --bg-settings-surface: transparent;
  --bg-settings-tabs: rgba(255, 255, 255, 0.28);
}

[data-skin="aurora"][data-theme="dark"] {
  --accent: #b39cff;
  --accent-hover: #9f86ff;
  --accent-light: #2a2040;
  --bg-user-msg: #2c2448;
  --thinking-dot: #b39cff;
  --text-user-msg-link: #b39cff;
  --text-user-msg-link-hover: #d0c4ff;

  --bg-body: #10101c;
  --bg-main: #141422;
  --bg-sidebar: #12121e;
  --bg-primary: #161624;
  --bg-secondary: #12121e;
  --bg-input-box: rgba(28, 28, 44, 0.92);
  --bg-hover: rgba(179, 156, 255, 0.12);
  --border-color: rgba(179, 156, 255, 0.22);

  --bg-main-image: linear-gradient(165deg, #12121f 0%, #1a1430 50%, #10141c 100%);
  --bg-main-overlay: rgba(12, 12, 24, 0.40);
  --bg-main-surface: transparent;

  --bg-sidebar-image: linear-gradient(180deg, #12121e, #16122a);
  --bg-sidebar-overlay: transparent;
  --bg-sidebar-surface: transparent;

  --bg-settings: rgba(24, 22, 42, 0.72);
  --bg-settings-image: url("./assets/settings-dark.webp");
  --bg-settings-overlay: rgba(10, 8, 24, 0.28);
  --bg-settings-surface: transparent;
  --bg-settings-tabs: rgba(16, 12, 36, 0.42);
}
```

---

### 分区背景槽位（一等能力）

皮肤通过 CSS 变量控制各区域，互不强制继承图片。

#### 区域对照表

| 区域 | DOM | 底色 | 背景图 | 蒙层 | surface |
|---|---|---|---|---|---|
| 整页 | `body` | `--bg-body` | `--bg-body-image` | `--bg-body-overlay` | — |
| 左侧栏 | `.sidebar` | `--bg-sidebar` | `--bg-sidebar-image` | `--bg-sidebar-overlay` | `--bg-sidebar-surface` |
| 中间主区 | `.main-area` | `--bg-main` | `--bg-main-image` | `--bg-main-overlay` | `--bg-main-surface` |
| 右侧文件树 | `.filer-panel` | `--bg-filer` | `--bg-filer-image` | `--bg-filer-overlay` | `--bg-filer-surface` |
| 设置弹层 | `.settings-panel` | `--bg-settings` | `--bg-settings-image` | `--bg-settings-overlay` | `--bg-settings-surface` |

补充变量：

| 变量 | 说明 |
|---|---|
| `--bg-settings-tabs` | 设置左侧 Tab 栏底色 |

#### 背景布局变量

```text
--bg-image-size
--bg-image-position
--bg-image-repeat

--bg-{region}-image-size
--bg-{region}-image-position
--bg-{region}-image-repeat
```

`region` 取值：`body` | `main` | `sidebar` | `filer` | `settings`

#### 实际渲染模型

```css
background-color: var(--bg-{region}-surface);
background-image:
  linear-gradient(var(--bg-{region}-overlay), var(--bg-{region}-overlay)),
  var(--bg-{region}-image);
```

含义：

1. `image`：`url(...)` / `linear-gradient(...)` / `none`
2. `overlay`：半透明遮罩，保证文字可读
3. `surface`：区域表面色；要透出背景图时设为 `transparent`
4. 右栏/设置**不会**自动继承侧栏背景图；要独立就单独设

#### 设置面板透图关键链路（必读）

要让设置背景图“看得见”，通常需要同时满足：

1. `--bg-settings-image` 有足够结构（不要接近纯色）
2. `--bg-settings-overlay` **偏薄**
3. `--bg-settings-surface: transparent`
4. `.settings-body` / `.settings-content` 不要被实色盖住
5. 列表卡片、分组块使用半透明底
6. 注意：`.settings-tab.active` 默认 `background: var(--bg-settings)`  
   - 若 `--bg-settings` 是不透明实色，激活 Tab 会像“色块”
   - 建议把 `--bg-settings` 设成**半透明**，或额外写：

```css
[data-skin="aurora"] .settings-tab.active {
  background: rgba(255, 255, 255, 0.35);
}
```

#### overlay 经验值（Agent 必读）

| 场景 | light overlay | dark overlay |
|---|---|---|
| 淡渐变、几乎无纹理 | `0.15 ~ 0.30` | `0.20 ~ 0.40` |
| 可见光斑/插画背景 | `0.18 ~ 0.35` | `0.25 ~ 0.45` |
| 细节较多的图 | `0.35 ~ 0.55` | `0.40 ~ 0.60` |
| 高对比皮肤 | 不用图，overlay 保持 `transparent` | 同左 |

失败案例：图本身对比很低，再叠加 `rgba(255,255,255,0.6+)`，看起来像“没有背景图”。  
正确做法：**图要有可见结构 + 遮罩要薄 + 内容卡片半透明**。

历史教训（ddd）：只换了 `url(...)` 但图接近纯色、overlay 过厚，用户会认为“功能坏了”。先查图与 overlay，再查安装链路。

---

### 常用语义色变量

除分区背景外，可覆盖 `theme.css` 中的语义变量。Agent 按需选取，不必全写。

#### 高优先级（几乎每个皮肤都该动）

```text
--accent
--accent-hover
--accent-light
--bg-user-msg
--text-user-msg
--text-user-msg-link
--text-user-msg-link-hover
--thinking-dot
```

#### 表面与文字

```text
--bg-body
--bg-main
--bg-sidebar
--bg-input-box
--bg-hover
--bg-primary
--bg-secondary
--bg-code
--bg-code-block
--text-primary
--text-secondary
--text-sidebar
--text-ai-msg
--text-code
--text-tertiary
--border-color
--border-input
--shadow
--shadow-lg
```

#### 可读性相关（对话区）

```text
--bg-input-box          /* 输入框保持较高不透明度 */
--bg-code / --bg-code-block
--text-primary / --text-ai-msg / --text-code
--reason-group-think-bg / --reason-group-think-border
--tag-bg / --tag-border
```

#### 功能色（默认不要动）

```text
--color-success / --color-danger / --color-warning
--error-bg / --error-border / --error-text
--git-line-* / --git-status-*
--color-wechat / --color-feishu / --color-dingtalk
```

除非用户明确要求“连成功/错误色也统一进主题”，否则保持默认，避免损害状态可辨识性。

---

### 安全可定制 DOM（选择器增强白名单）

官方槽位不够细时，允许用选择器增强。优先只动这些：

| 区域 | 选择器 | 常见用途 |
|---|---|---|
| 设置弹层 | `.settings-panel` | 圆角、描边、阴影、强制透明底 |
| 设置 Tab | `.settings-tabs` | 毛玻璃、分隔、半透明 |
| 设置激活 Tab | `.settings-tab.active` | 避免实色遮罩 |
| 设置内容 | `.settings-body` `.settings-content` | 透明，露出背景图 |
| 设置卡片/列表 | `.settings-section` 及列表项相关类 | 半透明卡片 |
| 欢迎页 | `.welcome-view` `.welcome-title` `.welcome-avatar` | 压缩留白、标题气质 |
| 皮肤卡片 | `.skin-card` | 仅当皮肤预览页也要统一风格时 |

不要大面积改：

- 消息气泡内部结构
- 代码高亮库标签语义
- 布局宽高承重容器（除非用户点名欢迎页留白）

---

### 场景配方（给 Agent 直接套用）

#### 配方 A：仅换强调色

适用：用户只说「紫色一点 / 更商务蓝」

- 覆盖高优先级强调色变量
- 不碰背景图
- 体积最小，最稳

#### 配方 B：主区氛围渐变（无图片文件）

适用：清新、海洋、森林、黄昏等

- `--bg-main-image: linear-gradient(...)`
- `--bg-sidebar-image` 可用更弱渐变
- 配中等偏薄 overlay
- **无需 assets/**

#### 配方 C：设置面板独立背景图

适用：用户点名设置面板、或参考 ddd

- 重点设：
  - `--bg-settings-image`
  - `--bg-settings-overlay`（偏薄）
  - `--bg-settings-surface: transparent`
  - `--bg-settings` 建议半透明
  - `--bg-settings-tabs` 半透明
- 可选选择器增强：
  - `.settings-panel` 边框/阴影
  - `.settings-tabs` 毛玻璃
  - `.settings-body` / `.settings-content` 透明
  - 列表卡片半透明
- 主区保持克制，避免整页都花

#### 配方 D：全局主题皮肤

适用：完整视觉改版

- 模板：`assets/templates/full-theme/`（`scaffold/make --recipe d`）
- 同步覆盖：accent + 各区表面色 + body/main/sidebar/settings/filer 背景
- light/dark 成对设计；设置区走完整透图链路
- 输入框、代码块保持高可读
- 建议 `--with-assets` 生成 main/settings 结构图 + `preview.png`

#### 配方 E：高对比 / 无障碍

适用：清晰、对比、弱视友好

- 强化 `--text-primary` / `--border-color`
- **所有 `*-image: none`**
- 不使用低对比灰字
- 不要毛玻璃和大面积透明

#### 配方 F：欢迎页（新对话）观感调整

说明：欢迎区目前**没有**独立官方背景槽位，它位于 `.main-area` 内，默认吃 `--bg-main-*`。

可做：

```css
[data-skin="aurora"] .welcome-view {
  justify-content: flex-start;
  padding-top: 12vh;
  padding-bottom: 48px;
}
[data-skin="aurora"] .welcome-avatar {
  width: 96px;
  height: 96px;
  margin-bottom: 20px;
}
[data-skin="aurora"] .welcome-title {
  letter-spacing: 0.02em;
}
```

不可假设存在：`--bg-welcome-image`（当前版本无此官方变量）。

---

### 资源文件约定

| 文件 | 用途 |
|---|---|
| `assets/main-light.webp` | 主区亮色背景 |
| `assets/main-dark.webp` | 主区暗色背景 |
| `assets/sidebar-light.webp` | 侧栏亮色 |
| `assets/sidebar-dark.webp` | 侧栏暗色 |
| `assets/filer-light.webp` | 右栏亮色 |
| `assets/filer-dark.webp` | 右栏暗色 |
| `assets/settings-light.webp` | 设置弹层亮色 |
| `assets/settings-dark.webp` | 设置弹层暗色 |
| `preview.png` | 皮肤列表预览（**固定 png**，建议 400~800px 宽） |

规则：

- CSS 中引用必须是相对皮肤根目录：`./assets/...`
- 不要写死 `http(s)://` 外链图
- 不要用超大 base64
- 若只能生成一张图，至少保证目标区域 light+dark 齐全
- 位图要“看得见图样”，不要接近纯色填充

无位图生成能力时：

- 用 CSS 渐变完成氛围
- 在 `description` 注明「纯 CSS 渐变，无位图」

有位图生成能力时的质量门槛：

- 至少能看出光斑/光束/色块边界
- 避开密集文字、密集 UI 截图作背景
- 先定 overlay，再导出图，不要导出后再盲目加厚遮罩

---

### Agent 标准工作流

> 在 **soloncode-skin-skill** 内执行时，优先走脚本，不要手写整包。

#### 步骤 0：一键生成（推荐）

```bash
# skill 根目录（在目标 workspace 下执行）
# 默认产出：.uploads/{name}-yyyyMMddHH.zip（如 aurora-2026071715.zip）
python3 scripts/make_skin.py \
  --name aurora --recipe c --theme aurora \
  --with-assets --force
```

| 脚本 | 作用 |
|---|---|
| `scripts/make_skin.py` | scaffold + 可选 PNG + validate + pack |
| `scripts/scaffold_skin.py` | 从配方模板脚手架（`--preview` / `--with-assets`） |
| `scripts/gen_preview.py` | 生成列表 `preview.png`（需 Pillow） |
| `scripts/gen_bg.py` | 生成有结构背景图，避免近纯色（需 Pillow） |
| `scripts/validate_skin.py` | 安装前校验 |
| `scripts/pack_skin.py` | 打扁平 zip |

配方映射：A=`minimal-accent`，B=`ocean-gradient`，C=`settings-panel`，D=`full-theme`，E/F 在模板上追加。

#### 步骤 1：解析用户需求

提取：

```text
name / displayName
主题关键词（海洋、极光、墨色、春节...）
主色 hex（用户没给就从主题推断 / --theme）
目标区域：main / sidebar / settings / filer / all
是否需要背景图（要 → --with-assets 或 gen_bg）
是否只要设置面板
明暗侧重点
禁止项（不要太花、不要改代码区等）
输出文件名（默认 {name}-yyyyMMddHH.zip）
```

#### 步骤 2：生成文件内容

优先：`make_skin.py` / `scaffold_skin.py`。  
手工时：

1. 写 `skin.json`
2. 写 `skin.css`（选择器 name 一致，light/dark 成对）
3. 如需图：用 `gen_bg.py` 生成 `assets/*`（单文件 < 2MB），或强 CSS 渐变
4. 用 `gen_preview.py` 输出 `preview.png`

#### 步骤 3：打包

```bash
python3 scripts/validate_skin.py /path/to/skin-dir
# 不传 -o 时默认 .uploads/{name}-yyyyMMddHH.zip
python3 scripts/pack_skin.py /path/to/skin-dir
```

也可：

```bash
zip -r name.zip skin.json skin.css preview.png assets
```

zip 内须**扁平**可见 `skin.json` 与 `skin.css`。最终交付物是 **`.zip` 文件路径**。

#### 步骤 4：自检清单（发布前必过）

- [ ] 含 `skin.json` + `skin.css`
- [ ] `name` 合法且非保留名（不是 default/eyecare/contrast）
- [ ] CSS 中 `data-skin` 等于 `name`（无残留模板 id）
- [ ] 同时有 light / dark
- [ ] 有背景图时必有合适 overlay（设置透图 overlay 宜 < 0.6）
- [ ] 要透图时 `surface` / 内容容器避免大面积实色遮挡
- [ ] 设置面板场景检查了 `--bg-settings` 与 `.settings-tab.active`
- [ ] 图片格式合法，单文件 ≤ 2MB，zip ≤ 8MB
- [ ] 无 js/html/svg/exe/sh
- [ ] 相对路径 `url("./assets/...")` 正确
- [ ] 预览图若提供，文件名为 `preview.png`
- [ ] 编码场景下正文、输入框、代码块仍可读
- [ ] 通过 `scripts/validate_skin.py`

#### 步骤 5：向用户说明如何安装

优先输出**一键安装链接**（zip 默认在 `.uploads/`，相对当前 workspace；前端渲染为醒目主按钮）：

```markdown
[⬇️ 点击安装皮肤](/web/settings/skins/install?file=.uploads/settings-aurora-2026071715.zip)
```

规则：`file` 为相对 workspace 路径，不要 `./` 前缀，不要绝对路径；默认推荐 `.uploads/{name}-yyyyMMddHH.zip`；链接文案用 `⬇️ 点击安装皮肤`，并单独成段。  
备用手动：设置 → 通用 → 皮肤选择 → 上传皮肤 → 启用。  
验收：切换 light/dark；若像旧版则强制刷新或先切默认再切回。同名重装覆盖。

---

### 运行时行为（Agent 排障用）

| 行为 | 说明 |
|---|---|
| 预置加载 | `/skin/{name}/skin.css` |
| 本地加载 | `/web/settings/skins/file?name={name}&file=skin.css`（服务端改写 url） |
| 本地资源 | `/web/settings/skins/file?name={name}&file=assets/...` |
| 启用持久化 | 后端 `settings.general.activeSkin` + 前端 `localStorage['chat-skin']` |
| 启动顺序 | 先 localStorage 快应用，再与服务端 list 对齐 |
| 默认皮肤 | 清除 `data-skin`，并回到预置 default css |
| 卸载 | 仅 local；预置不可卸载 |
| 卸载当前皮肤 | 前端会回退到可用皮肤/默认（以产品实现为准） |
| 重装同名 | 覆盖安装 |

前端关键对象（实现参考，不必写入皮肤包）：

- `window.applySkin(name, { source, forceLocal })`
- `window.BUILTIN_SKINS`
- `window.LOCAL_SKINS`

---

### 完整示例：设置面板主题皮肤

目标：主区克制，设置弹层有独立背景氛围。

`skin.json`：

```json
{
  "name": "settings-aurora",
  "displayName": "设置极光",
  "description": "设置面板独立极光氛围；主对话区保持克制",
  "author": "soloncode",
  "version": "1.0.0"
}
```

`skin.css` 关键片段：

```css
[data-skin="settings-aurora"][data-theme="light"] {
  --accent: #6d5efc;
  --accent-hover: #5a4ae6;
  --accent-light: #ece9ff;
  --bg-user-msg: #ebe7ff;
  --thinking-dot: #6d5efc;

  --bg-main-image: linear-gradient(165deg, #f7f8fd 0%, #eef1ff 48%, #f5f7fc 100%);
  --bg-main-overlay: rgba(255, 255, 255, 0.28);
  --bg-main-surface: transparent;

  --bg-settings: rgba(248, 247, 255, 0.60);
  --bg-settings-image: url("./assets/settings-light.png");
  --bg-settings-overlay: rgba(255, 255, 255, 0.22);
  --bg-settings-surface: transparent;
  --bg-settings-tabs: rgba(255, 255, 255, 0.28);
}

[data-skin="settings-aurora"][data-theme="dark"] {
  --accent: #a89bff;
  --accent-hover: #c0b6ff;
  --accent-light: #2a2448;
  --bg-user-msg: #2a2450;
  --thinking-dot: #a89bff;

  --bg-main-image: linear-gradient(165deg, #12121f 0%, #17142a 50%, #10141c 100%);
  --bg-main-overlay: rgba(12, 12, 24, 0.35);
  --bg-main-surface: transparent;

  --bg-settings: rgba(24, 22, 42, 0.66);
  --bg-settings-image: url("./assets/settings-dark.png");
  --bg-settings-overlay: rgba(10, 8, 24, 0.28);
  --bg-settings-surface: transparent;
  --bg-settings-tabs: rgba(16, 12, 36, 0.42);
}

[data-skin="settings-aurora"] .settings-panel {
  background-color: transparent !important;
}

[data-skin="settings-aurora"] .settings-tabs {
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  background: var(--bg-settings-tabs) !important;
}

[data-skin="settings-aurora"] .settings-body,
[data-skin="settings-aurora"] .settings-content {
  background: transparent;
}

[data-skin="settings-aurora"] .settings-tab.active {
  background: rgba(109, 94, 252, 0.12);
}
```

---

### 完整示例：无图片的海洋渐变皮肤

```json
{
  "name": "ocean",
  "displayName": "海洋",
  "description": "青蓝主色与淡海蓝渐变，纯 CSS 无位图",
  "author": "soloncode",
  "version": "1.0.0"
}
```

```css
[data-skin="ocean"][data-theme="light"] {
  --accent: #0b7ea4;
  --accent-hover: #096b8c;
  --accent-light: #e6f6fb;
  --bg-user-msg: #e7f5fa;
  --thinking-dot: #0b7ea4;
  --bg-main-image: linear-gradient(160deg, #e8f7fc 0%, #f5f8ff 45%, #eef6f4 100%);
  --bg-main-overlay: rgba(255, 255, 255, 0.30);
  --bg-main-surface: transparent;
  --bg-sidebar-image: linear-gradient(180deg, #f3fafc, #ffffff);
  --bg-sidebar-overlay: transparent;
}

[data-skin="ocean"][data-theme="dark"] {
  --accent: #3db8d9;
  --accent-hover: #2ea3c2;
  --accent-light: #12303a;
  --bg-user-msg: #1a4a5a;
  --thinking-dot: #3db8d9;
  --bg-main-image: linear-gradient(160deg, #0d1b24 0%, #12182a 50%, #0f1f1c 100%);
  --bg-main-overlay: rgba(10, 14, 24, 0.42);
  --bg-main-surface: transparent;
  --bg-sidebar-image: linear-gradient(180deg, #121c28, #16162a);
  --bg-sidebar-overlay: transparent;
}
```

说明：`ocean` 可作为**本地包名**；预置里没有 ocean，只有 `default` / `eyecare` / `contrast`。

---

### 颜色推导启发式

用户只给主题词时，Agent 可按此推断：

| 主题词 | 建议主色 light | 建议主色 dark | 背景倾向 |
|---|---|---|---|
| 海洋 / 青蓝 | `#0b7ea4` | `#3db8d9` | 蓝青渐变 |
| 森林 / 护眼 | `#3f7d4e` | `#6bbf7a` | 雾绿渐变 |
| 极光 / 紫 | `#6d5efc` | `#a89bff` | 紫青光斑 |
| 墨色 / 石墨 | `#3f3f46` | `#a1a1aa` | 近无图，重对比 |
| 日落 / 暖橙 | `#d97706` | `#fbbf24` | 暖色淡渐变 |
| 樱桃 / 粉 | `#db2777` | `#f472b6` | 浅粉表面 |
| 商务 / 默认感 | `#4f6ef7` | `#6b8aff` | 轻量或不铺图 |

推导后必须同步：

- `--accent-hover`：比 accent 略深/略亮
- `--accent-light`：极淡背景色
- `--bg-user-msg`：与 accent 同色相、低饱和
- `--thinking-dot`：通常等于 accent
- dark 模式下用户气泡若接近 accent 实色，注意 `--text-user-msg` 对比度

可读性底线（经验）：

- 正文与背景避免接近同亮度
- 输入框不透明度建议 ≥ 0.88
- 代码块保持独立深/浅底，不要强行全透明

---

### 常见错误与修复

| 现象 | 原因 | 修复 |
|---|---|---|
| 安装失败：找不到文件 | zip 多层嵌套或缺文件 | 扁平或单层目录内有 `skin.json`/`skin.css` |
| 安装失败：保留名 | name 为 default/eyecare/contrast | 换名 |
| 安装失败：资源过大 | 单文件 >2MB 或 zip>8MB | 压缩/转 webp |
| 安装失败：非法类型 | 含 js/html/svg 等 | 删除这些文件 |
| 上传成功但看不出变化 | 未启用，或 `data-skin` ≠ `name` | 对齐选择器并点击启用 |
| 只有亮色正常 | 没写 dark | 补 `[data-theme="dark"]` |
| 背景图“没有” | 图对比太低 / overlay 太厚 / surface 实色遮挡 | 增强图结构、减 overlay、surface/内容区透明 |
| 设置图仍不明显 | `--bg-settings` 实色，或 tab/卡片不透明 | 半透明化 settings 相关层 |
| 文字发虚看不清 | overlay 太薄或透明卡片过多 | 提高 overlay，输入框/消息保持足够实色 |
| 图片 404 | 路径不是 `./assets/...` 或文件没打进 zip | 检查打包内容与 url |
| 预览不显示 | 只提供了 webp/jpg | 改为 `preview.png` |
| 样式像旧版 | 缓存 | 强制刷新；或先切默认再切回 |

---

### 输出协议（建议 Agent 遵守）

生成完成后，回复用户时包含：

1. **Zip 路径**（相对 workspace，默认 `.uploads/{name}-yyyyMMddHH.zip`）
2. **一键安装链接**（必须，醒目文案）：`[⬇️ 点击安装皮肤](/web/settings/skins/install?file=.uploads/{name}-yyyyMMddHH.zip)`
3. **皮肤 name / displayName**
4. **覆盖了哪些区域**（main/sidebar/settings/...）
5. **是否含位图**
6. **备用手动安装步骤**
7. **建议验收点**（light/dark、设置面板、对话可读性）

示例回复结构：

```text
已生成皮肤包：.uploads/settings-aurora-2026071715.zip
- name: settings-aurora
- 展示名: 设置极光
- 区域: settings 独立背景 + 全局强调色；main 仅淡渐变
- 资源: assets/settings-light.png, assets/settings-dark.png, preview.png

[⬇️ 点击安装皮肤](/web/settings/skins/install?file=.uploads/settings-aurora-2026071715.zip)

备用：设置 → 通用 → 皮肤选择 → 上传皮肤
请分别检查 light/dark 下设置面板背景与正文可读性。
若看不出变化：强制刷新，或先切默认再切回。
```

---

### 版本边界（当前）

当前皮肤系统支持：

- 预置皮肤 + 本地 Zip 安装/启用/卸载
- 聊天一键安装：`POST /web/settings/skins/install?file={workspace相对zip}`（推荐 `.uploads/{name}-yyyyMMddHH.zip`；前端把 Markdown 链接渲成主按钮）
- 分区背景槽位：body / sidebar / main / filer / settings
- light/dark 正交切换
- 本地资源代理与 CSS `url()` 改写
- 同名覆盖安装
- 选择器级细节增强（非官方槽位）

当前不要假设支持：

- 欢迎页独立官方背景 token（如 `--bg-welcome-image`）
- 视频 / Lottie / 动态着色脚本
- 在线皮肤市场自动依赖远端 CSS
- 在皮肤包内执行脚本
- 用 zip 覆盖预置名
- 列表预览自动识别任意文件名（请用 `preview.png`）

若用户需求超出边界：用现有槽位近似实现，并明确说明限制。

---

### 一页速查

```text
必做：skin.json + skin.css + 合法 name + light/dark
背景：--bg-{region}-image + overlay + 必要时 surface:transparent
设置透图：image 有结构 + 薄 overlay + surface 透明 + 卡片/Tab 半透明
图片：./assets/* ，png/jpg/webp/gif，单文件≤2MB，zip≤8MB
预览：preview.png（不要只给 webp）
选择器：[data-skin="{name}"][data-theme="light|dark"]
保留名：default / eyecare / contrast
安装：设置 → 通用 → 皮肤选择 → 上传皮肤
原则：氛围服务于编码可读性，不是壁纸展示
```
