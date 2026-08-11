# i18n

This client is internationalized. Never hardcode user-facing strings directly in JSX/TSX.

- English source of truth: `src/lib/i18n/en.ts` - flat dot-namespaced keys (`namespace.camelCaseSlug`), grouped into `// ── Namespace ── ...` sections, kept alphabetical within each section.
- Translations: `src/lib/i18n/lt.ts` (Lithuanian) - a `Partial<Catalog>`, missing keys fall back to English.
- `src/lib/i18n/index.ts` exports the runtime:
  - `t(key, vars?)` - plain string lookup, `{name}`-style interpolation.
  - `tCount(key, count, vars?)` - pluralized lookup via `Intl.PluralRules`; the key is the stem, catalogue entries are `key_one` / `key_few` / `key_many` / `key_other`.
  - `tNodes(key, slots, vars?)` - like `t()` but for a sentence with something rendered inside it (a link, bold name, button) instead of plain text; `slots` maps `{name}` placeholders to `ReactNode`s.
- New/changed UI text: add the English string to `en.ts` under the matching namespace (alphabetical), then call `t`/`tCount`/`tNodes` at the call site. Don't forget the import: `import { t } from "../../lib/i18n";` (adjust relative path/added helpers as needed).
   - Watch for ternaries or template literals with embedded strings (`cond ? "A" : "B"`, `` `Foo ${x}` ``) - these don't get flagged by tooling the way JSX text does, but still need to go through `t()`.

Android uses the same English-first approach through `res/values/strings.xml` and
an intentionally empty `res/values-lt/strings.xml` fallback. Locale selection is
device-local and applied by `LocalizedActivity`; use `tools/extract_english_strings.py`
to regenerate the reviewed Android candidate catalogue.

## Community translation (Weblate)

The 10 real locale catalogues are community-translatable through the
self-hosted Weblate instance at `chat.oranges.lt/translation`. Weblate can't
edit `.ts` files, so `tools/i18n-bridge/` bridges them to flat JSON in
`i18n-weblate/`: `pnpm i18n:extract` turns the `.ts` catalogues into JSON
before syncing to Weblate, `pnpm i18n:regenerate` turns Weblate's translated
JSON back into `.ts` after pulling. See `tools/i18n-bridge/README.md`.
`pirate.ts`/`lolcat.ts` are excluded - they're generated from `en.ts` at
import time, not stored translations, so there's nothing for a translator to
edit.
