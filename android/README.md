# OrangChat - Android

Native Android client (Kotlin + Jetpack Compose, Material 3) for OrangChat, a
Discord-like real-time chat app. It is a functional port of the React web client
in `packages/client`, wired to the live Rust backend at
`https://chat.oranges.lt` (REST under `/api`, Socket.IO at `/socket.io`).

## Requirements

- Android Studio (Ladybug / 2024.2+), or a CLI toolchain with:
  - JDK 17
  - Android SDK (compileSdk 35, build-tools 35), `ANDROID_HOME`/`sdk.dir` set
  - Gradle 8.10.x (the wrapper points at 8.10.2)
- minSdk 31 (Android 12; required for non-extractable Keystore ECDH), targetSdk 35

## Build

```bash
# From this directory (android/):
./gradlew :app:assembleDebug          # build a debug APK
./gradlew :app:installDebug           # build + install on a connected device
```

The APK lands in `app/build/outputs/apk/debug/`.

> **Wrapper jar:** `gradle/wrapper/gradle-wrapper.jar` is a binary and is not
> committed here. Opening the project in Android Studio regenerates it
> automatically. On a pure CLI, run `gradle wrapper --gradle-version 8.10.2`
> once (with a system Gradle) to produce it, then use `./gradlew`.

No `local.properties` is committed - Android Studio writes it, or copy
`local.properties.example` and set `sdk.dir`.

## Configuration

The backend URLs are compiled in via `BuildConfig` (see `app/build.gradle.kts`):

- `API_BASE_URL = https://chat.oranges.lt/api/`
- `SOCKET_URL   = https://chat.oranges.lt`

## Architecture

- **UI:** Jetpack Compose + Material 3. Design tokens (colors, radii, type) ported
  1:1 from `packages/client/src/styles/index.css` into `ui/theme/` (dark default +
  light). Reusable primitives in `ui/components/` mirror `components/ui/*` (Button,
  TextField, Dialog, ConfirmDialog, Tabs, DropdownMenu, Avatar).
- **State:** MVVM - `AppViewModel` (activity-scoped) holds servers, channels,
  messages, DMs, friends, presence, typing; `AuthViewModel`, `ThemeViewModel` for
  their flows. StateFlow + `collectAsStateWithLifecycle`.
- **Networking:** Retrofit + OkHttp + kotlinx.serialization. `AuthInterceptor`
  attaches the Bearer access token; `TokenAuthenticator` refreshes once on 401 via
  `POST /auth/refresh` (refresh token rides an httpOnly cookie kept by
  `PersistentCookieJar`, backed by EncryptedSharedPreferences).
- **Realtime:** `SocketManager` (io.socket:socket.io-client) connects to
  `/socket.io`, passing the JWT on the handshake as `auth.token` - exactly how the
  Rust `socket.rs` middleware reads it. Server→client events are decoded into
  typed `SocketEvent`s and applied to `AppViewModel` state; client→server actions
  (`message:send/edit/delete`, reactions, typing, presence) use emit+ack.
- **Notifications:** `NotificationHelper` posts local notifications for incoming
  messages, driven by the live `message:new` socket event. `AppViewModel.maybeNotify`
  fires when the message isn't ours and the app is backgrounded or the message's
  channel isn't focused, prioritising DMs and `<@userId>` / `@everyone` / `@here`
  mentions of the current user. FCM push is scaffolded as a template only - see
  `docs/FcmService.kt.template` (backend has no FCM sender yet).

## Backend contract sources of truth

- Models: `packages/shared/src/types.ts` → `data/model/Models.kt`
- Events: `packages/shared/src/events.ts` → `realtime/SocketEvents.kt` + `SocketManager`
- REST paths: `packages/server-rs/src/http/*.rs` → `data/remote/ApiService.kt`
- Socket handshake/events: `packages/server-rs/src/socket.rs`

## Feature status

Wired against the live backend:
- Auth: signup, login, session restore (cookie refresh), logout
- Servers: list, create, open (channels + members + roles), join via invite, create channel
- Channels + messages: history load, live send / edit / delete / reply, reactions, typing
- DMs: list, open 1:1 conversation, realtime messages
- Friends: list, requests (incoming/outgoing), add/accept/decline/remove, message
- Presence: live status via socket; set own status
- Settings: theme (dark/light/system), status, logout
- Local message notifications (DMs + mentions), permission request on Android 13+

Stubbed / not yet done (see report):
- Voice (LiveKit) - not implemented; hooks exist in the backend
- Roles/permissions editor UI, channel permission overwrites UI, bans list UI
- Image upload UI (endpoint + Retrofit method present; no picker screen yet)
- Group-DM creation UI, profile editing UI (banner/bio/pronouns/CSS)
- FCM push (template only)
```
