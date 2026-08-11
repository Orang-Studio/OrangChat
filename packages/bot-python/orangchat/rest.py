"""Typed wrapper over the OrangChat REST API.

Bots are ordinary ``User`` rows server-side, so this is the same ``/api`` a
person's client talks to. The only difference is the ``Bot`` authorization
scheme, which states which kind of credential is being presented rather than
leaving the server to infer it from the token's shape.
"""

from __future__ import annotations

import json
from typing import Any

import aiohttp

from .models import Channel, Message, Server, User


class OrangChatError(Exception):
    """An error returned by the API."""

    def __init__(self, status: int, message: str) -> None:
        super().__init__(f"[{status}] {message}")
        self.status = status
        self.message = message


class Rest:
    def __init__(self, base_url: str, token: str) -> None:
        self._base_url = base_url.rstrip("/")
        self._token = token
        self._session: aiohttp.ClientSession | None = None

    async def _get_session(self) -> aiohttp.ClientSession:
        if self._session is None or self._session.closed:
            self._session = aiohttp.ClientSession(
                headers={"Authorization": f"Bot {self._token}"}
            )
        return self._session

    async def close(self) -> None:
        if self._session and not self._session.closed:
            await self._session.close()

    async def request(self, method: str, path: str, body: Any | None = None) -> Any:
        session = await self._get_session()
        async with session.request(method, f"{self._base_url}{path}", json=body) as res:
            text = await res.text()
            if res.status >= 400:
                message = text or res.reason or "request failed"
                try:
                    parsed = json.loads(text)
                    if isinstance(parsed, dict) and parsed.get("error"):
                        message = str(parsed["error"])
                except ValueError:
                    pass
                raise OrangChatError(res.status, message)

            if res.status == 204 or not text:
                return None
            return json.loads(text)

    async def me(self) -> User:
        """The bot's own account.

        Not ``/auth/me`` - that belongs to the human account surface and refuses
        bot tokens outright.
        """
        return User.from_dict(await self.request("GET", "/bot/me"))

    async def servers(self) -> list[Server]:
        data = await self.request("GET", "/servers")
        return [Server.from_dict(s) for s in data or []]

    async def channel(self, channel_id: str) -> Channel:
        return Channel.from_dict(await self.request("GET", f"/channels/{channel_id}"))

    async def send_message(
        self,
        channel_id: str,
        content: str,
        *,
        reply_to_id: str | None = None,
        attachment_ids: list[str] | None = None,
    ) -> dict[str, Any]:
        return await self.request(
            "POST",
            f"/channels/{channel_id}/messages",
            {
                "content": content,
                "replyToId": reply_to_id,
                "attachmentIds": attachment_ids,
            },
        )

    async def history(
        self, channel_id: str, *, before: str | None = None, limit: int | None = None
    ) -> list[Message]:
        params: list[str] = []
        if before:
            params.append(f"before={before}")
        if limit:
            params.append(f"limit={limit}")
        query = f"?{'&'.join(params)}" if params else ""
        data = await self.request("GET", f"/channels/{channel_id}/messages{query}")
        return [Message.from_dict(m) for m in (data or {}).get("items", [])]
