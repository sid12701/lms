/**
 * Public mock API surface.
 *
 * Every namespace listed in `frontend-implementation-plan.md` §10 has a slot
 * here. Phase 9 adds the four admin namespaces — `lsps`, `products`, `users`,
 * `apiClients` — alongside the existing borrower/loan/alerts/reports/audit
 * stack. The remaining `repayments` and `disbursements` namespaces stay as
 * `NotImplementedError`-throwing stubs so callers fail loudly rather than
 * silently 404 their way through development.
 *
 * `bootstrapMockApi()` is the one entry point app-shell needs:
 *   - loads persisted db OR seeds a fresh one
 *   - registers auth routes (auth module also self-registers on import)
 *
 * Future agents: drop a real handler module in this folder, register its
 * routes, and replace the corresponding stub here.
 */
import { resetDb, getDb, setDb } from "../db/state";
import { seedAuthFixtures } from "../db/seed";
import { seedDashboardFixtures } from "../db/dashboard-seed";
import { seedApiClientFixtures } from "../db/api-clients-seed";
import { loadDb, saveDb } from "../db/persistence";
import { NotImplementedError } from "../errors";
import * as authModule from "./auth";
import * as homeModule from "./home";
import * as loanApplicationsModule from "./loan-applications";
import * as borrowersModule from "./borrowers";
import * as alertsModule from "./alerts";
import * as reportsModule from "./reports";
import * as auditModule from "./audit";
import * as lspsModule from "./lsps";
import * as productsModule from "./products";
import * as usersModule from "./users";
import * as apiClientsModule from "./api-clients";

// Side-effect: registers every namespace's routes on import.
import "./auth";
import "./home";
import "./loan-applications";
import "./borrowers";
import "./alerts";
import "./reports";
import "./audit";
import "./lsps";
import "./products";
import "./users";
import "./api-clients";

/** Initialise the mock layer. Idempotent. */
let bootstrapped = false;
export function bootstrapMockApi(): void {
  if (bootstrapped) return;
  bootstrapped = true;

  const persisted = loadDb();
  if (persisted) {
    setDb(persisted);
    return;
  }

  resetDb();
  seedAuthFixtures(getDb());
  seedDashboardFixtures(getDb());
  seedApiClientFixtures(getDb());
  saveDb(getDb());
}

/** Test/dev helper — re-seed from scratch. */
export function resetMockApi(): void {
  bootstrapped = false;
  resetDb();
  seedAuthFixtures(getDb());
  seedDashboardFixtures(getDb());
  seedApiClientFixtures(getDb());
  bootstrapped = true;
}

// ─── Not-implemented namespace factory ───────────────────────────────────────

type NotImplementedNamespace = Record<string, (...args: unknown[]) => Promise<never>>;

function makeNotImplementedNamespace(name: string): NotImplementedNamespace {
  return new Proxy(
    {},
    {
      get(_target, prop: string) {
        return async (..._args: unknown[]): Promise<never> => {
          throw new NotImplementedError(`${name}.${String(prop)} — owner: future agent`);
        };
      },
    },
  ) as NotImplementedNamespace;
}

// ─── Public API ──────────────────────────────────────────────────────────────

export const auth = authModule;
export const home = homeModule;
export const loanApplications = loanApplicationsModule;
export const borrowers = borrowersModule;
export const alerts = alertsModule;
export const reports = reportsModule;
export const audit = auditModule;
export const lsps = lspsModule;
export const products = productsModule;
export const users = usersModule;
export const apiClients = apiClientsModule;

export const api = {
  auth: authModule,
  home: homeModule,
  loanApplications: loanApplicationsModule,
  borrowers: borrowersModule,
  alerts: alertsModule,
  reports: reportsModule,
  audit: auditModule,
  lsps: lspsModule,
  products: productsModule,
  users: usersModule,
  apiClients: apiClientsModule,
  // Stubs — every method throws NotImplementedError.
  repayments: makeNotImplementedNamespace("repayments"),
  disbursements: makeNotImplementedNamespace("disbursements"),
} as const;

export type MockApi = typeof api;
