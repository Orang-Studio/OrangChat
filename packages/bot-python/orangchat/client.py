"""The bot client: REST plus the realtime gateway."""

from __future__ import annotations

import asyncio
import inspect
import logging
from typing import Any, Awaitable, Callable, TypeVar

import socketio

from .models import Message, Server, User
from .rest import OrangChatError, Rest

log = logging.getLogger("orangchat")

DEFAULT_BASE_URL = "https://orangchat.lt"

Handler = Callable[..., Awaitable[None] | None]
H = TypeVar("H", bound=Handler)

#: Gateway event -> the name handlers register under.
_EVENT_MAP = {
    "message:new": "message",
    "message:updated": "message_update",
    "message:deleted": "message_delete",
    "reaction:add": "reaction_add",
    "reaction:remove": "reaction_remove",
    "typing": "typing",
}


class Client:
    """An OrangChat bot.

    REST for what a bot asks for, a realtime gateway for what happens to it. The
    gateway is the same Socket.IO namespace people's clients use - a bot is a
    ``User`` row server-side, so it joins the rooms of every server it has been
    invited to and receives that traffic with no extra setup.

    ::

        client = Client(token=os.environ["BOT_TOKEN"])

        @client.event
        async def on_message(message):
            if message.content == "!ping":
                await message.reply("pong")

        client.run()
    """

    def __init__(self, token: str, *, base_url: str = DEFAULT_BASE_URL) -> None:
        if not token:
            raise ValueError("A bot token is required")
        self._token = token
        self._base_url = base_url.rstrip("/")
        self.rest = Rest(f"{self._base_url}/api", token)
        self._sio = socketio.AsyncClient(reconnection=True, logger=False)
        self._handlers: dict[str, list[Handler]] = {}
        self._user: User | None = None
        self._register_gateway_handlers()

    @property
    def user(self) -> User | None:
        """The bot's own account, once :meth:`start` has connected."""
        return self._user

    # ── handler registration ────────────────────────────

    def event(self, func: H) -> H:
        """Register a handler by name: ``on_message`` handles ``message``."""
        name = func.__name__
        if not name.startswith("on_"):
            raise ValueError(f"Event handler '{name}' must be named on_<event>")
        self.add_listener(name[3:], func)
        return func

    def add_listener(self, event: str, handler: Handler) -> None:
        self._handlers.setdefault(event, []).append(handler)

    def remove_listener(self, event: str, handler: Handler) -> None:
        if handler in self._handlers.get(event, []):
            self._handlers[event].remove(handler)

    async def _dispatch(self, event: str, *args: Any) -> None:
        for handler in list(self._handlers.get(event, [])):
            try:
                result = handler(*args)
                if inspect.isawaitable(result):
                    await result
            except Exception:
                # One bad handler must not take the gateway down. Logged with a
                # traceback rather than swallowed, so it is still diagnosable.
                log.exception("error in '%s' handler", event)

    # ── gateway ─────────────────────────────────────────

    def _register_gateway_handlers(self) -> None:
        @self._sio.on("message:new")
        async def _on_message(data: dict[str, Any]) -> None:
            # A bot has no key for an encrypted message and would only ever see
            # an empty body, so it never reaches a handler as a blank message.
            if data.get("ciphertext"):
                return
            author = data.get("author") or {}
            # Bots that answer themselves loop forever. This is the most common
            # way a first bot takes a server down, so it is prevented here
            # rather than left to every handler to remember.
            if self._user and author.get("id") == self._user.id:
                return
            await self._dispatch("message", Message.from_dict(data, self))

        @self._sio.on("message:updated")
        async def _on_message_update(data: dict[str, Any]) -> None:
            await self._dispatch("message_update", Message.from_dict(data, self))

        @self._sio.on("message:deleted")
        async def _on_message_delete(data: dict[str, Any]) -> None:
            await self._dispatch("message_delete", data)

        @self._sio.on("reaction:add")
        async def _on_reaction_add(data: dict[str, Any]) -> None:
            await self._dispatch("reaction_add", data)

        @self._sio.on("reaction:remove")
        async def _on_reaction_remove(data: dict[str, Any]) -> None:
            await self._dispatch("reaction_remove", data)

        @self._sio.on("typing")
        async def _on_typing(data: dict[str, Any]) -> None:
            await self._dispatch("typing", data)

        @self._sio.event
        async def disconnect() -> None:
            await self._dispatch("disconnect")

    async def _ack(self, event: str, payload: Any) -> Any:
        """Emit and await the server's ``{ok, data}`` acknowledgement."""
        res = await self._sio.call(event, payload, timeout=10)
        if not isinstance(res, dict) or not res.get("ok"):
            message = (res or {}).get("error", f"{event} failed")
            raise OrangChatError(400, str(message))
        return res.get("data")

    # ── lifecycle ───────────────────────────────────────

    async def start(self) -> None:
        """Connect to the gateway and block until disconnected."""
        self._user = await self.rest.me()
        await self._sio.connect(
            self._base_url,
            # `Bot <token>`, mirroring the REST scheme. The server reads this
            # same field for people's JWTs and branches on the prefix.
            auth={"token": f"Bot {self._token}"},
            transports=["websocket"],
        )
        await self._dispatch("ready", self._user)
        await self._sio.wait()

    async def close(self) -> None:
        await self._sio.disconnect()
        await self.rest.close()

    def run(self) -> None:
        """Blocking entry point for scripts that have no event loop of their own."""

        async def runner() -> None:
            try:
                await self.start()
            finally:
                await self.close()

        try:
            asyncio.run(runner())
        except KeyboardInterrupt:
            pass

    # ── actions ─────────────────────────────────────────

    async def send_message(
        self, channel_id: str, content: str, *, reply_to_id: str | None = None
    ) -> Message:
        data = await self.rest.send_message(channel_id, content, reply_to_id=reply_to_id)
        return Message.from_dict(data, self)

    async def edit_message(self, channel_id: str, message_id: str, content: str) -> Message:
        data = await self._ack(
            "message:edit",
            {"channelId": channel_id, "messageId": message_id, "content": content},
        )
        return Message.from_dict(data or {}, self)

    async def delete_message(self, channel_id: str, message_id: str) -> None:
        await self._ack("message:delete", {"channelId": channel_id, "messageId": message_id})

    async def add_reaction(self, channel_id: str, message_id: str, emoji: str) -> None:
        await self._ack(
            "reaction:add",
            {"channelId": channel_id, "messageId": message_id, "emoji": emoji},
        )

    async def remove_reaction(self, channel_id: str, message_id: str, emoji: str) -> None:
        await self._ack(
            "reaction:remove",
            {"channelId": channel_id, "messageId": message_id, "emoji": emoji},
        )

    async def servers(self) -> list[Server]:
        return await self.rest.servers()

    async def history(
        self, channel_id: str, *, before: str | None = None, limit: int | None = None
    ) -> list[Message]:
        return await self.rest.history(channel_id, before=before, limit=limit)
