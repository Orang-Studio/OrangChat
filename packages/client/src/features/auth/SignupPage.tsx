import { useCallback, useRef, useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { z } from "zod";
import { displayNameSchema, signupSchema } from "@orangchat/shared";
import { AuthLayout } from "./AuthLayout";
import { OAuthButtons, OAuthDivider } from "./OAuthButtons";
import { signup } from "./api";
import { Recaptcha, type RecaptchaHandle } from "./Recaptcha";
import { Button } from "../../components/ui/Button";
import { TextField } from "../../components/ui/TextField";
import { PasswordField } from "../../components/ui/PasswordField";

/** Form-side variant: an empty display name means "not provided". */
const signupFormSchema = signupSchema.extend({
  displayName: displayNameSchema.or(z.literal("")),
});
type SignupFormValues = z.infer<typeof signupFormSchema>;

export function SignupPage() {
  const recaptcha = useRef<RecaptchaHandle>(null);
  const [recaptchaReady, setRecaptchaReady] = useState(false);
  const [captchaError, setCaptchaError] = useState("");
  const [awaitingCaptcha, setAwaitingCaptcha] = useState(false);
  const [verificationSent, setVerificationSent] = useState(false);
  const onCaptchaRequired = useCallback(() => setRecaptchaReady(true), []);

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<SignupFormValues>({
    resolver: zodResolver(signupFormSchema),
    defaultValues: { email: "", username: "", displayName: "", password: "" },
  });

  const mutation = useMutation({
    mutationFn: signup,
    onSuccess: () => {
      setVerificationSent(true);
    },
  });

  const onSubmit = async (values: SignupFormValues) => {
    setCaptchaError("");
    let recaptchaToken = "";
    setAwaitingCaptcha(true);
    try {
      recaptchaToken = (await recaptcha.current?.execute()) ?? "";
    } catch {
      setCaptchaError("The reCAPTCHA challenge wasn't completed. Please try again.");
      return;
    } finally {
      setAwaitingCaptcha(false);
    }
    mutation.mutate({
      ...values,
      displayName: values.displayName || undefined,
      recaptchaToken,
    });
  };

  return (
    <AuthLayout
      title="Create your account"
      subtitle="Claim your username and start chatting."
      footer={
        <>
          Already have an account?{" "}
          <Link to="/login" className="font-medium text-primary hover:underline">
            Log in
          </Link>
        </>
      }
    >
      <OAuthButtons />
      <OAuthDivider />
      <form noValidate onSubmit={handleSubmit(onSubmit)} className="space-y-4">
        {verificationSent ? (
          <p role="status" className="rounded-lg bg-primary-soft px-3 py-3 text-sm text-ink">Check your email for a verification link. You must verify it before signing in.</p>
        ) : (<>
        <TextField
          label="Email"
          type="email"
          autoComplete="email"
          error={errors.email?.message}
          {...register("email")}
        />
        <TextField
          label="Username"
          autoComplete="username"
          hint="Lowercase letters, numbers, underscores, and dots."
          error={errors.username?.message}
          {...register("username")}
        />
        <TextField
          label="Display name (optional)"
          autoComplete="nickname"
          error={errors.displayName?.message}
          {...register("displayName")}
        />
        <PasswordField
          label="Password"
          autoComplete="new-password"
          error={errors.password?.message}
          {...register("password")}
        />
        {(mutation.isError || captchaError) && (
          <p role="alert" className="rounded-lg bg-primary-soft px-3 py-2 text-sm text-danger">
            {captchaError || mutation.error?.message}
          </p>
        )}
        <Recaptcha ref={recaptcha} onRequired={onCaptchaRequired} />
        <Button
          type="submit"
          loading={mutation.isPending || awaitingCaptcha}
          disabled={!recaptchaReady}
          className="w-full"
        >
          Create account
        </Button>
        <p className="text-center text-xs leading-5 text-ink-muted">
          By creating an account, you agree to the{" "}
          <Link to="/terms" className="oc-link">Terms of Service</Link> and{" "}
          <Link to="/guidelines" className="oc-link">Community Guidelines</Link>, and acknowledge
          the <Link to="/privacy" className="oc-link">Privacy Policy</Link>.
        </p>
        </>)}
      </form>
    </AuthLayout>
  );
}
