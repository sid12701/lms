/**
 * Versioned localStorage persistence for the mock db.
 *
 * - Maps are serialised as `[key, value][]` arrays via `Array.from(map)`.
 * - Saves are debounced 200ms — chatty mutators don't thrash storage.
 * - On load, schema version mismatch wipes the slot and returns null so the
 *   caller can re-seed cleanly.
 * - All localStorage access is wrapped in try/catch — private/incognito modes
 *   throw `SecurityError` on writes.
 */
import { createEmptyDb, MOCK_DB_VERSION, type MockDb } from "./state";

export const STORAGE_KEY = "bhawana-lms-mock-db-v1";

interface SerializedDb {
  version: typeof MOCK_DB_VERSION;
  data: {
    lsps: [string, unknown][];
    users: [string, unknown][];
    apiClients: [string, unknown][];
    products: [string, unknown][];
    productLspMappings: [string, unknown][];
    borrowers: [string, unknown][];
    applications: [string, unknown][];
    documents: [string, unknown][];
    accounts: [string, unknown][];
    schedules: [string, unknown][];
    installments: [string, unknown][];
    payments: unknown[];
    alerts: [string, unknown][];
    reportRequests: [string, unknown][];
    auditApplication: unknown[];
    auditIntake: unknown[];
    auditPiiReveal: unknown[];
    auditDocumentAccess: unknown[];
    auditProduct: unknown[];
    currentSession: unknown;
  };
}

function serialise(db: MockDb): SerializedDb {
  return {
    version: MOCK_DB_VERSION,
    data: {
      lsps: Array.from(db.lsps.entries()),
      users: Array.from(db.users.entries()),
      apiClients: Array.from(db.apiClients.entries()),
      products: Array.from(db.products.entries()),
      productLspMappings: Array.from(db.productLspMappings.entries()),
      borrowers: Array.from(db.borrowers.entries()),
      applications: Array.from(db.applications.entries()),
      documents: Array.from(db.documents.entries()),
      accounts: Array.from(db.accounts.entries()),
      schedules: Array.from(db.schedules.entries()),
      installments: Array.from(db.installments.entries()),
      payments: db.payments,
      alerts: Array.from(db.alerts.entries()),
      reportRequests: Array.from(db.reportRequests.entries()),
      auditApplication: db.auditApplication,
      auditIntake: db.auditIntake,
      auditPiiReveal: db.auditPiiReveal,
      auditDocumentAccess: db.auditDocumentAccess,
      auditProduct: db.auditProduct,
      currentSession: db.currentSession,
    },
  };
}

function hydrate(payload: SerializedDb): MockDb {
  const db = createEmptyDb();
  const d = payload.data;
  // Cast through unknown — JSON round-trip discards class identity but the
  // shape is preserved; downstream Zod parsers re-validate when needed.
  db.lsps = new Map(d.lsps as [string, MockDb["lsps"] extends Map<string, infer V> ? V : never][]);
  db.users = new Map(
    d.users as [string, MockDb["users"] extends Map<string, infer V> ? V : never][],
  );
  db.apiClients = new Map(
    d.apiClients as [string, MockDb["apiClients"] extends Map<string, infer V> ? V : never][],
  );
  db.products = new Map(
    d.products as [string, MockDb["products"] extends Map<string, infer V> ? V : never][],
  );
  db.productLspMappings = new Map(
    d.productLspMappings as [
      string,
      MockDb["productLspMappings"] extends Map<string, infer V> ? V : never,
    ][],
  );
  db.borrowers = new Map(
    d.borrowers as [string, MockDb["borrowers"] extends Map<string, infer V> ? V : never][],
  );
  db.applications = new Map(
    d.applications as [string, MockDb["applications"] extends Map<string, infer V> ? V : never][],
  );
  db.documents = new Map(
    d.documents as [string, MockDb["documents"] extends Map<string, infer V> ? V : never][],
  );
  db.accounts = new Map(
    d.accounts as [string, MockDb["accounts"] extends Map<string, infer V> ? V : never][],
  );
  db.schedules = new Map(
    d.schedules as [string, MockDb["schedules"] extends Map<string, infer V> ? V : never][],
  );
  db.installments = new Map(
    d.installments as [string, MockDb["installments"] extends Map<string, infer V> ? V : never][],
  );
  db.payments = (d.payments as MockDb["payments"]) ?? [];
  db.alerts = new Map(
    d.alerts as [string, MockDb["alerts"] extends Map<string, infer V> ? V : never][],
  );
  db.reportRequests = new Map(
    d.reportRequests as [
      string,
      MockDb["reportRequests"] extends Map<string, infer V> ? V : never,
    ][],
  );
  db.auditApplication = (d.auditApplication as MockDb["auditApplication"]) ?? [];
  db.auditIntake = (d.auditIntake as MockDb["auditIntake"]) ?? [];
  db.auditPiiReveal = (d.auditPiiReveal as MockDb["auditPiiReveal"]) ?? [];
  db.auditDocumentAccess = (d.auditDocumentAccess as MockDb["auditDocumentAccess"]) ?? [];
  db.auditProduct = (d.auditProduct as MockDb["auditProduct"]) ?? [];
  db.currentSession = (d.currentSession as MockDb["currentSession"]) ?? null;
  return db;
}

const DEBOUNCE_MS = 200;
let saveTimer: ReturnType<typeof setTimeout> | null = null;
let pendingDb: MockDb | null = null;

function getStorage(): Storage | null {
  try {
    return typeof window === "undefined" ? null : window.localStorage;
  } catch {
    return null;
  }
}

function writeNow(db: MockDb): void {
  const ls = getStorage();
  if (!ls) return;
  try {
    ls.setItem(STORAGE_KEY, JSON.stringify(serialise(db)));
  } catch {
    // Quota or SecurityError — swallow; the mock degrades to in-memory only.
  }
}

/** Persist after a 200ms quiet period. The latest db wins. */
export function saveDb(db: MockDb): void {
  pendingDb = db;
  if (saveTimer !== null) clearTimeout(saveTimer);
  saveTimer = setTimeout(() => {
    if (pendingDb) writeNow(pendingDb);
    pendingDb = null;
    saveTimer = null;
  }, DEBOUNCE_MS);
}

/** Synchronous flush for tests + Devtools "snapshot now" buttons. */
export function flushPendingSave(): void {
  if (saveTimer !== null) {
    clearTimeout(saveTimer);
    saveTimer = null;
  }
  if (pendingDb) {
    writeNow(pendingDb);
    pendingDb = null;
  }
}

/** Returns null when nothing usable is stored — caller should re-seed. */
export function loadDb(): MockDb | null {
  const ls = getStorage();
  if (!ls) return null;
  try {
    const raw = ls.getItem(STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<SerializedDb>;
    if (parsed.version !== MOCK_DB_VERSION) {
      ls.removeItem(STORAGE_KEY);
      return null;
    }
    if (!parsed.data) return null;
    return hydrate(parsed as SerializedDb);
  } catch {
    return null;
  }
}

export function wipeDb(): void {
  if (saveTimer !== null) {
    clearTimeout(saveTimer);
    saveTimer = null;
  }
  pendingDb = null;
  const ls = getStorage();
  if (!ls) return;
  try {
    ls.removeItem(STORAGE_KEY);
  } catch {
    // Ignore.
  }
}
