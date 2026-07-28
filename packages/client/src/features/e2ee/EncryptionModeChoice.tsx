import { Check, Lock, ShieldCheck, Users } from 'lucide-react';
import { cn } from '../../lib/cn';

/**
 * The two modes of docs/E2EE.md §6, presented as the choice they actually are.
 *
 * It used to be a switch labelled "verify before sending", sitting under a
 * safety number, which reads as "encryption: off/on" to anyone who has not read
 * the design doc - the exact misreading §6 forbids. Both options here are
 * encrypted; what differs is whether a swapped key is prevented or merely
 * caught, so both cards say so and neither is decorated as the deficient one.
 */

export type EncryptionMode = 'standard' | 'verify-first';

function ModeCard({
  mode,
  selected,
  onSelect,
  disabled,
}: {
  mode: EncryptionMode;
  selected: boolean;
  onSelect: () => void;
  disabled?: boolean;
}) {
  const standard = mode === 'standard';
  const Icon = standard ? Lock : ShieldCheck;

  return (
    <button
      type="button"
      role="radio"
      aria-checked={selected}
      disabled={disabled}
      onClick={onSelect}
      className={cn(
        'flex w-full items-start gap-3 rounded-xl border px-3 py-3 text-left transition-colors',
        selected ? 'border-primary bg-primary-soft' : 'border-border hover:border-border-strong',
        disabled && 'cursor-not-allowed opacity-50',
      )}
    >
      <Icon
        aria-hidden
        className={cn('mt-0.5 size-4 shrink-0', selected ? 'text-primary' : 'text-ink-muted')}
      />
      <span className="min-w-0 flex-1">
        <span className="block text-sm font-medium">
          {standard ? 'Send straight away' : 'Check them first'}
        </span>
        <span className="mt-0.5 block text-xs leading-relaxed text-ink-secondary">
          {standard
            ? "Messages go as soon as you send them. If anyone ever swaps this person's lock, your devices notice and tell you."
            : 'Nothing is sent until you have checked their code in person or on a call. Until then your messages wait here, locked, and go nowhere.'}
        </span>
      </span>
      {selected && <Check aria-hidden className="mt-0.5 size-4 shrink-0 text-primary" />}
    </button>
  );
}

export function EncryptionModeChoice({
  mode,
  onChange,
  disabled,
}: {
  mode: EncryptionMode;
  onChange: (next: EncryptionMode) => void;
  disabled?: boolean;
}) {
  return (
    <div className="space-y-2">
      <div className="space-y-1">
        <p className="text-sm font-medium">Before a message leaves this device</p>
        <p className="text-xs leading-relaxed text-ink-muted">
          Both options encrypt everything. The difference is whether a swapped lock is stopped
          before it can be used, or caught afterwards.
        </p>
      </div>
      <div
        role="radiogroup"
        aria-label="Encryption mode for this conversation"
        className="space-y-2"
      >
        <ModeCard
          mode="standard"
          selected={mode === 'standard'}
          disabled={disabled}
          onSelect={() => mode !== 'standard' && onChange('standard')}
        />
        <ModeCard
          mode="verify-first"
          selected={mode === 'verify-first'}
          disabled={disabled}
          onSelect={() => mode !== 'verify-first' && onChange('verify-first')}
        />
      </div>
    </div>
  );
}

/**
 * Groups are standard-only in v1 (§6.3), and the reason has to be visible rather
 * than looking like a missing feature: one member holding a twenty-person
 * conversation until they have personally met nineteen people is not a security
 * win.
 */
export function GroupModeNote() {
  return (
    <div className="flex gap-3 rounded-xl border border-border px-3 py-3">
      <Users aria-hidden className="mt-0.5 size-4 shrink-0 text-ink-muted" />
      <div className="min-w-0 flex-1 space-y-1">
        <p className="text-sm font-medium">Group conversations send straight away</p>
        <p className="text-xs leading-relaxed text-ink-secondary">
          Checking someone's code is a one-to-one thing, so it happens in your direct message with
          them - and anyone you have checked there shows as checked here too. Waiting on everyone in
          a group to be checked would hold up people who never asked for it.
        </p>
      </div>
    </div>
  );
}
