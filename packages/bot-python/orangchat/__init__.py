"""Official Python SDK for building OrangChat bots.

::

    import os
    from orangchat import Client

    client = Client(token=os.environ["BOT_TOKEN"])

    @client.event
    async def on_message(message):
        if message.content == "!ping":
            await message.reply("pong")

    client.run()
"""

from .client import Client
from .models import Attachment, Channel, Message, Server, User
from .rest import OrangChatError, Rest

__all__ = [
    "Attachment",
    "Channel",
    "Client",
    "Message",
    "OrangChatError",
    "Rest",
    "Server",
    "User",
]

__version__ = "0.1.0"
