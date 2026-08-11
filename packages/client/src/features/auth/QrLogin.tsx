import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { RefreshCw, Smartphone } from "lucide-react";
import { Button } from "../../components/ui/Button";
import { qrPoll, qrStart } from "./api";
import { applySession } from "./session";
import { t } from "../../lib/i18n";

/** The QR encodes a deep link the app recognises, not a bare token. */
const linkFor = (token: string) => `orangchat://login?token=${token}`;

/**
 * QR sign-in on the web: show a code, let a signed-in phone scan and approve it,
 * and step in once it does. The web client only ever holds the opaque token; the
 * account is decided entirely on the phone.
 */
export function QrLogin({ onBack }: { onBack: () => void }) {
  const navigate = useNavigate();
  const [token, setToken] = useState<string | null>(null);
  const [svg, setSvg] = useState<string | null>(null);
  const [phase, setPhase] = useState<"loading" | "waiting" | "scanned" | "expired" | "error">(
    "loading",
  );

  const begin = useCallback(async () => {
    setPhase("loading");
    setSvg(null);
    try {
      const { token } = await qrStart();
      setToken(token);
      const qr = await import("qrcode");
      setSvg(await qr.toString(linkFor(token), { type: "svg", margin: 1, width: 208 }));
      setPhase("waiting");
    } catch {
      setPhase("error");
    }
  }, []);

  useEffect(() => {
    void begin();
  }, [begin]);

  // Poll while a code is live. Cleared on unmount and whenever the token changes.
  const savedNavigate = useRef(navigate);
  savedNavigate.current = navigate;
  useEffect(() => {
    if (!token || phase === "loading" || phase === "expired" || phase === "error") return;
    let stop = false;
    const tick = async () => {
      if (stop) return;
      try {
        const res = await qrPoll(token);
        if (stop) return;
        if (res.status === "approved" && res.session) {
          applySession(res.session.user, res.session.tokens);
          savedNavigate.current("/", { replace: true });
          return;
        }
        if (res.status === "expired") {
          setPhase("expired");
          return;
        }
        if (res.status === "scanned") setPhase("scanned");
      } catch {
        // Transient poll failure: keep trying until the code expires.
      }
      if (!stop) timer = window.setTimeout(tick, 2000);
    };
    let timer = window.setTimeout(tick, 2000);
    return () => {
      stop = true;
      window.clearTimeout(timer);
    };
  }, [token, phase]);

  return (
    <div className="flex flex-col items-center gap-4 text-center">
      <div className="relative flex size-52 items-center justify-center rounded-xl bg-white p-2">
        {svg ? (
          <div
            aria-label={t("qrLogin.signInQrCode")}
            className="size-full [&>svg]:size-full"
            dangerouslySetInnerHTML={{ __html: svg }}
          />
        ) : (
          <div className="size-full animate-pulse rounded-lg bg-surface-3" />
        )}
        {phase === "expired" && (
          <div className="absolute inset-0 flex flex-col items-center justify-center gap-2 rounded-xl bg-black/70 text-white">
            <p className="text-sm font-medium">{t("qrLogin.codeExpired")}</p>
            <Button type="button" size="sm" variant="secondary" onClick={() => void begin()}>
              <RefreshCw aria-hidden className="size-4" />
              {t("qrLogin.newCode")}
            </Button>
          </div>
        )}
      </div>

      <div className="space-y-1">
        {phase === "scanned" ? (
          <p className="flex items-center justify-center gap-1.5 text-sm font-medium text-primary">
            <Smartphone aria-hidden className="size-4" />
            {t("qrLogin.confirmOnYourPhone")}
          </p>
        ) : (
          <p className="text-sm font-medium">{t("qrLogin.scanWithTheOrangchatApp")}</p>
        )}
        <p className="text-xs text-ink-secondary">
          {t("qrLogin.openOrangchatOnYourPhoneAnd")}
        </p>
        {phase === "error" && (
          <p role="alert" className="text-xs text-danger">
            {t("qrLogin.couldNotStartQrSignIn")}{" "}
            <button type="button" className="underline" onClick={() => void begin()}>
              {t("qrLogin.tryAgain")}
            </button>
          </p>
        )}
      </div>

      <button type="button" onClick={onBack} className="text-sm text-ink-secondary hover:underline">
        {t("qrLogin.useEmailAndPasswordInstead")}
      </button>
    </div>
  );
}
