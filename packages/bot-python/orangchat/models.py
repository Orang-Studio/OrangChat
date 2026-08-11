"""Wire models.

Deliberately tolerant: every model is built with :meth:`from_dict`, which reads
the keys it knows and ignores the rest. The server adds fields over time, and a
bot written against an older release must keep running when it does.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:
    from .client import Client


@dataclass(slots=True)
class User:
    id: str
    username: str
    display_name: str
    avatar_url: str | None = None
    status: str = "offline"
    bio: str | None = None
    badges: list[str] = field(default_factory=list)
    bot: bool = False
    created_at: str = ""

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> User:
        return cls(
            id=data.get("id", ""),
            username=data.get("username", ""),
            display_name=data.get("displayName", ""),
            avatar_url=data.get("avatarUrl"),
            status=data.get("status", "offline"),
            bio=data.get("bio"),
            badges=list(data.get("badges") or []),
            bot=bool(data.get("bot", False)),
            created_at=data.get("createdAt", ""),
        )


@dataclass(slots=True)
class Attachment:
    id: str
    url: str
    filename: str
    content_type: str
    size: int
    spoiler: bool = False

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> Attachment:
        return cls(
            id=data.get("id", ""),
            url=data.get("url", ""),
            filename=data.get("filename", ""),
            content_type=data.get("contentType", ""),
            size=int(data.get("size") or 0),
            spoiler=bool(data.get("spoiler", False)),
        )


@dataclass(slots=True)
class Message:
    id: str
    channel_id: str
    author: User
    content: str
    created_at: str = ""
    edited_at: str | None = None
    reply_to_id: str | None = None
    attachments: list[Attachment] = field(default_factory=list)
    pinned: bool = False
    ciphertext: str | None = None

    _client: Client | None = field(default=None, repr=False, compare=False)

    @classmethod
    def from_dict(cls, data: dict[str, Any], client: Client | None = None) -> Message:
        return cls(
            id=data.get("id", ""),
            channel_id=data.get("channelId", ""),
            author=User.from_dict(data.get("author") or {}),
            content=data.get("content", ""),
            created_at=data.get("createdAt", ""),
            edited_at=data.get("editedAt"),
            reply_to_id=data.get("replyToId"),
            attachments=[Attachment.from_dict(a) for a in (data.get("attachments") or [])],
            pinned=bool(data.get("pinned", False)),
            ciphertext=data.get("ciphertext"),
            _client=client,
        )

    def _require_client(self) -> Client:
        if self._client is None:
            raise RuntimeError("This message is not bound to a client")
        return self._client

    async def reply(self, content: str) -> Message:
        """Send a message to the same channel."""
        return await self._require_client().send_message(self.channel_id, content)

    async def reply_to(self, content: str) -> Message:
        """Send a message to the same channel, threaded onto this one."""
        return await self._require_client().send_message(
            self.channel_id, content, reply_to_id=self.id
        )

    async def react(self, emoji: str) -> None:
        await self._require_client().add_reaction(self.channel_id, self.id, emoji)

    async def delete(self) -> None:
        await self._require_client().delete_message(self.channel_id, self.id)


@dataclass(slots=True)
class Channel:
    id: str
    server_id: str | None
    name: str | None
    type: str
    topic: str | None = None
    nsfw: bool = False

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> Channel:
        return cls(
            id=data.get("id", ""),
            server_id=data.get("serverId"),
            name=data.get("name"),
            type=data.get("type", "text"),
            topic=data.get("topic"),
            nsfw=bool(data.get("nsfw", False)),
        )


@dataclass(slots=True)
class Server:
    id: str
    name: str
    icon_url: str | None = None
    description: str | None = None
    owner_id: str = ""

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> Server:
        return cls(
            id=data.get("id", ""),
            name=data.get("name", ""),
            icon_url=data.get("iconUrl"),
            description=data.get("description"),
            owner_id=data.get("ownerId", ""),
        )
