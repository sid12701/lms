/**
 * Admin fixture seed (Phase 9).
 *
 * Layered on top of {@link seedAuthFixtures} which already drops 4 LSPs
 * (BHAW-DEMO, LSP-SOUTH, LSP-NORTH, LSP-EAST). This module:
 *
 *   - Adds 4 more LSPs so the admin list page renders ≥ 8 rows.
 *   - Seeds webhook subscriptions for 2 of the existing LSPs (BHAW-DEMO
 *     enabled, LSP-EAST disabled-but-present), exercising both the "row has
 *     webhook configured" and "subscription is paused" badges in the UI.
 *
 * Idempotent — calling twice does not duplicate rows. Safe to wire into
 * `bootstrapMockApi` and `resetMockApi` directly after `seedAuthFixtures`.
 */
import type { MockDb } from "./state";
import { LSP_BHAW_DEMO, LSP_EAST } from "./seed";

const SEED_TIMESTAMP = "2026-05-09T00:00:00.000Z";

// ─── Additional LSP UUIDs ────────────────────────────────────────────────────
export const LSP_WEST = "55555555-5555-4555-8555-555555555555";
export const LSP_METRO = "66666666-6666-4666-8666-666666666666";
export const LSP_RURAL = "77777777-7777-4777-8777-777777777777";
export const LSP_LEGACY = "88888888-8888-4888-8888-888888888888";

/**
 * Adds 4 additional LSPs (total 8 across base + admin seed) and 2 webhook
 * subscriptions. Safe to re-run.
 */
export function seedAdminLspFixtures(db: MockDb): MockDb {
  if (!db.lsps.has(LSP_WEST)) {
    db.lsps.set(LSP_WEST, {
      id: LSP_WEST,
      code: "LSP-WEST",
      name: "Western Originators LLP",
      status: "ACTIVE",
      createdAt: "2026-04-12T00:00:00.000Z",
    });
  }
  if (!db.lsps.has(LSP_METRO)) {
    db.lsps.set(LSP_METRO, {
      id: LSP_METRO,
      code: "LSP-METRO",
      name: "Metro Finance Partners",
      status: "ACTIVE",
      createdAt: "2026-03-28T00:00:00.000Z",
    });
  }
  if (!db.lsps.has(LSP_RURAL)) {
    db.lsps.set(LSP_RURAL, {
      id: LSP_RURAL,
      code: "LSP-RURAL",
      name: "Rural Outreach Lending",
      status: "INACTIVE",
      createdAt: "2026-02-10T00:00:00.000Z",
    });
  }
  if (!db.lsps.has(LSP_LEGACY)) {
    db.lsps.set(LSP_LEGACY, {
      id: LSP_LEGACY,
      code: "LSP-LEGACY",
      name: "Legacy Pilot Sandbox",
      status: "SUSPENDED",
      createdAt: "2025-11-04T00:00:00.000Z",
    });
  }

  // ── Webhook subscriptions (BHAW-DEMO enabled, LSP-EAST configured/paused) ──
  if (!db.lspWebhookSubscriptions.has(LSP_BHAW_DEMO)) {
    db.lspWebhookSubscriptions.set(LSP_BHAW_DEMO, {
      lspId: LSP_BHAW_DEMO,
      enabled: true,
      endpointUrl: "https://hooks.bhaw-demo.example/lms/events",
      signingSecret: "demo-signing-secret-do-not-use-in-real-life-32",
      eventTypes: [
        "loan.created",
        "loan.status.changed",
        "loan.disbursement.completed",
        "loan.repayment.posted",
        "loan.foreclosed",
      ],
    });
  }
  if (!db.lspWebhookSubscriptions.has(LSP_EAST)) {
    db.lspWebhookSubscriptions.set(LSP_EAST, {
      lspId: LSP_EAST,
      enabled: false,
      endpointUrl: "https://callbacks.lsp-east.example/bhawana",
      signingSecret: "east-signing-secret-do-not-use-in-real-life-32",
      eventTypes: ["loan.status.changed", "loan.disbursement.completed"],
    });
  }
  void SEED_TIMESTAMP;
  return db;
}
