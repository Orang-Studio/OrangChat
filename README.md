# OrangChat

A real-time chat platform with end-to-end encrypted DMs, voice, and media - Android, web, and Windows clients on a self-hostable Rust backend.

**No ads, no tracking, fully open source.**

<p align="center">
  <a href="https://chat.oranges.lt">
    <img src="https://img.shields.io/badge/instance-chat.oranges.lt-2563eb" alt="Public instance">
  </a>
  <a href="https://chat.oranges.lt/download/android">
    <img src="https://img.shields.io/badge/Android%20APK-chat.oranges.lt%2Fdownload%2Fandroid-16a34a" alt="Android APK">
  </a>
  <a href="LICENSE">
    <img src="https://img.shields.io/github/license/Orang-Studio/OrangChat" alt="GPLv3 License">
  </a>
</p>

## Features

- End-to-end encrypted DMs and group DMs: per-device identity keys, QR safety-code verification, and one-tap device transfer.
- Servers with channels, roles, invites, member management, and a moderation audit log.
- Reactions, replies, message search, fullscreen media viewer, and voice messages.
- Video calls over your own LiveKit server, with screen sharing on Windows.
- QR sign-in, TOTP two-factor authentication, and passkey sign-in.
- Bots (TypeScript or Python) and a marketplace of community plugins and themes.
- Eleven bundled languages, with translation fixes and new locales served live from the server - no app update needed.
- Native clients: Android (Kotlin + Jetpack Compose), web (React), and a Windows desktop shell.

> [!IMPORTANT]
> OrangChat is a full client-server platform - the clients need an OrangChat server to talk to. Use the public instance at [chat.oranges.lt](https://chat.oranges.lt), or host your own in minutes with `docker-compose` (see [deploy/DEPLOY.md](deploy/DEPLOY.md)).

## Usage

1. Open the web client at [chat.oranges.lt](https://chat.oranges.lt), or install the Android APK from [chat.oranges.lt/download/android](https://chat.oranges.lt/download/android).
2. Create an account - or sign in on another device by scanning the QR under **Settings → Encryption → Add another device**.
3. Create or join a server and start a DM. DMs and group DMs are end-to-end encrypted; every device pair shows a safety code you can verify out of band.

<details>
<summary><strong>Supported platforms</strong></summary>

<br>

- **Android 12+** - Kotlin + Jetpack Compose, Material 3. Signed APK with updates delivered over HTTPS from your server.
- **Web** - modern Chrome, Firefox, Safari, and Edge (React + Vite).
- **Windows** - desktop shell (Electron) that loads the web client with a tray icon, calls, screen sharing, and auto-updates.

</details>

<details>
<summary><strong>Repository layout</strong></summary>

<br>

| Path | What it is |
| --- | --- |
| `android/` | Native Android client |
| `packages/client` | React web client |
| `packages/desktop` | Windows desktop shell |
| `packages/server-rs` | Rust backend (axum + Socket.IO) |
| `packages/bot`, `packages/bot-python` | Bot SDKs |
| `packages/marketplace` | Plugin and theme marketplace (submodule) |
| `packages/shared` | Shared client code and scripts |
| `prisma/` | Postgres schema and migrations |
| `deploy/` | docker-compose, nginx, LiveKit, and systemd units |
| `docs/` | E2EE design, bots, and passkey notes |

</details>

## Privacy

OrangChat does not include:

- Advertisements
- Analytics
- User tracking
- Paid features
- A third-party cloud

The content of DMs and group DMs is end-to-end encrypted: the server only ever handles ciphertext, and device keys never leave your devices. Everything else - media, calls, translations - is served by the server you choose, so self-hosting puts every byte under your control.

## Disclaimer

> [!CAUTION]
> End-to-end encryption protects message content, not metadata - who talks to whom, and when, remains visible to the server operator. The design and its limits are documented in [docs/E2EE.md](docs/E2EE.md). Run your own server if you want that trust boundary to end at your own machine.

OrangChat is not affiliated with, endorsed by, or associated with Discord, Meta, or any other platform.

## Help and documentation

- [docs/E2EE.md](docs/E2EE.md) - end-to-end encryption design
- [docs/BOTS.md](docs/BOTS.md) - writing bots
- [docs/PASSKEYS.md](docs/PASSKEYS.md) - passkey sign-in
- [deploy/DEPLOY.md](deploy/DEPLOY.md) - self-hosting guide
- [GitHub Issues](https://github.com/Orang-Studio/OrangChat/issues) - bugs and feature requests
- [Wiki](https://github.com/Orang-Studio/OrangChat/wiki) - additional documentation

## Star History

<p align="center">
  <a href="https://www.star-history.com/#Orang-Studio/OrangChat&Date">
    <picture>
      <source
        media="(prefers-color-scheme: dark)"
        srcset="https://api.star-history.com/svg?repos=Orang-Studio/OrangChat&type=Date&theme=dark"
      >
      <source
        media="(prefers-color-scheme: light)"
        srcset="https://api.star-history.com/svg?repos=Orang-Studio/OrangChat&type=Date"
      >
      <img
        src="https://api.star-history.com/svg?repos=Orang-Studio/OrangChat&type=Date"
        alt="OrangChat star history chart"
      >
    </picture>
  </a>
</p>

## License

OrangChat is available under the [GPLv3 License](LICENSE). The marketplace submodule is licensed separately in its own repository.
