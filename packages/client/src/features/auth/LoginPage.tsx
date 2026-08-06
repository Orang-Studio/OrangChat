import { useCallback, useEffect, useRef, useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation } from '@tanstack/react-query';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { KeyRound, QrCode } from 'lucide-react';
import { loginSchema, type LoginInput } from '@orangchat/shared';
import { AuthLayout } from './AuthLayout';
import { OAuthButtons, OAuthDivider } from './OAuthButtons';
import { QrLogin } from './QrLogin';
import { Recaptcha, type RecaptchaHandle } from './Recaptcha';
import {
  login,
  passkeyFinish,
  passkeyStart,
  resendEmailCode,
  verifyEmailCode,
  type LoginChallenge,
} from './api';
import {
  autofillSupported,
  passkeyError,
  passkeysSupported,
  usePasskey,
  type RequestChallenge,
} from './webauthn';
import { applySession } from './session';
import { ApiError } from '../../lib/api';
import { Button } from '../../components/ui/Button';
import { TextField } from '../../components/ui/TextField';
import { PasswordField } from '../../components/ui/PasswordField';

/**
 * Signing in is three steps, not one: the password, an authenticator code when
 * the account has one, then a second factor - a passkey where the account has
 * one, the emailed code otherwise. Only the last step returns a session.
 *
 * A passkey can also replace the whole sequence: it proves the device, the
 * person and the origin in one gesture, so an account signing in that way skips
 * straight past the password and the code.
 */
type Step = 'credentials' | 'totp' | 'emailCode' | 'passkey';

export function LoginPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const from = (location.state as { from?: { pathname: string } } | null)?.from?.pathname ?? '/app';
  const [mode, setMode] = useState<'password' | 'qr'>('password');

  const [step, setStep] = useState<Step>('credentials');
  const [credentials, setCredentials] = useState<LoginInput | null>(null);
  const [loginToken, setLoginToken] = useState<string | null>(null);
  const [passkeyPrompt, setPasskeyPrompt] = useState<{
    challenge: RequestChallenge;
    ceremonyToken: string;
  } | null>(null);
  const [passkeyBusy, setPasskeyBusy] = useState(false);
  const [totpCode, setTotpCode] = useState('');
  const [emailCode, setEmailCode] = useState('');
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
    defaultValues: { email: '', password: '' },
  });

  /** The one place a passkey turns into a session, whichever flow started it. */
  const finishPasskey = useCallback(
    async (ceremonyToken: string, response: unknown) => {
      const result = await passkeyFinish(ceremonyToken, response);
      applySession(result.user, result.tokens);
      navigate(from, { replace: true });
    },
    [from, navigate],
  );

  const answerPasskey = async (prompt: Pick<LoginChallenge, 'challenge' | 'ceremonyToken'>) => {
    if (!prompt.challenge || !prompt.ceremonyToken) return;
    setPasskeyBusy(true);
    try {
      const response = await usePasskey(prompt.challenge);
      await finishPasskey(prompt.ceremonyToken, response);
    } catch (err) {
      setError(passkeyError(err) ?? 'That was cancelled. Try again, or use an email code.');
    } finally {
      setPasskeyBusy(false);
    }
  };

  /** Sign-in with nothing typed at all: the credential names the account. */
  const signInWithPasskey = async () => {
    setError(null);
    setNotice(null);
    setPasskeyBusy(true);
    try {
      const started = await passkeyStart();
      const response = await usePasskey(started.challenge);
      await finishPasskey(started.ceremonyToken, response);
    } catch (err) {
      const message = passkeyError(err);
      if (message) setError(message);
    } finally {
      setPasskeyBusy(false);
    }
  };

  // Conditional mediation: a request left open in the background so saved
  // passkeys appear in the browser's own dropdown on the email field. Most of
  // these are abandoned - somebody types a password instead - which is why it
  // aborts on unmount and why a cancellation is not reported as an error.
  useEffect(() => {
    let cancelled = false;
    const controller = new AbortController();
    void (async () => {
      if (!(await autofillSupported()) || cancelled) return;
      try {
        const started = await passkeyStart();
        if (cancelled) return;
        const response = await usePasskey(started.challenge, {
          conditional: true,
          signal: controller.signal,
        });
        await finishPasskey(started.ceremonyToken, response);
      } catch (err) {
        const message = passkeyError(err);
        if (message && !cancelled) setError(message);
      }
    })();
    return () => {
      cancelled = true;
      controller.abort();
    };
  }, [finishPasskey]);

  const restart = () => {
    setStep('credentials');
    setCredentials(null);
    setLoginToken(null);
    setPasskeyPrompt(null);
    setTotpCode('');
    setEmailCode('');
    setError(null);
    setNotice(null);
  };

  const loginMutation = useMutation({
    mutationFn: login,
    onSuccess: (result, input) => {
      if (result.passkeyRequired && result.challenge && result.ceremonyToken) {
        setCredentials(input);
        setPasskeyPrompt({ challenge: result.challenge, ceremonyToken: result.ceremonyToken });
        setStep('passkey');
        setError(null);
        setNotice(null);
        // Straight into the system sheet: the click that submitted the password
        // is the gesture, and making them click again would only add a step.
        void answerPasskey(result);
        return;
      }
      if (!result.loginToken) {
        setError('Could not start the sign-in. Try again.');
        return;
      }
      setCredentials(input);
      setLoginToken(result.loginToken);
      setStep('emailCode');
      setEmailCode('');
      setError(null);
      setNotice(null);
    },
    onError: (err, input) => {
      setNotice(null);
      if (err instanceof ApiError && err.code === '2fa_required') {
        setCredentials(input);
        setStep('totp');
        // Only a rejected code is worth an alert; the first prompt is not a failure.
        setError(input.totpCode ? err.message : null);
        setTotpCode('');
        return;
      }
      setError(err.message);
    },
  });

  const submitLogin = async (values: LoginInput & { skipPasskey?: boolean }) => {
    let recaptchaToken = '';
    try {
      recaptchaToken = (await recaptcha.current?.execute()) ?? '';
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
      setEmailCode('');
      setError(null);
      setNotice('We sent a new code. Check your email.');
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
    if (mode === 'qr') return <QrLogin onBack={() => setMode('password')} />;

    if (step === 'emailCode' && loginToken) {
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

    if (step === 'passkey' && passkeyPrompt) {
      return (
        <div className="space-y-4">
          <p className="text-sm text-ink-secondary">
            Confirm with your passkey to finish signing in.
          </p>
          {alert}
          <Button
            type="button"
            loading={passkeyBusy}
            onClick={() => void answerPasskey(passkeyPrompt)}
            className="w-full"
          >
            Use passkey
          </Button>
          {/* The way out for someone whose authenticator isn't to hand. It
              re-submits the password, so this is a fallback and not a bypass. */}
          <Button
            type="button"
            variant="ghost"
            loading={loginMutation.isPending}
            onClick={() => credentials && void submitLogin({ ...credentials, skipPasskey: true })}
            className="w-full"
          >
            Email me a code instead
          </Button>
          <Button type="button" variant="ghost" onClick={restart} className="w-full">
            Back
          </Button>
        </div>
      );
    }

    if (step === 'totp' && credentials) {
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
        <form noValidate onSubmit={handleSubmit(submitLogin)} className="space-y-4">
          <TextField
            label="Email"
            type="email"
            // "webauthn" is what lets the browser list saved passkeys in this
            // field's own dropdown, alongside the conditional request above.
            autoComplete="email webauthn"
            error={errors.email?.message}
            {...register('email')}
          />
          <PasswordField
            label="Password"
            autoComplete="current-password"
            error={errors.password?.message}
            {...register('password')}
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
        {passkeysSupported() && (
          <button
            type="button"
            disabled={passkeyBusy}
            onClick={() => void signInWithPasskey()}
            className="mt-4 flex w-full items-center justify-center gap-2 text-sm text-ink-secondary hover:underline disabled:opacity-60"
          >
            <KeyRound aria-hidden className="size-4" />
            Sign in with a passkey
          </button>
        )}
        <button
          type="button"
          onClick={() => setMode('qr')}
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
          New to OrangChat?{' '}
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
