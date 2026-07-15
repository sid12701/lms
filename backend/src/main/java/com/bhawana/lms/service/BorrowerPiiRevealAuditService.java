package com.bhawana.lms.service;

import com.bhawana.lms.domain.Borrower;
import com.bhawana.lms.domain.BorrowerPiiRevealAudit;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.repo.BorrowerPiiRevealAuditRepository;
import com.bhawana.lms.repo.LspRepository;
import com.bhawana.lms.tenant.AdminScopedTransactionExecutor;
import com.bhawana.lms.tenant.TenantDataAccessContextHolder;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class BorrowerPiiRevealAuditService {

    static final String BANK_DETAILS_REVEALED_FIELDS =
            "bankAccountNumber,ifscCode,accountHolderName,bankName";

    private final BorrowerPiiRevealAuditRepository borrowerPiiRevealAuditRepository;
    private final LspRepository lspRepository;
    private final AdminScopedTransactionExecutor adminScopedTransactionExecutor;

    public BorrowerPiiRevealAuditService(
            BorrowerPiiRevealAuditRepository borrowerPiiRevealAuditRepository,
            LspRepository lspRepository,
            AdminScopedTransactionExecutor adminScopedTransactionExecutor
    ) {
        this.borrowerPiiRevealAuditRepository = borrowerPiiRevealAuditRepository;
        this.lspRepository = lspRepository;
        this.adminScopedTransactionExecutor = adminScopedTransactionExecutor;
    }

    public void recordBankDetailsReveal(
            Borrower borrower,
            UUID lspId,
            String actorUsername,
            String actorType,
            String clientIp,
            String correlationId
    ) {
        adminScopedTransactionExecutor.run(() -> recordBankDetailsRevealAsAdmin(
                borrower,
                lspId,
                actorUsername,
                actorType,
                clientIp,
                correlationId
        ));
    }

    private void recordBankDetailsRevealAsAdmin(
            Borrower borrower,
            UUID lspId,
            String actorUsername,
            String actorType,
            String clientIp,
            String correlationId
    ) {
        TenantDataAccessContextHolder.useAdmin();
        Lsp lsp = lspId == null ? null : lspRepository.findById(lspId).orElse(null);
        borrowerPiiRevealAuditRepository.save(new BorrowerPiiRevealAudit(
                borrower,
                lsp,
                actorUsername,
                actorType,
                BANK_DETAILS_REVEALED_FIELDS,
                clientIp,
                correlationId
        ));
    }
}
