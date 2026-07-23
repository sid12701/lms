/**
 * Barrel for the loan-application detail tabs.
 *
 * Owner split (Phase 5):
 *   - This file owns: ScheduleTab, DocumentsTab, RepaymentsTab.
 *   - Agent C appends: OverviewTab, ActivityTab, WebhooksTab.
 *
 * Keep exports alphabetised within each agent's block to make merges
 * deterministic.
 */
export { ScheduleTab } from "./ScheduleTab";
export { DocumentsTab } from "./DocumentsTab";
export { RepaymentsTab } from "./RepaymentsTab";

// ─── Agent C exports ────────────────────────────────────────────────────────
export { OverviewTab } from "./OverviewTab";
export { ActivityTab } from "./ActivityTab";
export { WebhooksTab } from "./WebhooksTab";
