import * as RadixDialog from "@radix-ui/react-dialog";
import { useRef, useState } from "react";
import { Download, Pause, Play, Volume2, VolumeX, X } from "lucide-react";
import type { Attachment } from "@orangchat/shared";
import { cn } from "../../lib/cn";
import { formatBytes } from "./attachments";
import { MediaSenderBar, type MediaContext } from "./MediaSenderBar";
import { useMediaZoom, type MediaOrigin } from "./useMediaZoom";
import { t } from "../../lib/i18n";


export function VideoLightbox({
  attachment,
  context,
  origin,
  open,
  startTime,
  onOpenChange,
}: {
  attachment: Attachment;

  context?: MediaContext;

  origin?: MediaOrigin | null;
  open: boolean;
  startTime: number;
  onOpenChange: (open: boolean) => void;
}) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const [playing, setPlaying] = useState(false);
  const [muted, setMuted] = useState(false);
  const [currentTime, setCurrentTime] = useState(startTime);
  const [duration, setDuration] = useState(0);
  const zoom = useMediaZoom(origin);

  const togglePlaying = () => {
    const video = videoRef.current;
    if (!video) return;
    if (video.paused) void video.play().catch(() => {});
    else video.pause();
  };

  const toggleMuted = () => {
    const video = videoRef.current;
    if (!video) return;
    video.muted = !video.muted;
    setMuted(video.muted);
  };

  return (
    <RadixDialog.Root open={open} onOpenChange={onOpenChange}>
      <RadixDialog.Portal>
        <RadixDialog.Overlay className="oc-backdrop fixed inset-0 z-40 bg-black/80" />
        <RadixDialog.Content
          aria-describedby={undefined}
          className="fixed inset-0 z-50 flex flex-col focus:outline-none"
          onCloseAutoFocus={(event) => event.preventDefault()}
        >
          <RadixDialog.Title className="sr-only">{attachment.filename}</RadixDialog.Title>

           <div className="oc-chrome-top absolute inset-x-0 top-0 z-10 flex items-center gap-3 bg-black/40 px-4 py-2.5 text-white">
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-medium">{attachment.filename}</p>
              {attachment.size > 0 && (
                <p className="text-[11px] text-white/60">{formatBytes(attachment.size)}</p>
              )}
            </div>
            <a
              href={attachment.url}
              download={attachment.filename}
              aria-label={`Download ${attachment.filename}`}
              className="flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-xs font-medium transition-colors hover:bg-white/15"
            >
              <Download aria-hidden className="size-4" />
              {t("common.download")}
            </a>
            <RadixDialog.Close
              aria-label={t("common.close")}
              className="rounded-lg p-1.5 transition-colors hover:bg-white/15"
            >
              <X aria-hidden className="size-4" />
            </RadixDialog.Close>
          </div>

           <div
             className={cn(
               "absolute inset-0 flex items-center justify-center p-4 pt-16",
               context ? "pb-40" : "pb-16",
             )}
            onClick={() => onOpenChange(false)}
          >
             <div ref={zoom.ref} className="flex max-h-full max-w-full">
             <video
               ref={videoRef}
               src={attachment.url}
              aria-label={attachment.filename}
              controls
              controlsList="nodownload"
              autoPlay
              playsInline
              preload="metadata"
               onLoadedMetadata={(event) => {
                 setDuration(event.currentTarget.duration);
                 if (startTime > 0) event.currentTarget.currentTime = startTime;
                 zoom.onReady();
               }}
               onTimeUpdate={(event) => setCurrentTime(event.currentTarget.currentTime)}
               onPlay={() => setPlaying(true)}
               onPause={() => setPlaying(false)}
               onEnded={() => setPlaying(false)}
               onClick={(event) => event.stopPropagation()}
               className="max-h-full max-w-full bg-black object-contain"
             />
             </div>
           </div>
           <div className="oc-chrome-bottom absolute inset-x-0 bottom-0 z-10">
           <div
             className="flex items-center gap-2 bg-black/60 px-4 py-2.5 text-white"
             onClick={(event) => event.stopPropagation()}
           >
             <button
               type="button"
               aria-label={playing ? "Pause video" : "Play video"}
               onClick={togglePlaying}
               className="rounded-lg p-1.5 transition-colors hover:bg-white/15"
             >
               {playing ? <Pause aria-hidden className="size-4" /> : <Play aria-hidden className="size-4" />}
             </button>
             <input
               type="range"
               min={0}
               max={Number.isFinite(duration) ? duration : 0}
               step={0.1}
               value={Math.min(currentTime, duration || currentTime)}
               disabled={!duration}
               onChange={(event) => {
                 const time = Number(event.target.value);
                 if (videoRef.current) videoRef.current.currentTime = time;
                 setCurrentTime(time);
               }}
               aria-label={t("videoLightbox.seekVideo")}
               className="min-w-0 flex-1 accent-[var(--oc-primary)]"
             />
             <span className="shrink-0 text-[11px] text-white/75">
               {formatVideoTime(currentTime)} / {formatVideoTime(duration)}
             </span>
             <button
               type="button"
               aria-label={muted ? "Unmute video" : "Mute video"}
               onClick={toggleMuted}
               className="rounded-lg p-1.5 transition-colors hover:bg-white/15"
             >
               {muted ? <VolumeX aria-hidden className="size-4" /> : <Volume2 aria-hidden className="size-4" />}
             </button>
           </div>
           {/* Under the transport, not beside it: the controls are about the
               clip, this is about the message it arrived on. */}
           {context && <MediaSenderBar context={context} />}
           </div>
         </RadixDialog.Content>
      </RadixDialog.Portal>
    </RadixDialog.Root>
  );
}

function formatVideoTime(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds < 0) return "0:00";
  const total = Math.floor(seconds);
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, "0")}`;
}
