import { mapApiErrorMessage } from "@/lib/api/user-messages";

export function isUnauthorized(err: unknown): boolean {
  if (!err) return false;
  if (typeof err === "object" && err !== null && "code" in err) {
    const code = (err as { code?: unknown }).code;
    if (code === "UNAUTHORIZED") return true;
  }
  const msg = err instanceof Error ? err.message : String(err);
  return /UNAUTHORIZED/i.test(msg);
}

export function extractAdminErrorMessage(err: unknown): string | null {
  if (!err) return null;
  return mapApiErrorMessage(err, "Something went wrong. Try again in a moment.");
}
