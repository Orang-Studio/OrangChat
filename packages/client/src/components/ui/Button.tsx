import { type ButtonHTMLAttributes, forwardRef } from "react";
import { Slot, Slottable } from "@radix-ui/react-slot";
import { cva, type VariantProps } from "class-variance-authority";
import { Loader2 } from "lucide-react";
import { cn } from "../../lib/cn";

export const buttonVariants = cva(
  "inline-flex items-center justify-center gap-2 rounded-lg text-sm transition-colors disabled:pointer-events-none disabled:opacity-60",
  {
    variants: {
      variant: {
        primary:
          "bg-primary font-semibold text-ink-on-primary hover:bg-primary-hover active:bg-primary-active",
        secondary:
          "border border-border-strong bg-surface-3 text-ink hover:bg-surface-4",
        ghost: "text-ink-secondary hover:bg-surface-3 hover:text-ink",
        danger: "bg-danger font-semibold text-white hover:opacity-90",
      },
      size: {
        sm: "h-8 px-3",
        md: "h-10 px-4",
        lg: "h-11 px-5",
        icon: "size-10",
      },
    },
    defaultVariants: { variant: "primary", size: "md" },
  },
);

export interface ButtonProps
  extends ButtonHTMLAttributes<HTMLButtonElement>,
    VariantProps<typeof buttonVariants> {

  asChild?: boolean;
  loading?: boolean;
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(
  function Button(
    { variant, size, asChild = false, loading = false, className, children, disabled, ...rest },
    ref,
  ) {
    const Comp = asChild ? Slot : "button";
    return (
      <Comp
        ref={ref}
        disabled={disabled || loading}
        aria-busy={loading || undefined}
        className={cn(buttonVariants({ variant, size }), className)}
        {...rest}
      >
        {loading && <Loader2 aria-hidden className="size-4 animate-spin" />}
        {/* Slottable marks which child Slot should merge into. Without it the
            spinner slot above counts as a second child -- even when `loading` is
            false, since `false` is still a child -- and Slot rejects anything
            but a single element. */}
        <Slottable>{children}</Slottable>
      </Comp>
    );
  },
);
