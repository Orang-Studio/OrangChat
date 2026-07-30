"""A complete OrangChat bot.

    BOT_TOKEN=... python examples/ping.py

Create the bot and its token in OrangChat under Settings -> Developers, then
invite it to a server from the same screen.
"""

import os
import sys

from orangchat import Client

token = os.environ.get("BOT_TOKEN")
if not token:
    sys.exit("Set BOT_TOKEN to the token from Settings -> Developers.")

client = Client(token, base_url=os.environ.get("ORANGCHAT_URL", "https://orangchat.lt"))


@client.event
async def on_ready(user):
    print(f"Signed in as {user.display_name} (@{user.username})")


@client.event
async def on_message(message):
    if message.content == "!ping":
        await message.reply("pong")

    if message.content == "!hello":
        await message.reply_to(f"Hello, {message.author.display_name}.")


client.run()
