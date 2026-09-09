package com.bhawana.lms.service;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.common.util.Strings;
import com.bhawana.lms.common.api.error.BusinessRuleViolationException;
import com.bhawana.lms.domain.Borrower;
import com.bhawana.lms.domain.BorrowerBankDetailsUpdateAudit;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.repo.BorrowerBankDetailsUpdateAuditRepository;
import com.bhawana.lms.repo.BorrowerRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * C06-phase-2: the single audited policy for shared borrower bank fields. Both explicit bank
 * edits and onboarding profile merges route changed bank fields through here, so the mutation
 * and its audit row commit (or roll back) in the caller's transaction — never a split
 * REQUIRES_NEW afterthought. Unchanged (no-op) submissions write nothing: no save, no audit
 * row, no velocity noise. The caller must hold the borrower row lock first.
 */
@Component
public class BorrowerBankUpdatePolicy {

    private static final Logger log = LoggerFactory.getLogger(BorrowerBankUpdatePolicy.class);

    private final BorrowerRepository borrowerRepository;
    private final BorrowerBankDetailsUpdateAuditRepository bankDetailsUpdateAuditRepository;
    private final BorrowerActiveLoanChecker borrowerActiveLoanChecker;
    private final BorrowerBankDetailsProperties properties;
    private final Clock clock;
    private final OpsAlertEmitters opsAlertEmitters;

    public BorrowerBankUpdatePolicy(
            BorrowerRepository borrowerRepository,
            BorrowerBankDetailsUpdateAuditRepository bankDetailsUpdateAuditRepository,
            BorrowerActiveLoanChecker borrowerActiveLoanChecker,
            BorrowerBankDetailsProperties properties,
            Clock clock,
            OpsAlertEmitters opsAlertEmitters
    ) {
        this.borrowerRepository = borrowerRepository;
        this.bankDetailsUpdateAuditRepository = bankDetailsUpdateAuditRepository;
        this.borrowerActiveLoanChecker = borrowerActiveLoanChecker;
        this.properties = properties;
        this.clock = clock;
        this.opsAlertEmitters = opsAlertEmitters;
    }

    /**
     * Normalized no-op check using the same normalization as
     * {@link Borrower#updateBankDetails}: blank becomes null, IFSC is upper-cased
     * ({@link Locale#ROOT} so casing never depends on the default locale).
     */
    public boolean isNoop(
            Borrower borrower,
            String bankAccountNumber,
            String bankName,
            String ifscCode,
            String accountHolderName
    ) {
        return Objects.equals(borrower.getBankAccountNumber(), normalizeOptional(bankAccountNumber))
                && Objects.equals(borrower.getBankName(), normalizeOptional(bankName))
                && Objects.equals(borrower.getIfscCode(), normalizeCoded(ifscCode))
                && Objects.equals(borrower.getAccountHolderName(), normalizeOptional(accountHolderName));
    }

    /**
     * Applies changed bank fields to the already-locked borrower and records the audit row in
     * the caller's transaction. The global in-flight gate runs here — under the caller's
     * borrower lock — so neither explicit edits nor onboarding merges can redirect an
     * instruction another LSP has live. Returns {@code true} when anything changed.
     *
     * <p>A no-op (normalized-equal) submission is an intentional successful no-write: the
     * borrower row, the audit table, the loan event log and velocity state are all left
     * untouched, and the current borrower is returned as-is. The in-flight and caller
     * eligibility gates still apply to identical resubmissions.
     */
    public boolean applyChangedBankDetails(
            Borrower borrower,
            Lsp lsp,
            String bankAccountNumber,
            String bankName,
            String ifscCode,
            String accountHolderName,
            String actorUsername,
            String actorType,
            String clientIp
    ) {
        if (borrowerActiveLoanChecker.hasInFlightDisbursementAcrossAllLsps(borrower.getId())) {
            log.warn(
                    "borrower_bank_details_update_blocked reason=disbursement_in_flight borrowerId={} lspId={}",
                    borrower.getId(),
                    lsp == null ? null : lsp.getId()
            );
            throw new BusinessRuleViolationException(
                    "BANK_DETAILS_LOCKED_DISBURSEMENT_IN_FLIGHT",
                    "Bank details cannot be updated while a disbursement is in progress for this borrower.",
                    Map.of()
            );
        }
        if (isNoop(borrower, bankAccountNumber, bankName, ifscCode, accountHolderName)) {
            return false;
        }
        // Velocity baseline is read BEFORE this change is written, while the borrower lock is
        // held: the admin aggregate runs on its own connection on tenant calls (it cannot see
        // this transaction's uncommitted row) but joins the current transaction on admin calls
        // (it would see the row once written). Counting first and adding this change once keeps
        // the threshold identical on both scopes without double-counting either.
        long committedUpdates = countCommittedUpdates(borrower);
        String previousAccount = borrower.getBankAccountNumber();
        String previousBankName = borrower.getBankName();
        String previousIfsc = borrower.getIfscCode();
        String previousHolder = borrower.getAccountHolderName();

        borrower.updateBankDetails(bankAccountNumber, bankName, ifscCode, accountHolderName);
        Borrower saved = borrowerRepository.save(borrower);
        bankDetailsUpdateAuditRepository.save(new BorrowerBankDetailsUpdateAudit(
                saved,
                lsp,
                actorUsername,
                actorType,
                previousAccount,
                previousBankName,
                previousIfsc,
                previousHolder,
                saved.getBankAccountNumber(),
                saved.getBankName(),
                saved.getIfscCode(),
                saved.getAccountHolderName(),
                clientIp,
                CorrelationIdHolder.get()
        ));
        evaluateVelocityAlert(saved, committedUpdates + 1);
        return true;
    }

    private long countCommittedUpdates(Borrower borrower) {
        Instant since = clock.instant().minus(Duration.ofDays(properties.getVelocityWindowDays()));
        return borrowerActiveLoanChecker.countBankUpdatesAcrossAllLsps(borrower.getId(), since);
    }

    private void evaluateVelocityAlert(Borrower borrower, long updates) {
        if (updates >= properties.getVelocityMaxUpdates()) {
            opsAlertEmitters.emitBorrowerBankDetailsVelocity(borrower, (int) updates);
        }
    }

    private static String normalizeOptional(String value) {
        return Strings.normalizeOptional(value);
    }

    private static String normalizeCoded(String value) {
        String normalized = Strings.normalizeOptional(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }
}
