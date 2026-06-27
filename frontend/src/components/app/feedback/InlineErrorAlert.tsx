import { cn } from "@/lib/utils";

export interface InlineErrorAlertProps {
  message: string | null;
  className?: string;
}

/** Accessible inline error alert using project danger tokens (4.5:1 on light surfaces). */
export function InlineErrorAlert({ message, className }: InlineErrorAlertProps) {
  if (!message) return null;
  return (
    <div
      role="alert"
      className={cn(
        "border-danger/30 bg-danger/5 text-danger rounded-md border p-3 text-sm",
        className,
      )}
    >
      {message}
    </div>
  );
}
