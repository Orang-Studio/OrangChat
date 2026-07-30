# Bots

OrangChat bots are ordinary accounts driven by code. They post, react, read
channel history and respond to events in real time, and they appear in the
member list with a `BOT` label beside the name.

There are two official SDKs — `@orangchat/bot` for JavaScript/TypeScript and
`orangchat` for Python — but neither is required. The whole surface is a REST API
plus a Socket.IO gateway, and a bot can be written against those directly.

## 1. What a bot is

A bot is a `User` row with `isBot = true` and an `ownerId`. That is not an
implementation detail worth hiding: it is why bots work everywhere without a
parallel API. Message authorship, server membership, roles and the permission
bitfield all key on `User`, so a bot is subject to exactly the same permission
checks as a person, and anything you can express with roles you can apply to it.

The consequences are worth stating plainly:

- A bot has no password and no email it can receive at. It cannot sign in to the
  apps, reset anything, or be recovered — its token is its only credential.
- A bot **cannot read DMs.** See §5.
- Deleting the owner's account deletes their bots.

## 2. Creating one

**Settings → Developers → New bot.** You get the bot and its first token in one
step. The token is shown **once**; the server keeps only a SHA-256 digest and
cannot show it again. Lose it and you mint a new one.

An owner may hold up to 25 bots, and each bot up to 5 concurrent tokens. More
than one token exists so you can rotate without downtime: mint the new one,
deploy it, then revoke the old.

### Inviting a bot to a server

From the same screen, pick a server and add it. This needs `MANAGE_SERVER` on
the target server, and the bot joins with the server's `@everyone` defaults; give
it more from the roles screen like any other member.

The permission bitfield you request is **intersected with what you hold
yourself**. Inviting a bot cannot be used to manufacture permissions you were
never granted — the same rule that governs editing a role.

## 3. Authentication

Every request carries the token under the `Bot` scheme:

```
Authorization: Bot <token>
```

`Bot`, not `Bearer`. The scheme states which kind of credential is being
presented rather than leaving the server to guess from the token's shape, and it
is what the SDKs send.

The gateway uses the same string in its handshake:

```js
io('https://orangchat.lt', { auth: { token: `Bot ${token}` } })
```

### What a bot token cannot do

Bot tokens are refused outright on the routes that belong to a person:
`/auth/*`, `/security/*`, `/e2ee/*`, `/connections/*`, `/drafts/*`, `/push/*`
and `/friends/*`. A leaked bot token cannot delete the owner's account, read
their sessions, touch key material, or mint more bots.

Because `/auth/me` is among those, a bot asks **`GET /api/bot/me`** for its own
identity instead.

## 4. The gateway

The gateway is the same Socket.IO namespace people's clients use. On connect, a
bot joins the room of every server it is a member of, and receives that traffic
with no further setup — there is no subscribe step and there are no intents. What
a bot can see is decided entirely by where it has been invited and what
permissions it holds there.

Events delivered to a bot:

| Event | Meaning |
| --- | --- |
| `message:new` | A message was posted |
| `message:updated` | A message was edited |
| `message:deleted` | A message was deleted |
| `reaction:add` / `reaction:remove` | A reaction changed |
| `typing` | Someone started typing |

Sending, editing, deleting and reacting are acknowledged emits of the shape
`{ ok, data }` / `{ ok: false, error }`:

| Emit | Payload |
| --- | --- |
| `message:send` | `{ channelId, content, replyToId?, attachmentIds? }` |
| `message:edit` | `{ channelId, messageId, content }` |
| `message:delete` | `{ channelId, messageId }` |
| `reaction:add` / `reaction:remove` | `{ channelId, messageId, emoji }` |

Posting is also available over REST as `POST /api/channels/:channelId/messages`,
which is what both SDKs use for sending.

### Rate limits

Message sending is capped at 60 per 5 seconds per bot, six times a person's
allowance, because answering one trigger with several replies is normal bot
traffic rather than abuse. The coarse per-IP API ceiling (600/min) still applies
to REST calls. Bucketing those on the credential instead would mean trusting an
unverified header to choose the bucket, which is a bypass, so it was not done.

## 5. Bots cannot read DMs

Every DM and group DM is end-to-end encrypted with no opt-out
(`docs/E2EE.md` §1). The server holds only ciphertext and has no key, so there is
nothing it could hand a bot even if it wanted to. A bot added to a DM would
receive envelopes it cannot open.

Both SDKs therefore **drop encrypted messages before they reach your handler**,
rather than delivering them as blank ones. Server text channels are plaintext,
and are the entire surface a bot works on.

This is a property of the encryption design, not a limitation to be lifted later.

## 6. JavaScript / TypeScript

```bash
npm install @orangchat/bot
```

```ts
import { Client } from '@orangchat/bot';

const client = new Client({ token: process.env.BOT_TOKEN! });

client.on('ready', (self) => console.log(`Signed in as @${self.username}`));

client.on('messageCreate', async (message) => {
  if (message.content === '!ping') await message.reply('pong');
});

await client.login();
```

`messageCreate` hands you the message plus `reply`, `replyTo`, `react` and
`delete`. Other events: `messageUpdate`, `messageDelete`, `reactionAdd`,
`reactionRemove`, `typingStart`, `error`, `disconnect`.

`client.rest` exposes the REST surface directly (`me`, `servers`, `channel`,
`sendMessage`, `history`).

## 7. Python

```bash
pip install orangchat
```

```python
import os
from orangchat import Client

client = Client(os.environ["BOT_TOKEN"])

@client.event
async def on_ready(user):
    print(f"Signed in as @{user.username}")

@client.event
async def on_message(message):
    if message.content == "!ping":
        await message.reply("pong")

client.run()
```

Handlers are registered by name — `on_message` handles `message`. Events:
`ready`, `message`, `message_update`, `message_delete`, `reaction_add`,
`reaction_remove`, `typing`, `disconnect`. Handlers may be sync or async, and one
raising an exception is logged rather than taking the gateway down.

Use `await client.start()` instead of `client.run()` inside an existing event
loop.

## 8. Both SDKs ignore the bot's own messages

A bot that replies to itself loops forever, and it is the most common way a first
bot takes a server down. Both SDKs drop messages authored by the bot before they
reach a handler, so this cannot happen by forgetting to check.

If you genuinely need to observe your own output, read it back from
`client.history(...)` rather than from the event stream.

## 9. Self-hosting

Point the SDK at your own deployment:

```ts
new Client({ token, baseUrl: 'https://chat.example.com' });
```

```python
Client(token, base_url="https://chat.example.com")
```
