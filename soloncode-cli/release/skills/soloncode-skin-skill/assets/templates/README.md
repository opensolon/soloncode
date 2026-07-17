# Skin templates

## 一键（推荐）

```bash
# 在目标 workspace 下执行
# 默认 zip：.uploads/{name}-yyyyMMddHH.zip（如 aurora-2026071715.zip）
python3 scripts/make_skin.py \
  --name aurora --recipe c --theme aurora \
  --with-assets --force
```

## 分步

```bash
python3 scripts/scaffold_skin.py --name aurora --recipe b --theme ocean --out /tmp/aurora-skin --preview
python3 scripts/validate_skin.py /tmp/aurora-skin
# 不传 -o 时默认 .uploads/{name}-yyyyMMddHH.zip
python3 scripts/pack_skin.py /tmp/aurora-skin
```

## 模板表

| Dir | Recipe | Notes |
|---|---|---|
| `minimal-accent/` | A | 仅强调色 |
| `ocean-gradient/` | B | 主区/侧栏纯 CSS 渐变 |
| `settings-panel/` | C | 设置面板重点；`--with-assets` 生成 settings-*.png |
| `full-theme/` | D | 多区完整主题；`--with-assets` 生成 main/settings 图 |

配方 E/F：

- **E** `scaffold/make --recipe e` → 高对比文字 + 禁用装饰图
- **F** `scaffold/make --recipe f` → 追加 `.welcome-view` 布局

## 资源脚本

| 脚本 | 作用 |
|---|---|
| `scripts/gen_preview.py` | 生成列表用 `preview.png` |
| `scripts/gen_bg.py` | 生成**有结构**背景（光斑/光束/光环，避免纯色） |
| `scripts/make_skin.py` | scaffold + 可选资源 + validate + pack |

需要 Pillow：`pip install pillow`。  
`pack_skin.py` 会忽略 `assets/README.txt` 脚手架说明。
