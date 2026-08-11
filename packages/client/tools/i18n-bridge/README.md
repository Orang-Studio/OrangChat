# i18n bridge

Weblate edits flat JSON files in `../../i18n-weblate/` and the bridge keeps
those files synchronized with the TypeScript catalogues in `src/lib/i18n/`.

- `pnpm i18n:extract` writes JSON from the TypeScript catalogues.
- `pnpm i18n:regenerate` writes translated JSON back to the TypeScript files.

The ten real locales round-trip through this bridge. Pirate and LOLCAT are
generated from English at runtime and are not translation files.

Weblate is available at `https://oranges.lt/translate/`. Its component reads
`packages/client/i18n-weblate/*.json` from the `main` branch.
