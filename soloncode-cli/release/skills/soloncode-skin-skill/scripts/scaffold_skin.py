#!/usr/bin/env python3
"""Scaffold a SolonCode skin working directory from a recipe template."""

from __future__ import annotations

import argparse
import json
import re
import shutil
import sys
from pathlib import Path

NAME_RE = re.compile(r"^[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}$")
RESERVED = {"default", "eyecare", "contrast"}

# recipe id -> template directory name under assets/templates/
RECIPE_MAP = {
    "a": "minimal-accent",
    "minimal": "minimal-accent",
    "minimal-accent": "minimal-accent",
    "b": "ocean-gradient",
    "ocean": "ocean-gradient",
    "ocean-gradient": "ocean-gradient",
    "gradient": "ocean-gradient",
    "c": "settings-panel",
    "settings": "settings-panel",
    "settings-panel": "settings-panel",
    "d": "full-theme",
    "full": "full-theme",
    "full-theme": "full-theme",
    # E/F start from closest template then append extras
    "e": "minimal-accent",
    "contrast": "minimal-accent",
    "high-contrast": "minimal-accent",
    "f": "ocean-gradient",
    "welcome": "ocean-gradient",
}

THEME_PRESETS = {
    "ocean": {
        "light": {
            "accent": "#0b7ea4",
            "accent_hover": "#096b8c",
            "accent_light": "#e6f6fb",
            "bg_user_msg": "#e7f5fa",
        },
        "dark": {
            "accent": "#3db8d9",
            "accent_hover": "#2ea3c2",
            "accent_light": "#12303a",
            "bg_user_msg": "#1a4a5a",
        },
    },
    "forest": {
        "light": {
            "accent": "#3f7d4e",
            "accent_hover": "#356b42",
            "accent_light": "#e8f3eb",
            "bg_user_msg": "#e6f2e9",
        },
        "dark": {
            "accent": "#6bbf7a",
            "accent_hover": "#57a866",
            "accent_light": "#1a2e20",
            "bg_user_msg": "#1f3a28",
        },
    },
    "aurora": {
        "light": {
            "accent": "#6d5efc",
            "accent_hover": "#5a4ae6",
            "accent_light": "#ece9ff",
            "bg_user_msg": "#ebe7ff",
        },
        "dark": {
            "accent": "#a89bff",
            "accent_hover": "#c0b6ff",
            "accent_light": "#2a2448",
            "bg_user_msg": "#2a2450",
        },
    },
    "ink": {
        "light": {
            "accent": "#3f3f46",
            "accent_hover": "#27272a",
            "accent_light": "#f4f4f5",
            "bg_user_msg": "#f0f0f2",
        },
        "dark": {
            "accent": "#a1a1aa",
            "accent_hover": "#d4d4d8",
            "accent_light": "#27272a",
            "bg_user_msg": "#3f3f46",
        },
    },
    "warm": {
        "light": {
            "accent": "#d97706",
            "accent_hover": "#b45309",
            "accent_light": "#fff7ed",
            "bg_user_msg": "#ffedd5",
        },
        "dark": {
            "accent": "#fbbf24",
            "accent_hover": "#f59e0b",
            "accent_light": "#3b2a12",
            "bg_user_msg": "#4a3418",
        },
    },
    "pink": {
        "light": {
            "accent": "#db2777",
            "accent_hover": "#be185d",
            "accent_light": "#fdf2f8",
            "bg_user_msg": "#fce7f3",
        },
        "dark": {
            "accent": "#f472b6",
            "accent_hover": "#f9a8d4",
            "accent_light": "#3b1830",
            "bg_user_msg": "#4a2140",
        },
    },
    "business": {
        "light": {
            "accent": "#4f6ef7",
            "accent_hover": "#3b57d9",
            "accent_light": "#eef2ff",
            "bg_user_msg": "#e8edff",
        },
        "dark": {
            "accent": "#6b8aff",
            "accent_hover": "#8aa3ff",
            "accent_light": "#1a2240",
            "bg_user_msg": "#243056",
        },
    },
}


def skill_root() -> Path:
    return Path(__file__).resolve().parent.parent


def templates_root() -> Path:
    return skill_root() / "assets" / "templates"


def resolve_recipe(recipe: str) -> str:
    key = (recipe or "b").strip().lower()
    if key not in RECIPE_MAP:
        known = ", ".join(sorted(set(RECIPE_MAP.values())))
        raise SystemExit(f"未知配方: {recipe!r}；可用: a/b/c/d/e/f 或 {known}")
    return RECIPE_MAP[key]


def validate_name(name: str) -> str:
    name = (name or "").strip()
    if not NAME_RE.match(name):
        raise SystemExit(f"非法 name: {name!r}（需匹配 {NAME_RE.pattern}）")
    if name.lower() in RESERVED:
        raise SystemExit(f"name 为保留预置名，不可使用: {name}")
    return name


def replace_skin_id(css: str, old_name: str, new_name: str) -> str:
    css = css.replace(f'data-skin="{old_name}"', f'data-skin="{new_name}"')
    css = css.replace(f"data-skin='{old_name}'", f"data-skin='{new_name}'")
    # header comment convenience
    css = re.sub(rf"skin:\s*{re.escape(old_name)}\b", f"skin: {new_name}", css, count=1)
    return css


def apply_theme_colors(css: str, theme: str | None) -> str:
    if not theme:
        return css
    key = theme.strip().lower()
    if key not in THEME_PRESETS:
        print(f"WARN\t未知主题色词 {theme!r}，跳过配色覆盖；可用: {', '.join(THEME_PRESETS)}")
        return css
    preset = THEME_PRESETS[key]

    def patch_block(block: str, colors: dict) -> str:
        mapping = {
            "--accent": colors["accent"],
            "--accent-hover": colors["accent_hover"],
            "--accent-light": colors["accent_light"],
            "--bg-user-msg": colors["bg_user_msg"],
            "--thinking-dot": colors["accent"],
            "--text-user-msg-link": colors["accent"],
            "--text-user-msg-link-hover": colors["accent_hover"],
        }
        for var, val in mapping.items():
            block = re.sub(
                rf"({re.escape(var)}\s*:\s*)([^;]+)(;)",
                rf"\g<1>{val}\3",
                block,
                count=1,
            )
        return block

    def replacer(m: re.Match) -> str:
        full = m.group(0)
        theme_attr = m.group(1)
        body = m.group(2)
        colors = preset["light" if theme_attr == "light" else "dark"]
        return full[: full.index("{") + 1] + patch_block(body, colors) + "}"

    # Patch each data-theme block body
    css = re.sub(
        r'\[data-skin="[^"]+"\]\[data-theme="(light|dark)"\]\s*\{([^{}]*(?:\{[^{}]*\}[^{}]*)*)\}',
        replacer,
        css,
        flags=re.S,
    )
    return css


def write_high_contrast_extra(css: str, name: str) -> str:
    """Append recipe E readability boosts if not already present."""
    if ".msg-content" in css and "letter-spacing" in css:
        return css
    extra = f"""

/* recipe E: high-contrast readability boosts */
[data-skin="{name}"][data-theme="light"] {{
  --text-primary: #111827;
  --text-secondary: #374151;
  --border-color: rgba(17, 24, 39, 0.28);
  --bg-main-image: none;
  --bg-sidebar-image: none;
  --bg-settings-image: none;
  --bg-filer-image: none;
  --bg-body-image: none;
}}
[data-skin="{name}"][data-theme="dark"] {{
  --text-primary: #f9fafb;
  --text-secondary: #e5e7eb;
  --border-color: rgba(249, 250, 251, 0.28);
  --bg-main-image: none;
  --bg-sidebar-image: none;
  --bg-settings-image: none;
  --bg-filer-image: none;
  --bg-body-image: none;
}}
"""
    return css.rstrip() + extra


def write_welcome_extra(css: str, name: str) -> str:
    if ".welcome-view" in css:
        return css
    extra = f"""

/* recipe F: welcome layout (unofficial slot; uses main-area background) */
[data-skin="{name}"] .welcome-view {{
  justify-content: flex-start;
  padding-top: 12vh;
  padding-bottom: 48px;
}}
[data-skin="{name}"] .welcome-avatar {{
  width: 96px;
  height: 96px;
  margin-bottom: 20px;
}}
[data-skin="{name}"] .welcome-avatar img {{
  width: 88px;
  height: 88px;
}}
"""
    return css.rstrip() + extra


def ensure_settings_transparency_selectors(css: str, name: str) -> str:
    """Guarantee settings transparency chain selectors exist for recipe C/D."""
    if f'[data-skin="{name}"] .settings-tab.active' in css:
        return css
    extra = f"""

/* settings transparency helpers */
[data-skin="{name}"] .settings-panel {{
  background-color: transparent !important;
}}
[data-skin="{name}"] .settings-tabs {{
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  background: var(--bg-settings-tabs, transparent) !important;
}}
[data-skin="{name}"] .settings-body,
[data-skin="{name}"] .settings-content {{
  background: transparent;
}}
[data-skin="{name}"] .settings-tab.active {{
  background: color-mix(in srgb, var(--accent) 14%, transparent);
  color: var(--accent);
  box-shadow: inset 3px 0 0 var(--accent);
}}
[data-skin="{name}"] .settings-section,
[data-skin="{name}"] .settings-card,
[data-skin="{name}"] .settings-group {{
  background: rgba(255, 255, 255, 0.40);
  border: 1px solid color-mix(in srgb, var(--accent) 16%, transparent);
  backdrop-filter: blur(6px);
  -webkit-backdrop-filter: blur(6px);
}}
[data-skin="{name}"][data-theme="dark"] .settings-section,
[data-skin="{name}"][data-theme="dark"] .settings-card,
[data-skin="{name}"][data-theme="dark"] .settings-group {{
  background: rgba(18, 18, 28, 0.48);
}}
"""
    return css.rstrip() + extra


def scaffold(
    name: str,
    recipe: str,
    out_dir: Path,
    display_name: str | None = None,
    description: str | None = None,
    theme: str | None = None,
    force: bool = False,
    with_preview: bool = False,
    with_assets: bool = False,
) -> Path:
    name = validate_name(name)
    template_name = resolve_recipe(recipe)
    src = templates_root() / template_name
    if not src.is_dir():
        raise SystemExit(f"模板不存在: {src}")

    if out_dir.exists():
        if any(out_dir.iterdir()) and not force:
            raise SystemExit(f"目标目录非空: {out_dir}（加 --force 覆盖）")
        if force:
            shutil.rmtree(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    # copy template files
    for p in src.rglob("*"):
        if p.is_file():
            rel = p.relative_to(src)
            dest = out_dir / rel
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(p, dest)

    # rewrite skin.json
    meta_path = out_dir / "skin.json"
    meta = json.loads(meta_path.read_text(encoding="utf-8"))
    old_name = str(meta.get("name") or template_name)
    meta["name"] = name
    meta["displayName"] = display_name or name
    if description:
        meta["description"] = description
    elif not meta.get("description"):
        meta["description"] = f"SolonCode skin: {name}"
    meta.setdefault("author", "soloncode")
    meta.setdefault("version", "1.0.0")
    meta_path.write_text(json.dumps(meta, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    # rewrite skin.css
    css_path = out_dir / "skin.css"
    css = css_path.read_text(encoding="utf-8")
    css = replace_skin_id(css, old_name, name)
    css = apply_theme_colors(css, theme)

    recipe_key = (recipe or "b").strip().lower()
    if recipe_key in {"e", "contrast", "high-contrast"}:
        css = write_high_contrast_extra(css, name)
    if recipe_key in {"f", "welcome"}:
        css = write_welcome_extra(css, name)
    if template_name in {"settings-panel", "full-theme"} or recipe_key in {
        "c",
        "settings",
        "settings-panel",
        "d",
        "full",
        "full-theme",
    }:
        css = ensure_settings_transparency_selectors(css, name)

    css_path.write_text(css if css.endswith("\n") else css + "\n", encoding="utf-8")

    # optional generated assets / preview (requires Pillow)
    if with_assets and template_name in {"settings-panel", "full-theme"}:
        _generate_optional_assets(out_dir, name, theme or "aurora", template_name)
    elif template_name in {"settings-panel", "full-theme"}:
        assets = out_dir / "assets"
        assets.mkdir(exist_ok=True)
        # only leave helper note when no bitmaps generated
        if not any(assets.glob("*.png")):
            readme = assets / "README.txt"
            if not readme.exists():
                readme.write_text(
                    "Optional: put settings-light.png / settings-dark.png here and set\n"
                    '--bg-settings-image: url("./assets/settings-light.png");\n'
                    "Keep overlay thin (0.18-0.35). Ensure image has visible structure.\n"
                    "Or: python3 scripts/gen_bg.py -o assets/settings-light.png --mode light --theme aurora\n"
                    "Or one-shot: python3 scripts/make_skin.py --name X --recipe c --with-assets\n",
                    encoding="utf-8",
                )

    if with_preview:
        _generate_preview(out_dir, name, theme or "aurora")

    print(f"OK\tscaffolded recipe={template_name} name={name} -> {out_dir}")
    print("NEXT\t1) edit skin.css / optional assets / preview.png")
    print("NEXT\t2) python3 scripts/validate_skin.py <dir>")
    print(f"NEXT\t3) python3 scripts/pack_skin.py <dir>   # default .uploads/{name}-yyyyMMddHH.zip")
    print("TIP\tone-shot: python3 scripts/make_skin.py --name <id> --recipe c --theme aurora --with-assets")
    return out_dir


def _generate_preview(out_dir: Path, name: str, theme: str) -> None:
    script = skill_root() / "scripts" / "gen_preview.py"
    if not script.is_file():
        print("WARN\tgen_preview.py 不存在，跳过 preview")
        return
    import subprocess

    out = out_dir / "preview.png"
    proc = subprocess.run(
        [sys.executable, str(script), "-o", str(out), "--theme", theme, "--label", name],
        cwd=str(skill_root()),
    )
    if proc.returncode != 0:
        print("WARN\tpreview 生成失败（可稍后手动 gen_preview.py）")


def _set_var_in_theme_blocks(css: str, var_name: str, light_value: str, dark_value: str) -> str:
    """Set a CSS custom property inside light/dark [data-theme] blocks only."""

    def replacer(m: re.Match) -> str:
        theme_attr = m.group(1)
        body = m.group(2)
        value = light_value if theme_attr == "light" else dark_value
        if re.search(rf"{re.escape(var_name)}\s*:", body):
            body = re.sub(
                rf"({re.escape(var_name)}\s*:\s*)([^;]+)(;)",
                rf"\g<1>{value}\3",
                body,
                count=1,
            )
        else:
            body = body.rstrip() + f"\n  {var_name}: {value};\n"
        head = m.group(0)[: m.group(0).index("{") + 1]
        return head + body + "}"

    return re.sub(
        r'\[data-skin="[^"]+"\]\[data-theme="(light|dark)"\]\s*\{([^{}]*(?:\{[^{}]*\}[^{}]*)*)\}',
        replacer,
        css,
        flags=re.S,
    )


def _generate_optional_assets(out_dir: Path, name: str, theme: str, template_name: str) -> None:
    script = skill_root() / "scripts" / "gen_bg.py"
    if not script.is_file():
        print("WARN\tgen_bg.py 不存在，跳过 assets")
        return
    import subprocess

    assets = out_dir / "assets"
    assets.mkdir(exist_ok=True)
    light = assets / "settings-light.png"
    dark = assets / "settings-dark.png"
    for mode, path in (("light", light), ("dark", dark)):
        proc = subprocess.run(
            [sys.executable, str(script), "-o", str(path), "--mode", mode, "--theme", theme],
            cwd=str(skill_root()),
        )
        if proc.returncode != 0:
            print(f"WARN\t{mode} 背景生成失败")
            return

    css_path = out_dir / "skin.css"
    css = css_path.read_text(encoding="utf-8")
    css = _set_var_in_theme_blocks(
        css,
        "--bg-settings-image",
        'url("./assets/settings-light.png")',
        'url("./assets/settings-dark.png")',
    )

    if template_name == "full-theme":
        m_light = assets / "main-light.png"
        m_dark = assets / "main-dark.png"
        ok_main = True
        for mode, path in (("light", m_light), ("dark", m_dark)):
            proc = subprocess.run(
                [
                    sys.executable,
                    str(script),
                    "-o",
                    str(path),
                    "--mode",
                    mode,
                    "--theme",
                    theme,
                    "--width",
                    "1440",
                    "--height",
                    "900",
                ],
                cwd=str(skill_root()),
            )
            if proc.returncode != 0:
                ok_main = False
        if ok_main and m_light.is_file() and m_dark.is_file():
            css = _set_var_in_theme_blocks(
                css,
                "--bg-main-image",
                'url("./assets/main-light.png")',
                'url("./assets/main-dark.png")',
            )

    css_path.write_text(css if css.endswith("\n") else css + "\n", encoding="utf-8")
    print(f"OK\tgenerated structured assets for {name}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Scaffold SolonCode skin from recipe template")
    parser.add_argument("--name", required=True, help="skin name (id), e.g. aurora")
    parser.add_argument(
        "--recipe",
        default="b",
        help="recipe: a/b/c/d/e/f or minimal-accent/ocean-gradient/settings-panel (default: b)",
    )
    parser.add_argument(
        "--out",
        default=None,
        help="output directory (default: ./<name>-skin)",
    )
    parser.add_argument("--display-name", default=None, help="displayName for skin.json")
    parser.add_argument("--description", default=None, help="description for skin.json")
    parser.add_argument(
        "--theme",
        default=None,
        help="optional color heuristic: ocean/forest/aurora/ink/warm/pink/business",
    )
    parser.add_argument("--force", action="store_true", help="overwrite non-empty out dir")
    parser.add_argument("--preview", action="store_true", help="also generate preview.png (needs Pillow)")
    parser.add_argument(
        "--with-assets",
        action="store_true",
        help="for recipe c/d: generate structured settings/main PNGs and wire url() (needs Pillow)",
    )
    args = parser.parse_args()

    out = Path(args.out).expanduser().resolve() if args.out else (Path.cwd() / f"{args.name}-skin")
    try:
        scaffold(
            name=args.name,
            recipe=args.recipe,
            out_dir=out,
            display_name=args.display_name,
            description=args.description,
            theme=args.theme,
            force=args.force,
            with_preview=args.preview,
            with_assets=args.with_assets,
        )
    except SystemExit as exc:
        if exc.code not in (0, None):
            print(f"ERROR\t{exc}")
            return 2 if isinstance(exc.code, int) and exc.code == 2 else 1
        return 0
    return 0


if __name__ == "__main__":
    sys.exit(main())
