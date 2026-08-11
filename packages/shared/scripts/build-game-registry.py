
"""Regenerate packages/shared/games.json - the game-presence detection registry.

The executable data comes from Discord's public `applications/detectable`
endpoint, which is the industry's de-facto game database: ~10k titles, each with
the process names its client watches for and, for most, the Steam appid. Nothing
here is hand-typed; the earlier approach of scraping Steam's charts covered ~150
games, and appinfo's launch list is what Steam *runs* rather than what ends up in
the process list.

Three things the raw feed needs before it can drive presence:

  1. Discord matches a path suffix (`game/eldenring.exe`); we compare bare
     process names, so entries are reduced to a basename.
  2. `>`-prefixed entries mean "exact match only" and are the generic runtimes -
     `>javaw.exe` for Minecraft would otherwise claim every Java program.
  3. 427 executable names are claimed by more than one title (`game.exe` alone
     by 192). A shared name does not merely miss a game, it announces the wrong
     one, so it is dropped from every entry that claims it.

Run fetch-game-art.py afterwards to build the artwork tiles.

Usage:  python3 packages/shared/scripts/build-game-registry.py
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / "packages" / "shared" / "games.json"
HERE = Path(__file__).resolve().parent
OVERRIDES = HERE / "non-steam-games.json"

TIMEOUT = 60
USER_AGENT = "orangchat-game-registry/2.0 (https://chat.oranges.lt)"

DETECTABLE = "https://discord.com/api/v9/applications/detectable"

AMBIGUOUS = {
    "game.exe",
    "launcher.exe",
    "launch.exe",
    "start.exe",
    "start_protected_game.exe",
    "main.exe",
    "client.exe",
    "app.exe",
    "engine.exe",
    "autorun.exe",
    "java.exe",
    "javaw.exe",
    "java",
    "javaw",
    "python.exe",
    "nw.exe",
    "electron.exe",
    "dosbox.exe",
    "retroarch.exe",
    "steam.exe",
}

SKIP_EXECUTABLE = re.compile(
    r"(anticheat|battleye|easyanti|crashhandler|crashreport|unins|setup"
    r"|redist|benchmark|dedicated|-server\b|_server\b|\bserver\.exe$)",
    re.I,
)


def get_json(url: str, quiet: bool = False) -> dict | list | None:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT) as response:
            return json.loads(response.read())
    except (urllib.error.URLError, TimeoutError, OSError, json.JSONDecodeError) as error:
        if not quiet:
            print(f"  ! {url}: {error}", file=sys.stderr)
        return None


def slug(name: str) -> str:
    """A stable, filename-safe id. Also the artwork filename."""
    cleaned = re.sub(r"[^a-z0-9]+", "", name.lower().replace("&", "and"))
    return cleaned or "game"


def normalize_executable(raw: str) -> str | None:
    """A detectable-feed entry -> the bare process name to compare against.

    Returns None for the entries that are not usable as a bare name: `>`-prefixed
    exact-match runtimes, and anything with no letters in it.
    """
    if raw.startswith(">"):
        return None
    name = re.split(r"[\\/]", raw)[-1].strip().lower()
    if not name or any(character in name for character in "?=&"):
        return None
    if name.endswith(".app"):
        name = name[: -len(".app")]
    if name in AMBIGUOUS or SKIP_EXECUTABLE.search(name):
        return None
    return name if re.search(r"[a-z]", name) else None


def steam_app_id(app: dict) -> int | None:
    for sku in app.get("third_party_skus") or []:
        if sku.get("distributor") == "steam" and str(sku.get("id", "")).isdigit():
            return int(sku["id"])
    return None


def build_entries(feed: list) -> list[dict]:
    """One registry entry per distinct title that has a usable process name.

    The feed is not one record per game: World of Warcraft is three, each with
    its own launch configuration, and there are hundreds of pairs like it. Those
    have to be merged rather than kept apart, because keeping them apart makes
    every executable they share look like a collision between two titles - which
    is how `wow.exe` got pruned out of the registry entirely.

    Merging is keyed on the *exact* name, not the slug: if two records call
    themselves the same thing then the presence line reads the same either way,
    so there is nothing to get wrong. Names that merely slugify alike ("Portal 2"
    against "Portal-2") stay separate and the later one is qualified by appid.
    """
    by_name: dict[str, dict] = {}
    used_ids: set[str] = set()

    for app in feed:
        name = app.get("name")
        if not isinstance(name, str) or not name.strip():
            continue
        name = name.strip()

        executables: list[str] = []
        for executable in app.get("executables") or []:
            if not isinstance(executable, dict):
                continue
            normalized = normalize_executable(str(executable.get("name", "")))
            if normalized and normalized not in executables:
                executables.append(normalized)
        if not executables:
            continue

        app_id = steam_app_id(app)
        existing = by_name.get(name)
        if existing is not None:
            for normalized in executables:
                if normalized not in existing["executables"]:
                    existing["executables"].append(normalized)
            existing.setdefault("steamAppId", app_id)
            if existing["steamAppId"] is None:
                existing["steamAppId"] = app_id
            continue

        identifier = slug(name)
        if identifier in used_ids:
            identifier = f"{identifier}{app_id}" if app_id else f"{identifier}-{app['id']}"
            if identifier in used_ids:
                continue
        used_ids.add(identifier)
        by_name[name] = {
            "id": identifier,
            "name": name,
            "executables": executables,
            "steamAppId": app_id,
        }

    return [
        {key: value for key, value in entry.items() if value is not None}
        for entry in by_name.values()
    ]


def apply_overrides(entries: list[dict]) -> list[dict]:
    """Merge the hand file: patch an existing id, or add a missing title.

    Patching has to target an id the feed already produced. Inventing a second id
    for a title the feed also carries is the one thing that cannot work here: the
    two entries then claim the same executable, and drop_collisions - which has no
    way to tell a real ambiguity from this - removes it from both. That is how
    `gta5.exe` disappeared while a hand entry existed specifically to add it.
    """
    by_id = {entry["id"]: entry for entry in entries}
    for override in json.loads(OVERRIDES.read_text())["games"]:
        existing = by_id.get(override["id"])
        if existing is None:
            fresh = {
                key: override[key]
                for key in ("id", "name", "executables", "steamAppId")
                if key in override
            }
            entries.append(fresh)
            by_id[fresh["id"]] = fresh
            continue
        for executable in override["executables"]:
            if executable not in existing["executables"]:
                existing["executables"].append(executable)
        if override.get("steamAppId") and not existing.get("steamAppId"):
            existing["steamAppId"] = override["steamAppId"]
    return entries


def drop_collisions(entries: list[dict]) -> list[dict]:
    """Remove every executable that more than one title claims.

    `game.exe` is claimed by 192 apps and `hl2.exe` by the whole GoldSrc/Source
    catalogue. Announcing one of those would name the wrong game to everyone in
    the member list, which is worse than not detecting it at all.
    """
    owners: dict[str, set[str]] = {}
    for entry in entries:
        for executable in entry["executables"]:
            owners.setdefault(executable, set()).add(entry["id"])
    shared = {name for name, ids in owners.items() if len(ids) > 1}

    kept: list[dict] = []
    for entry in entries:
        executables = [name for name in entry["executables"] if name not in shared]
        if executables:
            kept.append({**entry, "executables": executables})
    print(
        f"  dropped {len(shared)} ambiguous names, {len(entries) - len(kept)} entries left empty",
        file=sys.stderr,
    )
    return kept


def main() -> int:
    argparse.ArgumentParser().parse_args()

    print("Fetching Discord's detectable applications…", file=sys.stderr)
    feed = get_json(DETECTABLE)
    if not isinstance(feed, list):
        print("could not read the detectable feed", file=sys.stderr)
        return 1
    print(f"  {len(feed)} applications", file=sys.stderr)

    entries = drop_collisions(apply_overrides(build_entries(feed)))
    entries.sort(key=lambda entry: entry["name"].lower())

    OUT.write_text(
        json.dumps(
            {
                "version": 2,
                "note": (
                    "GENERATED by scripts/build-game-registry.py from Discord's public "
                    "applications/detectable feed, merged with scripts/non-steam-games.json. "
                    "Do not hand-edit: put additions and corrections in non-steam-games.json "
                    "and re-run. Executables are matched case-insensitively against the bare "
                    "process name; a name claimed by more than one title is dropped from both, "
                    "so a match is never the wrong game. Only titles listed here are "
                    "auto-detected, which is what keeps an unrecognised process from ever "
                    "becoming public presence text."
                ),
                "games": entries,
            },
            indent=1,
            ensure_ascii=False,
        )
        + "\n"
    )

    with_art = sum(1 for entry in entries if entry.get("steamAppId"))
    executables = sum(len(entry["executables"]) for entry in entries)
    print(
        f"\n{len(entries)} games, {executables} executables "
        f"({with_art} with Steam artwork) -> {OUT.relative_to(ROOT)}",
        file=sys.stderr,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
