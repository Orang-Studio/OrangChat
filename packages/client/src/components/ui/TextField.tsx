import { forwardRef, useId, type InputHTMLAttributes, type ReactNode } from "react";
import { cn } from "../../lib/cn";

export interface TextFieldProps extends InputHTMLAttributes<HTMLInputElement> {
  label: string;
  error?: string;
  hint?: string;

  trailing?: ReactNode;
}

export const TextField = forwardRef<HTMLInputElement, TextFieldProps>(
  function TextField(
    { label, error, hint, trailing, id: idProp, className, ...rest },
    ref,
  ) {
    const autoId = useId();
    const id = idProp ?? autoId;
    const describedBy = error ? `${id}-error` : hint ? `${id}-hint` : undefined;

    return (
      <div className="space-y-1.5">
        <label
          htmlFor={id}
          className="block text-sm font-medium text-ink-secondary"
        >
          {label}
        </label>
        <div className="relative">
          <input
            ref={ref}
            id={id}
            aria-invalid={error ? true : undefined}
            aria-describedby={describedBy}
            className={cn(
              "h-11 w-full rounded-lg border bg-surface-1 px-3 text-base text-ink placeholder:text-ink-muted md:h-10 md:text-sm",
              error ? "border-danger" : "border-border hover:border-border-strong",
              trailing && "pr-11",
              className,
            )}
            {...rest}
          />
          {trailing && (
            <div className="absolute inset-y-0 right-1.5 flex items-center">
              {trailing}
            </div>
          )}
        </div>
        {error ? (
          <p id={`${id}-error`} role="alert" className="text-sm text-danger">
            {error}
          </p>
        ) : hint ? (
          <p id={`${id}-hint`} className="text-xs text-ink-muted">
            {hint}
          </p>
        ) : null}
      </div>
    );
  },
);
