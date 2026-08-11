import { Phone, PhoneOff, Video } from "lucide-react";
import { Avatar } from "../../components/Avatar";
import { Button } from "../../components/ui/Button";
import { Dialog, DialogContent } from "../../components/ui/Dialog";
import { callActions, useCallStore } from "./callStore";
import { t } from "../../lib/i18n";

/**
 * The ringing popup. Mounted once app-wide (see AppShell) rather than under a
 * route, because a call can arrive whatever the user is looking at.
 */
export function IncomingCallDialog() {
  const incoming = useCallStore((s) => s.incoming);
  if (!incoming) return null;

  const group = incoming.ringing.length + incoming.participants.length > 2;
  const kind = incoming.video ? "video call" : "voice call";

  return (
    <Dialog
      open
      // Dismissing a ringing call - Escape, the close button, a click outside -
      // means declining it, never silently ignoring it.
      onOpenChange={(open) => {
        if (!open) callActions.decline();
      }}
    >
      <DialogContent
        title={`Incoming ${kind}`}
        description={
          group
            ? `${incoming.caller.displayName} started a ${kind} in this group`
            : `${incoming.caller.displayName} is calling you`
        }
        className="max-w-sm"
      >
        <div className="flex flex-col items-center gap-4 py-2">
          <Avatar user={incoming.caller} className="size-20 animate-pulse" />
          <p className="text-base font-semibold">{incoming.caller.displayName}</p>

          <div className="mt-2 flex w-full items-center justify-center gap-2">
            <Button
              variant="danger"
              onClick={() => callActions.decline()}
              className="flex-1"
            >
              <PhoneOff aria-hidden className="size-4" />
              {t("incomingCallDialog.decline")}
            </Button>
            <Button
              onClick={() => void callActions.accept()}
              className="flex-1 bg-success text-white hover:opacity-90"
            >
              <Phone aria-hidden className="size-4" />
              {t("incomingCallDialog.accept")}
            </Button>
          </div>
          {incoming.video && (
            <Button
              variant="secondary"
              onClick={() => void callActions.accept({ video: true })}
              className="w-full"
            >
              <Video aria-hidden className="size-4" />
              {t("incomingCallDialog.acceptWithCamera")}
            </Button>
          )}
        </div>
      </DialogContent>
    </Dialog>
  );
}
