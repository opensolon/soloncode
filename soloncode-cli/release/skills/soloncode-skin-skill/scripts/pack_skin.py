#!/usr/bin/env python3
"""Pack a SolonCode skin directory into an installable flat zip."""

from __future__ import annotations

import argparse
import importlib.util
import json
import sys
import zipfile
from datetime import datetime
from pathlib import Path

MAX_ZIP_BYTES = 8 * 1024 * 1024
SCRIPT_DIR = Path(__file__).resolve().parent


def _load_validator():
    """Load validate_skin from the same directory even if not on sys.path."""
    mod_path = SCRIPT_DIR / "validate_skin.py"
    if not mod_path.is_file():
        return None, None, MAX_ZIP_BYTES
    spec = importlib.util.spec_from_file_location("soloncode_skin_validate", mod_path)
    if spec is None or spec.loader is None:
        return None, None, MAX_ZIP_BYTES
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return getattr(mod, "validate_tree", None), getattr(mod, "Finding", None), getattr(mod, "MAX_ZIP_BYTES", MAX_ZIP_BYTES)


validate_tree, Finding, MAX_ZIP_BYTES = _load_validator()


def resolve_skin_root(path: Path) -> Path:
    if (path / "skin.json").is_file() and (path / "skin.css").is_file():
        return path
    subdirs = [d for d in path.iterdir() if d.is_dir() and not d.name.startswith(".") and d.name != "__MACOSX"]
    if len(subdirs) == 1 and (subdirs[0] / "skin.json").is_file() and (subdirs[0] / "skin.css").is_file():
        return subdirs[0]
    raise FileNotFoundError("未找到 skin.json + skin.css（支持扁平或单层目录）")


def stamp_yyyyMMddHH(dt: datetime | None = None) -> str:
    """Local timestamp for zip file names: yyyyMMddHH (hour precision)."""
    return (dt or datetime.now()).strftime("%Y%m%d%H")


def default_zip_basename(name: str, dt: datetime | None = None) -> str:
    """{name}-yyyyMMddHH.zip — avoids overwrite when regenerating the same skin."""
    safe = (name or "skin").strip() or "skin"
    return f"{safe}-{stamp_yyyyMMddHH(dt)}.zip"


def default_out_path(skin_root: Path, out: Path | None) -> Path:
    if out is not None:
        return out
    name = "skin"
    try:
        meta = json.loads((skin_root / "skin.json").read_text(encoding="utf-8"))
        name = str(meta.get("name") or name)
    except Exception:  # noqa: BLE001
        pass
    # Align with Web attachments dir (.uploads is gitignored).
    # Stamp hour so re-generate does not clobber the previous zip / install link.
    return Path.cwd() / ".uploads" / default_zip_basename(name)


def should_include(rel: str, path: Path) -> bool:
    if not path.is_file():
        return False
    parts = Path(rel).parts
    if any(p.startswith(".") or p == "__MACOSX" or p.endswith(".DS_Store") for p in parts):
        return False
    if path.name.startswith("._") or path.name == ".DS_Store":
        return False
    # drop scaffold helper notes from final zip
    if rel in {"assets/README.txt", "assets/README.md"}:
        return False
    return True


def pack(skin_root: Path, out_zip: Path) -> int:
    files: list[tuple[Path, str]] = []
    for p in sorted(skin_root.rglob("*")):
        rel = p.relative_to(skin_root).as_posix()
        if should_include(rel, p):
            files.append((p, rel))

    if not any(rel == "skin.json" for _, rel in files):
        raise RuntimeError("打包列表缺少 skin.json")
    if not any(rel == "skin.css" for _, rel in files):
        raise RuntimeError("打包列表缺少 skin.css")

    out_zip.parent.mkdir(parents=True, exist_ok=True)
    if out_zip.exists():
        out_zip.unlink()

    with zipfile.ZipFile(out_zip, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for path, rel in files:
            zf.write(path, arcname=rel)

    size = out_zip.stat().st_size
    print(f"OK\tpacked {len(files)} files -> {out_zip} ({size} bytes)")
    if size > MAX_ZIP_BYTES:
        print(f"ERROR\tzip 超过 8MB: {size}")
        return 1
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Pack SolonCode skin directory to zip")
    parser.add_argument("skin_dir", help="skin directory (flat or one-level wrapper)")
    parser.add_argument(
        "-o",
        "--output",
        help="output zip path (default: .uploads/{name}-yyyyMMddHH.zip)",
    )
    parser.add_argument("--skip-validate", action="store_true", help="skip validation before pack")
    args = parser.parse_args()

    src = Path(args.skin_dir).expanduser().resolve()
    if not src.is_dir():
        print(f"ERROR\t不是目录: {src}")
        return 2

    try:
        skin_root = resolve_skin_root(src)
    except FileNotFoundError as exc:
        print(f"ERROR\t{exc}")
        return 2

    if not args.skip_validate and validate_tree is not None and Finding is not None:
        findings = Finding()
        validate_tree(skin_root, findings)
        errors = 0
        for level, msg in findings:
            print(f"{level}\t{msg}")
            if level == "ERROR":
                errors += 1
        if errors:
            print(f"FAILED\tvalidation {errors} error(s); use --skip-validate to force")
            return 1
    elif not args.skip_validate:
        print("WARN\tvalidate_skin 不可导入，跳过校验")

    out = Path(args.output).expanduser().resolve() if args.output else default_out_path(skin_root, None)
    try:
        return pack(skin_root, out)
    except Exception as exc:  # noqa: BLE001
        print(f"ERROR\t{exc}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
