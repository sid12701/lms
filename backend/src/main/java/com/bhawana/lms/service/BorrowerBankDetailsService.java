package com.bhawana.lms.service;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.common.api.error.ResourceNotFoundException;
import com.bhawana.lms.domain.Borrower;
import com.bhawana.lms.domain.BorrowerBankDetailsUpdateAudit;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanDisbursementBankMismatchLog;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.WebhookEventType;
import com.bhawana.lms.repo.BorrowerBankDetailsUpdateAuditRepository;
import com.bhawana.lms.repo.BorrowerRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanDisbursementBankMismatchLogRepository;
import com.bhawana.lms.repo.LspRepository;
import com.bhawana.lms.tenant.AdminScopedTransactionExecutor;
import com.bhawana.lms.tenant.TenantDataAccessContextHolder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BorrowerBankDetailsService {

    private final BorrowerRepository borrowerRepository;
    private final LspRepository lspRepository;
    private final LoanApplicationRepository loanApplicationRepository;
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
            details.put("submittedBankAccountNumber", submittedBankAccountNumber);
            details.put("submittedIfscCode", submittedIfscCode);
            details.put("onFileBankAccountNumber", application.getBorrower().getBankAccountNumber());
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
            payload.put("bankAccountNumber", savedBorrower.getBankAccountNumber());
            payload.put("bankName", savedBorrower.getBankName());
            payload.put("ifscCode", savedBorrower.getIfscCode());
            payload.put("accountHolderName", savedBorrower.getAccountHolderName());
            payload.put("previousBankAccountNumber", previousAccount);
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
