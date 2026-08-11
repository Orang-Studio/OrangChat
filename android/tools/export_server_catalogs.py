
"""Export Android string catalogs for the server-served i18n mechanism.

Reads every `res/values-*/strings.xml` (plus `res/values/` itself for English)
and writes `packages/server-rs/i18n/android/<code>.json` - the files the Rust
backend serves on `/i18n/languages` and `/i18n/catalog`. The APK keeps its
bundled copies as the offline fallback; these JSON files are what lets a
translation fix, or an entirely new language, reach devices without an app
release.

Adding a language:
  1. create `res/values-de/strings.xml` with the 492 translated keys
  2. add `"de": "language_german"` to ENDONYMS below (the English picker label
     in `res/values/strings.xml`)
  3. run this script and commit the generated JSON
  4. restart the server (catalogs load at boot)

`rev` is the sha1 of the canonical JSON of the strings map, so it changes
exactly when a translation does - clients use it to skip unchanged fetches.
"""

from __future__ import annotations

import hashlib
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
RES = REPO / "android/app/src/main/res"
OUT = REPO / "packages/server-rs/i18n/android"

ENDONYMS = {
    "en": "language_english",
    "lt": "language_lithuanian",
    "zh": "language_chinese",
    "hi": "language_hindi",
    "es": "language_spanish",
    "ar": "language_arabic",
    "fr": "language_french",
    "bn": "language_bengali",
    "pt": "language_portuguese",
    "ru": "language_russian",
    "ur": "language_urdu",
}
FUN_ENDONYMS = {
    "en-x-pirate": "Pirate",
    "en-x-lolcat": "LOLCAT",
}

ANDROID_ESCAPES = {
    "n": "\n",
    "t": "\t",
    "r": "\r",
    "b": "\b",
    "f": "\f",
    "'": "'",
    '"': '"',
    "\\": "\\",
}


def dir_code(dirname: str) -> str:
    """`values` -> en, `values-lt` -> lt, `values-b+en+x+pirate` -> en-x-pirate."""
    suffix = dirname.removeprefix("values").lstrip("-")
    if not suffix:
        return "en"
    if suffix.startswith("b+"):
        return suffix[2:].replace("+", "-")
    return suffix


def android_unescape(text: str) -> str:
    """Decode Android resource escapes the way aapt does at build time.

    ElementTree has already handled XML entities (`&amp;` etc.); what remains
    are the backslash escapes Android itself understands.
    """
    out: list[str] = []
    i = 0
    while i < len(text):
        char = text[i]
        if char == "\\" and i + 1 < len(text):
            nxt = text[i + 1]
            if nxt in ANDROID_ESCAPES:
                out.append(ANDROID_ESCAPES[nxt])
                i += 2
                continue
            if nxt == "u" and i + 5 < len(text):
                try:
                    out.append(chr(int(text[i + 2 : i + 6], 16)))
                    i += 6
                    continue
                except ValueError:
                    pass
        out.append(char)
        i += 1
    return "".join(out)


def read_catalog(dirpath: Path, code: str) -> dict[str, str]:
    strings: dict[str, str] = {}
    tree = ET.parse(dirpath / "strings.xml")
    for elem in tree.getroot():
        if elem.tag != "string":
            continue
        strings[elem.get("name", "")] = android_unescape(elem.text or "")
    return strings


def rev_of(strings: dict[str, str]) -> str:
    canonical = json.dumps(strings, sort_keys=True, ensure_ascii=False, separators=(",", ":"))
    return hashlib.sha1(canonical.encode("utf-8")).hexdigest()


def main() -> None:
    english = read_catalog(RES / "values", "en")

    catalogs: dict[str, dict] = {}
    for dirpath in sorted(RES.iterdir()):
        if not (dirpath.is_dir() and dirpath.name.startswith("values")):
            continue
        if not (dirpath / "strings.xml").is_file():
            print(f"skipping {dirpath.name} (no strings.xml)")
            continue
        code = dir_code(dirpath.name)
        endonym = FUN_ENDONYMS.get(code)
        if endonym is None:
            endonym_key = ENDONYMS.get(code)
            endonym = english.get(endonym_key, code) if endonym_key else code
            if endonym_key is None:
                print(f"⚠  {code}: no ENDONYMS entry; picker will show the bare code")
        strings = read_catalog(dirpath, code)
        catalogs[code] = {
            "code": code,
            "endonym": endonym,
            "rev": rev_of(strings),
            "strings": strings,
        }

    OUT.mkdir(parents=True, exist_ok=True)
    for code, catalog in sorted(catalogs.items()):
        target = OUT / f"{code}.json"
        target.write_text(
            json.dumps(catalog, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        print(f"wrote {target.relative_to(REPO)} ({len(catalog['strings'])} strings)")


if __name__ == "__main__":
    main()
