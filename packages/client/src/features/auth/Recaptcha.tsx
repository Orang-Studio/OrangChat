import { forwardRef, useEffect, useImperativeHandle, useRef, useState } from "react";
import { api } from "../../lib/api";

type GrecaptchaRenderOptions = {
  sitekey: string;
  size: "invisible";
  badge: "bottomright" | "bottomleft" | "inline";
  callback: (token: string) => void;
  "expired-callback": () => void;
  "error-callback": () => void;
};

declare global {
  interface Window {
    grecaptcha?: {
      ready: (cb: () => void) => void;
      render: (element: HTMLElement, options: GrecaptchaRenderOptions) => number;
      execute: (id?: number) => void;
      reset: (id?: number) => void;
    };
  }
}

/** Resolves with a token, or "" when this deployment has no reCAPTCHA keys. */
export type RecaptchaHandle = { execute: () => Promise<string> };

const CHALLENGE_TIMEOUT_MS = 120_000;
const ONLOAD_CALLBACK = "__orangchatRecaptchaReady";

let scriptLoad: Promise<void> | undefined;

/**
 * `render=explicit` defines `window.grecaptcha` well before `grecaptcha.render`
 * exists, so keying off `script.onload` throws "render is not a function". The
 * documented `onload` parameter is the only signal that the API is usable.
 */
function loadScript(): Promise<void> {
  scriptLoad ??= new Promise<void>((resolve, reject) => {
    if (window.grecaptcha?.render) return resolve();
    (window as unknown as Record<string, () => void>)[ONLOAD_CALLBACK] = () => resolve();
    const script = document.createElement("script");
    script.src = `https://www.google.com/recaptcha/api.js?render=explicit&onload=${ONLOAD_CALLBACK}`;
    script.async = true;
    script.defer = true;
    script.onerror = () => reject(new Error("script"));
    document.head.appendChild(script);
  });
  return scriptLoad;
}

/**
 * Google reCAPTCHA v2, invisible variant: no checkbox, the challenge is raised
 * by `execute()` at submit time and only appears when Google wants one. The API
 * secret is deliberately never exposed.
 */
export const Recaptcha = forwardRef<
  RecaptchaHandle,
  { onRequired: (required: boolean) => void }
>(function Recaptcha({ onRequired }, ref) {
  const node = useRef<HTMLDivElement>(null);
  const widget = useRef<number>();
  const pending = useRef<{ resolve: (token: string) => void; reject: (err: Error) => void }>();
  const [error, setError] = useState("");

  useEffect(() => {
    let live = true;
    const settle = (token: string) => {
      pending.current?.resolve(token);
      pending.current = undefined;
    };
    const fail = () => {
      pending.current?.reject(new Error("challenge"));
      pending.current = undefined;
    };

    void api<{ enabled: boolean; siteKey?: string }>("/auth/recaptcha/config")
      .then(async ({ enabled, siteKey }) => {
        if (!enabled || !siteKey) {
          onRequired(false);
          return;
        }
        await loadScript();
        if (!live || !node.current || widget.current !== undefined) return;
        window.grecaptcha?.ready(() => {
          if (!live || !node.current || widget.current !== undefined) return;
          widget.current = window.grecaptcha?.render(node.current, {
            sitekey: siteKey,
            size: "invisible",
            badge: "inline",
            callback: settle,
            "expired-callback": fail,
            "error-callback": fail,
          });
          onRequired(true);
        });
      })
      .catch(() => {
        // A deployment that cannot reach Google must not become a wall: the
        // server still rejects a missing token, so the form stays usable and
        // the failure surfaces there instead of as a permanently dead button.
        onRequired(false);
        setError("reCAPTCHA could not load. Please refresh and try again.");
      });
    return () => {
      live = false;
      fail();
    };
  }, [onRequired]);

  useImperativeHandle(ref, () => ({
    execute() {
      if (widget.current === undefined || !window.grecaptcha) return Promise.resolve("");
      window.grecaptcha.reset(widget.current);
      return new Promise<string>((resolve, reject) => {
        const timer = setTimeout(() => {
          pending.current = undefined;
          reject(new Error("timeout"));
        }, CHALLENGE_TIMEOUT_MS);
        pending.current = {
          resolve: (token) => {
            clearTimeout(timer);
            resolve(token);
          },
          reject: (err) => {
            clearTimeout(timer);
            reject(err);
          },
        };
        window.grecaptcha?.execute(widget.current);
      });
    },
  }));

  return (
    <>
      {error ? <p role="alert" className="text-sm text-danger">{error}</p> : null}
      <div ref={node} className="flex justify-center [&:empty]:hidden" />
    </>
  );
});
