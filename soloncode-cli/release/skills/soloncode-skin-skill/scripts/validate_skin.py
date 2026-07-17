#!/usr/bin/env python3
"""Validate a SolonCode skin directory or zip before install/delivery."""

from __future__ import annotations

import argparse
import json
import re
import sys
import tempfile
import zipfile
from pathlib import Path

RESERVED = {"default", "eyecare", "contrast"}
NAME_RE = re.compile(r"^[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}$")
ALLOWED_IMAGE_EXT = {".png", ".jpg", ".jpeg", ".webp", ".gif"}
FORBIDDEN_EXT = {
    ".js",
    ".html",
    ".htm",
    ".svg",
    ".exe",
    ".sh",
    ".bat",
    ".cmd",
    ".dll",
    ".so",
    ".dylib",
    ".jar",
    ".class",
    ".php",
    ".py",
    ".rb",
    ".pl",
}
# soft-forbidden in assets/root even if not hard-blocked by server
DISCOURAGED_EXT = {".md", ".txt", ".css"}  # only warn outside known roots
MAX_ZIP_BYTES = 8 * 1024 * 1024
MAX_FILE_BYTES = 2 * 1024 * 1024
MAX_UNZIPPED_BYTES = 32 * 1024 * 1024
ROOT_ALLOW = {
    "skin.json",
    "skin.css",
    "preview.png",
    "preview.webp",
    "preview.jpg",
    "preview.jpeg",
    "readme.md",
    "license",
    "license.txt",
    "license.md",
}
# unofficial tokens that agents sometimes invent
UNKNOWN_TOKEN_HINTS = (
    "--bg-welcome-image",
    "--bg-welcome-overlay",
    "--bg-chat-image",
    "--welcome-bg",
)


class Finding(list):
    def error(self, msg: str) -> None:
        self.append(("ERROR", msg))

    def warn(self, msg: str) -> None:
        self.append(("WARN", msg))

    def ok(self, msg: str) -> None:
        self.append(("OK", msg))


def load_json(path: Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:  # noqa: BLE001
        raise ValueError(f"skin.json 无法解析: {exc}") from exc


def iter_files(root: Path):
    for p in sorted(root.rglob("*")):
        if p.is_file():
            yield p


def parse_rgba_alpha(value: str) -> float | None:
    m = re.search(
        r"rgba\(\s*[\d.]+\s*,\s*[\d.]+\s*,\s*[\d.]+\s*,\s*([01](?:\.\d+)?|\.\d+)\s*\)",
        value,
        flags=re.I,
    )
    if not m:
        return None
    try:
        return float(m.group(1))
    except ValueError:
        return None


def extract_var_value(css: str, var_name: str) -> list[str]:
    return re.findall(rf"{re.escape(var_name)}\s*:\s*([^;}}]+);", css, flags=re.I)


def validate_tree(root: Path, findings: Finding, zip_size: int | None = None) -> str | None:
    skin_json = root / "skin.json"
    skin_css = root / "skin.css"

    if not skin_json.is_file():
        # allow one-level wrapper
        subdirs = [d for d in root.iterdir() if d.is_dir() and not d.name.startswith(".") and d.name != "__MACOSX"]
        if len(subdirs) == 1 and (subdirs[0] / "skin.json").is_file():
            root = subdirs[0]
            skin_json = root / "skin.json"
            skin_css = root / "skin.css"
            findings.warn(f"检测到单层目录包装: {root.name}/ （可用，但更推荐扁平结构）")
        else:
            findings.error("缺少 skin.json（扁平根目录或单层子目录内）")
            return None

    if not skin_css.is_file():
        findings.error("缺少 skin.css")
        return None

    findings.ok(f"找到皮肤根目录: {root}")

    try:
        meta = load_json(skin_json)
    except ValueError as exc:
        findings.error(str(exc))
        return None

    if not isinstance(meta, dict):
        findings.error("skin.json 必须是 JSON 对象")
        return None

    name = str(meta.get("name") or "").strip()
    if not name:
        findings.error("skin.json.name 不能为空")
        return None
    if not NAME_RE.match(name):
        findings.error(f"name 非法: {name!r}（需匹配 {NAME_RE.pattern}）")
    elif name.lower() in RESERVED:
        findings.error(f"name 为保留预置名，不可使用: {name}")
    else:
        findings.ok(f"name 合法: {name}")

    if not meta.get("displayName"):
        findings.warn("建议提供 displayName")
    if not meta.get("version"):
        findings.warn("建议提供 version")

    # skin.json itself is subject to 2MB asset limit on server (except skin.css)
    json_size = skin_json.stat().st_size
    if json_size > MAX_FILE_BYTES:
        findings.error(f"skin.json 超过 2MB: {json_size} bytes")

    css_raw = skin_css.read_text(encoding="utf-8", errors="replace")
    # strip comments before structural checks so examples in comments don't false-fail
    css = re.sub(r"/\*.*?\*/", "", css_raw, flags=re.S)
    if name and f'data-skin="{name}"' not in css and f"data-skin='{name}'" not in css:
        findings.error(f'skin.css 中未找到 data-skin="{name}"')
    else:
        findings.ok("data-skin 与 name 对齐")

    # detect leftover template ids still referenced
    other_ids = set(re.findall(r'data-skin\s*=\s*["\']([^"\']+)["\']', css))
    if name:
        leftovers = sorted(x for x in other_ids if x != name)
        if leftovers:
            findings.error(f"skin.css 仍包含其它 data-skin id（模板未改干净）: {', '.join(leftovers)}")

    has_light = bool(re.search(r'data-theme\s*=\s*["\']light["\']', css))
    has_dark = bool(re.search(r'data-theme\s*=\s*["\']dark["\']', css))
    if not has_light or not has_dark:
        findings.error('必须同时包含 [data-theme="light"] 与 [data-theme="dark"] 规则')
    else:
        findings.ok("light/dark 成对")

    for token in UNKNOWN_TOKEN_HINTS:
        if token in css:
            findings.warn(f"检测到非官方/当前不存在的 token: {token}（不会生效）")

    # url() checks
    for m in re.finditer(r"url\(\s*(['\"]?)([^'\")]+)\1\s*\)", css, flags=re.I):
        raw = m.group(2).strip()
        if raw.startswith("data:") or raw in {"none", "about:blank"}:
            continue
        if raw.startswith(("http://", "https://", "//")):
            findings.warn(f"外链 url 不推荐（安装器不改写）: {raw}")
            continue
        # bare absolute path
        if raw.startswith("/"):
            findings.error(f"禁止绝对路径 url: {raw}")
            continue
        normalized = raw.replace("\\", "/")
        if ".." in normalized.split("/"):
            findings.error(f"url 含 '..' 会被改写失效: {raw}")
            continue
        rel = raw[2:] if raw.startswith("./") else raw
        asset = root / rel
        if not asset.is_file():
            findings.error(f"css 引用文件不存在: {raw}")
        else:
            findings.ok(f"资源存在: {rel}")
            # assets path best practice
            if not rel.startswith("assets/") and not rel.startswith("preview"):
                findings.warn(f"建议把背景图放到 assets/ 下: {rel}")

    # settings transparency heuristics (only when image is actually decorative)
    settings_images = [v.strip().lower() for v in extract_var_value(css, "--bg-settings-image")]
    has_settings_decor = any(v and v not in {"none", "initial", "unset"} for v in settings_images)
    
    # theme-paired asset wiring: light block should not point to *-dark.* and vice versa
    for theme_attr, bad_token, good_hint in (
        ("light", "-dark.", "settings-light / main-light"),
        ("dark", "-light.", "settings-dark / main-dark"),
    ):
        blocks = re.findall(
            rf'\[data-skin=["\'][^"\']+["\']\]\[data-theme=["\']{theme_attr}["\']\]\s*\{{([^{{}}]*(?:\{{[^{{}}]*\}}[^{{}}]*)*)\}}',
            css,
            flags=re.S | re.I,
        )
        for body in blocks:
            for m in re.finditer(r"url\(\s*['\"]?([^'\")]+)['\"]?\s*\)", body, flags=re.I):
                rel = m.group(1).strip().lower()
                if bad_token in rel and ("settings" in rel or "main" in rel or "sidebar" in rel):
                    findings.error(
                        f'data-theme="{theme_attr}" 引用了疑似另一主题的资源: {m.group(1)}（建议用 {good_hint}）'
                    )
    
    if has_settings_decor:
        surface_vals = [v.strip().lower() for v in extract_var_value(css, "--bg-settings-surface")]
        if not surface_vals:
            findings.warn("设置背景图场景建议显式设置 --bg-settings-surface: transparent")
        elif all(v not in {"transparent", "none"} and not v.startswith("rgba(") for v in surface_vals):
            findings.warn(f"--bg-settings-surface 可能过实: {surface_vals[0]}")
            
        for ov in extract_var_value(css, "--bg-settings-overlay"):
            alpha = parse_rgba_alpha(ov)
            if alpha is not None and alpha >= 0.6:
                findings.warn(f"settings overlay 可能过厚（alpha={alpha}），背景图容易看不清")
            
        for bg in extract_var_value(css, "--bg-settings"):
            bg_l = bg.strip().lower()
            if bg_l.startswith("#") or re.match(r"rgb\(\s*\d", bg_l):
                findings.warn("--bg-settings 建议使用半透明 rgba，避免 active tab 变成实色块")
            alpha = parse_rgba_alpha(bg)
            if alpha is not None and alpha >= 0.92:
                findings.warn(f"--bg-settings 几乎不透明（alpha={alpha}），可能遮挡背景图")
            
        if ".settings-tab.active" not in css and "settings-tab.active" not in css:
            findings.warn("设置透图场景建议覆盖 .settings-tab.active，避免默认实色 active 底")

        # low-contrast solid-ish gradient heuristic: single color stops only
        for img in settings_images:
            if "linear-gradient" in img:
                stops = re.findall(r"#(?:[0-9a-fA-F]{3,8})", img)
                if len(stops) >= 2 and len(set(s.lower() for s in stops)) == 1:
                    findings.warn("settings 渐变色标几乎相同，背景可能看起来像纯色")
    
    # accent presence (soft)
    if "--accent" not in css:
        findings.warn("未设置 --accent，观感可能几乎不变")
    
    total_size = 0
    preview_png = root / "preview.png"
    if not preview_png.is_file():
        for alt in ("preview.webp", "preview.jpg", "preview.jpeg"):
            if (root / alt).is_file():
                findings.warn(f"发现 {alt}，但列表 UI 当前只稳定加载 preview.png，建议改为 preview.png")
                break
        else:
            findings.warn("未提供 preview.png（列表无预览图）")
    else:
        findings.ok("存在 preview.png")
        if preview_png.stat().st_size < 200:
            findings.warn("preview.png 过小，可能是空图/损坏")

    for path in iter_files(root):
        rel = path.relative_to(root).as_posix()
        if rel.startswith("__MACOSX/") or path.name.startswith("._") or path.name == ".DS_Store":
            findings.warn(f"建议删除 Mac 垃圾文件: {rel}")
            continue
        size = path.stat().st_size
        total_size += size
        ext = path.suffix.lower()

        if ext in FORBIDDEN_EXT:
            findings.error(f"禁止文件类型: {rel}")

        # root files
        if "/" not in rel:
            if rel.lower() not in ROOT_ALLOW and path.name not in {"skin.css", "skin.json"}:
                findings.warn(f"根目录非常规文件: {rel}")
        elif rel.startswith("assets/"):
            # allow tiny helper text in template scaffolds
            if path.name.lower() in {"readme.txt", "readme.md"}:
                findings.warn(f"assets 内说明文件不会被皮肤系统使用: {rel}")
            elif ext and ext not in ALLOWED_IMAGE_EXT:
                findings.error(f"assets 仅允许图片: {rel}")
        else:
            findings.warn(f"非常规路径文件: {rel}")

        # server: skin.css exempt from 2MB; skin.json is NOT exempt
        if path.name != "skin.css" and size > MAX_FILE_BYTES:
            findings.error(f"单文件超过 2MB: {rel} ({size} bytes)")

    if total_size > MAX_UNZIPPED_BYTES:
        findings.error(f"解压总体积超限: {total_size} > {MAX_UNZIPPED_BYTES}")
    else:
        findings.ok(f"解压体积可接受: {total_size} bytes")

    if zip_size is not None:
        if zip_size > MAX_ZIP_BYTES:
            findings.error(f"zip 超过 8MB: {zip_size}")
        else:
            findings.ok(f"zip 体积可接受: {zip_size} bytes")

    return name


def extract_zip(zip_path: Path, dest: Path) -> None:
    with zipfile.ZipFile(zip_path, "r") as zf:
        # basic zip-slip guard
        dest_norm = dest.resolve()
        for info in zf.infolist():
            name = info.filename
            if not name or ".." in name.replace("\\", "/").split("/"):
                continue
            target = (dest / name).resolve()
            if not str(target).startswith(str(dest_norm)):
                continue
            if info.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with zf.open(info) as src, open(target, "wb") as out:
                    out.write(src.read())


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate SolonCode skin dir/zip")
    parser.add_argument("path", help="skin directory or .zip")
    args = parser.parse_args()

    target = Path(args.path).expanduser().resolve()
    findings = Finding()

    if not target.exists():
        print(f"ERROR\t路径不存在: {target}")
        return 2

    skin_name = None
    if target.is_file() and target.suffix.lower() == ".zip":
        size = target.stat().st_size
        with tempfile.TemporaryDirectory(prefix="skin-validate-") as tmp:
            tmp_path = Path(tmp)
            try:
                extract_zip(target, tmp_path)
            except zipfile.BadZipFile:
                print("ERROR\t无效 zip")
                return 2
            skin_name = validate_tree(tmp_path, findings, zip_size=size)
    elif target.is_dir():
        skin_name = validate_tree(target, findings)
    else:
        print("ERROR\t请传入皮肤目录或 .zip")
        return 2

    errors = 0
    for level, msg in findings:
        print(f"{level}\t{msg}")
        if level == "ERROR":
            errors += 1

    if errors:
        print(f"\nFAILED\t{errors} error(s)" + (f"\tname={skin_name}" if skin_name else ""))
        return 1

    print(f"\nPASSED\tname={skin_name or '?'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
