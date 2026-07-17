import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation } from "@tanstack/react-query";
import { Link, useNavigate } from "react-router-dom";
import { z } from "zod";
import { displayNameSchema, signupSchema } from "@orangchat/shared";
import { AuthLayout } from "./AuthLayout";
import { OAuthButtons, OAuthDivider } from "./OAuthButtons";
import { signup } from "./api";
import { applySession } from "./session";
import { Button } from "../../components/ui/Button";
import { TextField } from "../../components/ui/TextField";
import { PasswordField } from "../../components/ui/PasswordField";

/** Form-side variant: an empty display name means "not provided". */
const signupFormSchema = signupSchema.extend({
  displayName: displayNameSchema.or(z.literal("")),
});
type SignupFormValues = z.infer<typeof signupFormSchema>;

export function SignupPage() {
  const navigate = useNavigate();

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
    onSuccess: (result) => {
      applySession(result.user, result.tokens);
      navigate("/", { replace: true });
    },
  });

  const onSubmit = (values: SignupFormValues) =>
    mutation.mutate({
      ...values,
      displayName: values.displayName || undefined,
    });

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
        {mutation.isError && (
          <p role="alert" className="rounded-lg bg-primary-soft px-3 py-2 text-sm text-danger">
            {mutation.error.message}
          </p>
        )}
        <Button type="submit" loading={mutation.isPending} className="w-full">
          Create account
        </Button>
      </form>
    </AuthLayout>
  );
}
