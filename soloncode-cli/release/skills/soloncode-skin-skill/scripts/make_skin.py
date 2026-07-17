#!/usr/bin/env python3
"""One-shot SolonCode skin builder: scaffold → assets → validate → pack."""

from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
SKILL_ROOT = SCRIPT_DIR.parent


def run_py(script: Path, args: list[str]) -> None:
    cmd = [sys.executable, str(script), *args]
    print("RUN\t" + " ".join(cmd), flush=True)
    proc = subprocess.run(cmd, cwd=str(SKILL_ROOT))
    if proc.returncode != 0:
        raise SystemExit(proc.returncode)


def main() -> int:
    parser = argparse.ArgumentParser(description="One-shot build SolonCode skin zip")
    parser.add_argument("--name", required=True, help="skin id")
    parser.add_argument("--recipe", default="b", help="a/b/c/d/e/f")
    parser.add_argument("--theme", default="aurora", help="color heuristic theme")
    parser.add_argument("--display-name", default=None)
    parser.add_argument("--description", default=None)
    parser.add_argument("-o", "--output", default=None, help="output zip path (default: ./{name}.zip)")
    parser.add_argument("--work-dir", default=None, help="keep working dir (default: temp)")
    parser.add_argument("--no-preview", action="store_true", help="skip preview.png (default: generate)")
    parser.add_argument(
        "--with-assets",
        action="store_true",
        help="generate structured PNG backgrounds for settings/main when recipe supports them",
    )
    parser.add_argument("--force", action="store_true", help="overwrite work-dir / output zip")
    args = parser.parse_args()

    name = args.name.strip()
    recipe = (args.recipe or "b").strip().lower()
    theme = (args.theme or "aurora").strip().lower()
    out_zip = Path(args.output).expanduser().resolve() if args.output else (Path.cwd() / f"{name}.zip")
    if out_zip.exists() and not args.force:
        print(f"ERROR\t输出已存在: {out_zip}（加 --force 覆盖）", flush=True)
        return 2

    keep_work = args.work_dir is not None
    work = Path(args.work_dir).expanduser().resolve() if args.work_dir else Path(tempfile.mkdtemp(prefix=f"skin-{name}-"))
    if keep_work and work.exists() and any(work.iterdir()) and not args.force:
        print(f"ERROR\t工作目录非空: {work}（加 --force）", flush=True)
        return 2

    scaffold = SCRIPT_DIR / "scaffold_skin.py"
    validate = SCRIPT_DIR / "validate_skin.py"
    pack = SCRIPT_DIR / "pack_skin.py"

    try:
        sc_args = [
            "--name",
            name,
            "--recipe",
            recipe,
            "--theme",
            theme,
            "--out",
            str(work),
            "--force",
        ]
        if args.display_name:
            sc_args += ["--display-name", args.display_name]
        if args.description:
            sc_args += ["--description", args.description]
        if not args.no_preview:
            sc_args.append("--preview")
        if args.with_assets:
            sc_args.append("--with-assets")

        run_py(scaffold, sc_args)
        run_py(validate, [str(work)])
        run_py(pack, [str(work), "-o", str(out_zip)])

        print(f"DONE\t{out_zip}", flush=True)
        if keep_work:
            print(f"WORK\t{work}", flush=True)
        else:
            print(f"WORK\t{work} (temp; removed)", flush=True)
        return 0
    finally:
        if not keep_work and work.exists():
            shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
