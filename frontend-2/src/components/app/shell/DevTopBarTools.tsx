import { DropdownMenu } from "radix-ui";
import { Wrench, Database, RefreshCw, FlaskConical, Send, Banknote, Workflow } from "lucide-react";
import { useQueryClient } from "@tanstack/react-query";
import { useNavigate, useParams } from "react-router-dom";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { useMockScenario } from "@/app/providers";
import { useSession } from "@/features/auth/session-context";
import { wipeDb } from "@/mocks/db/persistence";
import { resetMockApi } from "@/mocks/api";
import {
  submitGeneratedSchedule,
  completeForeclosureForApplication,
  runFullLifecycle,
} from "@/mocks/lifecycle-automation";
import type { ScenarioKind } from "@/mocks/scenarios";

const SCENARIOS: { value: ScenarioKind; label: string }[] = [
  { value: "happy", label: "Happy path" },
  { value: "permission-denied", label: "Permission denied" },
  { value: "server-error", label: "Server error" },
  { value: "slow-network", label: "Slow network" },
];

const menuClass =
  "z-50 min-w-[16rem] rounded-md border border-[var(--color-border)] bg-popover p-1 shadow-md outline-none";
const itemClass =
  "flex cursor-pointer items-center gap-2 rounded-sm px-2 py-1.5 text-sm outline-none data-[highlighted]:bg-accent/10 data-[disabled]:cursor-not-allowed data-[disabled]:opacity-50";

/**
 * DEV-only convenience menu in the top bar. Provides:
 *  - scenario switcher (router-level fault injection)
 *  - reset DB (wipes localStorage + reloads)
 *  - switch user (sign out + redirect to /login)
 *  - loan lifecycle automation (simulate LSP schedule POST, foreclosure POST,
 *    or end-to-end run on the loan id parsed from the current URL)
 *
 * Returns null in production builds so the entire branch is tree-shaken.
 */
export function DevTopBarTools() {
  const { kind, setKind } = useMockScenario();
  const { signOut } = useSession();
  const navigate = useNavigate();
  const params = useParams<{ id?: string }>();
  const queryClient = useQueryClient();
  if (!import.meta.env.DEV) return null;

  const loanId = params.id;
  const disabled = !loanId;

  const invalidateLoanCaches = () => {
    queryClient.invalidateQueries({ queryKey: ["loan-application"] });
    queryClient.invalidateQueries({ queryKey: ["loan-applications"] });
  };

  const handleSimulateSchedule = async () => {
    if (!loanId) return;
    const result = await submitGeneratedSchedule(loanId);
    if (result.ok) {
      toast.success("LSP schedule submitted");
    } else {
      toast.error(result.error ?? "Submission failed");
    }
    invalidateLoanCaches();
  };

  const handleSimulateForeclosure = async () => {
    if (!loanId) return;
    const result = await completeForeclosureForApplication(loanId);
    if (result.ok) {
      toast.success("Foreclosure submitted");
    } else {
      toast.error(result.error ?? "Submission failed");
    }
    invalidateLoanCaches();
  };

  const handleRunLifecycle = async () => {
    if (!loanId) return;
    const result = await runFullLifecycle(loanId, {
      onStep: (label: string) => toast.message(label, { id: "lifecycle-step" }),
    });
    if (result.finalStatus === "CLOSED") {
      toast.success(`Lifecycle complete: ${result.finalStatus}`);
    } else {
      toast.error(`Lifecycle stopped: ${result.lastStepError ?? "unknown error"}`);
    }
    invalidateLoanCaches();
  };

  return (
    <DropdownMenu.Root>
      <DropdownMenu.Trigger asChild>
        <Button
          type="button"
          variant="ghost"
          size="icon-sm"
          aria-label="Developer tools"
          title="Developer tools"
        >
          <Wrench className="h-4 w-4" />
        </Button>
      </DropdownMenu.Trigger>
      <DropdownMenu.Portal>
        <DropdownMenu.Content sideOffset={6} align="end" className={menuClass}>
          <DropdownMenu.Label className="text-foreground-muted px-2 py-1 text-xs tracking-wide uppercase">
            Mock scenario
          </DropdownMenu.Label>
          <DropdownMenu.RadioGroup value={kind} onValueChange={(v) => setKind(v as ScenarioKind)}>
            {SCENARIOS.map((s) => (
              <DropdownMenu.RadioItem key={s.value} value={s.value} className={itemClass}>
                <FlaskConical aria-hidden="true" className="h-3.5 w-3.5" />
                <span>{s.label}</span>
                {kind === s.value ? (
                  <span className="text-foreground-muted ml-auto text-xs">active</span>
                ) : null}
              </DropdownMenu.RadioItem>
            ))}
          </DropdownMenu.RadioGroup>
          <DropdownMenu.Separator className="my-1 h-px bg-[var(--color-border)]" />
          <DropdownMenu.Item
            className={itemClass}
            onSelect={() => {
              wipeDb();
              resetMockApi();
              window.location.reload();
            }}
          >
            <Database aria-hidden="true" className="h-4 w-4" />
            <span>Reset mock DB</span>
          </DropdownMenu.Item>
          <DropdownMenu.Item
            className={itemClass}
            onSelect={async () => {
              await signOut();
              navigate("/login", { replace: true });
            }}
          >
            <RefreshCw aria-hidden="true" className="h-4 w-4" />
            <span>Switch user</span>
          </DropdownMenu.Item>
          <DropdownMenu.Separator className="my-1 h-px bg-[var(--color-border)]" />
          <DropdownMenu.Label className="text-foreground-muted px-2 py-1 text-xs tracking-wide uppercase">
            Loan lifecycle automation
          </DropdownMenu.Label>
          <DropdownMenu.Item
            className={itemClass}
            disabled={disabled}
            onSelect={(event) => {
              if (disabled) {
                event.preventDefault();
                return;
              }
              void handleSimulateSchedule();
            }}
          >
            <Send aria-hidden="true" className="h-4 w-4" />
            <span>Simulate LSP schedule POST</span>
          </DropdownMenu.Item>
          <DropdownMenu.Item
            className={itemClass}
            disabled={disabled}
            onSelect={(event) => {
              if (disabled) {
                event.preventDefault();
                return;
              }
              void handleSimulateForeclosure();
            }}
          >
            <Banknote aria-hidden="true" className="h-4 w-4" />
            <span>Simulate LSP foreclosure POST</span>
          </DropdownMenu.Item>
          <DropdownMenu.Item
            className={itemClass}
            disabled={disabled}
            onSelect={(event) => {
              if (disabled) {
                event.preventDefault();
                return;
              }
              void handleRunLifecycle();
            }}
          >
            <Workflow aria-hidden="true" className="h-4 w-4" />
            <span>Run full lifecycle</span>
          </DropdownMenu.Item>
        </DropdownMenu.Content>
      </DropdownMenu.Portal>
    </DropdownMenu.Root>
  );
}
