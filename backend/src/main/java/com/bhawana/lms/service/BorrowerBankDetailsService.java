package com.bhawana.lms.service;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.common.api.error.BusinessRuleViolationException;
import com.bhawana.lms.common.api.error.ResourceNotFoundException;
import com.bhawana.lms.common.pii.BankAccountMasking;
import com.bhawana.lms.domain.Borrower;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanDisbursementBankMismatchLog;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LoanEventType;
import com.bhawana.lms.repo.BorrowerRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanDisbursementBankMismatchLogRepository;
import com.bhawana.lms.repo.LspRepository;
import com.bhawana.lms.tenant.AdminScopedTransactionExecutor;
import com.bhawana.lms.tenant.ScopePreservingTransactionExecutor;
import com.bhawana.lms.tenant.TenantDataAccessContextHolder;
import jakarta.persistence.EntityManager;
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

    private static final Set<LoanApplicationStatus> PRE_DISBURSAL_APPLICATION_STATUSES = EnumSet.of(
            LoanApplicationStatus.INITIALIZED,
            LoanApplicationStatus.AWAITING_APPROVAL,
            LoanApplicationStatus.APPROVED_PENDING_DISBURSAL,
            LoanApplicationStatus.DISBURSEMENT_RETRY
    );

    private final BorrowerRepository borrowerRepository;
    private final LspRepository lspRepository;
    private final LoanApplicationRepository loanApplicationRepository;
    private final LoanDisbursementBankMismatchLogRepository bankMismatchLogRepository;
    private final LoanEventLog loanEventLog;
    private final OpsAlertEmitters opsAlertEmitters;
    private final BorrowerBankDetailsProperties properties;
    private final Clock clock;
    private final BorrowerBankUpdatePolicy bankUpdatePolicy;
    private final EntityManager entityManager;
    private final AdminScopedTransactionExecutor adminScopedTransactionExecutor;
    private final ScopePreservingTransactionExecutor scopePreservingTransactionExecutor;

    public BorrowerBankDetailsService(
            BorrowerRepository borrowerRepository,
            LspRepository lspRepository,
            LoanApplicationRepository loanApplicationRepository,
            LoanDisbursementBankMismatchLogRepository bankMismatchLogRepository,
            LoanEventLog loanEventLog,
            OpsAlertEmitters opsAlertEmitters,
            BorrowerBankDetailsProperties properties,
            Clock clock,
            BorrowerBankUpdatePolicy bankUpdatePolicy,
            EntityManager entityManager,
            AdminScopedTransactionExecutor adminScopedTransactionExecutor,
            ScopePreservingTransactionExecutor scopePreservingTransactionExecutor
    ) {
        this.borrowerRepository = borrowerRepository;
        this.lspRepository = lspRepository;
        this.loanApplicationRepository = loanApplicationRepository;
        this.bankMismatchLogRepository = bankMismatchLogRepository;
        this.loanEventLog = loanEventLog;
        this.opsAlertEmitters = opsAlertEmitters;
        this.properties = properties;
        this.clock = clock;
        this.bankUpdatePolicy = bankUpdatePolicy;
        this.entityManager = entityManager;
        this.adminScopedTransactionExecutor = adminScopedTransactionExecutor;
        this.scopePreservingTransactionExecutor = scopePreservingTransactionExecutor;
    }

    @Transactional(readOnly = true)
    public Borrower getBorrower(UUID borrowerId) {
        TenantDataAccessContextHolder.useAdmin();
        return borrowerRepository.findById(borrowerId)
                .orElseThrow(() -> new ResourceNotFoundException("Unknown borrower id: " + borrowerId));
    }

    public Borrower getBorrowerForLsp(UUID lspId, UUID borrowerId) {
        return scopePreservingTransactionExecutor.call(() -> loadBorrowerForLsp(lspId, borrowerId));
    }

    public Borrower updateBankDetailsForLsp(
            UUID lspId,
            UUID borrowerId,
            BorrowerBankDetailsCommand command,
            String actorUsername,
            String clientIp
    ) {
        return scopePreservingTransactionExecutor.call(() -> updateBankDetails(
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
        return adminScopedTransactionExecutor.call(() -> updateBankDetailsAsAdmin(
                borrowerId,
                command,
                actorUsername,
                actorType,
                clientIp
        ));
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
        Borrower borrower = borrowerRepository.findById(borrowerId)
                .orElseThrow(() -> new ResourceNotFoundException("Unknown borrower id: " + borrowerId));
        if (!borrower.hasVisibilityFor(lspId)) {
            throw new ResourceNotFoundException("Unknown borrower id: " + borrowerId);
        }
        return borrower;
    }

    private Borrower updateBankDetailsAsAdmin(
            UUID borrowerId,
            BorrowerBankDetailsCommand command,
            String actorUsername,
            String actorType,
            String clientIp
    ) {
        TenantDataAccessContextHolder.useAdmin();
        return updateBankDetails(
                null,
                borrowerId,
                command,
                actorUsername,
                actorType,
                clientIp
        );
    }

    private Borrower updateBankDetails(
            UUID lspId,
            UUID borrowerId,
            BorrowerBankDetailsCommand command,
            String actorUsername,
            String actorType,
            String clientIp
    ) {
        // Borrower-first order: the borrower row lock comes before every
        // application/account/intent touch, and the entity is refreshed after the wait so a
        // cached copy can never overwrite a concurrently committed instruction.
        Borrower borrower = borrowerRepository.findByIdForUpdate(borrowerId)
                .orElseThrow(() -> new ResourceNotFoundException("Unknown borrower id: " + borrowerId));
        entityManager.refresh(borrower);
        if (lspId != null && !borrower.hasVisibilityFor(lspId)) {
            throw new ResourceNotFoundException("Unknown borrower id: " + borrowerId);
        }

        assertBankDetailsUpdatable(lspId, borrowerId);

        String previousAccount = borrower.getBankAccountNumber();
        String previousIfsc = borrower.getIfscCode();

        Lsp lsp = lspId == null ? null : lspRepository.findById(lspId).orElse(null);
        // Shared audited policy in the caller's transaction: mutation and audit commit or
        // roll back together (V122 grants the tenant role its narrow audit access). A no-op
        // resubmission writes nothing — no save, no audit row, no event.
        boolean changed = bankUpdatePolicy.applyChangedBankDetails(
                borrower,
                lsp,
                command.bankAccountNumber(),
                command.bankName(),
                command.ifscCode(),
                command.accountHolderName(),
                actorUsername,
                actorType,
                clientIp
        );
        if (!changed) {
            return borrower;
        }

        if (lsp != null) {
            UUID loanApplicationId = loanApplicationRepository
                    .findTopByBorrower_IdAndLsp_IdOrderByCreatedAtDesc(borrowerId, lspId)
                    .map(LoanApplication::getId)
                    .orElse(null);
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            payload.put("borrowerId", borrowerId);
            // Full and unmasked: the loan event log is one schema for every LSP, carrying the
            // complete loan and borrower data (ADR 0007 as clarified by spec 003). Minimisation
            // is a pre-production review, not a per-payload decision taken here.
            payload.put("bankAccountNumber", borrower.getBankAccountNumber());
            payload.put("bankName", borrower.getBankName());
            payload.put("ifscCode", borrower.getIfscCode());
            payload.put("accountHolderName", borrower.getAccountHolderName());
            payload.put("previousBankAccountNumber", previousAccount);
            payload.put("previousIfscCode", previousIfsc);
            loanEventLog.append(
                    lsp,
                    LoanEventType.BORROWER_BANK_DETAILS_UPDATED,
                    "BORROWER",
                    borrowerId.toString(),
                    loanApplicationId,
                    payload
            );
        }

        return borrower;
    }

    /**
     * LSP updates require a pre-disbursal application of their own for the borrower; admin
     * updates bypass that gate. The cross-LSP disbursement freeze lives in the shared
     * {@link BorrowerBankUpdatePolicy}, which runs under the borrower's row lock.
     */
    private void assertBankDetailsUpdatable(UUID lspId, UUID borrowerId) {
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

    public record BorrowerBankDetailsCommand(
            String bankAccountNumber,
            String bankName,
            String ifscCode,
            String accountHolderName
    ) {
    }
}
