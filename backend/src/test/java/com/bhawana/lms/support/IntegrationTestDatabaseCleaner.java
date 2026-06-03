package com.bhawana.lms.support;

import com.bhawana.lms.repo.ApiClientAuditEventRepository;
import com.bhawana.lms.repo.ApiClientRepository;
import com.bhawana.lms.repo.AppUserAuditEventRepository;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.repo.BorrowerBankDetailsUpdateAuditRepository;
import com.bhawana.lms.repo.BorrowerRepository;
import com.bhawana.lms.repo.DisbursementOutcomeAuditRepository;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationAssignmentEventRepository;
import com.bhawana.lms.repo.LoanApplicationAuditEventRepository;
import com.bhawana.lms.repo.LoanApplicationDocumentAccessAuditRepository;
import com.bhawana.lms.repo.LoanApplicationDocumentChecklistRepository;
import com.bhawana.lms.repo.LoanApplicationIntakeAuditRepository;
import com.bhawana.lms.repo.LoanApplicationPiiRevealAuditRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanApplicationStatusTransitionRepository;
import com.bhawana.lms.repo.LoanDisbursementBankMismatchLogRepository;
import com.bhawana.lms.repo.LoanDisbursementRequestLogRepository;
import com.bhawana.lms.repo.LoanForeclosureQuoteRepository;
import com.bhawana.lms.repo.LoanPaymentTransactionRepository;
import com.bhawana.lms.repo.LoanProductAuditEventRepository;
import com.bhawana.lms.repo.LoanProductLspMappingRepository;
import com.bhawana.lms.repo.LoanProductRepository;
import com.bhawana.lms.repo.LoanRepaymentScheduleInstallmentRepository;
import com.bhawana.lms.repo.LspApiIdempotencyRecordRepository;
import com.bhawana.lms.repo.LspAuditEventRepository;
import com.bhawana.lms.repo.LspIpAllowlistRepository;
import com.bhawana.lms.repo.LspRepository;
import com.bhawana.lms.repo.LspUiIpAllowlistRepository;
import com.bhawana.lms.repo.OpsAlertRepository;
import com.bhawana.lms.repo.WebhookEventDeliveryAttemptRepository;
import com.bhawana.lms.repo.WebhookEventOutboxRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * FK-safe deletion order for {@code @SpringBootTest} classes that share the H2
 * database. Call {@link #cleanIntegrationTestData()} in {@code @BeforeEach} so
 * cross-test rows (e.g. {@code app_user_audit_event} from user-admin tests, or
 * {@code lsp_api_ip_allowlist} from LSP admin tests) do not block teardown.
 */
@Component
public class IntegrationTestDatabaseCleaner {

    private final JdbcTemplate jdbcTemplate;
    private final OpsAlertRepository opsAlertRepository;
    private final DisbursementOutcomeAuditRepository disbursementOutcomeAuditRepository;
    private final LoanDisbursementBankMismatchLogRepository loanDisbursementBankMismatchLogRepository;
    private final BorrowerBankDetailsUpdateAuditRepository borrowerBankDetailsUpdateAuditRepository;
    private final WebhookEventDeliveryAttemptRepository webhookEventDeliveryAttemptRepository;
    private final WebhookEventOutboxRepository webhookEventOutboxRepository;
    private final LoanForeclosureQuoteRepository loanForeclosureQuoteRepository;
    private final LoanPaymentTransactionRepository loanPaymentTransactionRepository;
    private final LoanDisbursementRequestLogRepository loanDisbursementRequestLogRepository;
    private final LoanRepaymentScheduleInstallmentRepository loanRepaymentScheduleInstallmentRepository;
    private final LoanAccountRepository loanAccountRepository;
    private final LoanApplicationAuditEventRepository loanApplicationAuditEventRepository;
    private final LoanApplicationDocumentAccessAuditRepository loanApplicationDocumentAccessAuditRepository;
    private final LoanApplicationAssignmentEventRepository loanApplicationAssignmentEventRepository;
    private final LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository;
    private final LoanApplicationStatusTransitionRepository loanApplicationStatusTransitionRepository;
    private final LoanApplicationPiiRevealAuditRepository loanApplicationPiiRevealAuditRepository;
    private final LspApiIdempotencyRecordRepository lspApiIdempotencyRecordRepository;
    private final LoanApplicationIntakeAuditRepository loanApplicationIntakeAuditRepository;
    private final LoanApplicationRepository loanApplicationRepository;
    private final BorrowerRepository borrowerRepository;
    private final ApiClientAuditEventRepository apiClientAuditEventRepository;
    private final ApiClientRepository apiClientRepository;
    private final AppUserAuditEventRepository appUserAuditEventRepository;
    private final AppUserRepository appUserRepository;
    private final LoanProductAuditEventRepository loanProductAuditEventRepository;
    private final LoanProductLspMappingRepository loanProductLspMappingRepository;
    private final LoanProductRepository loanProductRepository;
    private final LspAuditEventRepository lspAuditEventRepository;
    private final LspIpAllowlistRepository lspIpAllowlistRepository;
    private final LspUiIpAllowlistRepository lspUiIpAllowlistRepository;
    private final LspRepository lspRepository;

    public IntegrationTestDatabaseCleaner(
            JdbcTemplate jdbcTemplate,
            OpsAlertRepository opsAlertRepository,
            DisbursementOutcomeAuditRepository disbursementOutcomeAuditRepository,
            LoanDisbursementBankMismatchLogRepository loanDisbursementBankMismatchLogRepository,
            BorrowerBankDetailsUpdateAuditRepository borrowerBankDetailsUpdateAuditRepository,
            WebhookEventDeliveryAttemptRepository webhookEventDeliveryAttemptRepository,
            WebhookEventOutboxRepository webhookEventOutboxRepository,
            LoanForeclosureQuoteRepository loanForeclosureQuoteRepository,
            LoanPaymentTransactionRepository loanPaymentTransactionRepository,
            LoanDisbursementRequestLogRepository loanDisbursementRequestLogRepository,
            LoanRepaymentScheduleInstallmentRepository loanRepaymentScheduleInstallmentRepository,
            LoanAccountRepository loanAccountRepository,
            LoanApplicationAuditEventRepository loanApplicationAuditEventRepository,
            LoanApplicationDocumentAccessAuditRepository loanApplicationDocumentAccessAuditRepository,
            LoanApplicationAssignmentEventRepository loanApplicationAssignmentEventRepository,
            LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository,
            LoanApplicationStatusTransitionRepository loanApplicationStatusTransitionRepository,
            LoanApplicationPiiRevealAuditRepository loanApplicationPiiRevealAuditRepository,
            LspApiIdempotencyRecordRepository lspApiIdempotencyRecordRepository,
            LoanApplicationIntakeAuditRepository loanApplicationIntakeAuditRepository,
            LoanApplicationRepository loanApplicationRepository,
            BorrowerRepository borrowerRepository,
            ApiClientAuditEventRepository apiClientAuditEventRepository,
            ApiClientRepository apiClientRepository,
            AppUserAuditEventRepository appUserAuditEventRepository,
            AppUserRepository appUserRepository,
            LoanProductAuditEventRepository loanProductAuditEventRepository,
            LoanProductLspMappingRepository loanProductLspMappingRepository,
            LoanProductRepository loanProductRepository,
            LspAuditEventRepository lspAuditEventRepository,
            LspIpAllowlistRepository lspIpAllowlistRepository,
            LspUiIpAllowlistRepository lspUiIpAllowlistRepository,
            LspRepository lspRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.opsAlertRepository = opsAlertRepository;
        this.disbursementOutcomeAuditRepository = disbursementOutcomeAuditRepository;
        this.loanDisbursementBankMismatchLogRepository = loanDisbursementBankMismatchLogRepository;
        this.borrowerBankDetailsUpdateAuditRepository = borrowerBankDetailsUpdateAuditRepository;
        this.webhookEventDeliveryAttemptRepository = webhookEventDeliveryAttemptRepository;
        this.webhookEventOutboxRepository = webhookEventOutboxRepository;
        this.loanForeclosureQuoteRepository = loanForeclosureQuoteRepository;
        this.loanPaymentTransactionRepository = loanPaymentTransactionRepository;
        this.loanDisbursementRequestLogRepository = loanDisbursementRequestLogRepository;
        this.loanRepaymentScheduleInstallmentRepository = loanRepaymentScheduleInstallmentRepository;
        this.loanAccountRepository = loanAccountRepository;
        this.loanApplicationAuditEventRepository = loanApplicationAuditEventRepository;
        this.loanApplicationDocumentAccessAuditRepository = loanApplicationDocumentAccessAuditRepository;
        this.loanApplicationAssignmentEventRepository = loanApplicationAssignmentEventRepository;
        this.loanApplicationDocumentChecklistRepository = loanApplicationDocumentChecklistRepository;
        this.loanApplicationStatusTransitionRepository = loanApplicationStatusTransitionRepository;
        this.loanApplicationPiiRevealAuditRepository = loanApplicationPiiRevealAuditRepository;
        this.lspApiIdempotencyRecordRepository = lspApiIdempotencyRecordRepository;
        this.loanApplicationIntakeAuditRepository = loanApplicationIntakeAuditRepository;
        this.loanApplicationRepository = loanApplicationRepository;
        this.borrowerRepository = borrowerRepository;
        this.apiClientAuditEventRepository = apiClientAuditEventRepository;
        this.apiClientRepository = apiClientRepository;
        this.appUserAuditEventRepository = appUserAuditEventRepository;
        this.appUserRepository = appUserRepository;
        this.loanProductAuditEventRepository = loanProductAuditEventRepository;
        this.loanProductLspMappingRepository = loanProductLspMappingRepository;
        this.loanProductRepository = loanProductRepository;
        this.lspAuditEventRepository = lspAuditEventRepository;
        this.lspIpAllowlistRepository = lspIpAllowlistRepository;
        this.lspUiIpAllowlistRepository = lspUiIpAllowlistRepository;
        this.lspRepository = lspRepository;
    }

    public void cleanIntegrationTestData() {
        jdbcTemplate.execute("DELETE FROM report_request");
        opsAlertRepository.deleteAllInBatch();
        disbursementOutcomeAuditRepository.deleteAllInBatch();
        loanDisbursementBankMismatchLogRepository.deleteAllInBatch();
        borrowerBankDetailsUpdateAuditRepository.deleteAllInBatch();
        webhookEventDeliveryAttemptRepository.deleteAllInBatch();
        webhookEventOutboxRepository.deleteAllInBatch();
        loanForeclosureQuoteRepository.deleteAllInBatch();
        loanPaymentTransactionRepository.deleteAllInBatch();
        loanDisbursementRequestLogRepository.deleteAllInBatch();
        loanRepaymentScheduleInstallmentRepository.deleteAllInBatch();
        loanAccountRepository.deleteAllInBatch();
        loanApplicationAuditEventRepository.deleteAllInBatch();
        loanApplicationDocumentAccessAuditRepository.deleteAllInBatch();
        loanApplicationAssignmentEventRepository.deleteAllInBatch();
        loanApplicationDocumentChecklistRepository.deleteAllInBatch();
        loanApplicationStatusTransitionRepository.deleteAllInBatch();
        loanApplicationPiiRevealAuditRepository.deleteAllInBatch();
        lspApiIdempotencyRecordRepository.deleteAllInBatch();
        loanApplicationIntakeAuditRepository.deleteAllInBatch();
        loanApplicationRepository.deleteAllInBatch();
        borrowerRepository.deleteAllInBatch();
        apiClientAuditEventRepository.deleteAllInBatch();
        apiClientRepository.deleteAllInBatch();
        appUserAuditEventRepository.deleteAllInBatch();
        appUserRepository.deleteAllInBatch();
        loanProductAuditEventRepository.deleteAllInBatch();
        loanProductLspMappingRepository.deleteAllInBatch();
        loanProductRepository.deleteAllInBatch();
        lspAuditEventRepository.deleteAllInBatch();
        lspIpAllowlistRepository.deleteAllInBatch();
        lspUiIpAllowlistRepository.deleteAllInBatch();
        lspRepository.deleteAllInBatch();
    }
}
