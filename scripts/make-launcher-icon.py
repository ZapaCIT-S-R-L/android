"""Builds the Android launcher icon and the Play listing icon from the 1024x1024 app icon (robot on black).

Run (needs Pillow): python3 scripts/make-launcher-icon.py <path to icon.png>
Writes, under app/src/main/res and store/:
  drawable-<density>/ic_launcher_foreground.png  robot only, transparent, inside the 66dp safe circle of 108dp
  drawable-<density>/ic_launcher_monochrome.png  white silhouette for themed icons (Android 13+)
  store/icon-512.png                             Play listing icon
The adaptive icon (mipmap-anydpi/ic_launcher.xml) puts the foreground on a black background color. minSdk is 26, so
there are no legacy bitmap icons: every device uses the adaptive one.
"""

import math
import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "src" / "main" / "res"
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
CANVAS_DP = 108
SAFE_RADIUS_DP = 33 * 0.97  # a little inside the 66dp safe circle
NOISE = 12  # alpha below this is background


def robot_layer(src: Image.Image) -> Image.Image:
    """The source is the robot on pure black: brightness becomes alpha, colour is un-premultiplied over black,
    so the layer on a black background looks exactly like the source."""
    rgb = src.convert("RGB")
    out = Image.new("RGBA", rgb.size)
    px_in, px_out = rgb.load(), out.load()
    for y in range(rgb.height):
        for x in range(rgb.width):
            r, g, b = px_in[x, y]
            a = max(r, g, b)
            if a < NOISE:
                px_out[x, y] = (0, 0, 0, 0)
            else:
                px_out[x, y] = (min(255, r * 255 // a), min(255, g * 255 // a), min(255, b * 255 // a), a)
    return out


def enclosing_circle(layer: Image.Image) -> tuple[float, float, float]:
    """Centre (of the bounding box) and the radius that holds every visible pixel."""
    left, top, right, bottom = layer.getchannel("A").point(lambda a: 255 if a >= NOISE else 0).getbbox()
    cx, cy = (left + right) / 2, (top + bottom) / 2
    alpha = layer.getchannel("A").load()
    radius = 0.0
    for y in range(top, bottom):
        for x in range(left, right):
            if alpha[x, y] >= NOISE:
                radius = max(radius, math.hypot(x + 0.5 - cx, y + 0.5 - cy))
    return cx, cy, radius


def place(layer: Image.Image, cx: float, cy: float, radius: float, size: int) -> Image.Image:
    """The layer scaled so its circle fits the safe zone, centred on a size x size canvas."""
    scale = (SAFE_RADIUS_DP / CANVAS_DP * size) / radius
    scaled = layer.resize((round(layer.width * scale), round(layer.height * scale)), Image.LANCZOS)
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas.alpha_composite(scaled, (round(size / 2 - cx * scale), round(size / 2 - cy * scale)))
    return canvas


MONO_SOLID = 110  # brightness from which the silhouette is fully opaque (the grey body), so themed icons read well


def monochrome(layer: Image.Image) -> Image.Image:
    """White silhouette; the dark visor and mouth stay see-through, so the face shows in themed icons."""
    white = Image.new("RGBA", layer.size, (255, 255, 255, 0))
    white.putalpha(layer.getchannel("A").point(lambda a: min(255, a * 255 // MONO_SOLID)))
    return white


def main() -> None:
    src = Image.open(sys.argv[1])
    if src.size != (1024, 1024):
        sys.exit(f"expected a 1024x1024 icon, got {src.size}")
    layer = robot_layer(src)
    cx, cy, radius = enclosing_circle(layer)
    for name, factor in DENSITIES.items():
        size = round(CANVAS_DP * factor)
        drawable = RES / f"drawable-{name}"
        drawable.mkdir(parents=True, exist_ok=True)
        fg = place(layer, cx, cy, radius, size)
        fg.save(drawable / "ic_launcher_foreground.png", optimize=True)
        monochrome(fg).save(drawable / "ic_launcher_monochrome.png", optimize=True)
    (ROOT / "store").mkdir(exist_ok=True)
    src.convert("RGB").resize((512, 512), Image.LANCZOS).save(ROOT / "store" / "icon-512.png", optimize=True)


if __name__ == "__main__":
    main()
