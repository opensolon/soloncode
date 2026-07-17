#!/usr/bin/env python3
"""Generate structured decorative background PNGs for SolonCode skins.

Avoid near-solid images: output must contain visible blobs / beams / rings so
settings/main panel backgrounds remain perceptible under thin overlays.
"""

from __future__ import annotations

import argparse
import colorsys
import math
import re
import sys
from pathlib import Path

try:
    from PIL import Image, ImageDraw, ImageFilter
except ImportError:  # pragma: no cover
    print("ERROR\t需要 Pillow: pip install pillow", file=sys.stderr)
    sys.exit(2)

THEME_PALETTES = {
    "ocean": {
        "light": ("#d9f2fa", "#eef7ff", "#0b7ea4", "#3db8d9"),
        "dark": ("#0d1b24", "#12182a", "#3db8d9", "#7ad4ec"),
    },
    "forest": {
        "light": ("#e7f3ea", "#f4faf5", "#3f7d4e", "#6bbf7a"),
        "dark": ("#142018", "#1a2a20", "#6bbf7a", "#9dd8a8"),
    },
    "aurora": {
        "light": ("#efe9ff", "#e8f4ff", "#6d5efc", "#22d3ee"),
        "dark": ("#12121f", "#1a1430", "#a89bff", "#67e8f9"),
    },
    "ink": {
        "light": ("#f4f4f5", "#e4e4e7", "#3f3f46", "#71717a"),
        "dark": ("#18181b", "#27272a", "#a1a1aa", "#d4d4d8"),
    },
    "warm": {
        "light": ("#fff7ed", "#ffedd5", "#d97706", "#f59e0b"),
        "dark": ("#1c1410", "#2a1c12", "#fbbf24", "#fdba74"),
    },
    "pink": {
        "light": ("#fdf2f8", "#fce7f3", "#db2777", "#f472b6"),
        "dark": ("#1a1016", "#2a1830", "#f472b6", "#f9a8d4"),
    },
    "business": {
        "light": ("#eef2ff", "#e8edff", "#4f6ef7", "#6b8aff"),
        "dark": ("#12182a", "#1a2240", "#6b8aff", "#93a9ff"),
    },
}


def parse_hex(color: str) -> tuple[int, int, int]:
    s = color.strip().lstrip("#")
    if len(s) == 3:
        s = "".join(ch * 2 for ch in s)
    if len(s) != 6 or not re.fullmatch(r"[0-9a-fA-F]{6}", s):
        raise ValueError(f"非法颜色: {color}")
    return int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16)


def mix(a: tuple[int, int, int], b: tuple[int, int, int], t: float) -> tuple[int, int, int]:
    return tuple(int(x + (y - x) * t) for x, y in zip(a, b))  # type: ignore[return-value]


def shift_hue(rgb: tuple[int, int, int], delta: float) -> tuple[int, int, int]:
    r, g, b = [c / 255 for c in rgb]
    h, l, s = colorsys.rgb_to_hls(r, g, b)
    rr, gg, bb = colorsys.hls_to_rgb((h + delta) % 1.0, l, min(1.0, s * 1.05))
    return int(rr * 255), int(gg * 255), int(bb * 255)


def fill_diagonal(img: Image.Image, c0: tuple[int, int, int], c1: tuple[int, int, int]) -> None:
    w, h = img.size
    px = img.load()
    for y in range(h):
        for x in range(w):
            t = (x * 0.65 + y * 0.35) / max(w + h * 0.35, 1)
            px[x, y] = mix(c0, c1, min(1.0, max(0.0, t)))


def add_blob(layer: Image.Image, center: tuple[int, int], radius: int, color: tuple[int, int, int], alpha: int) -> None:
    d = ImageDraw.Draw(layer)
    x, y = center
    d.ellipse([x - radius, y - radius, x + radius, y + radius], fill=(*color, alpha))


def add_beam(layer: Image.Image, p1: tuple[int, int], p2: tuple[int, int], width: int, color: tuple[int, int, int], alpha: int) -> None:
    d = ImageDraw.Draw(layer)
    d.line([p1, p2], fill=(*color, alpha), width=width)


def add_ring(layer: Image.Image, center: tuple[int, int], radius: int, width: int, color: tuple[int, int, int], alpha: int) -> None:
    d = ImageDraw.Draw(layer)
    x, y = center
    bbox = [x - radius, y - radius, x + radius, y + radius]
    d.ellipse(bbox, outline=(*color, alpha), width=width)


def generate_bg(
    out: Path,
    mode: str,
    theme: str,
    width: int = 1280,
    height: int = 800,
    accent: str | None = None,
    base: str | None = None,
    secondary: str | None = None,
) -> Path:
    theme = (theme or "aurora").strip().lower()
    mode = (mode or "light").strip().lower()
    if mode not in {"light", "dark"}:
        raise ValueError("mode 只能是 light/dark")
    if theme not in THEME_PALETTES:
        print(f"WARN\t未知 theme={theme!r}，回退 aurora")
        theme = "aurora"

    c_base, c_alt, c_acc, c_sec = THEME_PALETTES[theme][mode]
    if base:
        c_base = base
    if accent:
        c_acc = accent
    if secondary:
        c_sec = secondary

    base_rgb = parse_hex(c_base)
    alt_rgb = parse_hex(c_alt)
    acc_rgb = parse_hex(c_acc)
    sec_rgb = parse_hex(c_sec)
    pinkish = shift_hue(acc_rgb, 0.12 if mode == "light" else -0.08)

    img = Image.new("RGB", (width, height), base_rgb)
    fill_diagonal(img, base_rgb, alt_rgb)

    glow = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    # large soft blobs
    add_blob(glow, (int(width * 0.22), int(height * 0.28)), int(min(width, height) * 0.28), acc_rgb, 90 if mode == "light" else 110)
    add_blob(glow, (int(width * 0.78), int(height * 0.35)), int(min(width, height) * 0.32), sec_rgb, 80 if mode == "light" else 100)
    add_blob(glow, (int(width * 0.55), int(height * 0.78)), int(min(width, height) * 0.26), pinkish, 70 if mode == "light" else 90)
    add_blob(glow, (int(width * 0.12), int(height * 0.82)), int(min(width, height) * 0.18), sec_rgb, 55)

    # beams
    add_beam(glow, (0, int(height * 0.15)), (width, int(height * 0.55)), max(18, width // 40), acc_rgb, 40)
    add_beam(glow, (int(width * 0.1), height), (int(width * 0.85), 0), max(12, width // 55), sec_rgb, 35)

    # rings
    add_ring(glow, (int(width * 0.7), int(height * 0.3)), int(min(width, height) * 0.16), 6, acc_rgb, 70)
    add_ring(glow, (int(width * 0.32), int(height * 0.62)), int(min(width, height) * 0.12), 5, sec_rgb, 60)

    # subtle noise-like dots for structure (deterministic)
    dots = ImageDraw.Draw(glow)
    for i in range(48):
        ang = i * 0.37
        rr = 0.15 + (i % 7) * 0.05
        x = int(width * (0.5 + rr * math.cos(ang)))
        y = int(height * (0.5 + rr * math.sin(ang * 1.3)))
        r = 3 + (i % 4)
        dots.ellipse([x - r, y - r, x + r, y + r], fill=(*acc_rgb, 28 + (i % 5) * 6))

    glow = glow.filter(ImageFilter.GaussianBlur(radius=22 if mode == "light" else 18))
    out_img = Image.alpha_composite(img.convert("RGBA"), glow).convert("RGB")

    out = out.expanduser().resolve()
    out.parent.mkdir(parents=True, exist_ok=True)
    # keep under 2MB: moderate size + optimize
    out_img.save(out, format="PNG", optimize=True)
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate structured SolonCode skin background PNG")
    parser.add_argument("-o", "--output", required=True, help="output png path")
    parser.add_argument("--mode", choices=["light", "dark"], default="light")
    parser.add_argument("--theme", default="aurora", help="ocean/forest/aurora/ink/warm/pink/business")
    parser.add_argument("--accent", default=None)
    parser.add_argument("--base", default=None)
    parser.add_argument("--secondary", default=None)
    parser.add_argument("--width", type=int, default=1280)
    parser.add_argument("--height", type=int, default=800)
    args = parser.parse_args()

    try:
        path = generate_bg(
            Path(args.output),
            mode=args.mode,
            theme=args.theme,
            width=args.width,
            height=args.height,
            accent=args.accent,
            base=args.base,
            secondary=args.secondary,
        )
    except Exception as exc:  # noqa: BLE001
        print(f"ERROR\t{exc}")
        return 1

    size = path.stat().st_size
    print(f"OK\tbg[{args.mode}] -> {path} ({size} bytes)")
    if size > 2 * 1024 * 1024:
        print("ERROR\t背景图超过 2MB，请缩小尺寸")
        return 1
    if size < 8 * 1024:
        print("WARN\t背景图过小，可能缺乏可见结构")
    return 0


if __name__ == "__main__":
    sys.exit(main())
