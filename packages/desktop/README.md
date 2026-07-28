# @orangchat/desktop

Windows desktop shell for OrangChat. The window loads the live site
(`https://chat.oranges.lt`) rather than bundling the client build, so `/api`,
`/socket.io` and the httpOnly refresh cookie stay same-origin and neither the
client nor the backend needs a desktop-specific code path. Web deploys reach
desktop users without shipping a new installer.

Point it somewhere else with `ORANGCHAT_URL=http://localhost:5173 pnpm start`.

## What the shell adds over a browser tab

| Feature | Where |
| --- | --- |
| Tray icon, close-to-tray (keeps calls alive when the window is closed) | `src/tray.ts` |
| Screen-share source picker (`getDisplayMedia` fails without this in Electron) | `src/screenPicker.ts`, `src/picker.html` |
| Taskbar flash + tray tooltip when a notification fires unfocused | `src/preload.ts` |
| Download progress on the taskbar, notification -> show in folder | `src/downloads.ts` |
| Taskbar unread badge (`setBadgeCount` is a no-op on Windows; needs an overlay icon) | `src/badge.ts` |
| Auto-update on startup, then every 6h, plus a manual Help -> Check for Updates and a tray item (both report their result) | `src/updater.ts` |
| Start with Windows, close-to-tray toggles | `src/settings.ts`, `src/autoLaunch.ts` |
| Window geometry + zoom persistence | `src/windowState.ts`, `src/settings.ts` |
| `orangchat://` deep links (invites), single-instance focus | `src/config.ts`, `src/main.ts` |
| Global show/hide hotkey (`Ctrl+Shift+O`) | `src/main.ts` |

Settings and window state live in `%APPDATA%/OrangChat/`.

## Security posture

`contextIsolation` and `sandbox` on, `nodeIntegration` off. Navigation and
`window.open` are pinned to the app origin - anything else opens in the system
browser. Permission requests (mic, camera, notifications) are granted only to
the app origin and only from an allowlist; every other permission is denied.
IPC senders are origin-checked. The preload exposes `window.orangchatDesktop`
(`isDesktop`, `platform`, `version`, `setBadgeCount`, `flashFrame`).

`setBadgeCount` is the one hook the client does not call yet: nothing in
`packages/client` tracks a global unread total or touches `document.title`, so
the shell has no way to derive one on its own. Wiring `stores/unread.ts` to
`window.orangchatDesktop?.setBadgeCount(total)` is all that is needed - it is a
no-op in browsers - but it requires a client build and deploy to take effect.
The taskbar flash works today because it hangs off the existing Notification API.

## Building

```bash
pnpm build          # compile main/preload to dist/
pnpm start          # run the shell locally
pnpm dist:win:docker  # NSIS installer + portable zip (recommended)
pnpm dist:win       # same targets without Docker; exe icon needs wine
```

`dist:win:docker` builds inside `electronuserland/builder:wine`, because
electron-builder needs wine to stamp the `.exe` icon and run makensis. The
first run pulls a ~2 GB image. Output lands in `release/`:

- `OrangChat-Setup-<version>.exe` - installer (per-user, choosable directory)
- `OrangChat-<version>-x64.zip` - portable

Neither artifact is code-signed, so Windows SmartScreen will warn on first run
until a signing certificate is configured.

## Releasing

Downloads are served from `https://chat.oranges.lt/download/windows/`, mirroring
the `/download/android/` setup (nginx block in `deploy/`-adjacent
`/etc/nginx/sites-available/chat.oranges.lt`).

1. Bump `version` in `package.json` - electron-updater compares against it, so a
   build that reuses a published version is invisible to existing installs.
2. `pnpm dist:win:docker`
3. Copy `OrangChat-Setup-<version>.exe`, its `.blockmap`, the zip, and
   `latest.yml` into `/var/www/chat.oranges.lt/download/windows/`.

`latest.yml` is the freshness signal and is served `no-cache`; the versioned
binaries are `immutable`. Publish the exe and blockmap **before** `latest.yml`,
or clients briefly see an update they cannot download. Keep old installers in
place - `latest.yml` only ever points at the newest.

Auto-update is verified as far as Linux allows: the shell fetches and parses the
live feed and correctly resolves an upgrade. The actual download-and-install leg
is Windows-only and has not been exercised end-to-end.

## Icons

`build/icon.ico` and `build/icon.png` are generated from
`packages/client/public/icon.svg`. To regenerate, rasterize the SVG to
`build/icon-{16,24,32,48,64,128,256,512}.png`, then run `node scripts/make-ico.mjs`
(pure Node, packs the PNGs into a multi-resolution .ico and cleans up).
