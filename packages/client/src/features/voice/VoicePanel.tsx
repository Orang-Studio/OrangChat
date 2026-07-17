import {
  Headphones,
  HeadphoneOff,
  Mic,
  MicOff,
  PhoneOff,
  ScreenShare,
  ScreenShareOff,
  Signal,
  Video,
  VideoOff,
} from "lucide-react";
import { cn } from "../../lib/cn";
import { SoundboardPanel } from "../soundboard/SoundboardPanel";
import { callActions, useCallStore } from "./callStore";
import { useVoiceStore, voiceActions } from "./store";

function ControlButton({
  label,
  active,
  onClick,
  children,
}: {
  label: string;
  active?: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      aria-pressed={active}
      onClick={onClick}
      className={cn(
        "rounded-lg p-2 transition-colors hover:bg-surface-2",
        active ? "text-danger" : "text-ink-muted hover:text-ink",
      )}
    >
      {children}
    </button>
  );
}

/** Sidebar strip shown while in (or joining) a voice channel or DM call. */
export function VoicePanel() {
  const session = useVoiceStore((s) => s.session);
  const call = useCallStore((s) => s.current);
  if (!session) return null;

  // A call still ringing out reads as "Calling…" rather than "Voice connected",
  // even though we are already sitting in the LiveKit room.
  const ringingOut = call?.channelId === session.channelId && call.phase === "outgoing";
  const label =
    session.status === "error"
      ? "Voice error"
      : ringingOut
        ? "Calling…"
        : session.status === "connected"
          ? "Voice connected"
          : "Connecting…";

  const hangUp = () => {
    // Leaving voice without ending the call would strand it server-side.
    if (call?.channelId === session.channelId) void callActions.leave();
    else void voiceActions.leave();
  };

  return (
    <div className="border-t border-border bg-surface-0/40 p-2">
      <div className="flex items-center gap-2 px-1">
        <Signal
          aria-hidden
          className={cn(
            "size-4 shrink-0",
            session.status === "connected" && !ringingOut && "text-success",
            (session.status === "connecting" || ringingOut) && "animate-pulse text-warning",
            session.status === "error" && "text-danger",
          )}
        />
        <div className="min-w-0 flex-1 leading-tight">
          <p
            className={cn(
              "text-xs font-semibold",
              session.status === "connected" && !ringingOut
                ? "text-success"
                : "text-ink-secondary",
            )}
          >
            {label}
          </p>
          <p className="truncate text-xs text-ink-muted">
            {session.status === "error" ? session.error : session.channelName}
          </p>
        </div>
        <ControlButton
          label={session.muted ? "Unmute" : "Mute"}
          active={session.muted}
          onClick={() => void voiceActions.toggleMute()}
        >
          {session.muted ? (
            <MicOff aria-hidden className="size-4" />
          ) : (
            <Mic aria-hidden className="size-4" />
          )}
        </ControlButton>
        <ControlButton
          label={session.deafened ? "Undeafen" : "Deafen"}
          active={session.deafened}
          onClick={() => void voiceActions.toggleDeafen()}
        >
          {session.deafened ? (
            <HeadphoneOff aria-hidden className="size-4" />
          ) : (
            <Headphones aria-hidden className="size-4" />
          )}
        </ControlButton>
        <ControlButton
          label={session.video ? "Turn camera off" : "Turn camera on"}
          onClick={() => void voiceActions.toggleCamera()}
        >
          {session.video ? (
            <Video aria-hidden className="size-4 text-primary" />
          ) : (
            <VideoOff aria-hidden className="size-4" />
          )}
        </ControlButton>
        <SoundboardPanel />
        <ControlButton
          label={session.screenSharing ? "Stop sharing your screen" : "Share your screen"}
          onClick={() => void voiceActions.toggleScreenShare()}
        >
          {session.screenSharing ? (
            <ScreenShareOff aria-hidden className="size-4 text-primary" />
          ) : (
            <ScreenShare aria-hidden className="size-4" />
          )}
        </ControlButton>
        <ControlButton label={call ? "Hang up" : "Disconnect"} onClick={hangUp}>
          <PhoneOff aria-hidden className="size-4" />
        </ControlButton>
      </div>
    </div>
  );
}
