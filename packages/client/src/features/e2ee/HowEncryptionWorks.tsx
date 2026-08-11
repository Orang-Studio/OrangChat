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
import { t } from "../../lib/i18n";

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
          {t("howEncryptionWorks.yourDirectMessagesAreLockedBefore")}
        </p>
        <p className="mt-2 text-sm leading-relaxed text-ink-secondary">
          {t("howEncryptionWorks.onlyThePeopleInTheConversation")}
        </p>
      </div>

      <Section icon={KeyRound} title={t("howEncryptionWorks.everyDeviceCutsItsOwnKey")}>
        <p>
          {t("howEncryptionWorks.yourPhoneAndYourComputerEach")}
        </p>
        <p>
          {t("howEncryptionWorks.thatIsAlsoWhyAddingA")}
        </p>
      </Section>

      <Section icon={Lock} title={t("howEncryptionWorks.whatHappensWhenYouPressSend")}>
        <p>
          {t("howEncryptionWorks.yourDevicePutsTheMessageIn")}
        </p>
        <p>
          {t("howEncryptionWorks.weCanStillSeeTheOutside")}
        </p>
      </Section>

      <Section icon={BookOpen} title={t("howEncryptionWorks.theHardPartIsItReally")}>
        <p>
          {t("howEncryptionWorks.toLockABoxForSomeone")}
        </p>
        <p>{t("howEncryptionWorks.twoAnswersAndYouPickHow")}</p>
        <p>
          <span className="font-medium text-ink">{t("howEncryptionWorks.standardASwapGetsCaught")}</span> {t("howEncryptionWorks.everyLockAnyonePublishesIsWritten")}
        </p>
        <p>
          <span className="font-medium text-ink">{t("howEncryptionWorks.verifyFirstASwapCannotHappen")}</span> {t("howEncryptionWorks.youCheckTheOtherPersonsLock")}
        </p>
        <p>
          {t("howEncryptionWorks.standardIsWhatEveryConversationGets")}
        </p>
      </Section>

      <Section icon={ScanLine} title={t("howEncryptionWorks.howYouVerifySomeone")}>
        <p>
          {t("howEncryptionWorks.bothOfYouOpenTheConversation")}
        </p>
        <p>
          {t("howEncryptionWorks.notInTheSameRoomYou")}
        </p>
      </Section>

      <Section icon={TriangleAlert} title={t("howEncryptionWorks.ifSomethingEverLooksWrong")}>
        <p>
          {t("howEncryptionWorks.ifASafetyCodeChangesOr")}
        </p>
        <p>
          {t("howEncryptionWorks.itIsNotAlwaysAnAttack")}
        </p>
      </Section>

      <Section icon={Smartphone} title={t("howEncryptionWorks.ifYouLoseYourOnlyDevice")}>
        <p>
          {t("howEncryptionWorks.theMessagesOnlyThatDeviceCould")}
        </p>
        <p>
          {t("howEncryptionWorks.addingASecondDeviceBeforeYou")}
        </p>
      </Section>

      <Section icon={EyeOff} title={t("howEncryptionWorks.whatThisDoesNotHide")}>
        <ul className="list-disc space-y-1.5 pl-5">
          <li>
            {t("howEncryptionWorks.whoYouTalkToWhenAnd")}
          </li>
          <li>
            {t("howEncryptionWorks.whateverTheOtherPersonDoesWith")}
          </li>
          <li>
            {t("howEncryptionWorks.anythingOnADeviceThatIs")}
          </li>
          <li>
            {t("howEncryptionWorks.serverChannelsThoseHaveSharedHistory")}
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
        title={t("howEncryptionWorks.howYourMessagesAreProtected")}
        description={t("howEncryptionWorks.inPlainLanguageWithNoJargon")}
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
        {t("howEncryptionWorks.howDoesThisWork")}
      </button>
      <HowEncryptionWorksDialog open={open} onOpenChange={setOpen} />
    </>
  );
}
