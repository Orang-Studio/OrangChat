import { useCallback, useRef, useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation } from "@tanstack/react-query";
import { Link, useLocation, useNavigate } from "react-router-dom";
import { QrCode } from "lucide-react";
import { loginSchema, type LoginInput } from "@orangchat/shared";
import { AuthLayout } from "./AuthLayout";
import { OAuthButtons, OAuthDivider } from "./OAuthButtons";
import { QrLogin } from "./QrLogin";
import { Recaptcha, type RecaptchaHandle } from "./Recaptcha";
import { login, resendEmailCode, verifyEmailCode } from "./api";
import { applySession } from "./session";
import { ApiError } from "../../lib/api";
import { Button } from "../../components/ui/Button";
import { TextField } from "../../components/ui/TextField";
import { PasswordField } from "../../components/ui/PasswordField";

/**
 * Signing in is three steps, not one: the password, an authenticator code when
 * the account has one, then the code the server mails every time. Only the last
 * step returns a session.
 */
type Step = "credentials" | "totp" | "emailCode";

export function LoginPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const from = (location.state as { from?: { pathname: string } } | null)?.from?.pathname ?? "/";
  const [mode, setMode] = useState<"password" | "qr">("password");

  const [step, setStep] = useState<Step>("credentials");
  const [credentials, setCredentials] = useState<LoginInput | null>(null);
  const [loginToken, setLoginToken] = useState<string | null>(null);
  const [totpCode, setTotpCode] = useState("");
  const [emailCode, setEmailCode] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  // The server starts demanding a captcha after a couple of failed attempts on
  // an account. Without a token to hand it, the form could never recover from
  // one mistyped password; invisible reCAPTCHA collects it silently.
  const recaptcha = useRef<RecaptchaHandle>(null);
  const [recaptchaReady, setRecaptchaReady] = useState(false);
  const onCaptchaRequired = useCallback(() => setRecaptchaReady(true), []);

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<LoginInput>({
    resolver: zodResolver(loginSchema),
    defaultValues: { email: "", password: "" },
  });

  const restart = () => {
    setStep("credentials");
    setCredentials(null);
    setLoginToken(null);
    setTotpCode("");
    setEmailCode("");
    setError(null);
    setNotice(null);
  };

  const loginMutation = useMutation({
    mutationFn: login,
    onSuccess: (result, input) => {
      if (!result.loginToken) {
        setError("Could not start the sign-in. Try again.");
        return;
      }
      setCredentials(input);
      setLoginToken(result.loginToken);
      setStep("emailCode");
      setEmailCode("");
      setError(null);
      setNotice(null);
    },
    onError: (err, input) => {
      setNotice(null);
      if (err instanceof ApiError && err.code === "2fa_required") {
        setCredentials(input);
        setStep("totp");
        // Only a rejected code is worth an alert; the first prompt is not a failure.
        setError(input.totpCode ? err.message : null);
        setTotpCode("");
        return;
      }
      setError(err.message);
    },
  });

  const submitLogin = async (values: LoginInput) => {
    let recaptchaToken = "";
    try {
      recaptchaToken = (await recaptcha.current?.execute()) ?? "";
    } catch {
      setError("The reCAPTCHA challenge wasn't completed. Please try again.");
      return;
    }
    loginMutation.mutate({ ...values, recaptchaToken });
  };

  const verifyMutation = useMutation({
    mutationFn: ({ token, code }: { token: string; code: string }) => verifyEmailCode(token, code),
    onSuccess: (result) => {
      applySession(result.user, result.tokens);
      navigate(from, { replace: true });
    },
    onError: (err) => {
      setNotice(null);
      setError(err.message);
    },
  });

  const resendMutation = useMutation({
    mutationFn: resendEmailCode,
    onSuccess: () => {
      setEmailCode("");
      setError(null);
      setNotice("We sent a new code. Check your email.");
    },
    onError: (err) => {
      setNotice(null);
      setError(err.message);
    },
  });

  const alert = error && (
    <p role="alert" className="rounded-lg bg-primary-soft px-3 py-2 text-sm text-danger">
      {error}
    </p>
  );

  const body = () => {
    if (mode === "qr") return <QrLogin onBack={() => setMode("password")} />;

    if (step === "emailCode" && loginToken) {
      return (
        <form
          noValidate
          onSubmit={(event) => {
            event.preventDefault();
            verifyMutation.mutate({ token: loginToken, code: emailCode.trim() });
          }}
          className="space-y-4"
        >
          <p className="text-sm text-ink-secondary">
            We emailed you a sign-in code. It expires in 10 minutes.
          </p>
          <TextField
            label="Email code"
            inputMode="numeric"
            autoComplete="one-time-code"
            autoFocus
            placeholder="123456"
            value={emailCode}
            onChange={(event) => setEmailCode(event.target.value.slice(0, 12))}
          />
          {notice && <p className="text-sm text-ink-secondary">{notice}</p>}
          {alert}
          <Button
            type="submit"
            loading={verifyMutation.isPending}
            disabled={!emailCode.trim()}
            className="w-full"
          >
            Sign in
          </Button>
          <Button
            type="button"
            variant="ghost"
            loading={resendMutation.isPending}
            onClick={() => resendMutation.mutate(loginToken)}
            className="w-full"
          >
            Send a new code
          </Button>
          <Button type="button" variant="ghost" onClick={restart} className="w-full">
            Back
          </Button>
        </form>
      );
    }

    if (step === "totp" && credentials) {
      return (
        <form
          noValidate
          onSubmit={(event) => {
            event.preventDefault();
            void submitLogin({ ...credentials, totpCode: totpCode.trim() });
          }}
          className="space-y-4"
        >
          <TextField
            label="Authenticator code"
            inputMode="numeric"
            autoComplete="one-time-code"
            autoFocus
            placeholder="123456"
            hint="Enter the 6-digit code from your app, or a recovery code."
            value={totpCode}
            onChange={(event) => setTotpCode(event.target.value.slice(0, 32))}
          />
          {alert}
          <Button
            type="submit"
            loading={loginMutation.isPending}
            disabled={!totpCode.trim()}
            className="w-full"
          >
            Verify
          </Button>
          <Button type="button" variant="ghost" onClick={restart} className="w-full">
            Back
          </Button>
        </form>
      );
    }

    return (
      <>
        <OAuthButtons />
        <OAuthDivider />
        <form
          noValidate
          onSubmit={handleSubmit(submitLogin)}
          className="space-y-4"
        >
          <TextField
            label="Email"
            type="email"
            autoComplete="email"
            error={errors.email?.message}
            {...register("email")}
          />
          <PasswordField
            label="Password"
            autoComplete="current-password"
            error={errors.password?.message}
            {...register("password")}
          />
          {alert}
          <Button
            type="submit"
            loading={loginMutation.isPending}
            disabled={!recaptchaReady}
            className="w-full"
          >
            Log in
          </Button>
        </form>
        <button
          type="button"
          onClick={() => setMode("qr")}
          className="mt-4 flex w-full items-center justify-center gap-2 text-sm text-ink-secondary hover:underline"
        >
          <QrCode aria-hidden className="size-4" />
          Sign in with your phone
        </button>
      </>
    );
  };

  return (
    <AuthLayout
      title="Welcome back"
      subtitle="Log in to keep the conversation going."
      footer={
        <>
          New to OrangChat?{" "}
          <Link to="/signup" className="font-medium text-primary hover:underline">
            Create an account
          </Link>
        </>
      }
    >
      {body()}
      {/* Mounted for every step: the authenticator retry hits the same
          captcha-after-failures rule as the password step does. */}
      <Recaptcha ref={recaptcha} onRequired={onCaptchaRequired} />
    </AuthLayout>
  );
}
