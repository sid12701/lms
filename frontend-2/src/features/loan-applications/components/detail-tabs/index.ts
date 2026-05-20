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
export { ScheduleTab, type ScheduleTabProps } from "./ScheduleTab";
export { DocumentsTab, type DocumentsTabProps } from "./DocumentsTab";
export { RepaymentsTab, type RepaymentsTabProps } from "./RepaymentsTab";

// ─── Agent C exports ────────────────────────────────────────────────────────
export { OverviewTab, type OverviewTabProps } from "./OverviewTab";
export { ActivityTab, type ActivityTabProps } from "./ActivityTab";
export { WebhooksTab, type WebhooksTabProps } from "./WebhooksTab";
