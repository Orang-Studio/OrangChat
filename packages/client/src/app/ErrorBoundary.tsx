import { Component, type ErrorInfo, type ReactNode } from "react";
import { LogoMark } from "../components/LogoMark";

interface ErrorBoundaryProps {
  children: ReactNode;
}

interface ErrorBoundaryState {
  error: Error | null;
}

/**
 * App-level error boundary. A render error anywhere below unmounts the whole
 * React tree — without this, the user gets a blank screen with no recovery.
 * User-supplied CSS, marketplace plugins and profile markdown render inline,
 * so a bad value reaching a component can throw at any time.
 */
export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  override state: ErrorBoundaryState = { error: null };

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return { error };
  }

  override componentDidCatch(error: Error, info: ErrorInfo): void {
    console.error("Uncaught render error:", error, info.componentStack);
  }

  override render() {
    if (this.state.error) {
      return (
        <div
          role="alert"
          className="flex min-h-dvh flex-col items-center justify-center gap-4 bg-surface-0 p-6 text-center"
        >
          <LogoMark className="size-16" />
          <h1 className="text-lg font-semibold text-ink">Something went wrong</h1>
          <p className="max-w-md text-sm text-ink-muted">
            OrangChat hit an unexpected error and could not keep showing this
            page. Your messages are safe — reload to try again.
          </p>
          <pre className="max-h-40 max-w-full overflow-auto rounded-md border border-border bg-surface-2 p-3 text-left text-xs text-ink-secondary">
            {this.state.error.message || String(this.state.error)}
          </pre>
          <button
            type="button"
            onClick={() => window.location.reload()}
            className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-ink-on-primary hover:bg-primary-hover"
          >
            Reload OrangChat
          </button>
        </div>
      );
    }

    return this.props.children;
  }
}
