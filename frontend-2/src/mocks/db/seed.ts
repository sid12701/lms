/**
 * Minimal auth seed: 4 LSPs, 5 products, 6 users, 0 borrowers/applications.
 *
 * IDs are stable literal UUIDs so tests can assert. Loan/borrower fixtures
 * are deferred to a future batch — only the auth flow needs data here.
 *
 * Passwords are mock-only (never validated) and exist purely so the
 * change-password flow can be exercised end-to-end.
 */
import type { Role } from "@/types";
import type { MockDb } from "./state";
import { seedAdminLspFixtures } from "./admin-seed";

const SEED_TIMESTAMP = "2026-05-09T00:00:00.000Z";

// ─── LSP UUIDs ────────────────────────────────────────────────────────────────
export const LSP_BHAW_DEMO = "11111111-1111-4111-8111-111111111111";
export const LSP_SOUTH = "22222222-2222-4222-8222-222222222222";
export const LSP_NORTH = "33333333-3333-4333-8333-333333333333";
export const LSP_EAST = "44444444-4444-4444-8444-444444444444";

// ─── User UUIDs ───────────────────────────────────────────────────────────────
export const USER_OPS_ADMIN = "aaaaaaaa-1111-4aaa-8aaa-aaaaaaaaaaaa";
export const USER_OPS_USER = "aaaaaaaa-2222-4aaa-8aaa-aaaaaaaaaaaa";
export const USER_PRODUCT_ADMIN = "aaaaaaaa-3333-4aaa-8aaa-aaaaaaaaaaaa";
export const USER_LSP_READ = "aaaaaaaa-4444-4aaa-8aaa-aaaaaaaaaaaa";
export const USER_LSP_WRITE = "aaaaaaaa-5555-4aaa-8aaa-aaaaaaaaaaaa";
export const USER_TEMP = "aaaaaaaa-6666-4aaa-8aaa-aaaaaaaaaaaa";

// ─── Product UUIDs ────────────────────────────────────────────────────────────
export const PROD_PERSONAL = "bbbbbbbb-1111-4bbb-8bbb-bbbbbbbbbbbb";
export const PROD_BUSINESS = "bbbbbbbb-2222-4bbb-8bbb-bbbbbbbbbbbb";
export const PROD_CONSUMER = "bbbbbbbb-3333-4bbb-8bbb-bbbbbbbbbbbb";
export const PROD_GOLD = "bbbbbbbb-4444-4bbb-8bbb-bbbbbbbbbbbb";
export const PROD_EDUCATION = "bbbbbbbb-5555-4bbb-8bbb-bbbbbbbbbbbb";

export interface SeedUser {
  id: string;
  username: string;
  /** Mock-only — never used as real auth. */
  password: string;
  email: string;
  role: Role;
  lspId: string | null;
  mustChangePassword: boolean;
}

export const SEED_USERS: readonly SeedUser[] = [
  {
    id: USER_OPS_ADMIN,
    username: "ops.admin",
    password: "demo",
    email: "ops.admin@bhawana.example",
    role: "SYSTEM_ADMIN",
    lspId: null,
    mustChangePassword: false,
  },
  {
    id: USER_OPS_USER,
    username: "ops.user",
    password: "demo",
    email: "ops.user@bhawana.example",
    role: "OPS_USER",
    lspId: null,
    mustChangePassword: false,
  },
  {
    id: USER_PRODUCT_ADMIN,
    username: "product.admin",
    password: "demo",
    email: "product.admin@bhawana.example",
    role: "PRODUCT_ADMIN",
    lspId: null,
    mustChangePassword: false,
  },
  {
    id: USER_LSP_READ,
    username: "lsp.read",
    password: "demo",
    email: "lsp.read@bhaw-demo.example",
    role: "LSP_UI_READ",
    lspId: LSP_BHAW_DEMO,
    mustChangePassword: false,
  },
  {
    id: USER_LSP_WRITE,
    username: "lsp.write",
    password: "demo",
    email: "lsp.write@bhaw-demo.example",
    role: "LSP_UI_WRITE",
    lspId: LSP_BHAW_DEMO,
    mustChangePassword: false,
  },
  {
    id: USER_TEMP,
    username: "temp.user",
    password: "demo",
    email: "temp.user@bhawana.example",
    role: "SYSTEM_ADMIN",
    lspId: null,
    mustChangePassword: true,
  },
];

export function seedAuthFixtures(db: MockDb): MockDb {
  // ── LSPs ────────────────────────────────────────────────────────────────────
  db.lsps.set(LSP_BHAW_DEMO, {
    id: LSP_BHAW_DEMO,
    code: "BHAW-DEMO",
    name: "Bhawana Demo LSP",
    status: "ACTIVE",
    createdAt: SEED_TIMESTAMP,
  });
  db.lsps.set(LSP_SOUTH, {
    id: LSP_SOUTH,
    code: "LSP-SOUTH",
    name: "Southern Originator Pvt Ltd",
    status: "ACTIVE",
    createdAt: SEED_TIMESTAMP,
  });
  db.lsps.set(LSP_NORTH, {
    id: LSP_NORTH,
    code: "LSP-NORTH",
    name: "Northern Capital Partners",
    status: "ACTIVE",
    createdAt: SEED_TIMESTAMP,
  });
  db.lsps.set(LSP_EAST, {
    id: LSP_EAST,
    code: "LSP-EAST",
    name: "Eastern Lending Co",
    status: "SUSPENDED",
    createdAt: SEED_TIMESTAMP,
  });

  // ── Products ────────────────────────────────────────────────────────────────
  db.products.set(PROD_PERSONAL, {
    id: PROD_PERSONAL,
    code: "PL-STD",
    name: "Personal Loan — Standard",
    status: "ACTIVE",
    principalMin: 50_000,
    principalMax: 1_000_000,
    interestRatePct: 14.5,
    processingFeePct: 1.5,
    tenureMinMonths: 6,
    tenureMaxMonths: 60,
    createdAt: SEED_TIMESTAMP,
  });
  db.products.set(PROD_BUSINESS, {
    id: PROD_BUSINESS,
    code: "BL-SME",
    name: "Business Loan — SME",
    status: "ACTIVE",
    principalMin: 100_000,
    principalMax: 5_000_000,
    interestRatePct: 16.0,
    processingFeePct: 2.0,
    tenureMinMonths: 12,
    tenureMaxMonths: 84,
    createdAt: SEED_TIMESTAMP,
  });
  db.products.set(PROD_CONSUMER, {
    id: PROD_CONSUMER,
    code: "CD-EZY",
    name: "Consumer Durable",
    status: "ACTIVE",
    principalMin: 10_000,
    principalMax: 200_000,
    interestRatePct: 18.0,
    processingFeePct: 1.0,
    tenureMinMonths: 3,
    tenureMaxMonths: 24,
    createdAt: SEED_TIMESTAMP,
  });
  db.products.set(PROD_GOLD, {
    id: PROD_GOLD,
    code: "GL-AU",
    name: "Gold Loan",
    status: "ACTIVE",
    principalMin: 25_000,
    principalMax: 1_500_000,
    interestRatePct: 11.0,
    processingFeePct: 0.5,
    tenureMinMonths: 3,
    tenureMaxMonths: 36,
    createdAt: SEED_TIMESTAMP,
  });
  db.products.set(PROD_EDUCATION, {
    id: PROD_EDUCATION,
    code: "ED-STU",
    name: "Education Loan",
    status: "INACTIVE",
    principalMin: 100_000,
    principalMax: 2_500_000,
    interestRatePct: 9.5,
    processingFeePct: 1.0,
    tenureMinMonths: 12,
    tenureMaxMonths: 120,
    createdAt: SEED_TIMESTAMP,
  });

  // ── Product → LSP mappings ──────────────────────────────────────────────────
  db.productLspMappings.set(PROD_PERSONAL, {
    productId: PROD_PERSONAL,
    lspIds: [LSP_BHAW_DEMO, LSP_SOUTH, LSP_NORTH],
  });
  db.productLspMappings.set(PROD_BUSINESS, {
    productId: PROD_BUSINESS,
    lspIds: [LSP_BHAW_DEMO, LSP_SOUTH],
  });
  db.productLspMappings.set(PROD_CONSUMER, {
    productId: PROD_CONSUMER,
    lspIds: [LSP_BHAW_DEMO, LSP_NORTH, LSP_EAST],
  });
  db.productLspMappings.set(PROD_GOLD, {
    productId: PROD_GOLD,
    lspIds: [LSP_SOUTH, LSP_EAST],
  });
  db.productLspMappings.set(PROD_EDUCATION, {
    productId: PROD_EDUCATION,
    lspIds: [LSP_NORTH],
  });

  // ── Users ───────────────────────────────────────────────────────────────────
  for (const u of SEED_USERS) {
    db.users.set(u.id, {
      id: u.id,
      username: u.username,
      email: u.email,
      status: "ACTIVE",
      role: u.role,
      lspId: u.lspId,
      mustChangePassword: u.mustChangePassword,
      createdAt: SEED_TIMESTAMP,
    });
  }

  // ── Phase 9 admin fixtures (additional LSPs + webhook subscriptions) ────────
  seedAdminLspFixtures(db);

  return db;
}

/** Lookup helper used by the auth handler. Mock-only — never validates the password. */
export function findSeedUserByUsername(username: string): SeedUser | undefined {
  return SEED_USERS.find((u) => u.username === username);
}
