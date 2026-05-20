/**
 * Barrel for the borrower-detail tabs.
 *
 * Owned cooperatively between Phase 6 agents (`ProfileTab` lives with
 * agent B; `LoansTab` + `ActivityTab` are owned here). Each agent uses
 * the "create on miss, append on hit" rule when writing this file.
 */
export { LoansTab, type LoansTabProps } from "./LoansTab";
export { ActivityTab, type ActivityTabProps } from "./ActivityTab";
export { ProfileTab, type ProfileTabProps } from "./ProfileTab";
