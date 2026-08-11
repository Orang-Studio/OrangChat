
import { Client } from '@orangchat/bot';

const token = process.env.BOT_TOKEN;
if (!token) {
  console.error('Set BOT_TOKEN to the token from the Developers page.');
  process.exit(1);
}

const client = new Client({
  token,
  baseUrl: process.env.ORANGCHAT_URL ?? 'https://orangchat.lt',
});

client.on('ready', (self) => {
  console.log(`Signed in as ${self.displayName} (@${self.username})`);
});

client.on('messageCreate', async (message) => {
  if (message.content === '!ping') {
    await message.reply('pong');
  }

  if (message.content === '!hello') {
    await message.replyTo(`Hello, ${message.author.displayName}.`);
  }
});

client.on('error', (error) => console.error('gateway error:', error.message));
client.on('disconnect', (reason) => console.warn('disconnected:', reason));

await client.login();
