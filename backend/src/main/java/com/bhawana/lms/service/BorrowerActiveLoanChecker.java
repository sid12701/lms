package com.bhawana.lms.service;

import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.tenant.TenantDataAccessContextHolder;
import com.bhawana.lms.tenant.TenantDataAccessMode;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BorrowerActiveLoanChecker {

    // Cross-LSP dedup must see loans owned by any LSP, so this lookup always
    // runs on the admin datasource. We flip modes inside a REQUIRES_NEW
    // boundary so the caller's tenant-bound transaction continues afterward.
    private static final Set<LoanAccountStatus> OPEN_STATUSES = EnumSet.of(
            LoanAccountStatus.PENDING_DISBURSEMENT,
            LoanAccountStatus.DISBURSEMENT_REQUESTED,
            LoanAccountStatus.DISBURSED,
            LoanAccountStatus.DISBURSEMENT_PENDING_RECONCILIATION
    );

    private final LoanAccountRepository loanAccountRepository;

    public BorrowerActiveLoanChecker(LoanAccountRepository loanAccountRepository) {
        this.loanAccountRepository = loanAccountRepository;
    }

    public static Set<LoanAccountStatus> openStatuses() {
        return OPEN_STATUSES;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean hasOpenLoanAcrossAllLsps(UUID borrowerId) {
        TenantDataAccessMode previousMode = TenantDataAccessContextHolder.getMode();
        UUID previousLspId = TenantDataAccessContextHolder.getCurrentLspId();
        TenantDataAccessContextHolder.useAdmin();
        try {
            return loanAccountRepository.existsByBorrower_IdAndStatusIn(borrowerId, OPEN_STATUSES);
        } finally {
            restoreContext(previousMode, previousLspId);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public List<LoanAccount> findOpenLoansAcrossAllLsps(UUID borrowerId) {
        TenantDataAccessMode previousMode = TenantDataAccessContextHolder.getMode();
        UUID previousLspId = TenantDataAccessContextHolder.getCurrentLspId();
        TenantDataAccessContextHolder.useAdmin();
        try {
            return loanAccountRepository.findByBorrower_IdAndStatusIn(borrowerId, OPEN_STATUSES);
        } finally {
            restoreContext(previousMode, previousLspId);
        }
    }

    private static void restoreContext(TenantDataAccessMode previousMode, UUID previousLspId) {
        if (previousMode == TenantDataAccessMode.TENANT && previousLspId != null) {
            TenantDataAccessContextHolder.useTenant(previousLspId);
        } else {
            TenantDataAccessContextHolder.useAdmin();
        }
    }
}
