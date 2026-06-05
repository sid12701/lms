import { ApiError } from "@/lib/api/http-client";

const UNAUTHORIZED_CODES = new Set(["UNAUTHORIZED", "FORBIDDEN", "ACCESS_DENIED"]);

/** True when the API denied access (401/403 or known error codes). */
export function isUnauthorizedApiError(err: unknown): boolean {
  if (!err) return false;
  if (err instanceof ApiError) {
    if (err.status === 401 || err.status === 403) return true;
    if (err.code != null && UNAUTHORIZED_CODES.has(err.code)) return true;
  }
  if (typeof err === "object" && err !== null && "code" in err) {
    const code = (err as { code?: unknown }).code;
    if (typeof code === "string" && UNAUTHORIZED_CODES.has(code)) return true;
  }
  const msg = err instanceof Error ? err.message : String(err);
  return /\b(UNAUTHORIZED|FORBIDDEN|ACCESS_DENIED)\b/i.test(msg);
}

/** True when the resource is missing (404 / NOT_FOUND). */
export function isNotFoundApiError(err: unknown): boolean {
  if (!err) return false;
  if (err instanceof ApiError) {
    return err.status === 404 || err.code === "NOT_FOUND";
  }
  if (typeof err === "object" && err !== null && "code" in err) {
    return (err as { code?: unknown }).code === "NOT_FOUND";
  }
  return false;
}
