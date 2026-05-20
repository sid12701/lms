/**
 * Typed error envelope for the mock router.
 *
 * Every handler throws a `MockError` (or a subclass); the router catches
 * and serialises into `{ ok: false, error }`. Unknown throws are wrapped
 * as 500 INTERNAL_SERVER_ERROR.
 *
 * `BusinessRuleError` from `@/lib/lifecycle` is bridged in
 * `wrapBusinessRuleError()` — its `code` already lines up with our envelope's
 * `code` union via the shared `BusinessRuleErrorCode` type.
 */
import { BusinessRuleError, type BusinessRuleErrorCode } from "@/lib/lifecycle";

export type ErrorCode =
  | BusinessRuleErrorCode
  | "NOT_FOUND"
  | "UNAUTHORIZED"
  | "FORBIDDEN"
  | "BAD_REQUEST"
  | "INTERNAL_SERVER_ERROR"
  | "NOT_IMPLEMENTED"
  | "TIMEOUT"
  | "CONFLICT";

export interface MockErrorPayload {
  code: ErrorCode;
  message: string;
  correlationId: string;
  httpStatus: number;
  details?: unknown;
}

export interface MockErrorEnvelope {
  ok: false;
  error: MockErrorPayload;
}

export class MockError extends Error {
  public readonly code: ErrorCode;
  public readonly httpStatus: number;
  public readonly correlationId: string;
  public readonly details?: unknown;

  constructor(
    code: ErrorCode,
    httpStatus: number,
    correlationId: string,
    message?: string,
    details?: unknown,
  ) {
    super(message ?? code);
    this.name = "MockError";
    this.code = code;
    this.httpStatus = httpStatus;
    this.correlationId = correlationId;
    this.details = details;
  }

  toEnvelope(): MockErrorEnvelope {
    return {
      ok: false,
      error: {
        code: this.code,
        message: this.message,
        correlationId: this.correlationId,
        httpStatus: this.httpStatus,
        details: this.details,
      },
    };
  }
}

export class NotFoundError extends MockError {
  constructor(correlationId: string, message = "resource not found") {
    super("NOT_FOUND", 404, correlationId, message);
    this.name = "NotFoundError";
  }
}

export class UnauthorizedError extends MockError {
  constructor(correlationId: string, message = "authentication required") {
    super("UNAUTHORIZED", 401, correlationId, message);
    this.name = "UnauthorizedError";
  }
}

export class ForbiddenError extends MockError {
  constructor(correlationId: string, message = "permission denied") {
    super("FORBIDDEN", 403, correlationId, message);
    this.name = "ForbiddenError";
  }
}

export class BadRequestError extends MockError {
  constructor(correlationId: string, message = "bad request", details?: unknown) {
    super("BAD_REQUEST", 400, correlationId, message, details);
    this.name = "BadRequestError";
  }
}

export class ConflictError extends MockError {
  constructor(correlationId: string, message = "conflict") {
    super("CONFLICT", 409, correlationId, message);
    this.name = "ConflictError";
  }
}

export class NotImplementedError extends MockError {
  constructor(endpointOrOwner: string, correlationId = "n/a") {
    super("NOT_IMPLEMENTED", 501, correlationId, `not implemented: ${endpointOrOwner}`);
    this.name = "NotImplementedError";
  }
}

export class TimeoutError extends MockError {
  constructor(correlationId: string, message = "request timed out") {
    super("TIMEOUT", 504, correlationId, message);
    this.name = "TimeoutError";
  }
}

export class InternalServerError extends MockError {
  constructor(correlationId: string, message = "internal server error", details?: unknown) {
    super("INTERNAL_SERVER_ERROR", 500, correlationId, message, details);
    this.name = "InternalServerError";
  }
}

/**
 * Bridge a lifecycle BusinessRuleError into a MockError. Business rules map
 * to HTTP 422 Unprocessable Entity — the request was understood but business
 * preconditions failed.
 */
export function wrapBusinessRuleError(err: BusinessRuleError, correlationId: string): MockError {
  return new MockError(err.code, 422, correlationId, err.message);
}

/**
 * Coerce an unknown throw into a MockError. Already-typed MockErrors flow
 * through unchanged; BusinessRuleErrors are wrapped; everything else becomes
 * a 500.
 */
export function toMockError(err: unknown, correlationId: string): MockError {
  if (err instanceof MockError) return err;
  if (err instanceof BusinessRuleError) return wrapBusinessRuleError(err, correlationId);
  if (err instanceof Error) return new InternalServerError(correlationId, err.message);
  return new InternalServerError(correlationId, "unknown error");
}

/** Random correlation id for handlers that don't receive one in headers. */
export function newCorrelationId(): string {
  // crypto.randomUUID exists in Node 20+ and all browsers we target.
  return crypto.randomUUID();
}
