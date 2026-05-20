/**
 * In-memory mock database shape.
 *
 * Holds every domain entity the mock router can read/write. Maps are keyed
 * by entity id where each entity has a stable id; arrays are used for audit
 * streams (append-only) and for installments-per-account (many per parent).
 *
 * The `currentSessionId` field is the single source of truth for "who is
 * logged in" inside the mock. It is intentionally NOT a Map — only one
 * session can be active in this single-tab mock.
 *
 * IMPORTANT: this batch ships only the *shape* + an auth seed. Loan/borrower
 * fixtures are deferred to a later batch.
 */
import type {
  ApiClient,
  ApplicationAuditEvent,
  Borrower,
  DocumentAccessEvent,
  IntakeAuditEvent,
  LoanAccount,
  LoanApplication,
  LoanDocument,
  LoanProduct,
  Lsp,
  OperationalAlert,
  PaymentTransaction,
  PiiRevealEvent,
  ProductAuditEvent,
  ProductLspMapping,
  RepaymentInstallment,
  RepaymentSchedule,
  ReportRequest,
  User,
} from "@/types";
import type { LspWebhookSubscription } from "@/schemas/lsp";

export const MOCK_DB_VERSION = 1 as const;

/**
 * Active session record. Stored on the db so persistence + tests can
 * round-trip "logged in" state without a separate store. Token is a
 * mock-shaped string `mock.<userId>.<expiresAt>` — never a real JWT.
 */
export interface MockSession {
  userId: string;
  accessToken: string;
  expiresAt: string;
}

export interface MockDb {
  // Tenant + auth
  lsps: Map<string, Lsp>;
  users: Map<string, User>;
  apiClients: Map<string, ApiClient>;
  /** One subscription per LSP (keyed by lspId). Phase 9 admin surface. */
  lspWebhookSubscriptions: Map<string, LspWebhookSubscription>;

  // Catalog
  products: Map<string, LoanProduct>;
  productLspMappings: Map<string, ProductLspMapping>;

  // Loan domain (empty in this batch — populated later)
  borrowers: Map<string, Borrower>;
  applications: Map<string, LoanApplication>;
  documents: Map<string, LoanDocument>;
  accounts: Map<string, LoanAccount>;
  schedules: Map<string, RepaymentSchedule>;
  /** Keyed by accountId. */
  installments: Map<string, RepaymentInstallment[]>;
  payments: PaymentTransaction[];

  // Ops
  alerts: Map<string, OperationalAlert>;
  reportRequests: Map<string, ReportRequest>;

  // Audit streams
  auditApplication: ApplicationAuditEvent[];
  auditIntake: IntakeAuditEvent[];
  auditPiiReveal: PiiRevealEvent[];
  auditDocumentAccess: DocumentAccessEvent[];
  auditProduct: ProductAuditEvent[];

  // Auth state (single active session — single-tab mock)
  currentSession: MockSession | null;
}

export function createEmptyDb(): MockDb {
  return {
    lsps: new Map(),
    users: new Map(),
    apiClients: new Map(),
    lspWebhookSubscriptions: new Map(),
    products: new Map(),
    productLspMappings: new Map(),
    borrowers: new Map(),
    applications: new Map(),
    documents: new Map(),
    accounts: new Map(),
    schedules: new Map(),
    installments: new Map(),
    payments: [],
    alerts: new Map(),
    reportRequests: new Map(),
    auditApplication: [],
    auditIntake: [],
    auditPiiReveal: [],
    auditDocumentAccess: [],
    auditProduct: [],
    currentSession: null,
  };
}

/** Deep-clone via JSON for tests. Maps are reconstructed manually. */
export function cloneDb(db: MockDb): MockDb {
  const cloneMap = <K, V>(m: Map<K, V>): Map<K, V> =>
    new Map(JSON.parse(JSON.stringify(Array.from(m.entries()))) as [K, V][]);

  return {
    lsps: cloneMap(db.lsps),
    users: cloneMap(db.users),
    apiClients: cloneMap(db.apiClients),
    lspWebhookSubscriptions: cloneMap(db.lspWebhookSubscriptions),
    products: cloneMap(db.products),
    productLspMappings: cloneMap(db.productLspMappings),
    borrowers: cloneMap(db.borrowers),
    applications: cloneMap(db.applications),
    documents: cloneMap(db.documents),
    accounts: cloneMap(db.accounts),
    schedules: cloneMap(db.schedules),
    installments: cloneMap(db.installments),
    payments: JSON.parse(JSON.stringify(db.payments)) as PaymentTransaction[],
    alerts: cloneMap(db.alerts),
    reportRequests: cloneMap(db.reportRequests),
    auditApplication: JSON.parse(JSON.stringify(db.auditApplication)) as ApplicationAuditEvent[],
    auditIntake: JSON.parse(JSON.stringify(db.auditIntake)) as IntakeAuditEvent[],
    auditPiiReveal: JSON.parse(JSON.stringify(db.auditPiiReveal)) as PiiRevealEvent[],
    auditDocumentAccess: JSON.parse(
      JSON.stringify(db.auditDocumentAccess),
    ) as DocumentAccessEvent[],
    auditProduct: JSON.parse(JSON.stringify(db.auditProduct)) as ProductAuditEvent[],
    currentSession: db.currentSession ? { ...db.currentSession } : null,
  };
}

/**
 * Module-singleton db instance. Handlers read/write through this; the
 * router resets it (via `setDb`) when persistence loads a previous state.
 */
let _db: MockDb = createEmptyDb();

export function getDb(): MockDb {
  return _db;
}

export function setDb(next: MockDb): void {
  _db = next;
}

export function resetDb(): void {
  _db = createEmptyDb();
}
