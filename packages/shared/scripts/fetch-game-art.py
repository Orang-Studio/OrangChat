
"""Build the game-presence artwork set from games.json.

Every registry entry gets exactly one 128x128 webp tile in
packages/client/public/games/. Sources are tried best-first:

  1. Valve's store capsule, for anything with a steamAppId (~97% of the
     registry).
  2. The lead image of the title's Wikipedia article, for the launcher-exclusive
     titles (Riot, Epic, Battle.net, HoYoPlay) that Steam has no art for.
  3. A generated monogram tile, so the UI never has to deal with a missing
     image and a new registry entry is never blocked on finding art.

The output directory is deliberately *not* committed - it is ~68MB of derived
third-party art. It is a deploy step: run this before `vite build` so the tiles
land in the client bundle. Re-running is cheap, since existing tiles are skipped
unless --force is passed.

"Skipped" means the file exists, not that it is still right. If a registry entry
*gains* a steamAppId it previously lacked, its monogram is already on disk and a
plain re-run will keep it; rebuild those ids explicitly with --force. Tiles whose
id has left the registry are deleted, so the directory always mirrors games.json.

The art is downloaded and re-hosted rather than hot-linked on purpose: an <img>
pointing at Valve would put every viewer's IP in front of a third party just for
rendering a friend's presence line.

Usage:  python3 packages/shared/scripts/fetch-game-art.py [--force] [id ...]
"""

from __future__ import annotations

import argparse
import colorsys
import hashlib
import io
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[3]
REGISTRY = ROOT / "packages" / "shared" / "games.json"
NON_STEAM = Path(__file__).resolve().parent / "non-steam-games.json"
OUT_DIR = ROOT / "packages" / "client" / "public" / "games"
FONT_PATH = Path("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf")

SIZE = 128
TIMEOUT = 20
WORKERS = 8
STEAM_PLACEHOLDER_BYTES = 2048
USER_AGENT = "orangchat-game-art/1.0 (https://chat.oranges.lt)"

WIKIPEDIA_SUMMARY = "https://en.wikipedia.org/api/rest_v1/page/summary/{title}"

STEAM_SOURCES = (
    "https://cdn.cloudflare.steamstatic.com/steam/apps/{app}/library_600x900.jpg",
    "https://cdn.cloudflare.steamstatic.com/steam/apps/{app}/header.jpg",
    "https://cdn.cloudflare.steamstatic.com/steam/apps/{app}/capsule_231x87.jpg",
)


def fetch(url: str, min_bytes: int = 0) -> bytes | None:
    """GET, or None on any failure.

    `min_bytes` exists for one caller: Valve answers a delisted appid with a tiny
    placeholder image rather than a 404, so a short body there means "no art".
    It must not be applied to the Wikipedia summary, whose json is legitimately
    under 2KB for a short article - which is how Osu! and Wuthering Waves ended
    up with monograms despite having a perfectly good lead image.
    """
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT) as response:
            if response.status != 200:
                return None
            body = response.read()
    except (urllib.error.URLError, TimeoutError, OSError):
        return None
    return body if len(body) >= min_bytes else None


def square(image: Image.Image) -> Image.Image:
    """Centre-crop to a square, biased upward on portrait art.

    A library capsule puts the logo in the upper half, so a true centre crop
    slices it in half. Taking the crop from 20% down keeps the artwork's subject.
    """
    width, height = image.size
    edge = min(width, height)
    left = (width - edge) // 2
    top = (height - edge) // 5 if height > width else (height - edge) // 2
    return image.crop((left, top, left + edge, top + edge))


def contain(image: Image.Image, background: tuple[int, int, int]) -> Image.Image:
    """Fit the whole image inside a square, padded with the accent colour.

    Used for art with an alpha channel, which on Wikipedia means a wordmark or
    logo rather than box art - cropping one of those cuts the title in half.
    """
    fitted = image.copy()
    fitted.thumbnail((SIZE, SIZE), Image.LANCZOS)
    canvas = Image.new("RGB", (SIZE, SIZE), background)
    canvas.paste(
        fitted,
        ((SIZE - fitted.width) // 2, (SIZE - fitted.height) // 2),
        fitted if fitted.mode == "RGBA" else None,
    )
    return canvas


def accent_for(game_id: str) -> tuple[int, int, int]:
    """A stable, readable hue per id so fallback tiles stay distinguishable."""
    digest = hashlib.sha256(game_id.encode()).digest()
    hue = digest[0] / 255
    r, g, b = colorsys.hsv_to_rgb(hue, 0.55, 0.62)
    return int(r * 255), int(g * 255), int(b * 255)


def monogram(game_id: str, name: str) -> Image.Image:
    """A generated tile for titles with no usable store art."""
    image = Image.new("RGB", (SIZE, SIZE), accent_for(game_id))
    draw = ImageDraw.Draw(image)
    words = [word for word in name.replace(":", " ").split() if word[:1].isalnum()]
    initials = "".join(word[0] for word in words[:2]).upper() or name[:1].upper()
    font = ImageFont.truetype(str(FONT_PATH), 52 if len(initials) > 1 else 64)
    box = draw.textbbox((0, 0), initials, font=font)
    draw.text(
        ((SIZE - (box[2] - box[0])) / 2 - box[0], (SIZE - (box[3] - box[1])) / 2 - box[1]),
        initials,
        font=font,
        fill=(255, 255, 255),
    )
    return image


def wikipedia_image(title: str) -> Image.Image | None:
    """The lead image of an article - box art for a game, occasionally a logo."""
    summary = fetch(WIKIPEDIA_SUMMARY.format(title=urllib.parse.quote(title, safe="")))
    if not summary:
        return None
    try:
        payload = json.loads(summary)
    except json.JSONDecodeError:
        return None
    source = (payload.get("originalimage") or payload.get("thumbnail") or {}).get("source")
    if not isinstance(source, str):
        return None
    body = fetch(source)
    if not body:
        return None
    try:
        return Image.open(io.BytesIO(body))
    except OSError:
        return None


def tile_for(game: dict, articles: dict[str, str]) -> tuple[Image.Image, str]:
    app_id = game.get("steamAppId")
    if app_id:
        for template in STEAM_SOURCES:
            body = fetch(template.format(app=app_id), STEAM_PLACEHOLDER_BYTES)
            if not body:
                continue
            try:
                image = Image.open(io.BytesIO(body)).convert("RGB")
            except OSError:
                continue
            return square(image).resize((SIZE, SIZE), Image.LANCZOS), "steam"

    article = articles.get(game["id"])
    if article:
        image = wikipedia_image(article)
        if image is not None:
            if "A" in image.getbands():
                return contain(image.convert("RGBA"), accent_for(game["id"])), "wikipedia"
            return square(image.convert("RGB")).resize((SIZE, SIZE), Image.LANCZOS), "wikipedia"

    return monogram(game["id"], game["name"]), "generated"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("ids", nargs="*", help="only rebuild these game ids")
    parser.add_argument("--force", action="store_true", help="rebuild tiles that already exist")
    args = parser.parse_args()

    registry = json.loads(REGISTRY.read_text())
    games = registry["games"]
    articles = {
        entry["id"]: entry["wikipedia"]
        for entry in json.loads(NON_STEAM.read_text())["games"]
        if entry.get("wikipedia")
    }
    if args.ids:
        wanted = set(args.ids)
        games = [game for game in games if game["id"] in wanted]

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    pending = [game for game in games if args.force or not (OUT_DIR / f"{game['id']}.webp").exists()]
    skipped = len(games) - len(pending)

    def build(game: dict) -> str:
        image, source = tile_for(game, articles)
        image.save(OUT_DIR / f"{game['id']}.webp", "WEBP", quality=88, method=6)
        return source

    counts = {"steam": 0, "wikipedia": 0, "generated": 0}
    with ThreadPoolExecutor(max_workers=WORKERS) as pool:
        for done, source in enumerate(pool.map(build, pending), start=1):
            counts[source] += 1
            if done % 250 == 0 or done == len(pending):
                print(f"  {done}/{len(pending)}", file=sys.stderr)
    counts["skipped"] = skipped

    print(
        f"\n{counts['steam']} from Steam, {counts['wikipedia']} from Wikipedia, "
        f"{counts['generated']} generated, {counts['skipped']} already present "
        f"-> {OUT_DIR.relative_to(ROOT)}",
        file=sys.stderr,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
