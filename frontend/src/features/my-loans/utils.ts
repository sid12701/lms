import { mapApiErrorMessage } from "@/lib/api/user-messages";

export function fmt(value: string | null | undefined, fallback = "—"): string {
  if (value == null) return fallback;
  const trimmed = value.trim();
  return trimmed === "" ? fallback : trimmed;
}

export function safeApiMessage(err: unknown, fallback: string): string {
  return mapApiErrorMessage(err, fallback);
}
