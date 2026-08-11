import { useState } from 'react';
import { ShieldAlert } from 'lucide-react';
import type { User } from '@orangchat/shared';
import { Button } from '../../components/ui/Button';
import { useBlockedByVerification } from '../chat/outbox';
import { VerifyDialog } from './VerifyDialog';
import { t, tCount } from "../../lib/i18n";

/**
 * What the blocked state of strict mode looks like from inside the conversation
 * (§6.5). It has to read as a deliberate choice rather than a bug - especially
 * to the person who chose nothing - and the way out has to be one tap away.
 *
 * Nothing has been sent and nothing has been wrapped: the message is sitting on
 * this device, sealed, waiting.
 */
export function VerificationNotice({ channelId, peers }: { channelId: string; peers: User[] }) {
  const blocked = useBlockedByVerification(channelId);
  const [open, setOpen] = useState(false);

  if (!blocked || peers.length === 0) return null;

  const who = peers.length === 1 ? peers[0]!.displayName : 'everyone here';

  return (
    <>
      <div className="mx-4 mb-2 flex items-start gap-2 rounded-lg border border-warning/40 bg-warning/10 px-3 py-2">
        <ShieldAlert aria-hidden className="mt-0.5 size-4 shrink-0 text-warning" />
        <div className="min-w-0 flex-1">
          <p className="text-xs font-medium">{t("verificationNotice.yourMessageIsWaitingNotLost")}</p>
          <p className="text-xs leading-relaxed text-ink-secondary">
            {t("verificationNotice.notCheckedYet", { who })}
          </p>
        </div>
        <Button type="button" size="sm" className="shrink-0" onClick={() => setOpen(true)}>
          {tCount("verificationNotice.check", peers.length)}
        </Button>
      </div>
      {open && <VerifyDialog open onOpenChange={setOpen} peers={peers} channelId={channelId} />}
    </>
  );
}
