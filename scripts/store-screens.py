# SPDX-License-Identifier: GPL-3.0-or-later
"""Play Store screenshots: the raw emulator captures in docs/store/raw/<lang>/
placed in a realistic phone, on the brand background, with a headline.

Each screen is laid out in HTML/CSS and rendered by headless Chrome at
exactly 1080x1920. Output: fastlane/metadata/android/<lang>/images/phoneScreenshots/.
Usage: python scripts/store-screens.py [chrome.exe]
"""
import html
import pathlib
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
FONT = ROOT / "android/app/src/main/res/font/manrope.ttf"
CHROME = sys.argv[1] if len(sys.argv) > 1 else r"C:\Program Files\Google\Chrome\Application\chrome.exe"
W, H = 1080, 1920

# (raw capture, headline with *gold* word, subline)
SHOTS = {
    "en-US": [
        ("1-vaults", "Your VeraCrypt® vaults, *in your pocket*", "Open, edit and create containers on Android"),
        ("2-unlock", "Unlock with *password* or fingerprint", "Every VeraCrypt cipher, PIM and keyfiles"),
        ("4-photos", "Photos stay *encrypted*", "Previews live in memory only, never on disk"),
        ("3-browser", "All your files, *organized*", "Import, rename, move, search and sort"),
        ("5-viewer", "View without *leaving traces*", "No plaintext copies on your phone"),
        ("6-create", "Create containers *in seconds*", "exFAT or FAT, compatible with your computer"),
        ("7-settings", "Private *by design*", "No internet permission, no ads, open source"),
        ("0-onboarding", "Made for people who *care*", "Free and open source, built to be checked"),
    ],
    "it-IT": [
        ("1-vaults", "I tuoi volumi VeraCrypt®, *in tasca*", "Apri, modifica e crea container su Android"),
        ("2-unlock", "Sblocca con *password* o impronta", "Tutti i cifrari VeraCrypt, PIM e keyfile"),
        ("4-photos", "Le foto restano *cifrate*", "Anteprime solo in memoria, mai sul telefono"),
        ("3-browser", "Tutti i tuoi file, *in ordine*", "Importa, rinomina, sposta, cerca e ordina"),
        ("5-viewer", "Guarda senza *lasciare tracce*", "Nessuna copia in chiaro sul telefono"),
        ("6-create", "Crea container *in pochi secondi*", "exFAT o FAT, compatibili con il computer"),
        ("7-settings", "Privato *per scelta*", "Nessun accesso a internet, niente pubblicità"),
        ("0-onboarding", "Fatto per chi *ci tiene*", "Gratuito e open source, verificabile da tutti"),
    ],
}

HEX = ("data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='120' height='138' viewBox='0 0 120 138'>"
       "<path d='M60 4 112 34v70L60 134 8 104V34z' fill='none' stroke='%23E8B04A' stroke-opacity='.07' stroke-width='2'/></svg>")

PAGE = """<!doctype html><html><head><meta charset="utf-8"><style>
@font-face {{ font-family: Manrope; src: url("{font}"); font-weight: 200 800; }}
* {{ margin: 0; box-sizing: border-box; }}
html, body {{ width: {w}px; height: {h}px; overflow: hidden; }}
body {{
  font-family: Manrope, sans-serif; color: #fff; position: relative;
  background:
    radial-gradient(900px 700px at 85% 8%, rgba(232,176,74,.22), transparent 60%),
    radial-gradient(800px 900px at 0% 100%, rgba(31,120,90,.45), transparent 65%),
    linear-gradient(170deg, #0E4A37 0%, #0B3D2E 45%, #062419 100%);
}}
.pattern {{ position: absolute; inset: 0; background-image: url("{hex}"); background-size: 120px 138px;
  -webkit-mask-image: linear-gradient(180deg, #000 0%, transparent 55%); }}
.copy {{ position: absolute; top: 112px; left: 80px; right: 80px; text-align: center; }}
h1 {{ font-size: 74px; line-height: 1.08; font-weight: 800; letter-spacing: -1.5px; text-wrap: balance; }}
h1 em {{ font-style: normal; color: #F2C46A; }}
p {{ margin-top: 26px; font-size: 34px; line-height: 1.3; font-weight: 500; color: rgba(255,255,255,.74); }}
.phone {{
  position: absolute; left: 50%; top: 486px; width: 800px; height: 1376px; transform: translateX(-50%);
  border-radius: 112px; padding: 13px;
  background: linear-gradient(145deg, #5d6764 0%, #2a302e 18%, #6f7976 50%, #252b29 80%, #4d5653 100%);
  box-shadow: 0 60px 120px rgba(0,0,0,.55), 0 18px 40px rgba(0,0,0,.35), inset 0 0 0 2px rgba(255,255,255,.18);
}}
.bezel {{ width: 100%; height: 100%; border-radius: 100px; background: #050807; padding: 18px; position: relative; }}
.screen {{ width: 100%; height: 100%; border-radius: 84px; overflow: hidden; position: relative; background: #000; }}
.sb {{ height: 58px; display: flex; align-items: center; justify-content: space-between; padding: 6px 62px 0;
  font-weight: 700; font-size: 25px; background: {bar}; color: {ink}; }}
.sb svg {{ height: 24px; fill: {ink}; margin-left: 10px; }}
.shot {{ height: calc(100% - 58px); overflow: hidden; }}
.shot img {{ width: 100%; display: block; margin-top: -{crop}px; }}
.screen::after {{ content: ""; position: absolute; inset: 0; border-radius: 84px;
  background: linear-gradient(115deg, rgba(255,255,255,.10) 0%, rgba(255,255,255,0) 28%); pointer-events: none; }}
.cam {{ position: absolute; top: 40px; left: 50%; width: 30px; height: 30px; margin-left: -15px; border-radius: 50%;
  background: radial-gradient(circle at 40% 35%, #2b3a4a 0 18%, #07090c 45%); box-shadow: 0 0 0 3px #0b0e0d; z-index: 2; }}
.btn {{ position: absolute; right: -6px; width: 7px; border-radius: 0 6px 6px 0;
  background: linear-gradient(90deg, #3a423f, #79837f); }}
</style></head><body>
<div class="pattern"></div>
<div class="copy"><h1>{title}</h1><p>{sub}</p></div>
<div class="phone">
  <div class="btn" style="top:300px;height:110px"></div>
  <div class="btn" style="top:450px;height:190px"></div>
  <div class="bezel"><div class="cam"></div><div class="screen"><div class="sb"><span>12:00</span><span>
<svg viewBox="0 0 24 24"><path d="M12 21 0 8.5C3.3 5.4 7.5 3.5 12 3.5s8.7 1.9 12 5z"/></svg><svg viewBox="0 0 24 24"><path d="M2 22h3V17H2zm5 0h3v-8H7zm5 0h3V10h-3zm5 0h3V5h-3z"/></svg><svg viewBox="0 0 42 24"><rect x="1" y="4" width="36" height="16" rx="4" fill="none" stroke-width="2" style="stroke:{ink}"/><rect x="4" y="7" width="30" height="10" rx="2"/><rect x="38.5" y="9" width="2.5" height="6" rx="1"/></svg>
</span></div><div class="shot"><img src="{img}"></div></div></div>
</div>
</body></html>"""


def bar_colors(img: pathlib.Path) -> tuple[str, str]:
    """Status bar background sampled from the app's top bar, with readable icons."""
    from PIL import Image
    r, g, b = Image.open(img).convert("RGB").getpixel((360, 70))
    ink = "#1b1f1d" if (0.299 * r + 0.587 * g + 0.114 * b) > 140 else "#f2f4f3"
    return f"rgb({r},{g},{b})", ink


def headline(text: str) -> str:
    parts = html.escape(text).split("*")
    return "".join(f"<em>{p}</em>" if i % 2 else p for i, p in enumerate(parts))


def render(page: str, out: pathlib.Path, tmp: pathlib.Path) -> None:
    src = tmp / (out.stem + ".html")
    src.write_text(page, encoding="utf-8")
    subprocess.run([
        CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--force-device-scale-factor=1",
        "--allow-file-access-from-files", f"--window-size={W},{H}", f"--screenshot={out}", src.as_uri(),
    ], check=True, capture_output=True)


def main() -> None:
    with tempfile.TemporaryDirectory() as t:
        tmp = pathlib.Path(t)
        for lang, shots in SHOTS.items():
            dest = ROOT / f"fastlane/metadata/android/{lang}/images/phoneScreenshots"
            dest.mkdir(parents=True, exist_ok=True)
            for old in dest.glob("*.png"):
                old.unlink()
            for n, (raw, title, sub) in enumerate(shots, 1):
                img = ROOT / f"docs/store/raw/{lang}/{raw}.png"
                bar, ink = bar_colors(img)
                # The emulator status bar (48 px of 720) is replaced by a drawn one,
                # inset like a real phone's so the rounded corners never cut it.
                page = PAGE.format(font=FONT.as_uri(), hex=HEX, w=W, h=H, title=headline(title),
                                   sub=html.escape(sub), img=img.as_uri(), bar=bar, ink=ink,
                                   crop=round(48 * 738 / 720))
                render(page, dest / f"{n}.png", tmp)
                print(lang, n, raw)


if __name__ == "__main__":
    main()
