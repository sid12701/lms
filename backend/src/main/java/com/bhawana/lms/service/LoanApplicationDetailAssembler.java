package com.bhawana.lms.service;

import com.bhawana.lms.common.money.Money;
import com.bhawana.lms.common.web.ResourceNotFoundException;
import com.bhawana.lms.config.BusinessCalendar;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklist;
import com.bhawana.lms.domain.LoanRepaymentScheduleInstallment;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationDocumentChecklistRepository;
import com.bhawana.lms.repo.LoanApplicationIntakeAuditRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanApplicationStatusTransitionRepository;
import com.bhawana.lms.repo.LoanRepaymentScheduleInstallmentRepository;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoanApplicationDetailAssembler {

    private final LoanApplicationRepository loanApplicationRepository;
    private final LoanAccountRepository loanAccountRepository;
    private final LoanRepaymentScheduleInstallmentRepository loanRepaymentScheduleInstallmentRepository;
    private final LoanApplicationIntakeAuditRepository loanApplicationIntakeAuditRepository;
    private final LoanApplicationStatusTransitionRepository loanApplicationStatusTransitionRepository;
    private final LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository;
    private final BusinessCalendar businessCalendar;

    public LoanApplicationDetailAssembler(
            LoanApplicationRepository loanApplicationRepository,
            LoanAccountRepository loanAccountRepository,
            LoanRepaymentScheduleInstallmentRepository loanRepaymentScheduleInstallmentRepository,
            LoanApplicationIntakeAuditRepository loanApplicationIntakeAuditRepository,
            LoanApplicationStatusTransitionRepository loanApplicationStatusTransitionRepository,
            LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository,
            BusinessCalendar businessCalendar
    ) {
        this.loanApplicationRepository = loanApplicationRepository;
        this.loanAccountRepository = loanAccountRepository;
        this.loanRepaymentScheduleInstallmentRepository = loanRepaymentScheduleInstallmentRepository;
        this.loanApplicationIntakeAuditRepository = loanApplicationIntakeAuditRepository;
        this.loanApplicationStatusTransitionRepository = loanApplicationStatusTransitionRepository;
        this.loanApplicationDocumentChecklistRepository = loanApplicationDocumentChecklistRepository;
        this.businessCalendar = businessCalendar;
    }

    @Transactional(readOnly = true)
    public LoanApplicationDetailView getDetail(UUID applicationId) {
        LoanApplication application = loanApplicationRepository.findDetailedById(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException("Unknown loan application id: " + applicationId));

        Optional<LoanAccount> loanAccount = loanAccountRepository.findDetailedByLoanApplication_Id(applicationId);
        List<LoanRepaymentScheduleInstallment> installments = loanAccount
                .map(account -> loanRepaymentScheduleInstallmentRepository
                        .findByLoanAccount_IdOrderByInstallmentNumberAsc(account.getId()))
                .orElse(List.of());

        Optional<LoanApplicationService.LoanRepaymentScheduleSummary> repaymentScheduleSummary =
                buildRepaymentScheduleSummary(installments);
        Optional<LoanApplicationService.LoanDelinquencySummary> delinquencySummary =
                buildDelinquencySummary(installments);
        Optional<LoanApplicationService.LoanApplicationLastActivity> lastActivity =
                resolveLatestActivity(application, applicationId);

        return new LoanApplicationDetailView(
                application,
                loanAccount,
                lastActivity,
                repaymentScheduleSummary,
                delinquencySummary
        );
    }

    private Optional<LoanApplicationService.LoanApplicationLastActivity> resolveLatestActivity(
            LoanApplication application,
            UUID applicationId
    ) {
        Stream<ActivityCandidate> candidates = Stream.of(
                loanApplicationIntakeAuditRepository.findTopByLoanApplication_IdOrderByCreatedAtDesc(applicationId)
                        .map(audit -> new ActivityCandidate(
                                0,
                                new LoanApplicationService.LoanApplicationLastActivity(
                                        "INTAKE_CAPTURED",
                                        audit.getActorUsername(),
                                        "Application captured from " + application.getSourceChannel(),
                                        "External loan id " + application.getExternalLoanId(),
                                        audit.getCorrelationId(),
                                        audit.getCreatedAt()
                                )
                        )),
                loanApplicationStatusTransitionRepository.findTopByLoanApplication_IdOrderByCreatedAtDesc(applicationId)
                        .map(transition -> new ActivityCandidate(
                                1,
                                new LoanApplicationService.LoanApplicationLastActivity(
                                        "STATUS_TRANSITION",
                                        transition.getActorUsername(),
                                        "Moved from " + transition.getFromStatus().name() + " to " + transition.getToStatus().name(),
                                        transition.getReasonCode() == null
                                                ? transition.getNote()
                                                : transition.getNote() + " [" + transition.getReasonCode().name() + "]",
                                        transition.getCorrelationId(),
                                        transition.getCreatedAt()
                                )
                        )),
                loanApplicationDocumentChecklistRepository.findTopByLoanApplication_IdOrderByUpdatedAtDesc(applicationId)
                        .filter(this::hasMeaningfulDocumentActivity)
                        .map(item -> new ActivityCandidate(
                                2,
                                new LoanApplicationService.LoanApplicationLastActivity(
                                        "DOCUMENT_REVIEW_UPDATED",
                                        item.getUpdatedByUsername(),
                                        "Updated " + item.getDocumentType().getDisplayName()
                                                + " to " + item.getStatus().name(),
                                        item.getNote(),
                                        null,
                                        item.getUpdatedAt()
                                )
                        ))
        ).flatMap(Optional::stream);

        return candidates.max(Comparator
                        .comparing((ActivityCandidate candidate) -> candidate.activity().occurredAt())
                        .thenComparingInt(ActivityCandidate::priority))
                .map(ActivityCandidate::activity);
    }

    private Optional<LoanApplicationService.LoanRepaymentScheduleSummary> buildRepaymentScheduleSummary(
            List<LoanRepaymentScheduleInstallment> installments
    ) {
        if (installments.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new LoanApplicationService.LoanRepaymentScheduleSummary(
                installments.size(),
                installments.getFirst().getInstallmentAmount(),
                installments.getFirst().getDueDate(),
                installments.getLast().getDueDate()
        ));
    }

    private Optional<LoanApplicationService.LoanDelinquencySummary> buildDelinquencySummary(
            List<LoanRepaymentScheduleInstallment> installments
    ) {
        if (installments.isEmpty()) {
            return Optional.empty();
        }
        var today = businessCalendar.today();
        int maxDaysPastDue = installments.stream()
                .mapToInt(installment -> LoanApplicationService.calculateDaysPastDue(installment, today))
                .max()
                .orElse(0);
        BigDecimal overdueAmount = installments.stream()
                .filter(installment -> LoanApplicationService.calculateDaysPastDue(installment, today) > 0)
                .map(LoanRepaymentScheduleInstallment::getOutstandingAmount)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        long overdueInstallmentCount = installments.stream()
                .filter(installment -> LoanApplicationService.calculateDaysPastDue(installment, today) > 0)
                .count();
        return Optional.of(new LoanApplicationService.LoanDelinquencySummary(
                maxDaysPastDue,
                LoanApplicationService.resolveDelinquencyBucket(maxDaysPastDue),
                Math.toIntExact(overdueInstallmentCount),
                Money.scale(overdueAmount)
        ));
    }

    private boolean hasMeaningfulDocumentActivity(LoanApplicationDocumentChecklist checklistItem) {
        return checklistItem.getUpdatedAt() != null
                && checklistItem.getCreatedAt() != null
                && checklistItem.getUpdatedAt().isAfter(checklistItem.getCreatedAt());
    }

    private record ActivityCandidate(int priority, LoanApplicationService.LoanApplicationLastActivity activity) {
    }

    public record LoanApplicationDetailView(
            LoanApplication application,
            Optional<LoanAccount> loanAccount,
            Optional<LoanApplicationService.LoanApplicationLastActivity> lastActivity,
            Optional<LoanApplicationService.LoanRepaymentScheduleSummary> repaymentScheduleSummary,
            Optional<LoanApplicationService.LoanDelinquencySummary> delinquencySummary
    ) {
    }
}
