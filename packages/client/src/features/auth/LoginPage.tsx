import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation } from "@tanstack/react-query";
import { Link, useLocation, useNavigate } from "react-router-dom";
import { QrCode } from "lucide-react";
import { loginSchema, type LoginInput } from "@orangchat/shared";
import { AuthLayout } from "./AuthLayout";
import { OAuthButtons, OAuthDivider } from "./OAuthButtons";
import { QrLogin } from "./QrLogin";
import { login } from "./api";
import { applySession } from "./session";
import { Button } from "../../components/ui/Button";
import { TextField } from "../../components/ui/TextField";
import { PasswordField } from "../../components/ui/PasswordField";

export function LoginPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const from = (location.state as { from?: { pathname: string } } | null)?.from?.pathname ?? "/";
  const [mode, setMode] = useState<"password" | "qr">("password");

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<LoginInput>({
    resolver: zodResolver(loginSchema),
    defaultValues: { email: "", password: "" },
  });

  const mutation = useMutation({
    mutationFn: login,
    onSuccess: (result) => {
      applySession(result.user, result.tokens);
      navigate(from, { replace: true });
    },
  });

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
      {mode === "qr" ? (
        <QrLogin onBack={() => setMode("password")} />
      ) : (
        <>
      <OAuthButtons />
      <OAuthDivider />
      <form
        noValidate
        onSubmit={handleSubmit((values) => mutation.mutate(values))}
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
        {mutation.isError && (
          <p role="alert" className="rounded-lg bg-primary-soft px-3 py-2 text-sm text-danger">
            {mutation.error.message}
          </p>
        )}
        <Button type="submit" loading={mutation.isPending} className="w-full">
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
      )}
    </AuthLayout>
  );
}
