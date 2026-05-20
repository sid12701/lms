/**
 * Lifecycle automation helpers.
 *
 * These helpers drive a loan application through its full happy-path
 * lifecycle by walking the same mock loan-applications API a real LSP
 * client would. Used by DevTopBarTools demos and Phase 7 smoke flows.
 *
 * Every helper is "never throws" — failures are returned as
 * LifecycleStepResult entries with `ok: false` so the caller can render
 * progress / error UI without try/catch plumbing.
 */
import * as loanApi from "@/mocks/api/loan-applications";
import { newIdempotencyKey } from "@/lib/idempotency";
import type {
  SubmitScheduleInstallmentInput,
} from "@/features/loan-applications/types";

export interface LifecycleStepResult {
  step: string;
  ok: boolean;
  durationMs: number;
  error?: string;
}

export interface RunFullLifecycleOptions {
  onStep?: (label: string) => void;
  closeViaForeclosure?: boolean;
}

export interface RunFullLifecycleResult {
  applicationId: string;
  steps: LifecycleStepResult[];
  finalStatus: string;
  /** Last step's error message when a step failed. */
  lastStepError?: string;
}

/** Pre-approval transitions walked one at a time until AWAITING_APPROVAL. */
const PRE_APPROVAL_WALK: Record<string, string> = {
  INITIATED: "AWAITING_APPROVAL",
  KYC_PENDING: "DOCS_PENDING",
  DOCS_PENDING: "UNDER_REVIEW",
  UNDER_REVIEW: "AWAITING_APPROVAL",
};

/** Hard cap on the repayment-post loop so a misbehaving mock can't spin forever. */
const REPAYMENT_LOOP_CAP = 24;

/**
 * Synthesise a BR-11-reconciling schedule for `principal` over `tenureMonths`.
 *
 * Splits principal evenly into `tenureMonths` installments (interest=0,
 * installmentAmount=principalDue), then reconciles any residual into the
 * final row so sum(principalDue) === principal exactly.
 */
function synthesiseSchedule(
  principal: number,
  tenureMonths: number,
): SubmitScheduleInstallmentInput[] {
  const N = tenureMonths;
  const base = Math.round(principal / N);
  const now = new Date();
  const rows: SubmitScheduleInstallmentInput[] = [];
  for (let i = 1; i <= N; i++) {
    const due = new Date(now);
    due.setDate(due.getDate() + 30 * i);
    const dueDate = due.toISOString().slice(0, 10);
    rows.push({
      number: i,
      dueDate,
      principalDue: base,
      interestDue: 0,
      installmentAmount: base,
    });
  }
  const residual = principal - base * N;
  if (residual !== 0) {
    const last = rows[N - 1]!;
    last.principalDue += residual;
    last.installmentAmount += residual;
  }
  return rows;
}

/**
 * Wraps an async step with timing + try/catch. Pushes a LifecycleStepResult
 * to `steps`, then either returns the resolved value or re-throws so the
 * caller can short-circuit the flow.
 */
async function runStep<T>(
  steps: LifecycleStepResult[],
  label: string,
  fn: () => Promise<T>,
  onStep?: (l: string) => void,
): Promise<T> {
  onStep?.(label);
  const start = Date.now();
  try {
    const value = await fn();
    steps.push({ step: label, ok: true, durationMs: Date.now() - start });
    return value;
  } catch (err) {
    const error = err instanceof Error ? err.message : String(err);
    steps.push({ step: label, ok: false, durationMs: Date.now() - start, error });
    throw err;
  }
}

export async function runFullLifecycle(
  applicationId: string,
  opts?: RunFullLifecycleOptions,
): Promise<RunFullLifecycleResult> {
  const steps: LifecycleStepResult[] = [];
  try {
    let detail = await runStep(steps, "fetch-detail", () => loanApi.detail(applicationId), opts?.onStep);
    let status: string = detail.application.status;

    // Pre-approval walk.
    while (PRE_APPROVAL_WALK[status]) {
      const next = PRE_APPROVAL_WALK[status]!;
      await runStep(
        steps,
        `transition-${next}`,
        () =>
          loanApi.transition(applicationId, {
            to: next as never,
            reason: null,
          }, { idempotencyKey: newIdempotencyKey() }),
        opts?.onStep,
      );
      detail = await runStep(steps, "fetch-detail", () => loanApi.detail(applicationId), opts?.onStep);
      status = detail.application.status;
    }

    // Approval.
    if (status === "AWAITING_APPROVAL") {
      await runStep(
        steps,
        "transition-APPROVED_PENDING_DISBURSAL",
        () =>
          loanApi.transition(applicationId, {
            to: "APPROVED_PENDING_DISBURSAL" as never,
            reason: null,
          }, { idempotencyKey: newIdempotencyKey() }),
        opts?.onStep,
      );
      detail = await runStep(steps, "fetch-detail", () => loanApi.detail(applicationId), opts?.onStep);
      status = detail.application.status;
    }

    // Submit schedule + initiate disbursement.
    if (status === "APPROVED_PENDING_DISBURSAL") {
      const synthesised = synthesiseSchedule(
        detail.application.requestedAmount,
        detail.application.tenureMonths,
      );
      await runStep(
        steps,
        "submit-schedule",
        () =>
          (loanApi as unknown as {
            submitSchedule: (
              id: string,
              input: { installments: SubmitScheduleInstallmentInput[]; idempotencyKey: string },
            ) => Promise<unknown>;
          }).submitSchedule(applicationId, {
            installments: synthesised,
            idempotencyKey: newIdempotencyKey(),
          }),
        opts?.onStep,
      );
      await runStep(
        steps,
        "initiate-disbursement",
        () =>
          loanApi.initiateDisbursement(applicationId, { note: null }, { idempotencyKey: newIdempotencyKey() }),
        opts?.onStep,
      );
      detail = await runStep(steps, "fetch-detail", () => loanApi.detail(applicationId), opts?.onStep);
      status = detail.application.status;
    }

    if (opts?.closeViaForeclosure) {
      const sched = await runStep(
        steps,
        "fetch-schedule",
        () => loanApi.schedule(applicationId),
        opts?.onStep,
      );
      const outstandingPrincipal = sched.installments
        .filter((i) => i.status !== "PAID")
        .reduce((s, i) => s + i.principalDue, 0);
      await runStep(
        steps,
        "complete-foreclosure",
        () =>
          (loanApi as unknown as {
            completeForeclosure: (
              id: string,
              input: {
                outstandingPrincipal: number;
                accruedInterest: number;
                foreclosureCharge: number;
                settledAt: string;
                idempotencyKey: string;
              },
            ) => Promise<unknown>;
          }).completeForeclosure(applicationId, {
            outstandingPrincipal,
            accruedInterest: 0,
            foreclosureCharge: Math.round(outstandingPrincipal * 0.02),
            settledAt: new Date().toISOString(),
            idempotencyKey: newIdempotencyKey(),
          }),
        opts?.onStep,
      );
      detail = await runStep(steps, "fetch-detail", () => loanApi.detail(applicationId), opts?.onStep);
      status = detail.application.status;
    } else {
      // Repayment loop — post every remaining installment until CLOSED/FULLY_REPAID.
      let safety = 0;
      while (safety++ < REPAYMENT_LOOP_CAP && status !== "CLOSED" && status !== "FULLY_REPAID") {
        const sched = await runStep(
          steps,
          "fetch-schedule",
          () => loanApi.schedule(applicationId),
          opts?.onStep,
        );
        const next = sched.installments.find((i) => i.status !== "PAID");
        if (!next) break;
        await runStep(
          steps,
          `post-repayment-${next.number}`,
          () =>
            loanApi.postRepayment(
              applicationId,
              {
                installmentId: next.id,
                amount: next.outstandingAmount,
                postedAt: new Date().toISOString(),
                mode: "BANK_TRANSFER",
              },
              { idempotencyKey: newIdempotencyKey() },
            ),
          opts?.onStep,
        );
        detail = await runStep(steps, "fetch-detail", () => loanApi.detail(applicationId), opts?.onStep);
        status = detail.application.status;
      }
      if (safety >= REPAYMENT_LOOP_CAP && status !== "CLOSED" && status !== "FULLY_REPAID") {
        steps.push({
          step: "post-repayment-loop",
          ok: false,
          durationMs: 0,
          error: `safety cap (${REPAYMENT_LOOP_CAP}) hit before CLOSED`,
        });
      }
    }

    return { applicationId, steps, finalStatus: status };
  } catch (err) {
    const last = steps[steps.length - 1];
    const lastStepError =
      last?.ok === false ? last.error : err instanceof Error ? err.message : String(err);
    let finalStatus = "UNKNOWN";
    try {
      const d = await loanApi.detail(applicationId);
      finalStatus = d.application.status;
    } catch {
      /* swallow — best-effort */
    }
    return { applicationId, steps, finalStatus, lastStepError };
  }
}

export async function submitGeneratedSchedule(
  applicationId: string,
): Promise<LifecycleStepResult> {
  const steps: LifecycleStepResult[] = [];
  try {
    const detail = await runStep(steps, "fetch-detail", () => loanApi.detail(applicationId));
    const synthesised = synthesiseSchedule(
      detail.application.requestedAmount,
      detail.application.tenureMonths,
    );
    await runStep(steps, "submit-schedule", () =>
      (loanApi as unknown as {
        submitSchedule: (
          id: string,
          input: { installments: SubmitScheduleInstallmentInput[]; idempotencyKey: string },
        ) => Promise<unknown>;
      }).submitSchedule(applicationId, {
        installments: synthesised,
        idempotencyKey: newIdempotencyKey(),
      }),
    );
    return steps[steps.length - 1]!;
  } catch {
    return steps[steps.length - 1]!;
  }
}

export async function completeForeclosureForApplication(
  applicationId: string,
): Promise<LifecycleStepResult> {
  const steps: LifecycleStepResult[] = [];
  try {
    await runStep(steps, "fetch-detail", () => loanApi.detail(applicationId));
    const sched = await runStep(steps, "fetch-schedule", () => loanApi.schedule(applicationId));
    const outstandingPrincipal = sched.installments
      .filter((i) => i.status !== "PAID")
      .reduce((s, i) => s + i.principalDue, 0);
    await runStep(steps, "complete-foreclosure", () =>
      (loanApi as unknown as {
        completeForeclosure: (
          id: string,
          input: {
            outstandingPrincipal: number;
            accruedInterest: number;
            foreclosureCharge: number;
            settledAt: string;
            idempotencyKey: string;
          },
        ) => Promise<unknown>;
      }).completeForeclosure(applicationId, {
        outstandingPrincipal,
        accruedInterest: 0,
        foreclosureCharge: Math.round(outstandingPrincipal * 0.02),
        settledAt: new Date().toISOString(),
        idempotencyKey: newIdempotencyKey(),
      }),
    );
    return steps[steps.length - 1]!;
  } catch {
    return steps[steps.length - 1]!;
  }
}
