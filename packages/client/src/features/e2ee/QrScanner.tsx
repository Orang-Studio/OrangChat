import { useEffect, useRef, useState } from 'react';
import { Camera, ClipboardPaste } from 'lucide-react';
import { qrKindOf, type QrKind } from '@orangchat/shared';
import { Button } from '../../components/ui/Button';

/**
 * Reads one of the three OrangChat QR codes. They are visually identical and one
 * of them authorises a new device, so `expect` is mandatory and a code of the
 * wrong kind is rejected by name rather than "not recognised" - §6.7 exists
 * because "scan this to add me" otherwise ends with somebody scanning a
 * transfer code.
 */

type Detector = {
  detect: (source: CanvasImageSource) => Promise<{ rawValue: string }[]>;
};

interface BarcodeDetectorCtor {
  new (options?: { formats?: string[] }): Detector;
  getSupportedFormats?: () => Promise<string[]>;
}

function detectorCtor(): BarcodeDetectorCtor | null {
  const ctor = (globalThis as { BarcodeDetector?: BarcodeDetectorCtor }).BarcodeDetector;
  return typeof ctor === 'function' ? ctor : null;
}

const KIND_LABEL: Record<QrKind, string> = {
  login: 'sign-in',
  'device-transfer': 'device transfer',
  verify: 'contact verification',
};

export function QrScanner({
  expect,
  onScan,
  onCancel,
}: {
  expect: QrKind;
  onScan: (raw: string) => void;
  onCancel?: () => void;
}) {
  const video = useRef<HTMLVideoElement>(null);
  const [error, setError] = useState<string | null>(null);
  const [manual, setManual] = useState(!detectorCtor());
  const [pasted, setPasted] = useState('');

  useEffect(() => {
    if (manual) return;
    const Ctor = detectorCtor();
    if (!Ctor) return;

    let stream: MediaStream | null = null;
    let stopped = false;
    let frame = 0;

    const stop = () => {
      stopped = true;
      cancelAnimationFrame(frame);
      stream?.getTracks().forEach((track) => track.stop());
    };

    void (async () => {
      try {
        stream = await navigator.mediaDevices.getUserMedia({
          video: { facingMode: 'environment' },
        });
        if (stopped) return stop();
        const element = video.current;
        if (!element) return stop();
        element.srcObject = stream;
        await element.play();

        const detector = new Ctor({ formats: ['qr_code'] });
        const tick = async () => {
          if (stopped) return;
          try {
            const found = await detector.detect(element);
            const raw = found[0]?.rawValue;
            if (raw) {
              const kind = qrKindOf(raw);
              if (kind === expect) {
                stop();
                onScan(raw);
                return;
              }
              if (kind !== null) {
                setError(
                  `That is a ${KIND_LABEL[kind]} code. This screen only accepts a ${KIND_LABEL[expect]} code.`,
                );
              }
            }
          } catch {
            // A frame that will not decode is the normal case, not an error.
          }
          frame = requestAnimationFrame(() => void tick());
        };
        frame = requestAnimationFrame(() => void tick());
      } catch {
        setError('This browser would not give OrangChat the camera.');
        setManual(true);
      }
    })();

    return stop;
  }, [expect, manual, onScan]);

  const submitManual = () => {
    const raw = pasted.trim();
    const kind = qrKindOf(raw);
    if (kind === null) {
      setError('That is not an OrangChat code.');
      return;
    }
    if (kind !== expect) {
      setError(
        `That is a ${KIND_LABEL[kind]} code. This screen only accepts a ${KIND_LABEL[expect]} code.`,
      );
      return;
    }
    onScan(raw);
  };

  return (
    <div className="space-y-3">
      {!manual && (
        <div className="overflow-hidden rounded-xl border border-border bg-black">
          <video
            ref={video}
            muted
            playsInline
            aria-label="Camera preview for scanning a code"
            className="aspect-square w-full object-cover"
          />
        </div>
      )}

      {manual && (
        <div className="space-y-2">
          <label htmlFor="qr-manual" className="block text-sm text-ink-secondary">
            Paste the code text instead. It starts with <code>orangchat://</code>.
          </label>
          <textarea
            id="qr-manual"
            rows={3}
            value={pasted}
            onChange={(event) => {
              setPasted(event.target.value);
              setError(null);
            }}
            className="w-full rounded-lg border border-border bg-surface-1 p-2 font-mono text-xs text-ink"
          />
          <Button type="button" size="sm" onClick={submitManual} disabled={pasted.trim() === ''}>
            <ClipboardPaste aria-hidden className="size-4" />
            Use this code
          </Button>
        </div>
      )}

      {error && (
        <p role="alert" className="text-xs text-danger">
          {error}
        </p>
      )}

      <div className="flex items-center gap-2">
        {!manual && (
          <Button type="button" size="sm" variant="ghost" onClick={() => setManual(true)}>
            <ClipboardPaste aria-hidden className="size-4" />
            Paste instead
          </Button>
        )}
        {manual && detectorCtor() && (
          <Button
            type="button"
            size="sm"
            variant="ghost"
            onClick={() => {
              setManual(false);
              setError(null);
            }}
          >
            <Camera aria-hidden className="size-4" />
            Use the camera
          </Button>
        )}
        {onCancel && (
          <Button type="button" size="sm" variant="ghost" onClick={onCancel}>
            Cancel
          </Button>
        )}
      </div>
    </div>
  );
}
