import { useState, type ReactNode } from 'react';
import type { LucideIcon } from 'lucide-react';
import {
  BookOpen,
  CircleHelp,
  EyeOff,
  KeyRound,
  Lock,
  ScanLine,
  Smartphone,
  TriangleAlert,
} from 'lucide-react';
import { Dialog, DialogContent } from '../../components/ui/Dialog';
import { cn } from '../../lib/cn';

/**
 * The plain-language explanation of end-to-end encryption, written for somebody
 * with no technical background and no interest in acquiring one.
 *
 * Everything here is a metaphor of locks, keys and a logbook, deliberately: the
 * accurate words (identity key, epoch, transparency log, safety number) mean
 * nothing to the person the copy is for, and a reader who bounces off paragraph
 * one has learned less than one who reads a slightly lossy version to the end.
 *
 * Two things it must not do. It must not oversell the default - docs/E2EE.md
 * §6.4 is explicit that the honest sentence is "cannot read your messages
 * without being caught", not "cannot read your messages" - and it must not treat
 * the unverified default as a deficiency the reader should feel bad about.
 */

function Section({
  icon: Icon,
  title,
  children,
}: {
  icon: LucideIcon;
  title: string;
  children: ReactNode;
}) {
  return (
    <section className="flex gap-3">
      <span className="mt-0.5 flex size-8 shrink-0 items-center justify-center rounded-lg bg-surface-3 text-ink-secondary">
        <Icon aria-hidden className="size-4" />
      </span>
      <div className="min-w-0 flex-1 space-y-2">
        <h3 className="text-sm font-semibold">{title}</h3>
        <div className="space-y-2 text-sm leading-relaxed text-ink-secondary">{children}</div>
      </div>
    </section>
  );
}

export function HowEncryptionWorks() {
  return (
    <div className="space-y-5">
      <div className="rounded-xl border border-border bg-surface-1 p-4">
        <p className="flex items-center gap-2 text-sm font-semibold">
          <Lock aria-hidden className="size-4 text-primary" />
          Your direct messages are locked before they leave your device
        </p>
        <p className="mt-2 text-sm leading-relaxed text-ink-secondary">
          Only the people in the conversation can open them. Not OrangChat, not somebody who steals
          our database, not somebody who turns up with a court order - we do not hold a key we could
          hand over. This is on for every direct message and there is no way to switch it off.
        </p>
      </div>

      <Section icon={KeyRound} title="Every device cuts its own key">
        <p>
          Your phone and your computer each make their own key, on the device itself, the first time
          you use encryption there. It goes into the part of the device built to guard keys, and it
          is made so that it cannot be read back out - not by OrangChat, not by another app, not by
          you. There is nothing to leak and nothing to hand over.
        </p>
        <p>
          That is also why adding a second device is a small ceremony instead of a copy and paste.
          Your old device has to see the new one - a code on the screen, a camera pointed at it -
          before anything moves.
        </p>
      </Section>

      <Section icon={Lock} title="What happens when you press send">
        <p>
          Your device puts the message in a box and locks it. Every device in the conversation, and
          only those, holds a key to that box. Our servers store and pass along the locked box.
        </p>
        <p>
          We can still see the outside of it: who sent it, who it went to, how big it was and when.
          We cannot see what is inside.
        </p>
      </Section>

      <Section icon={BookOpen} title="The hard part: is it really their lock?">
        <p>
          To lock a box for someone, your device needs their lock, and it asks our servers for it.
          So the fair question is: what if we handed you a lock we had made ourselves, kept the key,
          and passed your messages along afterwards?
        </p>
        <p>Two answers, and you pick how much you want.</p>
        <p>
          <span className="font-medium text-ink">Standard - a swap gets caught.</span> Every lock
          anyone publishes is written into a logbook that can only be added to, never edited or
          erased. Your own devices read the page about your account every time they start. If a lock
          is ever published in your name that your devices did not make, they tell you, and the
          entry stays in the book where anyone can point at it. A swap can be attempted; it cannot
          be attempted quietly.
        </p>
        <p>
          <span className="font-medium text-ink">Verify first - a swap cannot happen.</span> You
          check the other person's lock yourself, in person or on a call, before anything is sent to
          them. Until you have, messages you type stay on your device, still locked, and go nowhere.
          There is nothing for a swapped lock to open.
        </p>
        <p>
          Standard is what every conversation gets. Verify first is the extra step, and it is only
          worth turning on for people you can realistically meet or ring.
        </p>
      </Section>

      <Section icon={ScanLine} title="How you verify someone">
        <p>
          Both of you open the conversation, tap the lock at the top, and one of you scans the
          other's code. Then swap and do it the other way round - one scan only proves one
          direction. It takes about ten seconds while you are stood together.
        </p>
        <p>
          Not in the same room? You will each see the same short row of numbers, called a safety
          code. Have them read it out on a phone call, or send it over another app you already
          trust, and type it into the box under the code - the app compares all sixty digits, which
          is more than anyone manages by eye. If it matches, nobody is in the middle. The one thing
          that does not count is an OrangChat voice call - that audio goes through the same servers
          this check exists to test.
        </p>
      </Section>

      <Section icon={TriangleAlert} title="If something ever looks wrong">
        <p>
          If a safety code changes, or a device you did not add appears on your account, OrangChat
          stops and takes over the screen instead of showing a notification you might swipe away.
        </p>
        <p>
          It is not always an attack - it is also what happens when somebody loses every device and
          has to start over. There is no way to tell those apart from inside the app, which is why
          it asks you to check with the person directly before you send anything else.
        </p>
      </Section>

      <Section icon={Smartphone} title="If you lose your only device">
        <p>
          The messages only that device could open stay locked, permanently. We cannot recover them
          for you - if we could, none of the above would be true.
        </p>
        <p>
          Adding a second device before you need one is the whole answer. That is also why
          two-factor authentication has to be on first: a spare device is a spare set of keys, and
          it should take more than a password to make one.
        </p>
      </Section>

      <Section icon={EyeOff} title="What this does not hide">
        <ul className="list-disc space-y-1.5 pl-5">
          <li>
            Who you talk to, when, and how often. Locked boxes still have to be addressed to
            somebody.
          </li>
          <li>
            Whatever the other person does with the message. They can screenshot it, save it, or
            show someone. Encryption stops strangers, not recipients.
          </li>
          <li>
            Anything on a device that is already unlocked and in someone else's hands. At that point
            they are reading it the same way you do.
          </li>
          <li>
            Server channels. Those have shared history, moderation and search, all of which need the
            server to read them. Only direct messages and group DMs are encrypted this way.
          </li>
        </ul>
      </Section>
    </div>
  );
}

export function HowEncryptionWorksDialog({
  open,
  onOpenChange,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        title="How your messages are protected"
        description="In plain language, with no jargon to get past first."
        className="max-w-lg"
      >
        <div className="mt-4">
          <HowEncryptionWorks />
        </div>
      </DialogContent>
    </Dialog>
  );
}

/** The way into the explainer from anywhere encryption is mentioned. */
export function HowEncryptionWorksLink({ className }: { className?: string }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button
        type="button"
        onClick={() => setOpen(true)}
        className={cn(
          'inline-flex items-center gap-1.5 text-xs text-ink-muted underline-offset-2 transition-colors hover:text-ink hover:underline',
          className,
        )}
      >
        <CircleHelp aria-hidden className="size-3.5" />
        How does this work?
      </button>
      <HowEncryptionWorksDialog open={open} onOpenChange={setOpen} />
    </>
  );
}
