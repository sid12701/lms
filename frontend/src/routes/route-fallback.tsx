import { Loader2 } from "lucide-react";

export interface RouteFallbackProps {
  /**
   * True when the fallback is the whole document (auth guard, landing
   * redirect — no `AppShell` around it): renders a `main` landmark + sr-only
   * h1 so axe's landmark/heading checks pass during the transient frame.
   * Leave false inside the shell — its `<main id="main">` already exists and
   * landmarks must not nest.
   */
  standalone?: boolean;
}

export function RouteFallback({ standalone = false }: RouteFallbackProps) {
  const spinner = (
    <>
      <Loader2 aria-hidden="true" className="text-foreground-muted h-5 w-5 animate-spin" />
      {standalone ? (
        <h1 className="sr-only">Loading page</h1>
      ) : (
        <span className="sr-only">Loading page</span>
      )}
    </>
  );
  const className = "flex min-h-[60vh] items-center justify-center";
  if (standalone) {
    return <main className={className}>{spinner}</main>;
  }
  return (
    <div role="status" className={className}>
      {spinner}
    </div>
  );
}
