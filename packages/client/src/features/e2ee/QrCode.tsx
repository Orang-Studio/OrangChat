import { useEffect, useState } from 'react';


export function QrCode({ value, label }: { value: string; label: string }) {
  const [svg, setSvg] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      const qr = await import('qrcode');
      const rendered = await qr.toString(value, { type: 'svg', margin: 1, width: 208 });
      if (!cancelled) setSvg(rendered);
    })();
    return () => {
      cancelled = true;
    };
  }, [value]);

  return (
    <div className="mx-auto flex size-52 items-center justify-center rounded-xl bg-white p-2">
      {svg ? (
        <div
          aria-label={label}
          className="size-full [&>svg]:size-full"
          dangerouslySetInnerHTML={{ __html: svg }}
        />
      ) : (
        <div className="size-full animate-pulse rounded-lg bg-surface-3" />
      )}
    </div>
  );
}
