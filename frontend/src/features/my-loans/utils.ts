import { ApiError } from "@/lib/api/http-client";

export function fmt(value: string | null | undefined, fallback = "—"): string {
  if (value == null) return fallback;
  const trimmed = value.trim();
  return trimmed === "" ? fallback : trimmed;
}

export function safeApiMessage(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    if (err.status === 401 || err.status === 403) {
      return "Your role cannot perform this action.";
    }
    return err.message || fallback;
  }
  if (err instanceof Error) return err.message;
  return fallback;
}
