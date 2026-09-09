package com.bhawana.lms.service;

import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.repo.BorrowerBankDetailsUpdateAuditRepository;
import com.bhawana.lms.repo.DisbursementIntentRepository;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.tenant.AdminScopedTransactionExecutor;
import com.bhawana.lms.tenant.TenantAccessContext;
import com.bhawana.lms.tenant.TenantDataAccessContextHolder;
import com.bhawana.lms.tenant.TenantDataAccessMode;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

@Service
public class BorrowerActiveLoanChecker {

    // Cross-LSP dedup must see loans owned by any LSP, so these lookups always
    // run on the admin datasource.
    private static final Set<LoanAccountStatus> OPEN_STATUSES = EnumSet.of(
            LoanAccountStatus.PENDING_DISBURSEMENT,
            LoanAccountStatus.DISBURSEMENT_REQUESTED,
            LoanAccountStatus.DISBURSED,
            LoanAccountStatus.DISBURSEMENT_PENDING_RECONCILIATION
    );

    /**
     * C06-phase-2: cross-LSP disbursement gate. REQUESTED (submitted, awaiting verdict),
     * PENDING_RECONCILIATION (parked) and any live intent (CREATED/REQUESTED/UNKNOWN) mean
     * the shared bank instruction is frozen — a tenant-scoped lookup cannot see another
     * LSP's accounts, so this narrow boolean read runs on the admin datasource like the
     * open-loan dedup above. Read-only; never a broad admin write.
     */
    private static final Set<LoanAccountStatus> IN_FLIGHT_STATUSES = EnumSet.of(
            LoanAccountStatus.DISBURSEMENT_REQUESTED,
            LoanAccountStatus.DISBURSEMENT_PENDING_RECONCILIATION
    );

    private final LoanAccountRepository loanAccountRepository;
    private final DisbursementIntentRepository disbursementIntentRepository;
    private final BorrowerBankDetailsUpdateAuditRepository bankDetailsUpdateAuditRepository;
    private final AdminScopedTransactionExecutor adminScopedTransactionExecutor;

    public BorrowerActiveLoanChecker(
            LoanAccountRepository loanAccountRepository,
            DisbursementIntentRepository disbursementIntentRepository,
            BorrowerBankDetailsUpdateAuditRepository bankDetailsUpdateAuditRepository,
            AdminScopedTransactionExecutor adminScopedTransactionExecutor
    ) {
        this.loanAccountRepository = loanAccountRepository;
        this.disbursementIntentRepository = disbursementIntentRepository;
        this.bankDetailsUpdateAuditRepository = bankDetailsUpdateAuditRepository;
        this.adminScopedTransactionExecutor = adminScopedTransactionExecutor;
    }

    public static Set<LoanAccountStatus> openStatuses() {
        return OPEN_STATUSES;
    }

    public boolean hasOpenLoanAcrossAllLsps(UUID borrowerId) {
        return readAcrossAllLsps(
                () -> loanAccountRepository.existsByBorrower_IdAndStatusIn(borrowerId, OPEN_STATUSES));
    }

    public List<LoanAccount> findOpenLoansAcrossAllLsps(UUID borrowerId) {
        return readAcrossAllLsps(
                () -> loanAccountRepository.findByBorrower_IdAndStatusIn(borrowerId, OPEN_STATUSES));
    }

    public boolean hasInFlightDisbursementAcrossAllLsps(UUID borrowerId) {
        return readAcrossAllLsps(() ->
                loanAccountRepository.existsByBorrower_IdAndStatusIn(borrowerId, IN_FLIGHT_STATUSES)
                        || disbursementIntentRepository.existsLiveIntentByBorrowerId(borrowerId));
    }

    /**
     * C06-phase-2: global bank-update velocity aggregate. The tenant connection only sees its
     * own audit rows (V122), which would silently narrow the pre-existing global velocity
     * semantics — so this narrow count runs on the admin datasource. Read-only aggregate;
     * the audit writes themselves stay in the caller's transaction.
     */
    public long countBankUpdatesAcrossAllLsps(UUID borrowerId, Instant since) {
        return readAcrossAllLsps(() ->
                bankDetailsUpdateAuditRepository.countByBorrower_IdAndCreatedAtAfter(borrowerId, since));
    }

    /**
     * Run a cross-LSP read on the admin datasource. When the caller is already inside an
     * admin-scoped transaction (e.g. admin-scoped onboarding), the read joins that transaction
     * instead of opening a second admin connection via {@code REQUIRES_NEW} — nesting a second
     * connection per request would exhaust the pool under concurrent onboarding. Otherwise it
     * flips to admin scope in a fresh transaction so a tenant-bound caller continues afterwards.
     */
    private <T> T readAcrossAllLsps(Supplier<T> read) {
        TenantAccessContext current = TenantDataAccessContextHolder.snapshot();
        if (current != null && current.mode() == TenantDataAccessMode.ADMIN) {
            return read.get();
        }
        return adminScopedTransactionExecutor.call(read);
    }
}
