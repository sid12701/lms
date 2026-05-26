/**
 * Real backend auth + system-context calls.
 *
 * Matches `com.bhawana.lms.web.AuthController` and
 * `/api/v1/internal/system/context`. The refresh-token rotation uses the
 * `lms-refresh` httpOnly cookie that the backend sets on login/refresh, so
 * we only need `credentials: include`.
 */
import { ApiError, requestJson } from "@/lib/api/http-client";

export interface BackendTokenResponse {
  accessToken: string;
  tokenType: string;
  expiresInSeconds: number;
  passwordChangeRequired: boolean;
}

export interface BackendSystemContext {
  application: string;
  activeProfiles: string[];
  id: string;
  username: string;
  roles: string[];
  correlationId: string | null;
  lspId: string | null;
  lspName: string | null;
}

export function loginWithPassword(username: string, password: string): Promise<BackendTokenResponse> {
  return requestJson<BackendTokenResponse>(
    "/api/v1/auth/login",
    { method: "POST", body: JSON.stringify({ username, password }) },
    { authenticated: false },
  );
}

export function refreshAccessToken(): Promise<BackendTokenResponse> {
  return requestJson<BackendTokenResponse>(
    "/api/v1/auth/refresh",
    { method: "POST" },
    { authenticated: false },
  );
}

export function completePasswordChange(newPassword: string): Promise<BackendTokenResponse> {
  return requestJson<BackendTokenResponse>("/api/v1/auth/password", {
    method: "POST",
    body: JSON.stringify({ newPassword }),
  });
}

export function logoutSession(): Promise<void> {
  return requestJson<void>(
    "/api/v1/auth/logout",
    { method: "POST" },
    { authenticated: false },
  );
}

export function fetchSystemContext(accessToken?: string): Promise<BackendSystemContext> {
  return requestJson<BackendSystemContext>(
    "/api/v1/internal/system/context",
    {},
    accessToken ? { accessToken } : {},
  );
}

export function isPasswordChangeRequiredError(error: unknown): boolean {
  if (!(error instanceof ApiError)) return false;
  return (
    error.status === 428 ||
    error.code === "PASSWORD_CHANGE_REQUIRED" ||
    error.code === "PASSWORD_RESET_REQUIRED" ||
    error.body.includes("PASSWORD_CHANGE_REQUIRED") ||
    error.body.includes("PASSWORD_RESET_REQUIRED")
  );
}
