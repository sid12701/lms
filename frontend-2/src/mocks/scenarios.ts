/**
 * Failure-injection scenarios for demos + tests.
 *
 * - `current` is the active scenario; switched via `scenario.set()`.
 * - `failNextOnce` arms a one-shot fail consumed by the next mutation.
 * - `shouldFail()` returns whether the next request should be short-circuited
 *   into an error envelope, and which `ErrorCode` to use.
 *
 * Domain-specific scenarios (kyc-incomplete, docs-incomplete,
 * schedule-missing, all-overdue, webhook-flaky) are recognised but currently
 * read by per-endpoint handlers (deferred batch). The router-level
 * scenarios are `permission-denied`, `server-error`, `slow-network`.
 */
import { setLatencyOverride } from "./latency";
import type { ErrorCode } from "./errors";

export type ScenarioKind =
  | "happy"
  | "kyc-incomplete"
  | "docs-incomplete"
  | "schedule-missing"
  | "all-overdue"
  | "webhook-flaky"
  | "permission-denied"
  | "server-error"
  | "slow-network";

export interface MockRequestLike {
  method: string;
  path: string;
}

interface ShouldFailHit {
  fail: true;
  kind: ErrorCode;
  message: string;
}
interface ShouldFailMiss {
  fail: false;
}
export type ShouldFailResult = ShouldFailHit | ShouldFailMiss;

interface ScenarioState {
  current: ScenarioKind;
  failNextOnce: boolean;
}

const state: ScenarioState = {
  current: "happy",
  failNextOnce: false,
};

const SLOW_NETWORK_MS = 2000;

export const scenario = {
  state,

  get current(): ScenarioKind {
    return state.current;
  },

  set(kind: ScenarioKind): void {
    state.current = kind;
    if (kind === "slow-network") {
      setLatencyOverride(SLOW_NETWORK_MS);
    } else {
      setLatencyOverride(null);
    }
  },

  /** Arm a one-shot fail on the next mutation. */
  failNext(): void {
    state.failNextOnce = true;
  },

  reset(): void {
    state.current = "happy";
    state.failNextOnce = false;
    setLatencyOverride(null);
  },

  /**
   * Decide whether the request should be short-circuited.
   * Mutations are POST/PUT/PATCH/DELETE; reads (GET) are not affected by
   * `permission-denied` or `failNextOnce` so the screen still renders.
   */
  shouldFail(req: MockRequestLike): ShouldFailResult {
    const isMutation = req.method !== "GET";

    if (state.failNextOnce && isMutation) {
      state.failNextOnce = false;
      return {
        fail: true,
        kind: "INTERNAL_SERVER_ERROR",
        message: "scenario: failNext armed",
      };
    }

    switch (state.current) {
      case "permission-denied":
        if (isMutation) {
          return {
            fail: true,
            kind: "FORBIDDEN",
            message: "scenario: permission-denied",
          };
        }
        return { fail: false };
      case "server-error":
        return {
          fail: true,
          kind: "INTERNAL_SERVER_ERROR",
          message: "scenario: server-error",
        };
      // happy + slow-network + the deferred domain-specific kinds don't fail
      // at the router level.
      default:
        return { fail: false };
    }
  },
};
