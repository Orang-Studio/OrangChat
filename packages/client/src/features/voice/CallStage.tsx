import { useEffect, useMemo, useRef, useState } from "react";
import {
  Headphones,
  HeadphoneOff,
  Mic,
  MicOff,
  PictureInPicture2,
  PhoneOff,
  ScreenShare,
  ScreenShareOff,
  Video,
  VideoOff,
  type LucideIcon,
} from "lucide-react";
import type { User } from "@orangchat/shared";
import { Avatar } from "../../components/Avatar";
import { cn } from "../../lib/cn";
import { useAuthStore } from "../../stores/auth";
import { conversationName, useConversations } from "../dms/queries";
import { callActions, useCallStore } from "./callStore";
import type { VideoTile } from "./livekit";
import { useVoiceStore, voiceActions } from "./store";
import { t } from "../../lib/i18n";

function VideoSurface({ tile, contain = false }: { tile: VideoTile; contain?: boolean }) {
  const ref = useRef<HTMLVideoElement>(null);
  const screen = tile.source === "screen";

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    tile.attach(el);
    return () => tile.detach(el);
  }, [tile]);

  return (
    <video
      ref={ref}
      autoPlay
      playsInline
      muted={tile.isLocal}
      className={cn(
        "size-full",
        contain || screen ? "object-contain" : "object-cover",
        tile.isLocal && !screen && "-scale-x-100",
      )}
    />
  );
}


function FocusedVideo({ tile, onClose }: { tile: VideoTile; onClose: () => void }) {
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  return (
    <div className="fixed inset-0 z-50 flex flex-col bg-black">
      <button
        type="button"
        onClick={onClose}
        aria-label={t("callStage.closeFullScreen")}
        className="absolute inset-0 cursor-zoom-out"
      />
      <VideoSurface tile={tile} contain />
      <span className="pointer-events-none absolute bottom-6 left-1/2 -translate-x-1/2 rounded-full bg-black/70 px-3 py-1 text-sm text-white">
        {tile.source === "screen" ? screenLabel(tile) : tile.name}
      </span>
    </div>
  );
}


const keyOf = (tile: VideoTile) =>
  `${tile.identity}:${tile.isLocal ? "self" : "remote"}:${tile.source}`;

const screenLabel = (tile: VideoTile) =>
  tile.isLocal ? t("callStage.yourScreen") : t("callStage.nameSScreen", { name: tile.name });

/** A shared screen stands on its own rather than replacing anybody's card. */
function ScreenCard({ tile, onExpand }: { tile: VideoTile; onExpand: () => void }) {
  return (
    <div className="relative min-h-48 overflow-hidden rounded-xl border border-primary/60 bg-black">
      <VideoSurface tile={tile} />
      <button
        type="button"
        onClick={onExpand}
        aria-label={`Expand ${screenLabel(tile)}`}
        title={`Expand ${screenLabel(tile)}`}
        className="absolute inset-0 z-10 cursor-zoom-in"
      />
      <span className="pointer-events-none absolute inset-x-0 bottom-0 flex items-center gap-1.5 bg-gradient-to-t from-black/80 to-transparent p-3 pt-10 font-semibold text-white">
        <ScreenShare aria-hidden className="size-4 shrink-0 text-primary" />
        <span className="truncate">{screenLabel(tile)}</span>
      </span>
    </div>
  );
}

/** One mic-off / headphones-off pill, legible over a camera tile or an avatar. */
function VoiceBadge({ icon: Icon, label }: { icon: LucideIcon; label: string }) {
  return (
    <span
      title={label}
      className="grid size-6 place-items-center rounded-full bg-black/60 text-danger"
    >
      <Icon aria-label={label} className="size-3.5" />
    </span>
  );
}

function ParticipantCard({
  user,
  tile,
  status,
  speaking,
  muted,
  deafened,
  onExpand,
}: {
  user: User;
  tile?: VideoTile;
  status: "connected" | "ringing" | "waiting";
  speaking: boolean;
  muted: boolean;
  deafened: boolean;
  onExpand?: () => void;
}) {
  // Anyone not actually in the call yet is drained of colour and dimmed, so a
  // glance at the stage says who is present without reading the status pills.
  const pending = status !== "connected";

  return (
    <div
      className={cn(
        "relative flex min-h-48 items-center justify-center overflow-hidden rounded-xl border bg-surface-2 transition-shadow",
        speaking
          ? "border-success shadow-[0_0_28px_rgba(63,189,110,0.5)]"
          : "border-border",
      )}
    >
      <div
        className={cn(
          "grid size-full place-items-center transition-all duration-300",
          pending && "grayscale",
        )}
      >
        {tile ? (
          <VideoSurface tile={tile} />
        ) : (
          <Avatar user={user} status={user.status} className="size-24" />
        )}
      </div>
      {/* Only a camera is worth opening - an avatar looks the same at any size.
          Above the wash and the badges so the whole tile is the hit target. */}
      {tile && onExpand && (
        <button
          type="button"
          onClick={onExpand}
          aria-label={`Expand ${user.displayName}'s camera`}
          title={`Expand ${user.displayName}'s camera`}
          className="absolute inset-0 z-10 cursor-zoom-in"
        />
      )}
      {/* A wash rather than opacity: it darkens the tile without also fading the
          name and status text layered over it. */}
      {pending && (
        <div
          aria-hidden
          className="pointer-events-none absolute inset-0 bg-black/55 transition-opacity duration-300"
        />
      )}
      {/* Muted and deafened are independent - you can be deafened and still
          talk - so neither icon stands in for the other. */}
      {(muted || deafened) && (
        <div className="absolute right-2 top-2 flex gap-1.5">
          {muted && <VoiceBadge icon={MicOff} label={t("callStage.muted")} />}
          {deafened && <VoiceBadge icon={HeadphoneOff} label={t("callStage.deafened")} />}
        </div>
      )}
      <div className="absolute inset-x-0 bottom-0 flex items-end justify-between bg-gradient-to-t from-black/80 to-transparent p-3 pt-10">
        <span className="font-semibold text-white">{user.displayName}</span>
        <span
          className={cn(
            "rounded-full bg-black/60 px-2 py-1 text-xs",
            status === "connected" && "text-success",
            status === "ringing" && "animate-pulse text-warning",
            status === "waiting" && "text-white/60",
          )}
        >
          {speaking
            ? t("callStage.speaking")
            : status === "connected"
              ? t("callStage.inCall")
              : status === "ringing"
                ? t("callStage.ringingStatus")
                : t("callStage.notInCall")}
        </span>
      </div>
    </div>
  );
}

function Control({
  label,
  danger,
  active,
  onClick,
  children,
}: {
  label: string;
  danger?: boolean;
  active?: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      onClick={onClick}
      className={cn(
        "grid size-12 place-items-center rounded-full border transition-colors",
        danger
          ? "border-danger bg-danger text-white hover:opacity-90"
          : active
            ? "border-primary bg-primary-soft text-primary"
            : "border-border bg-surface-3 text-ink-secondary hover:text-ink",
      )}
    >
      {children}
    </button>
  );
}


function DmCallScreen() {
  const call = useCallStore((s) => s.current);
  const session = useVoiceStore((s) => s.session);
  const tiles = useVoiceStore((s) => s.videoTiles);
  const speakingIds = useVoiceStore((s) => s.speakingIds);
  const voiceStates = useVoiceStore((s) => s.participants[call?.channelId ?? ""]);
  const self = useAuthStore((s) => s.user);
  const { data: conversations } = useConversations();
  const [minimized, setMinimized] = useState(false);

  const [focused, setFocused] = useState<string | null>(null);
  const focusedTile = tiles.find((tile) => keyOf(tile) === focused);

  useEffect(() => setMinimized(false), [call?.channelId]);
  useEffect(() => {
    if (focused && !focusedTile) setFocused(null);
  }, [focused, focusedTile]);

  const conversation = conversations?.find((item) => item.id === call?.channelId);
  const users = useMemo(() => {
    if (conversation) return conversation.participants;
    const fallback = [self, call?.caller].filter(Boolean) as User[];
    return [...new Map(fallback.map((user) => [user.id, user])).values()];
  }, [call?.caller, conversation, self]);

  if (!call || !session) return null;

  const title = conversation ? conversationName(conversation, self?.id) : call.caller.displayName;
  const connected = new Set(call.participants);
  const ringing = new Set(call.ringing);
  const cameraById = new Map(
    tiles.filter((tile) => tile.source === "camera").map((tile) => [tile.identity, tile]),
  );
  const screenTiles = tiles.filter((tile) => tile.source === "screen");

  const voiceFor = (userId: string) =>
    userId === self?.id
      ? { muted: session.muted, deafened: session.deafened }
      : {
          muted: voiceStates?.[userId]?.muted === true,
          deafened: voiceStates?.[userId]?.deafened === true,
        };

  const hangUp = () => void callActions.leave();
  const controls = (
    <div className="flex items-center justify-center gap-3">
      <Control
        label={session.muted ? t("callStage.unmute") : t("callStage.mute")}
        active={session.muted}
        onClick={() => void voiceActions.toggleMute()}
      >
        {session.muted ? <MicOff className="size-5" /> : <Mic className="size-5" />}
      </Control>
      <Control
        label={session.deafened ? t("callStage.undeafen") : t("callStage.deafen")}
        active={session.deafened}
        onClick={() => void voiceActions.toggleDeafen()}
      >
        {session.deafened ? (
          <HeadphoneOff className="size-5" />
        ) : (
          <Headphones className="size-5" />
        )}
      </Control>
      <Control
        label={session.video ? t("callStage.turnCameraOff") : t("callStage.turnCameraOn")}
        active={session.video}
        onClick={() => void voiceActions.toggleCamera()}
      >
        {session.video ? <Video className="size-5" /> : <VideoOff className="size-5" />}
      </Control>
      <Control
        label={
          session.screenSharing
            ? t("callStage.stopSharingYourScreen")
            : t("callStage.shareYourScreen")
        }
        active={session.screenSharing}
        onClick={() => void voiceActions.toggleScreenShare()}
      >
        {session.screenSharing ? (
          <ScreenShareOff className="size-5" />
        ) : (
          <ScreenShare className="size-5" />
        )}
      </Control>
      <Control label={t("callStage.hangUp")} danger onClick={hangUp}>
        <PhoneOff className="size-5" />
      </Control>
    </div>
  );

  if (minimized) {
    return (
      <div className="fixed right-4 bottom-4 z-30 w-[min(22rem,calc(100vw-2rem))] rounded-xl border border-border bg-surface-2 p-3 shadow-2xl">
        <button
          type="button"
          onClick={() => setMinimized(false)}
          className="mb-3 flex w-full items-center gap-3 text-left"
        >
          <div className="flex -space-x-2">
            {users.slice(0, 4).map((user) => {
              const { muted, deafened } = voiceFor(user.id);
              return (
                <span key={user.id} className="relative inline-block">
                  <Avatar
                    user={user}
                    className={cn(
                      "size-9 border-2 border-surface-2",
                      speakingIds.includes(user.id) && "ring-2 ring-success",
                    )}
                  />
                  {/* No room for both icons at this size: deafened is the
                      stronger signal, so it wins when someone is both. */}
                  {(muted || deafened) && (
                    <span
                      title={deafened ? t("callStage.deafened") : t("callStage.muted")}
                      className="absolute -bottom-0.5 -right-0.5 grid size-4 place-items-center rounded-full border border-surface-2 bg-surface-3 text-danger"
                    >
                      {deafened ? (
                        <HeadphoneOff aria-label={t("callStage.deafened")} className="size-2.5" />
                      ) : (
                        <MicOff aria-label={t("callStage.muted")} className="size-2.5" />
                      )}
                    </span>
                  )}
                </span>
              );
            })}
          </div>
          <div className="min-w-0 flex-1">
            <p className="truncate text-sm font-semibold">{title}</p>
            <p className="text-xs text-ink-muted">
              {call.phase === "outgoing"
                ? t("callStage.ringingStatus")
                : t("callStage.connectedInCall", { count: connected.size })}
            </p>
          </div>
          <PictureInPicture2 className="size-4 text-ink-muted" />
        </button>
        {controls}
      </div>
    );
  }

  return (
    <div className="fixed inset-0 z-30 flex flex-col bg-surface-1 p-4 sm:p-6">
      <header className="mb-4 flex items-center justify-between gap-4">
        <div>
          <p className="text-xs font-semibold uppercase tracking-wide text-success">
            {call.phase === "outgoing" ? t("callStage.calling") : t("callStage.callConnected")}
          </p>
          <h1 className="text-xl font-bold">{title}</h1>
          <p className="text-sm text-ink-muted">
            {t("callStage.participantsSummary", { connected: connected.size, ringing: ringing.size })}
          </p>
        </div>
        <button
          type="button"
          onClick={() => setMinimized(true)}
          className="rounded-lg border border-border bg-surface-2 p-2 text-ink-muted hover:text-ink"
          aria-label={t("callStage.minimizeCall")}
          title={t("callStage.minimizeCall")}
        >
          <PictureInPicture2 className="size-5" />
        </button>
      </header>

      <div
        className={cn(
          "grid min-h-0 flex-1 gap-3 overflow-auto",
          users.length + screenTiles.length > 1 && "sm:grid-cols-2",
        )}
      >
        {screenTiles.map((tile) => (
          <ScreenCard key={keyOf(tile)} tile={tile} onExpand={() => setFocused(keyOf(tile))} />
        ))}
        {users.map((user) => {
          const camera = cameraById.get(user.id);
          return (
            <ParticipantCard
              key={user.id}
              user={user}
              tile={camera}
              status={connected.has(user.id) ? "connected" : ringing.has(user.id) ? "ringing" : "waiting"}
              speaking={speakingIds.includes(user.id)}
              onExpand={camera ? () => setFocused(keyOf(camera)) : undefined}
              {...voiceFor(user.id)}
            />
          );
        })}
      </div>
      <footer className="pt-4">{controls}</footer>
      {focusedTile && <FocusedVideo tile={focusedTile} onClose={() => setFocused(null)} />}
    </div>
  );
}


export function CallStage() {
  const call = useCallStore((s) => s.current);
  const tiles = useVoiceStore((s) => s.videoTiles);
  const [focusedKey, setFocusedKey] = useState<string | null>(null);

  const focusedTile = tiles.find((tile) => keyOf(tile) === focusedKey);
  useEffect(() => {
    if (focusedKey && !focusedTile) setFocusedKey(null);
  }, [focusedKey, focusedTile]);

  if (call) return <DmCallScreen />;
  if (tiles.length === 0) return null;

  return (
    <>
      <div className="pointer-events-none fixed right-4 bottom-4 z-30 grid w-[min(90vw,26rem)] grid-cols-2 gap-2">
        {tiles.map((tile) => {
          const label =
            tile.source === "screen" ? screenLabel(tile) : `${tile.name}'s camera`;
          return (
            <button
              type="button"
              key={keyOf(tile)}
              onClick={() => setFocusedKey(keyOf(tile))}
              aria-label={`Expand ${label}`}
              title={`Expand ${label}`}
              className={cn(
                "pointer-events-auto aspect-video cursor-zoom-in overflow-hidden rounded-lg border bg-black shadow-lg",
                tile.source === "screen" ? "border-primary/60" : "border-border",
              )}
            >
              <VideoSurface tile={tile} />
            </button>
          );
        })}
      </div>
      {focusedTile && <FocusedVideo tile={focusedTile} onClose={() => setFocusedKey(null)} />}
    </>
  );
}
