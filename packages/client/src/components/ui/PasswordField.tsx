import { forwardRef, useState } from "react";
import { Eye, EyeOff } from "lucide-react";
import { TextField, type TextFieldProps } from "./TextField";

type PasswordFieldProps = Omit<TextFieldProps, "type" | "trailing">;

/** TextField with a show/hide-password eye toggle. */
export const PasswordField = forwardRef<HTMLInputElement, PasswordFieldProps>(
  function PasswordField(props, ref) {
    const [visible, setVisible] = useState(false);
    const Icon = visible ? EyeOff : Eye;

    return (
      <TextField
        ref={ref}
        type={visible ? "text" : "password"}
        trailing={
          <button
            type="button"
            aria-label={visible ? "Hide password" : "Show password"}
            aria-pressed={visible}
            onClick={() => setVisible((v) => !v)}
            className="rounded-md p-1.5 text-ink-muted transition-colors hover:text-ink"
          >
            <Icon aria-hidden className="size-4" />
          </button>
        }
        {...props}
      />
    );
  },
);
