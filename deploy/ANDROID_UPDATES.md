# Android releases & in-app updates

OrangChat's Android app ships outside any store, so it updates itself: the app
fetches a small manifest, compares version codes, downloads the APK and hands it
to the system installer. Server side is two static files behind nginx.

- App: `feature/updates/UpdateManager.kt` (+ `UpdateViewModel`, UI in the About screen)
- Publisher: `publishUpdate` in `android/app/build.gradle.kts`
- nginx: the `/download/android/` blocks in `deploy/nginx/chat.oranges.lt.conf`

## Signing

**The keystore is the one irreplaceable artifact here.** Android only installs an
update signed with the same key as the installed app. Lose it and no existing
install can ever be updated again — every user has to uninstall and reinstall,
losing local data. It is deliberately kept outside the repo:

```
~/.keystores/orangchat-release.jks          # the key itself (0600)
~/.keystores/orangchat-release.properties   # storeFile/passwords (0600)
```

`android/local.properties` points at the properties file:

```properties
signing.propertiesFile=/home/adasjusk/.keystores/orangchat-release.properties
```

**Back both files up somewhere off this machine.** Certificate fingerprint of the
current key, for checking an APK is really ours:

```
SHA-256  d7:25:35:7e:07:c4:e5:7f:e5:40:38:1e:5e:dc:aa:a3:e1:b1:c7:30:c5:5d:18:c2:35:15:05:ca:d6:f9:41:45
```

A checkout without the keystore still builds; the release APK just comes out
unsigned (`app-release-unsigned.apk`) and `publishUpdate` refuses to publish it.

## Cutting a release

1. **Bump the version** in `android/app/build.gradle.kts`. `versionCode` must
   increase — it is the *only* thing the updater compares. `versionName` is what
   users see, and names the published file.

   ```kotlin
   versionCode = 2
   versionName = "0.2.0"
   ```

2. **Build and publish:**

   ```bash
   cd ~/orangchat/android
   ./gradlew publishUpdate
   ```

   That assembles the signed release, hashes it, and writes both files to
   `/var/www/chat.oranges.lt/download/android/`:

   - `orangchat-<versionName>.apk` — versioned filename, never overwritten, so a
     client mid-download still finishes the one it asked for.
   - `update.json` — `versionCode`, `versionName`, `apkUrl`, `size`, `sha256`.

3. **Verify:**

   ```bash
   curl -s https://chat.oranges.lt/download/android/update.json
   ```

Existing installs pick it up from Settings → About → Updates. Nothing else needs
restarting: the files are static and nginx serves them from the SPA web root.

## Why it copies instead of serving the build directory

nginx runs as `www-data`, which cannot traverse `/home/adasjusk` (0750). Serving
Gradle's output in place would mean opening the home directory to the web
server — a worse trade than copying two files on each release.

## Notes

- The APK is public. That is the point: it is the only distribution channel, and
  the signature is what proves an APK genuine, not who may fetch it.
- The `sha256` in the manifest only catches a corrupt or truncated download,
  earlier and with a clearer message than the installer's parse error. It is not
  the security boundary — the signature is, and Android enforces that itself.
- Installing requires the user to allow "install unknown apps" for OrangChat
  once (a system settings toggle; there is no runtime dialog for it). The app
  routes them there when needed. The system installer still confirms every
  install; a silent update is not possible without a system-level privilege.
- `update.json` is served `no-cache`; APKs are immutable and cached 30 days.
