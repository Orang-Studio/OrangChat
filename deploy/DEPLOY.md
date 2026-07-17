# Deploying OrangChat (chat.oranges.lt)

Backend: Rust (`packages/server-rs`, axum + socket.io) on `127.0.0.1:3001` under
systemd, binary `target/release/orangchat-server`. Frontend: static
SPA served by nginx from `/var/www/chat.oranges.lt`. Nginx reverse-proxies `/api`
and `/socket.io` to the backend and serves the SPA for everything else.

## 1. DNS

Create `chat.oranges.lt` in Cloudflare. Use **DNS-only** (grey cloud) or ensure
WebSocket + large bodies are allowed if proxied.

## 2. TLS (acme.sh, DNS-01 via Cloudflare)

`CLOUDFLARE_API_TOKEN` is exported from `.bashrc` (scoped token; Bearer auth).

```bash
acme.sh --issue --dns dns_cf -d chat.oranges.lt
acme.sh --install-cert -d chat.oranges.lt \
  --key-file       /etc/letsencrypt/live/chat.oranges.lt/privkey.pem \
  --fullchain-file /etc/letsencrypt/live/chat.oranges.lt/fullchain.pem \
  --reloadcmd      "sudo systemctl reload nginx"
```

(`chain.pem` for OCSP stapling: copy from acme.sh's `ca.cer`, or drop the
`ssl_trusted_certificate` line if unused.)

## 3. Datastores

Postgres + Redis. Either the system services (`localhost:5432` / `localhost:6379`)
or the project's Docker compose. Create the role/db:

```sql
CREATE ROLE orangchat LOGIN PASSWORD '...';
CREATE DATABASE orangchat OWNER orangchat;
```

## 4. Build & install

```bash
cd ~/orangchat
pnpm install
cargo build --release --manifest-path packages/server-rs/Cargo.toml  # -> packages/server-rs/target/release/orangchat-server
pnpm --filter @orangchat/client build                                # -> packages/client/dist

sudo mkdir -p /var/lib/orangchat/uploads /var/www/chat.oranges.lt
sudo cp -r packages/client/dist/* /var/www/chat.oranges.lt/

# Digital Asset Links, so /invite/ links open the Android app rather than the
# browser. One-time; only needs redoing if the app's signing key changes.
sudo mkdir -p /var/www/chat.oranges.lt/.well-known
sudo cp deploy/assetlinks.json /var/www/chat.oranges.lt/.well-known/
```

The systemd unit runs the binary in place from `packages/server-rs/target/release/`
(see `deploy/orangchat.service`); no copy step needed for the backend.

`assetlinks.json` names the release signing certificate's SHA-256 fingerprint.
Android re-verifies it on install, so a debug-signed build will never take over
invite links — that is deliberate, and it means testing app links needs a
release-signed APK. Verify a device accepted it with:

```bash
adb shell pm get-app-links lt.oranges.orangchat   # want: chat.oranges.lt: verified
```

## 5. Environment + migrations

```bash
sudo mkdir -p /etc/orangchat
sudo cp deploy/orangchat.env.example /etc/orangchat/orangchat.env
sudo $EDITOR /etc/orangchat/orangchat.env     # set secrets (openssl rand -hex 32), DB url, OAuth, LiveKit
sudo chmod 600 /etc/orangchat/orangchat.env

# apply schema (Prisma migrations at repo root own the DB schema; the Rust
# backend reads it via sqlx and ships no migrations of its own)
cd ~/orangchat && sudo -E DATABASE_URL=... pnpm dlx prisma migrate deploy --schema ./prisma/schema.prisma
```

## 6. systemd

```bash
sudo cp deploy/orangchat.service /etc/systemd/system/orangchat.service
sudo systemctl daemon-reload
sudo systemctl enable --now orangchat
sudo systemctl status orangchat
curl -s http://127.0.0.1:3001/health
```

## 7. nginx

```bash
sudo cp deploy/nginx/chat.oranges.lt.conf /etc/nginx/sites-available/chat.oranges.lt
sudo ln -sf /etc/nginx/sites-available/chat.oranges.lt /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl reload nginx
```

`$connection_upgrade` is defined globally (conf.d) on this host and used for the
Socket.IO WebSocket upgrade.

## 8. Verify

- `https://chat.oranges.lt` loads the SPA.
- Sign up, create a server, send a message across two sessions.
- Network tab: `wss://chat.oranges.lt/socket.io/...` shows `101 Switching Protocols`.

## Voice (LiveKit)

Run a LiveKit server (its own container/service), set `LIVEKIT_URL` (wss),
`LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET` in the env file, restart `orangchat`.
