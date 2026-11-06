# SPDX-License-Identifier: GPL-3.0-or-later
"""Renders the Play Store icon (512x512) and feature graphics (1024x500, IT
and EN) from the launcher icon geometry (ic_launcher_foreground.xml) and the
brand palette (docs/brand/brand.md). Output: fastlane/metadata/android/<lang>/images/."""
import pathlib
import sys

from PIL import Image, ImageDraw, ImageFont

ROOT = pathlib.Path(__file__).resolve().parent.parent
GREEN, GOLD, LIGHT = (11, 61, 46), (232, 176, 74), (245, 211, 138)
FONTS = pathlib.Path("C:/Windows/Fonts")
SS = 4  # supersampling for smooth edges

# Launcher icon paths in its 108x108 viewport (nested hexagons).
SHAPES = [
    (GOLD, [(54, 24), (80, 39), (80, 69), (54, 84), (28, 69), (28, 39)]),
    (LIGHT, [(54, 24), (80, 39), (54, 54), (28, 39)]),
    (GREEN, [(54, 39), (67, 46.5), (67, 61.5), (54, 69), (41, 61.5), (41, 46.5)]),
    (GOLD, [(54, 46), (61, 50), (61, 58), (54, 62), (47, 58), (47, 50)]),
]


def draw_mark(d: ImageDraw.ImageDraw, x0: float, y0: float, size: float) -> None:
    """Draws the 108-unit viewport scaled to `size` px at (x0, y0)."""
    k = size / 108
    for color, pts in SHAPES:
        d.polygon([(x0 + x * k, y0 + y * k) for x, y in pts], fill=color)


def icon(out: pathlib.Path) -> None:
    n = 512 * SS
    img = Image.new("RGB", (n, n), GREEN)
    # Hexagon about 60% of the width: clear of Google's rounded-corner mask.
    size = n * 1.2
    draw_mark(ImageDraw.Draw(img), (n - size) / 2, (n - size) / 2, size)
    img.resize((512, 512), Image.LANCZOS).save(out)


def feature(out: pathlib.Path, tagline: str, sub: str) -> None:
    w, h = 1024 * SS, 500 * SS
    img = Image.new("RGB", (w, h), GREEN)
    d = ImageDraw.Draw(img)
    draw_mark(d, 0, 30 * SS, 440 * SS)
    title = ImageFont.truetype(str(FONTS / "segoeuib.ttf"), 112 * SS)
    body = ImageFont.truetype(str(FONTS / "segoeui.ttf"), 40 * SS)
    small = ImageFont.truetype(str(FONTS / "segoeui.ttf"), 30 * SS)
    x = 420 * SS
    d.text((x, 120 * SS), "Coffer", font=title, fill=(255, 255, 255))
    y = 270 * SS
    for line in tagline.split("\n"):
        d.text((x, y), line, font=body, fill=LIGHT)
        y += 52 * SS
    d.text((x, y + 14 * SS), sub, font=small, fill=(200, 215, 205))
    img.resize((1024, 500), Image.LANCZOS).save(out)


def main() -> None:
    base = ROOT / "fastlane/metadata/android"
    texts = {
        "it-IT": ("I tuoi volumi VeraCrypt®,\nal sicuro in tasca", "Senza root · Senza internet · Open source"),
        "en-US": ("Your VeraCrypt® volumes,\nsafe in your pocket", "No root · No internet · Open source"),
    }
    for lang, (tag, sub) in texts.items():
        images = base / lang / "images"
        images.mkdir(parents=True, exist_ok=True)
        icon(images / "icon.png")
        feature(images / "featureGraphic.png", tag, sub)
    print("ok")


if __name__ == "__main__":
    sys.exit(main())
