package com.bhawana.lms.service;

import com.bhawana.lms.common.money.Money;
import com.bhawana.lms.domain.LoanRepaymentScheduleInstallment;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * Canonical hash of a loan's persisted repayment schedule.
 *
 * <p>The hash is frozen on the disbursement intent at creation and re-validated before
 * submission preparation, so a schedule replacement that commits after the eligibility check
 * cannot silently change the terms the provider call executes against. The canonical form
 * fixes row order, date rendering and amount scale; any added/removed/reordered row or any
 * changed date/amount produces a different hash.
 */
public final class LoanRepaymentScheduleHash {

    private LoanRepaymentScheduleHash() {
    }

    public static String hashDrafts(List<LoanRepaymentScheduleService.InstallmentDraft> drafts) {
        List<LoanRepaymentScheduleService.InstallmentDraft> ordered = new ArrayList<>(drafts);
        ordered.sort(Comparator.comparingInt(LoanRepaymentScheduleService.InstallmentDraft::installmentNumber));
        StringBuilder canonical = new StringBuilder();
        for (LoanRepaymentScheduleService.InstallmentDraft draft : ordered) {
            if (!canonical.isEmpty()) {
                canonical.append(';');
            }
            canonical.append(draft.installmentNumber()).append('|')
                    .append(draft.dueDate()).append('|')
                    .append(amount(draft.openingPrincipal())).append('|')
                    .append(amount(draft.principalDue())).append('|')
                    .append(amount(draft.interestDue())).append('|')
                    .append(amount(draft.installmentAmount())).append('|')
                    .append(amount(draft.closingPrincipal()));
        }
        return sha256Hex(canonical.toString());
    }

    public static String hashEntities(List<LoanRepaymentScheduleInstallment> installments) {
        List<LoanRepaymentScheduleService.InstallmentDraft> drafts = installments.stream()
                .map(installment -> new LoanRepaymentScheduleService.InstallmentDraft(
                        installment.getInstallmentNumber(),
                        installment.getDueDate(),
                        installment.getOpeningPrincipal(),
                        installment.getPrincipalDue(),
                        installment.getInterestDue(),
                        installment.getInstallmentAmount(),
                        installment.getClosingPrincipal()
                ))
                .toList();
        return hashDrafts(drafts);
    }

    private static String amount(BigDecimal value) {
        return Money.scale(value).toPlainString();
    }

    private static String sha256Hex(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required for the repayment schedule hash.", exception);
        }
    }
}
