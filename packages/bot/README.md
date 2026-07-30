# @orangchat/bot

Official SDK for building [OrangChat](https://orangchat.lt) bots — a typed REST
client plus a realtime gateway.

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

Create a bot and its token under **Settings → Developers**, then invite it to a
server from the same screen.

## Events

`ready`, `messageCreate`, `messageUpdate`, `messageDelete`, `reactionAdd`,
`reactionRemove`, `typingStart`, `error`, `disconnect`.

A `messageCreate` payload carries `reply`, `replyTo`, `react` and `delete`
alongside the message fields.

## Notes

- **Bots cannot read DMs.** Every DM is end-to-end encrypted and the server holds
  only ciphertext, so encrypted messages are dropped before reaching a handler
  rather than arriving blank.
- **A bot never sees its own messages.** Replying to yourself loops forever, so
  it is prevented here rather than left to every handler to remember.
- Self-hosting: pass `baseUrl`.

Full documentation: [`docs/BOTS.md`](https://github.com/Vakarux12/orangchat/blob/main/docs/BOTS.md)

## Licence

MIT
