
"""Route every `context.getString(R.string.x)` through AppStrings.get(...).

The server-served i18n mechanism only works if string lookups can see the
fetched catalogues, which the plain resources path cannot. This is the
mechanical pass: rewrite the call, add the import. Run once; the result is
committed and the script stays as the record of the change.

Usage: python3 android/tools/route_strings_through_appstrings.py
"""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2] / "android/app/src/main/java"
IMPORT = "import lt.oranges.orangchat.util.AppStrings"
CALL = re.compile(r"\bcontext\.getString\(R\.string\.(?=\w)")


def insert_import(lines: list[str]) -> list[str]:
    if any(line.strip() == IMPORT for line in lines):
        return lines
    new_import = IMPORT + "\n"
    for index, line in enumerate(lines):
        if line.startswith("import "):
            if line.strip() > IMPORT:
                return lines[:index] + [new_import] + lines[index:]
        elif line.startswith(("package ", "/*", "*", "//")):
            continue
        elif index == 0:
            return [new_import] + lines
    return lines[:1] + [new_import] + lines[1:]


def main() -> None:
    total = 0
    for path in sorted(ROOT.rglob("*.kt")):
        original = path.read_text(encoding="utf-8")
        if "context.getString(R.string." not in original:
            continue
        replaced, count = CALL.subn("AppStrings.get(context, R.string.", original)
        if count == 0:
            continue
        lines = replaced.splitlines(keepends=True)
        lines = insert_import(lines)
        path.write_text("".join(lines), encoding="utf-8")
        total += count
        print(f"{path.relative_to(ROOT)}: {count}")
    print(f"\n{total} call sites rewritten")


if __name__ == "__main__":
    main()
