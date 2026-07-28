# @orangchat/server-rs

A **Rust** rewrite of the OrangChat backend (`packages/server`, Fastify + Socket.IO +
Prisma). It is a **drop-in replacement**: same Postgres schema, same Redis keys, same
JWT secrets, and the same REST + Socket.IO wire contract, so existing clients,
tokens, and data keep working unchanged.

## Stack

| Concern      | TS server            | Rust server              |
|--------------|----------------------|--------------------------|
| HTTP         | Fastify              | axum 0.7                 |
| Realtime     | Socket.IO            | socketioxide 0.15        |
| DB           | Prisma               | sqlx (raw SQL, Postgres) |
| Cache/state  | ioredis              | redis (ConnectionManager)|
| Auth         | jose (JWT) + argon2  | jsonwebtoken + argon2    |
| Passwords    | argon2id             | argon2id (same PHC)      |
| IDs          | Prisma `cuid()`      | `cuid` crate (cuid v1)   |

Compatibility notes:
- **Passwords**: argon2id PHC strings are cross-compatible - hashes made by either
  server verify on the other.
- **JWT**: HS256, issuer `orangchat`, identical claims (`sub`, `username`, `jti`).
  Tokens issued by the TS server validate here and vice-versa (shared secrets).
- **IDs**: `cuid` crate emits cuid v1 (25-char, `c…`), matching Prisma.
- **Timestamps**: serialized as `Date.toISOString()` (`…Z`, 3 ms digits).
- **Permissions**: `BIGINT` bitfields as decimal strings over the wire.
- **Socket.IO adapter**: single-instance in-memory (the TS server's Redis adapter
  is only needed for multi-instance fan-out; presence/refresh/voice still use Redis
  and remain multi-instance-correct).

## Run (dev)

```bash
cp .env.example .env      # dev DB is docker postgres :5433 / redis :6380
cargo run                 # binds 127.0.0.1:$PORT (default 3001)
```

The dev database is the same one `../server` uses (`docker compose up -d` at the repo
root). No migrations here - the schema is owned by Prisma in `../server/prisma`.

## Build (release)

```bash
cargo build --release     # -> target/release/orangchat-server
```

## Layout

```
src/
  main.rs           bootstrap: pool, redis, socket layer, axum server
  config.rs         env parsing (mirrors env.ts)
  state.rs          AppState (pool + redis + config + io handle)
  error.rs          AppError -> HTTP status mapping (matches app.ts)
  permissions.rs    Discord-style bitfield (mirrors shared/permissions.ts)
  auth.rs           argon2, JWT, refresh-token rotation store, cookies
  oauth.rs          Google/Discord OAuth clients
  models.rs         sqlx row structs
  dto.rs            wire DTOs + mappers (mirrors mappers.ts)
  services/         membership, channel, message, server, role,
                    moderation, dm, user, presence, voice
  http.rs + http/   axum routers (auth, servers, channels, dms, roles) + /health
  socket.rs         Socket.IO handshake auth + all event handlers
```

## Deploy (parity with the TS unit)

Same env file shape (`/etc/orangchat/orangchat.env`), same bind
`127.0.0.1:3001`, same nginx proxy. Point a systemd unit's `ExecStart` at the release
binary instead of `node dist/index.js`:

```ini
ExecStart=/home/adasjusk/orangchat/packages/server-rs/target/release/orangchat-server
WorkingDirectory=/home/adasjusk/orangchat/packages/server-rs
EnvironmentFile=/etc/orangchat/orangchat.env
```
