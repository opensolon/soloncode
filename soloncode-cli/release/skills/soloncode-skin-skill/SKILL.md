---
name: soloncode-skin-skill
description: "Generate installable SolonCode Web UI skin zip packages from natural-language design requests. Use when the user asks to create/make/customize a skin, theme, 皮肤, 换肤, settings panel background, welcome-page look, accent color theme, or produce a .zip skin for SolonCode (settings → general → skin)."
---

# SolonCode Skin Generator

根据用户自然语言需求，生成**可直接上传安装**的 SolonCode Web 皮肤 Zip。  
目标产物始终是可安装的 zip（默认 `.uploads/{name}-yyyyMMddHH.zip`），不是零散 CSS 片段。

**作用范围**：仅 SolonCode Web UI 皮肤系统。  
**禁止**：改 `soloncode-desktop`；生成 js/html/svg/可执行文件；使用保留名。

## When to Use

- 「做个皮肤 / 生成 skin / 换肤 / 主题包」
- 「设置面板加背景 / 像 ddd 那样」
- 「海洋/极光/护眼/高对比风格」
- 「欢迎页别太空 / 主区氛围图」
- 「根据 skin.md / skin-spec 出 zip」

## Critical Rules

1. **交付物必须是 zip**：至少含 `skin.json` + `skin.css`。
2. **`name` 合法**：`^[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}$`，且不是 `default` / `eyecare` / `contrast`。
3. **选择器绑定 name**：`[data-skin="{name}"][data-theme="light|dark"]`，light/dark 成对；模板 id 必须改干净。
4. **图片只写包内相对路径**：优先 `url("./assets/...")`；禁止 `..` 与绝对路径；不推荐外链。
5. **列表预览固定 `preview.png`**（不要只给 webp/jpg）。
6. **限制**：zip ≤ 8MB；单资源 ≤ 2MB（**仅 `skin.css` 例外**；`skin.json` 也算资源）；解压总量 ≤ 32MB。
7. **可读性优先**：输入框/正文/代码块必须可读；功能色（success/danger/git）默认不动。
8. **不要臆造 token**：当前无 `--bg-welcome-image` 等官方欢迎区槽位。
9. **无可靠位图能力时用 CSS 渐变**；有 Pillow 时用本 skill 的 `gen_bg.py` / `gen_preview.py`。
10. **详细规范按需加载**：`references/skin-spec.md`。

## Progressive Loading

| 需要时 | 读取 / 执行 |
|---|---|
| 完整规范 / 变量表 / 排障 | `references/skin-spec.md` |
| **一键生成（推荐）** | `scripts/make_skin.py` |
| 脚手架 | `scripts/scaffold_skin.py` |
| 预览图 | `scripts/gen_preview.py` |
| 有结构背景图 | `scripts/gen_bg.py` |
| 模板 A/B/C/D | `assets/templates/{minimal-accent,ocean-gradient,settings-panel,full-theme}/` |
| 校验 | `scripts/validate_skin.py` |
| 打包 | `scripts/pack_skin.py` |

脚本路径：先定位本 skill 根目录，再 `python3 scripts/...`。  
依赖：校验/打包仅需标准库；**生成 PNG 需要 Pillow**（`pip install pillow`）。

## Workflow

### 0. 优先一键（Agent 默认路径）

用户给了风格词时，直接：

```bash
python3 scripts/make_skin.py \
  --name aurora \
  --recipe c \
  --theme aurora \
  --display-name "极光设置" \
  --with-assets \
  --force
# 默认产出：.uploads/aurora-yyyyMMddHH.zip（如 aurora-2026071715.zip）
```

| 参数 | 说明 |
|---|---|
| `--name` | 皮肤 id |
| `--recipe` | `a/b/c/d/e/f` |
| `--theme` | `ocean/forest/aurora/ink/warm/pink/business` |
| `--with-assets` | 配方 C/D 生成有结构 PNG 并写入 `url("./assets/...")` |
| `--no-preview` | 跳过 preview.png（默认会生成） |
| `--work-dir` | 保留工作目录便于再改 |
| `-o` | 输出 zip（**默认** `.uploads/{name}-yyyyMMddHH.zip`，避免覆盖冲突） |

成功后按「输出协议」回复即可。

### 1. 解析需求

```text
name / displayName
主题词（海洋、极光、墨色…）
主色（未给则 --theme 启发式）
目标区域：main / sidebar / settings / filer / all
是否要位图（要 → --with-assets 或 gen_bg）
是否“只改设置”
明暗偏好（仍必须 light+dark）
输出路径（默认 .uploads/{name}-yyyyMMddHH.zip，已在 .gitignore）
```

默认：合法英文短名 + 配方 B + 无位图 + 不改功能色 + zip 落到 `.uploads/{name}-yyyyMMddHH.zip`。

### 2. 选择配方

```text
只提颜色/风格词 ────────► A  minimal-accent
氛围/渐变/海洋/森林 ─────► B  ocean-gradient
点名设置面板 / 参考 ddd ─► C  settings-panel（建议 --with-assets）
整站完整主题 ───────────► D  full-theme（建议 --with-assets）
高对比/无障碍 ──────────► E  强化文字边框，*-image:none
欢迎页留白/头像 ────────► F  .welcome-view + main 背景
```

| 配方 | 模板 | 重点 |
|---|---|---|
| A | `minimal-accent` | `--accent*` / 用户气泡 |
| B | `ocean-gradient` | 主区/侧栏 CSS 渐变 |
| C | `settings-panel` | `--bg-settings-*` + tabs/卡片半透明 |
| D | `full-theme` | 多区 image/overlay/surface + 设置透图 |
| E | minimal + extras | 高对比，禁用装饰图 |
| F | ocean + extras | 欢迎区布局（非正式槽位） |

### 3. 分步脚手架（需要细改时）

```bash
python3 scripts/scaffold_skin.py \
  --name aurora --recipe c --theme aurora \
  --display-name "极光设置" \
  --out /tmp/aurora-skin \
  --preview --with-assets --force
```

手动补资源：

```bash
python3 scripts/gen_bg.py -o /tmp/aurora-skin/assets/settings-light.png --mode light --theme aurora
python3 scripts/gen_bg.py -o /tmp/aurora-skin/assets/settings-dark.png --mode dark --theme aurora
python3 scripts/gen_preview.py -o /tmp/aurora-skin/preview.png --theme aurora --label aurora
```

**配方 C 透图清单（必须同时满足）**：

1. `--bg-settings-image` 有结构（真图或强渐变，勿近纯色）
2. overlay 偏薄（light 约 0.18–0.35）
3. `--bg-settings-surface: transparent`
4. `--bg-settings` 半透明 rgba
5. 覆盖 `.settings-tab.active` / 卡片，避免实色遮挡

### 4. 校验并打包

```bash
python3 scripts/validate_skin.py /tmp/aurora-skin
mkdir -p .uploads
# 不传 -o 时默认 .uploads/{name}-yyyyMMddHH.zip
python3 scripts/pack_skin.py /tmp/aurora-skin
```

zip 必须**扁平结构**（根上直接 `skin.json`）。  
`pack_skin.py` 会跳过 `assets/README.txt` 等脚手架说明。

### 5. 回复用户（输出协议）

必须包含：

1. Zip 路径（相对当前 workspace，供一键安装）  
2. **一键安装链接**（优先，Web 端点一点即可装）  
3. `name` / `displayName`  
4. 覆盖区域  
5. 是否含位图  
6. 备用手动安装步骤  
7. 验收点（light/dark、目标区域、可读性）

**默认落盘路径**：`.uploads/{name}-yyyyMMddHH.zip`

- 与 Web 聊天附件统一目录一致，已在仓库 `.gitignore`，不污染项目根
- 文件名带本地时戳 `yyyyMMddHH`（到小时），避免同名覆盖；同小时再次生成可加 `--force` 或换 `-o`
- 打包前确保目录存在：`mkdir -p .uploads`（脚本默认也会创建）
- 用户明确要求其它路径时才改；安装链接 `file=` 始终跟**真实相对路径**

**一键安装链接（必须）**：zip 落在当前工作区后，用 Markdown 链接输出。前端会把该链接渲染成**醒目主按钮**，并 `POST` 安装后自动启用：

```markdown
[⬇️ 点击安装皮肤](/web/settings/skins/install?file={相对路径.zip})
```

规则：

- `file` 为 **相对 workspace 的路径**，不要带 `./` 前缀，不要绝对路径
- 例：默认产物 `.uploads/aurora-2026071715.zip` → `file=.uploads/aurora-2026071715.zip`
- **链接文案必须醒目**，推荐固定：`⬇️ 点击安装皮肤`（或 `点击安装并启用皮肤`）；**不要**用「下载」「详情」等弱文案
- 链接单独成段（上下各空一行），放在摘要之后、备用说明之前，方便扫读
- 前端会拦截该链接并 `POST` 安装，成功后自动启用；**不要**只写纯文本路径而不给链接
- 若无法确定相对路径（极少见），才退回手动上传说明

```text
已生成皮肤包：.uploads/aurora-2026071715.zip
- name: aurora
- 展示名: 极光
- 区域: settings 独立背景 + 全局强调色
- 资源: preview.png + assets/settings-*.png

[⬇️ 点击安装皮肤](/web/settings/skins/install?file=.uploads/aurora-2026071715.zip)

备用：设置 → 通用 → 皮肤选择 → 上传皮肤
请检查 light/dark 设置面板；若无变化：强制刷新，或先切默认再切回。
```

## Settings Panel Checklist

背景图“看不见”时按序查：

1. 图是否接近纯色？（要用 `gen_bg.py` 或强渐变）
2. `--bg-settings-overlay` 是否过厚（≥0.6）？
3. `--bg-settings-surface` 是否仍是实色？
4. `--bg-settings` 是否不透明？
5. `.settings-body` / 卡片是否盖实色？
6. `url("./assets/...")` 与 zip 内路径是否一致？
7. 是否覆盖 `.settings-tab.active`？

## Welcome Page Note

欢迎区在 `.main-area` 内，**吃 `--bg-main-*`**，无官方独立背景 token。  
`scaffold/make --recipe f` 会预置 `.welcome-view` 布局增强。

## Color Heuristics

| 主题 | light accent | dark accent |
|---|---|---|
| ocean | `#0b7ea4` | `#3db8d9` |
| forest | `#3f7d4e` | `#6bbf7a` |
| aurora | `#6d5efc` | `#a89bff` |
| ink | `#3f3f46` | `#a1a1aa` |
| warm | `#d97706` | `#fbbf24` |
| pink | `#db2777` | `#f472b6` |
| business | `#4f6ef7` | `#6b8aff` |

脚手架 / make 可用 `--theme` 自动套用 accent / user-msg / thinking-dot。

## Install / Runtime

| 项 | 值 |
|---|---|
| 默认 zip 路径 | `.uploads/{name}-yyyyMMddHH.zip`（与 Web 附件目录一致，gitignore；时戳防冲突） |
| 一键安装 | Markdown 链接 `[⬇️ 点击安装皮肤](/web/settings/skins/install?file=.uploads/{name}-yyyyMMddHH.zip)` → 前端渲染为按钮并 POST 安装启用 |
| 手动安装 | 设置 → 通用 → 皮肤选择 → 上传皮肤 |
| 安装目录 | `~/.soloncode/skins/{name}/` |
| 预置 | `default` / `eyecare` / `contrast` |
| 本地 CSS | `/web/settings/skins/file?name={name}&file=skin.css`（服务端改写相对 url） |
| 同名重装 | 覆盖 |

## Self-Check Before Delivery

- [ ] 含 `skin.json` + `skin.css`
- [ ] name 合法且非保留名
- [ ] CSS `data-skin` == name（无残留模板 id）
- [ ] light + dark 成对
- [ ] 有图则 overlay 薄；透图场景 surface/卡片半透明
- [ ] 无 js/html/svg/exe/sh
- [ ] 相对路径正确；预览用 `preview.png`
- [ ] zip ≤ 8MB；资源 ≤ 2MB（css 除外）
- [ ] 通过 `validate_skin.py`
- [ ] 已产出 zip（优先 `make_skin.py` / `pack_skin.py`，默认 `.uploads/{name}-yyyyMMddHH.zip`）
- [ ] 回复中含醒目一键安装链接：`[⬇️ 点击安装皮肤](/web/settings/skins/install?file=.uploads/…zip)`（链接 `file=` 与真实路径一致）

## Do Not

- 不要只输出 CSS 文本就结束（除非用户只要片段）
- 不要修改 soloncode-desktop
- 不要使用/覆盖预置名
- 不要假设欢迎页官方独立背景变量
- 不要用厚遮罩 + 弱对比图冒充“有背景”
- 不要把模板目录名直接当最终 `name` 交付（除非用户明确要求）
- 不要手写接近纯色的 PNG；用 `gen_bg.py` 或纯 CSS 强渐变
