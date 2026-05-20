/**
 * API-client fixture seed (Phase 9 — Agent D).
 *
 * Additive on top of {@link seedAuthFixtures}. Seeds ≥ 6 ApiClient rows
 * across all four existing LSPs with a varied mix of statuses and
 * IP allow-list sizes so the `/api-clients` admin surface has realistic
 * data to render against.
 *
 * Idempotent — early-returns when api-client rows are already present.
 *
 * IDs follow a deterministic shape (`05000000-XXXX-4000-8000-…`) so tests
 * can address rows by counter:
 *   - row 1: BHAW-DEMO, ACTIVE, 2 allow-list entries
 *   - row 2: BHAW-DEMO, DISABLED, 0 allow-list entries
 *   - row 3: LSP-SOUTH, ACTIVE, 1 allow-list entry
 *   - row 4: LSP-SOUTH, ACTIVE, 4 allow-list entries
 *   - row 5: LSP-NORTH, ACTIVE, 3 allow-list entries
 *   - row 6: LSP-EAST,  DISABLED, 2 allow-list entries
 */
import type { ApiClient } from "@/types";
import type { MockDb } from "./state";
import { LSP_BHAW_DEMO, LSP_EAST, LSP_NORTH, LSP_SOUTH } from "./seed";

const SEED_TIMESTAMP = "2026-05-09T00:00:00.000Z";

function pad4(n: number): string {
  return n.toString(16).padStart(4, "0");
}

/** Stable api-client UUIDs — addressable by counter from tests. */
export function apiClientId(i: number): string {
  return `05000000-${pad4(i)}-4000-8000-000000000000`;
}

interface ApiClientSeedRow extends ApiClient {
  // No extra fields — the seed row is the wire shape.
}

const SEED_ROWS: readonly ApiClientSeedRow[] = [
  {
    id: apiClientId(1),
    clientId: "ak_live_1a2b3c4d",
    name: "Bhawana Demo — Origination",
    lspId: LSP_BHAW_DEMO,
    status: "ACTIVE",
    createdAt: SEED_TIMESTAMP,
    lastUsedAt: "2026-05-17T08:12:00.000Z",
    ipAllowList: ["10.0.0.0/8", "192.168.1.0/24"],
  },
  {
    id: apiClientId(2),
    clientId: "ak_live_2b3c4d5e",
    name: "Bhawana Demo — Legacy Pipeline",
    lspId: LSP_BHAW_DEMO,
    status: "DISABLED",
    createdAt: SEED_TIMESTAMP,
    lastUsedAt: null,
    ipAllowList: [],
  },
  {
    id: apiClientId(3),
    clientId: "ak_live_3c4d5e6f",
    name: "Southern Originator — Production",
    lspId: LSP_SOUTH,
    status: "ACTIVE",
    createdAt: SEED_TIMESTAMP,
    lastUsedAt: "2026-05-16T14:42:00.000Z",
    ipAllowList: ["203.0.113.10/32"],
  },
  {
    id: apiClientId(4),
    clientId: "ak_live_4d5e6f70",
    name: "Southern Originator — Disbursement",
    lspId: LSP_SOUTH,
    status: "ACTIVE",
    createdAt: SEED_TIMESTAMP,
    lastUsedAt: "2026-05-15T09:00:00.000Z",
    ipAllowList: [
      "10.10.0.0/16",
      "10.20.0.0/16",
      "10.30.0.0/16",
      "203.0.113.0/24",
    ],
  },
  {
    id: apiClientId(5),
    clientId: "ak_live_5e6f7081",
    name: "Northern Capital — Originator",
    lspId: LSP_NORTH,
    status: "ACTIVE",
    createdAt: SEED_TIMESTAMP,
    lastUsedAt: "2026-05-14T11:30:00.000Z",
    ipAllowList: ["198.51.100.0/24", "203.0.113.50/32", "10.50.0.0/16"],
  },
  {
    id: apiClientId(6),
    clientId: "ak_live_6f708192",
    name: "Eastern Lending — Sandbox",
    lspId: LSP_EAST,
    status: "DISABLED",
    createdAt: SEED_TIMESTAMP,
    lastUsedAt: null,
    ipAllowList: ["192.0.2.0/24", "198.51.100.10/32"],
  },
];

/**
 * Seed the api-client fixtures into the mock db. Idempotent: no-ops if any
 * api-client rows are already present (the bootstrap path is the only
 * caller, and it runs at most once per process unless `resetMockApi()` is
 * invoked).
 */
export function seedApiClientFixtures(db: MockDb): MockDb {
  if (db.apiClients.size > 0) return db;
  for (const row of SEED_ROWS) {
    db.apiClients.set(row.id, { ...row, ipAllowList: [...row.ipAllowList] });
  }
  return db;
}

/** Test helper — exposes the seeded ids by counter. */
export const SEED_API_CLIENT_IDS = SEED_ROWS.map((r) => r.id);
