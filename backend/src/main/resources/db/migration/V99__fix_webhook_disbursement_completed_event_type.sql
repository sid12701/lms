-- Repair webhook subscriptions bound to a phantom event type.
--
-- The frontend "Disbursement completed" chip historically mapped to the
-- WebhookEventType.LOAN_DISBURSEMENT_UPDATED enum value, which no producer ever
-- emits. Those subscriptions therefore never delivered: DisbursementOutcomeApplier
-- raises DISBURSEMENT_COMPLETED, and enqueueIfSubscribed matches on the exact
-- enum, so a stored LOAN_DISBURSEMENT_UPDATED never matched.
--
-- Rebind existing rows to DISBURSEMENT_COMPLETED so the (now corrected) chip
-- delivers. The token is unique and is not a substring of any other
-- WebhookEventType, so a plain replace() is safe. CSV order is not significant
-- (getWebhookEventTypes() only checks membership); the next subscription update
-- re-sorts the list.
UPDATE lsp
SET webhook_event_types =
        replace(webhook_event_types, 'LOAN_DISBURSEMENT_UPDATED', 'DISBURSEMENT_COMPLETED')
WHERE webhook_event_types LIKE '%LOAN_DISBURSEMENT_UPDATED%';
