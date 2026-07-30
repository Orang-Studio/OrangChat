# orangchat

Official Python SDK for building [OrangChat](https://orangchat.lt) bots — an
async REST client plus a realtime gateway.

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

Create a bot and its token under **Settings → Developers**, then invite it to a
server from the same screen.

## Events

Handlers are registered by name — `on_message` handles `message`.

`ready`, `message`, `message_update`, `message_delete`, `reaction_add`,
`reaction_remove`, `typing`, `disconnect`.

Handlers may be sync or async. One raising an exception is logged rather than
taking the gateway down.

Inside an existing event loop, use `await client.start()` instead of
`client.run()`.

## Notes

- **Bots cannot read DMs.** Every DM is end-to-end encrypted and the server holds
  only ciphertext, so encrypted messages are dropped before reaching a handler
  rather than arriving blank.
- **A bot never sees its own messages.** Replying to yourself loops forever, so
  it is prevented here rather than left to every handler to remember.
- Self-hosting: pass `base_url`.

Full documentation: [`docs/BOTS.md`](https://github.com/Vakarux12/orangchat/blob/main/docs/BOTS.md)

## Licence

MIT
