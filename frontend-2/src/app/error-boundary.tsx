import { Component, type ErrorInfo, type ReactNode } from "react";
import { ErrorState } from "@/components/app/feedback/ErrorState";

interface ErrorBoundaryState {
  error: Error | null;
  correlationId?: string;
}

interface ErrorBoundaryProps {
  children: ReactNode;
  /** Optional fallback override. If provided, takes precedence over default ErrorState. */
  fallback?: (error: Error, reset: () => void) => ReactNode;
}

/**
 * Top-level React error boundary. Renders the A6 ErrorState component on
 * caught errors, surfacing the correlationId if the thrown value carries one.
 */
export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  constructor(props: ErrorBoundaryProps) {
    super(props);
    this.state = { error: null };
  }

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    const correlationId =
      typeof (error as Error & { correlationId?: string }).correlationId === "string"
        ? (error as Error & { correlationId?: string }).correlationId
        : undefined;
    return { error, correlationId };
  }

  override componentDidCatch(error: Error, info: ErrorInfo): void {
    if (import.meta.env.DEV) {
      console.error("[ErrorBoundary]", error, info);
    }
  }

  reset = (): void => {
    this.setState({ error: null, correlationId: undefined });
  };

  override render(): ReactNode {
    const { error, correlationId } = this.state;
    if (!error) return this.props.children;
    if (this.props.fallback) return this.props.fallback(error, this.reset);

    return (
      <div className="flex min-h-screen items-center justify-center p-6">
        <ErrorState
          title="Something went wrong"
          description={error.message || "Unexpected error."}
          correlationId={correlationId}
          retry={{ label: "Try again", onClick: this.reset }}
        />
      </div>
    );
  }
}
