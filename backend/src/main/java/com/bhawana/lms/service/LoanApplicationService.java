package com.bhawana.lms.service;

import com.bhawana.lms.common.web.PagedResult;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationAuditEvent;
import com.bhawana.lms.domain.LoanApplicationDocumentAccessAudit;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklist;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus;
import com.bhawana.lms.domain.LoanApplicationDocumentType;
import com.bhawana.lms.domain.LoanApplicationIntakeAudit;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanApplicationStatusReasonCode;
import com.bhawana.lms.domain.LoanApplicationStatusTransition;
import com.bhawana.lms.domain.LoanDelinquencyBucket;
import com.bhawana.lms.domain.LoanDisbursementRequestLog;
import com.bhawana.lms.domain.LoanForeclosureQuote;
import com.bhawana.lms.domain.LoanInvalidationReason;
import com.bhawana.lms.domain.LoanPaymentChannel;
import com.bhawana.lms.domain.LoanPaymentTransaction;
import com.bhawana.lms.domain.LoanRepaymentScheduleInstallment;
import com.bhawana.lms.domain.MockDisbursementOutcome;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Compatibility facade retained while callers migrate to focused services.
 * New code should inject {@link LoanApplicationQueryService},
 * {@link LoanApplicationServicingReadService}, {@link LoanDisbursementCommandService},
 * {@link LoanApplicationLifecycleService}, or command services directly.
 */
@Service
public class LoanApplicationService {

    private final LoanApplicationQueryService loanApplicationQueryService;
    private final LoanApplicationServicingReadService loanApplicationServicingReadService;
    private final LoanApplicationLifecycleService loanApplicationLifecycleService;
    private final LoanDisbursementCommandService loanDisbursementCommandService;
    private final LoanRepaymentCommandService loanRepaymentCommandService;
    private final LoanServicingSupportService loanServicingSupportService;
    private final LoanForeclosureCommandService loanForeclosureCommandService;
    private final LoanDocumentService loanDocumentService;

    public LoanApplicationService(
            LoanApplicationQueryService loanApplicationQueryService,
            LoanApplicationServicingReadService loanApplicationServicingReadService,
            LoanApplicationLifecycleService loanApplicationLifecycleService,
            LoanDisbursementCommandService loanDisbursementCommandService,
            LoanRepaymentCommandService loanRepaymentCommandService,
            LoanServicingSupportService loanServicingSupportService,
            LoanForeclosureCommandService loanForeclosureCommandService,
            LoanDocumentService loanDocumentService
    ) {
        this.loanApplicationQueryService = loanApplicationQueryService;
        this.loanApplicationServicingReadService = loanApplicationServicingReadService;
        this.loanApplicationLifecycleService = loanApplicationLifecycleService;
        this.loanDisbursementCommandService = loanDisbursementCommandService;
        this.loanRepaymentCommandService = loanRepaymentCommandService;
        this.loanServicingSupportService = loanServicingSupportService;
        this.loanForeclosureCommandService = loanForeclosureCommandService;
        this.loanDocumentService = loanDocumentService;
    }

    @Transactional(readOnly = true)
    public List<LoanApplication> listApplications(
            UUID lspId,
            UUID productId,
            String status,
            String sourceChannel,
            String query,
            LocalDate disbursalDateFrom,
            LocalDate disbursalDateTo
    ) {
        return loanApplicationQueryService.listApplications(
                lspId, productId, status, sourceChannel, query, disbursalDateFrom, disbursalDateTo);
    }

    @Transactional(readOnly = true)
    public PagedResult<LoanApplication> listApplicationsPage(
            UUID lspId,
            UUID productId,
            String status,
            String sourceChannel,
            String query,
            String lspLoanId,
            String bhawLoanId,
            LocalDate disbursalDateFrom,
            LocalDate disbursalDateTo,
            Integer offset,
            Integer limit,
            boolean includePaginationDetails
    ) {
        return loanApplicationQueryService.listApplicationsPage(
                lspId, productId, status, sourceChannel, query, lspLoanId, bhawLoanId,
                disbursalDateFrom, disbursalDateTo, offset, limit, includePaginationDetails);
    }

    @Transactional(readOnly = true)
    public PagedResult<LoanApplication> listApplicationsPage(
            UUID lspId,
            UUID productId,
            String status,
            String sourceChannel,
            String query,
            LocalDate disbursalDateFrom,
            LocalDate disbursalDateTo,
            Integer offset,
            Integer limit,
            boolean includePaginationDetails
    ) {
        return listApplicationsPage(
                lspId, productId, status, sourceChannel, query, null, null,
                disbursalDateFrom, disbursalDateTo, offset, limit, includePaginationDetails);
    }

    @Transactional(readOnly = true)
    public Map<UUID, String> getLoanAccountNumbers(List<UUID> applicationIds) {
        return loanApplicationServicingReadService.getLoanAccountNumbers(applicationIds);
    }

    @Transactional(readOnly = true)
    public List<LoanApplication> listApplicationsForLsp(
            UUID lspId,
            UUID productId,
            String status,
            String sourceChannel,
            String query
    ) {
        return loanApplicationQueryService.listApplicationsForLsp(lspId, productId, status, sourceChannel, query);
    }

    @Transactional(readOnly = true)
    public PagedResult<LoanApplication> listApplicationsForLspPage(
            UUID lspId,
            UUID productId,
            String status,
            String sourceChannel,
            String query,
            Integer offset,
            Integer limit,
            boolean includePaginationDetails
    ) {
        return loanApplicationQueryService.listApplicationsForLspPage(
                lspId, productId, status, sourceChannel, query, offset, limit, includePaginationDetails);
    }

    @Transactional(readOnly = true)
    public LoanApplication getApplication(UUID applicationId) {
        return loanApplicationQueryService.getApplication(applicationId);
    }

    @Transactional(readOnly = true)
    public LoanApplication getApplicationForLsp(UUID lspId, UUID applicationId) {
        return loanApplicationQueryService.getApplicationForLsp(lspId, applicationId);
    }

    @Transactional(readOnly = true)
    public LoanApplication getApplicationForLspByExternalLoanId(UUID lspId, String externalLoanId) {
        return loanApplicationQueryService.getApplicationForLspByExternalLoanId(lspId, externalLoanId);
    }

    @Transactional(readOnly = true)
    public LoanAccount getLoanAccountForLsp(UUID lspId, UUID loanAccountId) {
        return loanServicingSupportService.getLoanAccountForLsp(lspId, loanAccountId);
    }

    @Transactional(readOnly = true)
    public List<LoanRepaymentScheduleInstallment> listRepaymentScheduleForLsp(UUID lspId, UUID loanAccountId) {
        return loanServicingSupportService.listRepaymentScheduleForLsp(lspId, loanAccountId);
    }

    @Transactional(readOnly = true)
    public List<LoanPaymentTransaction> listPaymentTransactionsForLsp(UUID lspId, UUID loanAccountId) {
        return loanServicingSupportService.listPaymentTransactionsForLsp(lspId, loanAccountId);
    }

    @Transactional(readOnly = true)
    public Optional<LoanApplicationLastActivity> getLatestActivity(UUID applicationId) {
        return loanApplicationServicingReadService.getLatestActivity(applicationId);
    }

    @Transactional(readOnly = true)
    public List<LoanApplicationStatusTransition> listStatusTransitions(UUID applicationId) {
        return loanApplicationServicingReadService.listStatusTransitions(applicationId);
    }

    @Transactional(readOnly = true)
    public List<LoanApplicationAuditEvent> listAuditEvents(UUID applicationId) {
        return loanApplicationServicingReadService.listAuditEvents(applicationId);
    }

    @Transactional(readOnly = true)
    public Optional<LoanAccount> getLoanAccount(UUID applicationId) {
        return loanApplicationServicingReadService.getLoanAccount(applicationId);
    }

    @Transactional(readOnly = true)
    public Optional<LoanRepaymentScheduleSummary> getLoanRepaymentScheduleSummary(UUID applicationId) {
        return loanApplicationServicingReadService.getLoanRepaymentScheduleSummary(applicationId);
    }

    @Transactional(readOnly = true)
    public Optional<LoanDelinquencySummary> getLoanDelinquencySummary(UUID applicationId) {
        return loanApplicationServicingReadService.getLoanDelinquencySummary(applicationId);
    }

    @Transactional(readOnly = true)
    public List<LoanRepaymentScheduleInstallment> listRepaymentSchedule(UUID applicationId) {
        return loanApplicationServicingReadService.listRepaymentSchedule(applicationId);
    }

    @Transactional(readOnly = true)
    public List<LoanApplicationWebhookEventProjection> listWebhookEventsForApplication(UUID applicationId) {
        return loanApplicationServicingReadService.listWebhookEventsForApplication(applicationId);
    }

    @Transactional(readOnly = true)
    public List<LoanDisbursementRequestLog> listDisbursementRequests(UUID applicationId) {
        return loanApplicationServicingReadService.listDisbursementRequests(applicationId);
    }

    @Transactional(readOnly = true)
    public List<LoanPaymentTransaction> listPaymentTransactions(UUID applicationId) {
        return loanApplicationServicingReadService.listPaymentTransactions(applicationId);
    }

    @Transactional(readOnly = true)
    public List<LoanForeclosureQuote> listForeclosureQuotes(UUID applicationId) {
        return loanApplicationServicingReadService.listForeclosureQuotes(applicationId);
    }

    @Transactional(readOnly = true)
    public LoanApplicationDocumentChecklist getDocumentChecklistItem(
            UUID applicationId,
            LoanApplicationDocumentType documentType
    ) {
        return loanApplicationServicingReadService.getDocumentChecklistItem(applicationId, documentType);
    }

    @Transactional
    public List<LoanApplicationDocumentChecklist> listDocumentChecklist(UUID applicationId, String actorUsername) {
        return loanApplicationServicingReadService.listDocumentChecklist(applicationId, actorUsername);
    }

    @Transactional(readOnly = true)
    public List<LoanApplicationDocumentAccessAudit> listDocumentAccessAudits(UUID applicationId) {
        return loanApplicationServicingReadService.listDocumentAccessAudits(applicationId);
    }

    @Transactional
    public LoanDocumentService.RetrievedDocumentContent downloadDocumentContent(
            UUID applicationId,
            LoanApplicationDocumentType documentType,
            String actorUsername,
            String actorIp,
            String correlationId
    ) {
        return loanApplicationServicingReadService.downloadDocumentContent(
                applicationId, documentType, actorUsername, actorIp, correlationId);
    }

    @Transactional
    public LoanDocumentService.ZipBuildResult downloadDocumentZip(
            UUID applicationId,
            String actorUsername,
            String actorIp,
            String correlationId
    ) {
        return loanApplicationServicingReadService.downloadDocumentZip(
                applicationId, actorUsername, actorIp, correlationId);
    }

    @Transactional(readOnly = true)
    public List<LoanApplicationDocumentChecklist> listSubmittedDocumentsForLsp(UUID lspId, UUID applicationId) {
        return loanDocumentService.listSubmittedDocumentsForLsp(lspId, applicationId);
    }

    @Transactional
    public LoanApplicationDocumentChecklist submitDocumentForLsp(
            UUID lspId,
            UUID applicationId,
            LoanApplicationDocumentType documentType,
            String actorUsername,
            String note,
            String fileName,
            String fileReference,
            String sourceReference,
            String contentType
    ) {
        return loanDocumentService.submitDocumentMetadataForLsp(
                lspId, applicationId, documentType, actorUsername, note,
                fileName, fileReference, sourceReference, contentType);
    }

    @Transactional
    public LoanApplication createApplication(String actorUsername, LoanApplicationOnboardingCommand command) {
        return loanApplicationLifecycleService.createApplication(actorUsername, command);
    }

    @Transactional
    public LoanApplication transitionStatus(
            UUID applicationId,
            String actorUsername,
            LoanApplicationStatus targetStatus,
            String note,
            LoanApplicationStatusReasonCode reasonCode
    ) {
        return loanApplicationLifecycleService.transitionStatus(
                applicationId, actorUsername, targetStatus, note, reasonCode);
    }

    @Transactional
    public LoanApplication manuallyOverrideStatus(
            UUID applicationId,
            String actorUsername,
            LoanApplicationStatus targetStatus,
            String note,
            LoanApplicationStatusReasonCode reasonCode
    ) {
        return loanApplicationLifecycleService.manuallyOverrideStatus(
                applicationId, actorUsername, targetStatus, note, reasonCode);
    }

    @Transactional
    public LoanApplication invalidateApplicationForLsp(
            UUID lspId,
            UUID applicationId,
            String actorUsername,
            LoanInvalidationReason invalidReason,
            String invalidReasonText
    ) {
        return loanApplicationLifecycleService.invalidateApplicationForLsp(
                lspId, applicationId, actorUsername, invalidReason, invalidReasonText);
    }

    @Transactional
    public DocumentChecklistUpdateResult updateDocumentChecklistItem(
            UUID applicationId,
            LoanApplicationDocumentType documentType,
            String actorUsername,
            LoanApplicationDocumentChecklistStatus status,
            String note,
            String fileName,
            String fileReference,
            String sourceReference,
            String contentType
    ) {
        return updateDocumentChecklistItem(
                applicationId, documentType, actorUsername, status, note,
                fileName, fileReference, sourceReference, contentType,
                null, null, null, false);
    }

    @Transactional
    public DocumentChecklistUpdateResult updateDocumentChecklistItem(
            UUID applicationId,
            LoanApplicationDocumentType documentType,
            String actorUsername,
            LoanApplicationDocumentChecklistStatus status,
            String note,
            String fileName,
            String fileReference,
            String sourceReference,
            String contentType,
            Long fileSizeBytes,
            String fileChecksum,
            String storageKey,
            boolean lmsManagedContent
    ) {
        return loanApplicationLifecycleService.updateDocumentChecklistItem(
                applicationId, documentType, actorUsername, status, note,
                fileName, fileReference, sourceReference, contentType,
                fileSizeBytes, fileChecksum, storageKey, lmsManagedContent);
    }

    @Transactional(readOnly = true)
    public boolean hasAllRequiredDocumentsUploaded(UUID applicationId) {
        return loanApplicationLifecycleService.hasAllRequiredDocumentsUploaded(applicationId);
    }

    @Transactional
    public LoanApplication autoApproveIfEligibleForLsp(UUID applicationId, String actorUsername) {
        return loanApplicationLifecycleService.autoApproveIfEligibleForLsp(applicationId, actorUsername);
    }

    @Transactional
    public LoanApplication initiateDisbursement(UUID applicationId, String actorUsername) {
        return loanDisbursementCommandService.initiateDisbursement(applicationId, actorUsername);
    }

    @Transactional
    public LoanApplication initiateDisbursement(
            UUID applicationId,
            String actorUsername,
            BigDecimal disbursementAmount
    ) {
        return loanDisbursementCommandService.initiateDisbursement(applicationId, actorUsername, disbursementAmount);
    }

    @Transactional
    public LoanApplication resolveMockDisbursementOutcome(
            UUID applicationId,
            String actorUsername,
            MockDisbursementOutcome outcome
    ) {
        return loanDisbursementCommandService.resolveMockDisbursementOutcome(applicationId, actorUsername, outcome);
    }

    @Transactional
    public LoanApplication resolveMockDisbursementOutcome(
            UUID applicationId,
            String actorUsername,
            String actorIp,
            String correlationId,
            MockDisbursementOutcome outcome
    ) {
        return loanDisbursementCommandService.resolveMockDisbursementOutcome(
                applicationId, actorUsername, actorIp, correlationId, outcome);
    }

    public LoanPaymentTransaction recordPaymentTransaction(
            UUID applicationId,
            String actorUsername,
            String idempotencyKey,
            UUID targetInstallmentId,
            BigDecimal amount,
            LocalDate postedAt,
            String reference,
            LoanPaymentChannel channel
    ) {
        return loanRepaymentCommandService.recordPaymentTransactionWithRecovery(
                applicationId, actorUsername, idempotencyKey, targetInstallmentId,
                amount, postedAt, reference, channel);
    }

    @Transactional
    public LoanForeclosureQuote requestForeclosureQuote(UUID applicationId, String actorUsername, LocalDate effectiveDate) {
        return loanForeclosureCommandService.requestForeclosureQuote(applicationId, actorUsername, effectiveDate);
    }

    @Transactional
    public LoanForeclosureQuote requestForeclosureQuoteForLsp(
            UUID lspId,
            UUID loanAccountId,
            String actorUsername,
            LocalDate effectiveDate
    ) {
        return loanForeclosureCommandService.requestForeclosureQuoteForLsp(
                lspId, loanAccountId, actorUsername, effectiveDate);
    }

    @Transactional
    public LoanForeclosureQuote executeForeclosureQuote(
            UUID applicationId,
            UUID quoteId,
            String actorUsername,
            LocalDate settlementDate,
            String reference,
            String note
    ) {
        return loanForeclosureCommandService.executeForeclosureQuote(
                applicationId, quoteId, actorUsername, settlementDate, reference, note);
    }

    @Transactional
    public LoanForeclosureQuote executeForeclosureQuoteForLsp(
            UUID lspId,
            UUID loanAccountId,
            UUID quoteId,
            String actorUsername,
            LocalDate settlementDate,
            String reference,
            String note
    ) {
        return loanForeclosureCommandService.executeForeclosureQuoteForLsp(
                lspId, loanAccountId, quoteId, actorUsername, settlementDate, reference, note);
    }

    @Transactional
    public List<LoanApplicationIntakeAudit> listIntakeAudits(UUID applicationId, String actorUsername) {
        return loanApplicationServicingReadService.listIntakeAudits(applicationId, actorUsername);
    }

    public boolean hasAllRequiredLmsManagedDocuments(UUID applicationId) {
        return loanApplicationLifecycleService.hasAllRequiredLmsManagedDocuments(applicationId);
    }

    /** @deprecated use {@link LoanDelinquencySupport#calculateDaysPastDue} */
    @Deprecated
    public static int calculateDaysPastDue(LoanRepaymentScheduleInstallment installment, LocalDate today) {
        return LoanDelinquencySupport.calculateDaysPastDue(installment, today);
    }

    /** @deprecated use {@link LoanDelinquencySupport#resolveDelinquencyBucket} */
    @Deprecated
    public static LoanDelinquencyBucket resolveDelinquencyBucket(int daysPastDue) {
        return LoanDelinquencySupport.resolveDelinquencyBucket(daysPastDue);
    }
}
