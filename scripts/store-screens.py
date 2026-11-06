# SPDX-License-Identifier: GPL-3.0-or-later
"""Store screenshots with a phone frame and a caption, from the raw captures
in docs/store/raw/<lang>/ (taken on the emulator with clean demo data).
Output: fastlane/metadata/android/<lang>/images/phoneScreenshots/, 1080x1920."""
import pathlib

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = pathlib.Path(__file__).resolve().parent.parent
FONTS = pathlib.Path("C:/Windows/Fonts")
GREEN_TOP, GREEN_BOTTOM = (16, 82, 62), (8, 44, 33)
GOLD, LIGHT = (232, 176, 74), (245, 211, 138)
W, H = 1080, 1920

CAPTIONS = {
    "it-IT": [
        ("Apri i tuoi container VeraCrypt®", "Password, PIM e keyfile, con tutti i cifrari"),
        ("I tuoi file, sempre cifrati", "Sfoglia, importa, rinomina, sposta ed elimina"),
        ("Foto e documenti al sicuro", "FAT ed exFAT, compatibili con il computer"),
        ("Guarda senza lasciare tracce", "Nessuna copia in chiaro sul telefono"),
        ("Tutti i container in un posto", "Si bloccano da soli quando esci dall'app"),
        ("Blocco con PIN o impronta", "Screenshot bloccati, nessun accesso a internet"),
    ],
    "en-US": [
        ("Open your VeraCrypt® containers", "Password, PIM and keyfiles, every cipher"),
        ("Your files, always encrypted", "Browse, import, rename, move and delete"),
        ("Photos and documents, safe", "FAT and exFAT, compatible with your computer"),
        ("View without leaving traces", "No plaintext copies on your phone"),
        ("All your containers in one place", "They lock themselves when you leave the app"),
        ("Lock with PIN or fingerprint", "Screenshots blocked, no internet access"),
    ],
}


def vertical_gradient(w, h, top, bottom):
    img = Image.new("RGB", (w, h))
    d = ImageDraw.Draw(img)
    for y in range(h):
        t = y / (h - 1)
        d.line([(0, y), (w, y)], fill=tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)))
    return img


def rounded_mask(size, radius):
    m = Image.new("L", size, 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, size[0] - 1, size[1] - 1], radius=radius, fill=255)
    return m


def fit_font(text, path, start, max_width):
    size = start
    while size > 30:
        f = ImageFont.truetype(str(path), size)
        if f.getlength(text) <= max_width:
            return f
        size -= 2
    return ImageFont.truetype(str(path), size)


def compose(raw: pathlib.Path, title: str, sub: str, out: pathlib.Path) -> None:
    canvas = vertical_gradient(W, H, GREEN_TOP, GREEN_BOTTOM)
    d = ImageDraw.Draw(canvas)
    # Caption.
    tf = fit_font(title, FONTS / "segoeuib.ttf", 72, W - 120)
    sf = fit_font(sub, FONTS / "segoeui.ttf", 40, W - 140)
    d.text((W / 2, 150), title, font=tf, fill=(255, 255, 255), anchor="mm")
    d.text((W / 2, 240), sub, font=sf, fill=LIGHT, anchor="mm")
    d.rounded_rectangle([W / 2 - 40, 288, W / 2 + 40, 294], radius=3, fill=GOLD)
    # Phone: body, screen with rounded corners, camera dot.
    shot = Image.open(raw).convert("RGB")
    screen_w = 820
    screen_h = int(shot.height * screen_w / shot.width)
    shot = shot.resize((screen_w, screen_h), Image.LANCZOS)
    bezel = 22
    body_w, body_h = screen_w + 2 * bezel, screen_h + 2 * bezel
    x0, y0 = (W - body_w) // 2, 345
    shadow = Image.new("L", (W, H), 0)
    ImageDraw.Draw(shadow).rounded_rectangle([x0 + 10, y0 + 24, x0 + body_w + 10, y0 + body_h + 24], radius=70, fill=150)
    canvas.paste((0, 0, 0), (0, 0), shadow.filter(ImageFilter.GaussianBlur(28)))
    d = ImageDraw.Draw(canvas)
    d.rounded_rectangle([x0, y0, x0 + body_w, y0 + body_h], radius=70, fill=(22, 24, 23))
    d.rounded_rectangle([x0 + 3, y0 + 3, x0 + body_w - 3, y0 + body_h - 3], radius=67, outline=(70, 74, 72), width=3)
    # Small radius: the status bar (clock) of the capture stays visible.
    canvas.paste(shot, (x0 + bezel, y0 + bezel), rounded_mask((screen_w, screen_h), 40))
    canvas.save(out, optimize=True)


def main() -> None:
    for lang, caps in CAPTIONS.items():
        raws = sorted((ROOT / "docs/store/raw" / lang).glob("*.png"))
        out_dir = ROOT / "fastlane/metadata/android" / lang / "images/phoneScreenshots"
        out_dir.mkdir(parents=True, exist_ok=True)
        for old in out_dir.glob("*.png"):
            old.unlink()
        for i, (raw, (title, sub)) in enumerate(zip(raws, caps), start=1):
            compose(raw, title, sub, out_dir / f"{i}.png")
    print("ok")


if __name__ == "__main__":
    main()
