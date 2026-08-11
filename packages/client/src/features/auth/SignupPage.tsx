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
import { t, tNodes } from "../../lib/i18n";

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
      title={t("signupPage.createYourAccount")}
      subtitle={t("signupPage.claimYourUsernameAndStartChatting")}
      footer={
        <>
          {t("signupPage.alreadyHaveAnAccount")}{" "}
          <Link to="/login" className="font-medium text-primary hover:underline">
            {t("signupPage.logIn")}
          </Link>
        </>
      }
    >
      <OAuthButtons />
      <OAuthDivider />
      <form noValidate onSubmit={handleSubmit(onSubmit)} className="space-y-4">
        {verificationSent ? (
          <p role="status" className="rounded-lg bg-primary-soft px-3 py-3 text-sm text-ink">{t("signupPage.checkYourEmailForAVerification")}</p>
        ) : (<>
        <TextField
          label={t("signupPage.email")}
          type="email"
          autoComplete="email"
          error={errors.email?.message}
          {...register("email")}
        />
        <TextField
          label={t("signupPage.username")}
          autoComplete="username"
          hint={t("signupPage.lowercaseLettersNumbersUnderscoresAndDots")}
          error={errors.username?.message}
          {...register("username")}
        />
        <TextField
          label={t("signupPage.displayNameOptional")}
          autoComplete="nickname"
          error={errors.displayName?.message}
          {...register("displayName")}
        />
        <PasswordField
          label={t("signupPage.password")}
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
          {t("signupPage.createAccount")}
        </Button>
        <p className="text-center text-xs leading-5 text-ink-muted">
          {tNodes("signupPage.legalConsent", {
            terms: (
              <Link to="/terms" className="oc-link">
                {t("signupPage.termsOfService")}
              </Link>
            ),
            guidelines: (
              <Link to="/guidelines" className="oc-link">
                {t("signupPage.communityGuidelines")}
              </Link>
            ),
            privacy: (
              <Link to="/privacy" className="oc-link">
                {t("signupPage.privacyPolicy")}
              </Link>
            ),
          })}
        </p>
        </>)}
      </form>
    </AuthLayout>
  );
}
