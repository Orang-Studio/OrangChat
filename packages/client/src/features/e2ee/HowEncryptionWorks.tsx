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
