import { Loader2 } from "lucide-react";

export function RouteFallback() {
  return (
    <div className="flex min-h-[60vh] items-center justify-center">
      <Loader2 aria-hidden="true" className="text-foreground-muted h-5 w-5 animate-spin" />
      <span className="sr-only">Loading page</span>
    </div>
  );
}
