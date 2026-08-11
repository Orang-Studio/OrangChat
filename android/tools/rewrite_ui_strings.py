
"""Bulk-rewrite hardcoded UI string literals in Kotlin Compose files into
res/values/strings.xml + context.getString(R.string.x) call sites.

Mirrors the web i18n codemod: scripted bulk pass, then manual review of what
it declines to touch. Only rewrites literals sitting in a curated whitelist of
UI-text positions (Text("..."), and named args like label/title/hint/...) so
identifiers, log messages, protocol/data values are left alone by construction
rather than filtered out after the fact.

Two context strategies, matching the two shapes already in this codebase:
  - Composable functions: ensure `val context = LocalContext.current` exists
    in the enclosing function body (inserted if missing), matching the
    existing convention of context.getString(...) over stringResource(...).
  - Everything else (ViewModels, services, receivers): only rewrite within
    functions/classes that already have a `context`-typed value in scope
    (constructor property or parameter). Files needing new @ApplicationContext
    injection are reported, not touched, for manual review.
"""

from __future__ import annotations

import argparse
import hashlib
import html
import re
import sys
from pathlib import Path

SKIP_EXACT = {
    "true", "false", "null", "default", "none", "online", "offline", "idle", "dnd",
    "mobile", "desktop", "browser", "GET", "POST", "PUT", "DELETE",
}


def decode(value: str) -> str:
    return bytes(value, "utf-8").decode("unicode_escape") if "\\" in value else value


def candidate(value: str) -> bool:
    value = value.strip()
    if len(value) < 3 or "\n" in value or "${" in value or "\\" in value or any(ord(char) < 32 and char not in "\t" for char in value) or not re.search(r"[A-Za-zÀ-ž]", value):
        return False
    if value in SKIP_EXACT or value.startswith(("http://", "https://", "/", "--", "rgb", "rgba", "application/", "image/")):
        return False
    if re.fullmatch(r"[A-Za-z0-9_.:/-]+", value) or re.search(r"[%^$\\]", value):
        return False
    return True


def slug(value: str) -> str:
    words = re.findall(r"[A-Za-z0-9]+", value.lower())[:7]
    stem = "_".join(words) or "text"
    digest = hashlib.sha1(value.encode()).hexdigest()[:8]
    return f"catalog_{stem}_{digest}"


ATTRS = [
    "text", "label", "contentDescription", "placeholder", "title", "hint",
    "description", "subtitle", "confirmText", "dismissText", "supportingText",
    "notice", "error", "body", "recordingError", "recordingHint", "scanError",
    "transferError", "done",
]
ATTR_RE = re.compile(r'\b(' + '|'.join(ATTRS) + r')\s*=\s*"((?:\\.|[^"\\])*)"')
TEXT_CALL_RE = re.compile(r'\bText\(\s*"((?:\\.|[^"\\])*)"')

POSITIONAL1_NAMES = [
    "SettingSection", "Section", "SettingsTopBar", "EmptyHint", "Body", "Hint",
    "MenuItem", "InfoRow", "SettingsNavRow",
]
POSITIONAL1_RE = re.compile(r'\b(?:' + '|'.join(POSITIONAL1_NAMES) + r')\(\s*"((?:\\.|[^"\\])*)"')
SETTINGS_NAV_ROW_SUBTITLE_RE = re.compile(
    r'\bSettingsNavRow\(\s*"(?:\\.|[^"\\])*"\s*,\s*"((?:\\.|[^"\\])*)"\s*,',
)

ARROW_RE = re.compile(r'->\s*"((?:\\.|[^"\\])*)"')
ELVIS_RE = re.compile(r'\?:\s*"((?:\\.|[^"\\])*)"')
ELSE_RE = re.compile(r'\belse\s+"((?:\\.|[^"\\])*)"')
THEN_RE = re.compile(r'\bif\s*\([^()]*\)\s*"((?:\\.|[^"\\])*)"')

FILE_SKIP = {"ProfileCardHtml.kt"}

TOKEN_RE = re.compile(r'"""(?:.|\n)*?"""|"(?:\\.|[^"\\])*"|//[^\n]*|/\*.*?\*/|[(){}]', re.DOTALL)
FUN_SIG_RE = re.compile(
    r'(?P<annot>@Composable\s*\n\s*)?'
    r'(?:(?:private|internal|public|protected)\s+)?(?:suspend\s+)?fun\s+\w+\s*(?:<[^>\n]*>)?\s*\(',
)


def find_functions(text: str) -> list[dict]:
    """Return function regions: {start, end, composable, sig_start} for every `fun` body brace pair."""
    funcs = []
    for m in FUN_SIG_RE.finditer(text):
        sig_start = m.start()
        depth = 1
        params_end = None
        for tok in TOKEN_RE.finditer(text, m.end()):
            t = tok.group(0)
            if t == "(":
                depth += 1
            elif t == ")":
                depth -= 1
                if depth == 0:
                    params_end = tok.end()
                    break
        if params_end is None:
            continue
        brace_pos = None
        is_expr_body = False
        for tok in TOKEN_RE.finditer(text, params_end):
            t = tok.group(0)
            if t == "{":
                brace_pos = tok.end()
                break
            if t in ("(", ")"):
                continue
            if t == "}":
                break
        if brace_pos is None:
            continue
        if "=" in text[params_end:brace_pos - 1]:
            continue

        body_start = brace_pos
        depth = 1
        end = None
        for tok in TOKEN_RE.finditer(text, body_start):
            t = tok.group(0)
            if t == "{":
                depth += 1
            elif t == "}":
                depth -= 1
                if depth == 0:
                    end = tok.start()
                    break
        if end is None:
            continue
        composable = m.group("annot") is not None
        funcs.append({"start": body_start, "end": end, "composable": composable, "sig_start": sig_start})
    return funcs


def fn_has_context_declared(text: str, fn: dict) -> bool:
    """True if `context` resolves to something already declared for this
    function - a parameter (`context: Context` in the signature) or a local
    `val context = ...`. Deliberately does NOT match `context.foo(...)`
    *usage*, since after rewriting every extracted literal becomes exactly
    that usage - matching on usage would make this always true post-rewrite
    and silently skip the declaration it's there to guarantee.
    """
    sig = text[fn["sig_start"]:fn["start"]]
    body = text[fn["start"]:fn["end"]]
    if re.search(r'\bcontext\s*:\s*(?:android\.content\.)?Context\b', sig):
        return True
    if re.search(r'\bval\s+context\b', body):
        return True
    return False


def enclosing_function(funcs: list[dict], pos: int) -> dict | None:
    best = None
    for f in funcs:
        if f["start"] <= pos < f["end"]:
            if best is None or f["start"] > best["start"]:
                best = f
    return best


def process_file(path: Path, catalogue: dict[str, tuple[str, str]], report: list[str], apply: bool) -> None:
    if path.name in FILE_SKIP:
        return
    text = path.read_text(encoding="utf-8")
    funcs = find_functions(text)

    def add_group(m: re.Match, group_idx: int, kind: str, out: list) -> None:
        value = decode(m.group(group_idx))
        if candidate(value):
            lit_start = m.start(group_idx) - 1
            lit_end = m.end(group_idx) + 1
            out.append((lit_start, lit_end, m.group(group_idx), kind))

    matches = []
    for m in ATTR_RE.finditer(text):
        add_group(m, 2, "attr", matches)
    for m in TEXT_CALL_RE.finditer(text):
        add_group(m, 1, "text", matches)
    for m in POSITIONAL1_RE.finditer(text):
        add_group(m, 1, "positional1", matches)
    for m in SETTINGS_NAV_ROW_SUBTITLE_RE.finditer(text):
        add_group(m, 1, "positional2", matches)
    for m in ARROW_RE.finditer(text):
        add_group(m, 1, "arrow", matches)
    for m in ELVIS_RE.finditer(text):
        add_group(m, 1, "elvis", matches)
    for m in ELSE_RE.finditer(text):
        add_group(m, 1, "else", matches)
    for m in THEN_RE.finditer(text):
        add_group(m, 1, "then", matches)

    if not matches:
        return

    matches.sort(key=lambda t: t[0])
    deduped = []
    last_end = -1
    for start, end, raw, kind in matches:
        if start < last_end:
            continue
        deduped.append((start, end, raw, kind))
        last_end = end

    has_class_context = bool(re.search(r'\bval\s+context\s*:\s*(?:android\.content\.)?Context\b', text))

    assigned = []
    for start, end, raw, kind in deduped:
        fn = enclosing_function(funcs, start)
        if fn is None:
            report.append(f"SKIP no-enclosing-fun: {path}: {raw[:60]!r}")
            continue
        if not fn["composable"] and not has_class_context:
            report.append(f"SKIP non-composable-fun: {path}: {raw[:60]!r}")
            continue
        assigned.append((start, end, raw, fn))

    if not assigned:
        return

    assigned.sort(key=lambda t: t[0], reverse=True)
    new_text = text
    used_here: dict[str, str] = {}
    for start, end, raw, fn in assigned:
        value = decode(raw)
        key = slug(value)
        used_here[key] = value
        catalogue.setdefault(key, (value, str(path)))
        replacement = f'context.getString(R.string.{key})'
        new_text = new_text[:start] + replacement + new_text[end:]

    if not used_here:
        return

    funcs2 = find_functions(new_text)
    to_fix = []
    for fn in funcs2:
        if not fn["composable"]:
            continue
        body = new_text[fn["start"]:fn["end"]]
        if "R.string." not in body:
            continue
        if fn_has_context_declared(new_text, fn):
            continue
        to_fix.append(fn["start"])
    for insert_at in sorted(set(to_fix), reverse=True):
        new_text = (
            new_text[:insert_at]
            + "\n        val context = LocalContext.current"
            + new_text[insert_at:]
        )

    needs_import = "R.string." in new_text and "import lt.oranges.orangchat.R\n" not in new_text
    needs_local_context_import = (
        "val context = LocalContext.current" in new_text
        and "import androidx.compose.ui.platform.LocalContext\n" not in new_text
    )

    if needs_import or needs_local_context_import:
        lines = new_text.split("\n")
        pkg_idx = next(i for i, l in enumerate(lines) if l.startswith("package "))
        import_idx = pkg_idx + 1
        while import_idx < len(lines) and lines[import_idx].strip() == "":
            import_idx += 1
        inserts = []
        if needs_local_context_import:
            inserts.append("import androidx.compose.ui.platform.LocalContext")
        if needs_import:
            inserts.append("import lt.oranges.orangchat.R")
        for line in reversed(inserts):
            lines.insert(import_idx, line)
        new_text = "\n".join(lines)

    report.append(f"OK {path}: {len(used_here)} strings extracted")
    if apply:
        path.write_text(new_text, encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path("app/src/main/java/lt/oranges/orangchat"))
    parser.add_argument("--dirs", nargs="+", default=["feature", "ui"])
    parser.add_argument("--apply", action="store_true", help="write changes; default is dry-run report")
    parser.add_argument("--catalogue-out", type=Path, default=Path("app/src/main/res/values/strings_extracted.xml"))
    args = parser.parse_args()

    catalogue: dict[str, tuple[str, str]] = {}
    report: list[str] = []
    files = []
    for d in args.dirs:
        files.extend(sorted((args.root / d).rglob("*.kt")))

    for f in files:
        process_file(f, catalogue, report, args.apply)

    for line in report:
        print(line)
    print(f"\n{sum(1 for l in report if l.startswith('OK'))} files touched, {len(catalogue)} unique strings", file=sys.stderr)

    if args.apply:
        args.catalogue_out.parent.mkdir(parents=True, exist_ok=True)
        lines = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>"]
        for name, (value, _source) in sorted(catalogue.items()):
            escaped = html.escape(value, quote=False).replace("'", "\\'").replace('"', '\\"')
            lines.append(f'    <string name="{name}" formatted="false">{escaped}</string>')
        lines.append("</resources>")
        args.catalogue_out.write_text("\n".join(lines) + "\n", encoding="utf-8")
        print(f"wrote {len(catalogue)} strings to {args.catalogue_out}", file=sys.stderr)


if __name__ == "__main__":
    main()
