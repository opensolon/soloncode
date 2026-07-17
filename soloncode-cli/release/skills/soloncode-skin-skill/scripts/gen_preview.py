#!/usr/bin/env python3
"""Generate a small preview.png for SolonCode skin list UI."""

from __future__ import annotations

import argparse
import colorsys
import re
import sys
from pathlib import Path

try:
    from PIL import Image, ImageDraw, ImageFilter
except ImportError:  # pragma: no cover
    print("ERROR\t需要 Pillow: pip install pillow", file=sys.stderr)
    sys.exit(2)

THEME_COLORS = {
    "ocean": ("#0b7ea4", "#e8f7fc", "#0d1b24"),
    "forest": ("#3f7d4e", "#e8f3eb", "#142018"),
    "aurora": ("#6d5efc", "#efe9ff", "#12121f"),
    "ink": ("#3f3f46", "#f4f4f5", "#18181b"),
    "warm": ("#d97706", "#fff7ed", "#1c1410"),
    "pink": ("#db2777", "#fdf2f8", "#1a1016"),
    "business": ("#4f6ef7", "#eef2ff", "#12182a"),
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


def lighten(rgb: tuple[int, int, int], amount: float = 0.35) -> tuple[int, int, int]:
    return mix(rgb, (255, 255, 255), amount)


def darken(rgb: tuple[int, int, int], amount: float = 0.35) -> tuple[int, int, int]:
    return mix(rgb, (0, 0, 0), amount)


def shift_hue(rgb: tuple[int, int, int], delta: float) -> tuple[int, int, int]:
    r, g, b = [c / 255 for c in rgb]
    h, l, s = colorsys.rgb_to_hls(r, g, b)
    h = (h + delta) % 1.0
    rr, gg, bb = colorsys.hls_to_rgb(h, l, s)
    return int(rr * 255), int(gg * 255), int(bb * 255)


def draw_preview(
    out: Path,
    accent: str,
    light_bg: str | None = None,
    dark_bg: str | None = None,
    width: int = 640,
    height: int = 360,
    label: str | None = None,
) -> Path:
    acc = parse_hex(accent)
    lbg = parse_hex(light_bg) if light_bg else lighten(acc, 0.82)
    dbg = parse_hex(dark_bg) if dark_bg else darken(acc, 0.78)
    acc2 = shift_hue(acc, 0.08)

    img = Image.new("RGB", (width, height), lbg)
    draw = ImageDraw.Draw(img, "RGBA")

    # split light/dark panels
    mid = width // 2
    for x in range(width):
        t = x / max(width - 1, 1)
        if x < mid:
            c = mix(lbg, lighten(acc, 0.7), 0.25 + 0.35 * (x / mid))
        else:
            local = (x - mid) / max(mid, 1)
            c = mix(dbg, darken(acc, 0.45), 0.2 + 0.5 * local)
        draw.line([(x, 0), (x, height)], fill=c)

    # soft blobs
    overlay = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    od = ImageDraw.Draw(overlay)
    od.ellipse([int(width * 0.05), int(height * 0.05), int(width * 0.48), int(height * 0.7)], fill=(*acc, 55))
    od.ellipse([int(width * 0.42), int(height * 0.2), int(width * 0.95), int(height * 0.95)], fill=(*acc2, 50))
    od.ellipse([int(width * 0.55), int(height * -0.05), int(width * 1.05), int(height * 0.45)], fill=(*lighten(acc, 0.2), 40))
    overlay = overlay.filter(ImageFilter.GaussianBlur(radius=28))
    img = Image.alpha_composite(img.convert("RGBA"), overlay)

    draw = ImageDraw.Draw(img, "RGBA")
    # fake UI chrome: sidebar + main card + accent bar
    sb_w = int(width * 0.18)
    draw.rounded_rectangle([18, 18, sb_w, height - 18], radius=14, fill=(255, 255, 255, 70))
    draw.rounded_rectangle([sb_w + 16, 18, width - 18, height - 18], radius=16, fill=(255, 255, 255, 48))
    draw.rounded_rectangle([sb_w + 34, 40, sb_w + 34 + int(width * 0.28), 78], radius=10, fill=(*acc, 210))
    draw.rounded_rectangle([sb_w + 34, 100, width - 48, 150], radius=10, fill=(255, 255, 255, 90))
    draw.rounded_rectangle([sb_w + 34, 168, width - 90, 210], radius=10, fill=(255, 255, 255, 70))
    # divider
    draw.line([(mid, 12), (mid, height - 12)], fill=(255, 255, 255, 90), width=2)

    if label:
        # tiny caption bar without depending on fonts
        bar_h = 28
        draw.rounded_rectangle([20, height - 48, 20 + max(90, 10 * len(label)), height - 48 + bar_h], radius=8, fill=(0, 0, 0, 90))

    out = out.expanduser().resolve()
    out.parent.mkdir(parents=True, exist_ok=True)
    img.convert("RGB").save(out, format="PNG", optimize=True)
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate SolonCode skin preview.png")
    parser.add_argument("-o", "--output", default="preview.png", help="output path (default: preview.png)")
    parser.add_argument("--theme", default="aurora", help="ocean/forest/aurora/ink/warm/pink/business")
    parser.add_argument("--accent", default=None, help="override accent hex, e.g. #6d5efc")
    parser.add_argument("--light-bg", default=None, help="override light panel base color")
    parser.add_argument("--dark-bg", default=None, help="override dark panel base color")
    parser.add_argument("--width", type=int, default=640)
    parser.add_argument("--height", type=int, default=360)
    parser.add_argument("--label", default=None, help="optional label (visual only)")
    args = parser.parse_args()

    theme = (args.theme or "aurora").strip().lower()
    if theme not in THEME_COLORS and not args.accent:
        print(f"WARN\t未知 theme={theme!r}，回退 aurora")
        theme = "aurora"
    accent, light_bg, dark_bg = THEME_COLORS.get(theme, THEME_COLORS["aurora"])
    if args.accent:
        accent = args.accent
    if args.light_bg:
        light_bg = args.light_bg
    if args.dark_bg:
        dark_bg = args.dark_bg

    try:
        path = draw_preview(
            Path(args.output),
            accent=accent,
            light_bg=light_bg,
            dark_bg=dark_bg,
            width=args.width,
            height=args.height,
            label=args.label,
        )
    except Exception as exc:  # noqa: BLE001
        print(f"ERROR\t{exc}")
        return 1

    size = path.stat().st_size
    print(f"OK\tpreview -> {path} ({size} bytes)")
    if size > 2 * 1024 * 1024:
        print("ERROR\tpreview.png 超过 2MB")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
