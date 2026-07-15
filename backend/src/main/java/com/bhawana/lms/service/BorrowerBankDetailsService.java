package com.bhawana.lms.service;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.common.api.error.BusinessRuleViolationException;
import com.bhawana.lms.common.api.error.ResourceNotFoundException;
import com.bhawana.lms.common.pii.BankAccountMasking;
import com.bhawana.lms.domain.Borrower;
import com.bhawana.lms.domain.BorrowerBankDetailsUpdateAudit;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanDisbursementBankMismatchLog;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.WebhookEventType;
import com.bhawana.lms.repo.BorrowerBankDetailsUpdateAuditRepository;
import com.bhawana.lms.repo.BorrowerRepository;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanDisbursementBankMismatchLogRepository;
import com.bhawana.lms.repo.LspRepository;
import com.bhawana.lms.tenant.AdminScopedTransactionExecutor;
import com.bhawana.lms.tenant.TenantDataAccessContextHolder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BorrowerBankDetailsService {

    private static final Logger log = LoggerFactory.getLogger(BorrowerBankDetailsService.class);

    private static final Set<LoanAccountStatus> IN_FLIGHT_DISBURSEMENT_STATUSES = EnumSet.of(
            LoanAccountStatus.DISBURSEMENT_REQUESTED,
            LoanAccountStatus.DISBURSEMENT_PENDING_RECONCILIATION
    );

    private static final Set<LoanApplicationStatus> PRE_DISBURSAL_APPLICATION_STATUSES = EnumSet.of(
            LoanApplicationStatus.INITIALIZED,
            LoanApplicationStatus.AWAITING_APPROVAL,
            LoanApplicationStatus.APPROVED_PENDING_DISBURSAL,
            LoanApplicationStatus.DISBURSEMENT_RETRY
    );

    private final BorrowerRepository borrowerRepository;
    private final LspRepository lspRepository;
    private final LoanApplicationRepository loanApplicationRepository;
    private final LoanAccountRepository loanAccountRepository;
    private final BorrowerBankDetailsUpdateAuditRepository bankDetailsUpdateAuditRepository;
    private final LoanDisbursementBankMismatchLogRepository bankMismatchLogRepository;
    private final WebhookOutboxService webhookOutboxService;
    private final OpsAlertEmitters opsAlertEmitters;
    private final BorrowerBankDetailsProperties properties;
    private final Clock clock;
    private final AdminScopedTransactionExecutor adminScopedTransactionExecutor;

    public BorrowerBankDetailsService(
            BorrowerRepository borrowerRepository,
            LspRepository lspRepository,
            LoanApplicationRepository loanApplicationRepository,
            LoanAccountRepository loanAccountRepository,
            BorrowerBankDetailsUpdateAuditRepository bankDetailsUpdateAuditRepository,
            LoanDisbursementBankMismatchLogRepository bankMismatchLogRepository,
            WebhookOutboxService webhookOutboxService,
            OpsAlertEmitters opsAlertEmitters,
            BorrowerBankDetailsProperties properties,
            Clock clock,
            AdminScopedTransactionExecutor adminScopedTransactionExecutor
    ) {
        this.borrowerRepository = borrowerRepository;
        this.lspRepository = lspRepository;
        this.loanApplicationRepository = loanApplicationRepository;
        this.loanAccountRepository = loanAccountRepository;
        this.bankDetailsUpdateAuditRepository = bankDetailsUpdateAuditRepository;
        this.bankMismatchLogRepository = bankMismatchLogRepository;
        this.webhookOutboxService = webhookOutboxService;
        this.opsAlertEmitters = opsAlertEmitters;
        this.properties = properties;
        this.clock = clock;
        this.adminScopedTransactionExecutor = adminScopedTransactionExecutor;
    }

    @Transactional(readOnly = true)
    public Borrower getBorrower(UUID borrowerId) {
        TenantDataAccessContextHolder.useAdmin();
        return borrowerRepository.findById(borrowerId)
                .orElseThrow(() -> new ResourceNotFoundException("Unknown borrower id: " + borrowerId));
    }

    public Borrower getBorrowerForLsp(UUID lspId, UUID borrowerId) {
        return adminScopedTransactionExecutor.call(() -> loadBorrowerForLsp(lspId, borrowerId));
    }

    public Borrower updateBankDetailsForLsp(
            UUID lspId,
            UUID borrowerId,
            BorrowerBankDetailsCommand command,
            String actorUsername,
            String clientIp
    ) {
        return adminScopedTransactionExecutor.call(() -> updateBankDetails(
                lspId,
                borrowerId,
                command,
                actorUsername,
                "LSP_API_CLIENT",
                clientIp
        ));
    }

    @Transactional
    public Borrower updateBankDetailsForAdmin(
            UUID borrowerId,
            BorrowerBankDetailsCommand command,
            String actorUsername,
            String actorType,
            String clientIp
    ) {
        return updateBankDetails(
                null,
                borrowerId,
                command,
                actorUsername,
                actorType,
                clientIp
        );
    }

    public void recordHardDisbursementBankMismatch(
            LoanApplication application,
            UUID lspId,
            String submittedBankAccountNumber,
            String submittedIfscCode,
            String submittedAccountHolderName
    ) {
        adminScopedTransactionExecutor.run(() -> recordHardDisbursementBankMismatchAsAdmin(
                application,
                lspId,
                submittedBankAccountNumber,
                submittedIfscCode,
                submittedAccountHolderName
        ));
    }

    private void recordHardDisbursementBankMismatchAsAdmin(
            LoanApplication application,
            UUID lspId,
            String submittedBankAccountNumber,
            String submittedIfscCode,
            String submittedAccountHolderName
    ) {
        Lsp lsp = lspRepository.findById(lspId).orElse(application.getLsp());
        bankMismatchLogRepository.save(new LoanDisbursementBankMismatchLog(
                application,
                lsp,
                submittedBankAccountNumber,
                submittedIfscCode,
                submittedAccountHolderName,
                CorrelationIdHolder.get(),
                false
        ));

        Instant since = clock.instant().minus(Duration.ofMinutes(properties.getMismatchWindowMinutes()));
        long attempts = bankMismatchLogRepository.countByLoanApplication_IdAndLsp_IdAndSoftIsFalseAndCreatedAtAfter(
                application.getId(),
                lspId,
                since
        );
        if (attempts >= properties.getMismatchMaxAttempts()) {
            Map<String, String> details = new LinkedHashMap<>();
            details.put("submittedBankAccountNumber", BankAccountMasking.mask(submittedBankAccountNumber));
            details.put("submittedIfscCode", submittedIfscCode);
            details.put("onFileBankAccountNumber", BankAccountMasking.mask(application.getBorrower().getBankAccountNumber()));
            details.put("onFileIfscCode", application.getBorrower().getIfscCode());
            details.put("attemptCount", String.valueOf(attempts));
            opsAlertEmitters.emitLspBoundViolation(
                    application,
                    "BANK_DETAIL_MISMATCH",
                    "Repeated disbursement bank-detail mismatches from LSP "
                            + application.getLsp().getCode()
                            + " for loan "
                            + application.getExternalLoanId()
                            + ".",
                    details
            );
        }
    }

    public void recordSoftHolderNameMismatch(
            LoanApplication application,
            UUID lspId,
            String submittedAccountHolderName,
            String onFileAccountHolderName
    ) {
        adminScopedTransactionExecutor.run(() -> recordSoftHolderNameMismatchAsAdmin(
                application,
                lspId,
                submittedAccountHolderName,
                onFileAccountHolderName
        ));
    }

    private void recordSoftHolderNameMismatchAsAdmin(
            LoanApplication application,
            UUID lspId,
            String submittedAccountHolderName,
            String onFileAccountHolderName
    ) {
        Lsp lsp = lspRepository.findById(lspId).orElse(application.getLsp());
        bankMismatchLogRepository.save(new LoanDisbursementBankMismatchLog(
                application,
                lsp,
                null,
                null,
                submittedAccountHolderName,
                CorrelationIdHolder.get(),
                true
        ));
        opsAlertEmitters.emitHolderNameSoftMismatch(
                application,
                submittedAccountHolderName,
                onFileAccountHolderName,
                CorrelationIdHolder.get()
        );
    }

    private Borrower loadBorrowerForLsp(UUID lspId, UUID borrowerId) {
        TenantDataAccessContextHolder.useAdmin();
        Borrower borrower = borrowerRepository.findById(borrowerId)
                .orElseThrow(() -> new ResourceNotFoundException("Unknown borrower id: " + borrowerId));
        if (!borrower.hasVisibilityFor(lspId)) {
            throw new ResourceNotFoundException("Unknown borrower id: " + borrowerId);
        }
        return borrower;
    }

    private Borrower updateBankDetails(
            UUID lspId,
            UUID borrowerId,
            BorrowerBankDetailsCommand command,
            String actorUsername,
            String actorType,
            String clientIp
    ) {
        TenantDataAccessContextHolder.useAdmin();
        Borrower borrower = borrowerRepository.findById(borrowerId)
                .orElseThrow(() -> new ResourceNotFoundException("Unknown borrower id: " + borrowerId));
        if (lspId != null && !borrower.hasVisibilityFor(lspId)) {
            throw new ResourceNotFoundException("Unknown borrower id: " + borrowerId);
        }

        assertBankDetailsUpdatable(lspId, borrowerId);

        String previousAccount = borrower.getBankAccountNumber();
        String previousBankName = borrower.getBankName();
        String previousIfsc = borrower.getIfscCode();
        String previousHolder = borrower.getAccountHolderName();

        borrower.updateBankDetails(
                command.bankAccountNumber(),
                command.bankName(),
                command.ifscCode(),
                command.accountHolderName()
        );
        Borrower savedBorrower = borrowerRepository.save(borrower);

        Lsp lsp = lspId == null ? null : lspRepository.findById(lspId).orElse(null);
        bankDetailsUpdateAuditRepository.save(new BorrowerBankDetailsUpdateAudit(
                savedBorrower,
                lsp,
                actorUsername,
                actorType,
                previousAccount,
                previousBankName,
                previousIfsc,
                previousHolder,
                savedBorrower.getBankAccountNumber(),
                savedBorrower.getBankName(),
                savedBorrower.getIfscCode(),
                savedBorrower.getAccountHolderName(),
                clientIp,
                CorrelationIdHolder.get()
        ));

        evaluateVelocityAlert(savedBorrower);

        if (lsp != null) {
            UUID loanApplicationId = loanApplicationRepository
                    .findTopByBorrower_IdAndLsp_IdOrderByCreatedAtDesc(borrowerId, lspId)
                    .map(LoanApplication::getId)
                    .orElse(null);
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            payload.put("borrowerId", borrowerId);
            // Masked in the webhook: the LSP already holds the full value it submitted;
            // the dedicated bank-details endpoint returns it unmasked when needed.
            payload.put("bankAccountNumber", BankAccountMasking.mask(savedBorrower.getBankAccountNumber()));
            payload.put("bankName", savedBorrower.getBankName());
            payload.put("ifscCode", savedBorrower.getIfscCode());
            payload.put("accountHolderName", savedBorrower.getAccountHolderName());
            payload.put("previousBankAccountNumber", BankAccountMasking.mask(previousAccount));
            payload.put("previousIfscCode", previousIfsc);
            webhookOutboxService.enqueueIfSubscribed(
                    lsp,
                    WebhookEventType.BORROWER_BANK_DETAILS_UPDATED,
                    "BORROWER",
                    borrowerId.toString(),
                    loanApplicationId,
                    payload
            );
        }

        return savedBorrower;
    }

    /**
     * LSP updates require an in-flight pre-disbursal application for the borrower; admin updates
     * bypass that gate but both paths block while a disbursement is in progress.
     */
    private void assertBankDetailsUpdatable(UUID lspId, UUID borrowerId) {
        if (loanAccountRepository.existsByBorrower_IdAndStatusIn(borrowerId, IN_FLIGHT_DISBURSEMENT_STATUSES)) {
            log.warn(
                    "borrower_bank_details_update_blocked reason=disbursement_in_flight borrowerId={} lspId={}",
                    borrowerId,
                    lspId
            );
            throw new BusinessRuleViolationException(
                    "BANK_DETAILS_LOCKED_DISBURSEMENT_IN_FLIGHT",
                    "Bank details cannot be updated while a disbursement is in progress for this borrower.",
                    Map.of()
            );
        }

        if (lspId == null) {
            return;
        }

        if (!loanApplicationRepository.existsByBorrower_IdAndLsp_IdAndStatusIn(
                borrowerId,
                lspId,
                PRE_DISBURSAL_APPLICATION_STATUSES
        )) {
            log.warn(
                    "borrower_bank_details_update_blocked reason=no_pre_disbursal_application borrowerId={} lspId={}",
                    borrowerId,
                    lspId
            );
            throw new BusinessRuleViolationException(
                    "BANK_DETAILS_UPDATE_NOT_ALLOWED",
                    "Bank details can only be updated while a loan application for this borrower is pre-disbursal.",
                    Map.of()
            );
        }
    }

    private void evaluateVelocityAlert(Borrower borrower) {
        Instant since = clock.instant().minus(Duration.ofDays(properties.getVelocityWindowDays()));
        long updates = bankDetailsUpdateAuditRepository.countByBorrower_IdAndCreatedAtAfter(
                borrower.getId(),
                since
        );
        if (updates >= properties.getVelocityMaxUpdates()) {
            opsAlertEmitters.emitBorrowerBankDetailsVelocity(borrower, (int) updates);
        }
    }

    public record BorrowerBankDetailsCommand(
            String bankAccountNumber,
            String bankName,
            String ifscCode,
            String accountHolderName
    ) {
    }
}
