package com.bhawana.lms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bhawana.lms.common.api.error.BusinessRuleViolationException;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanProductVersion;
import com.bhawana.lms.domain.LoanRepaymentScheduleInstallment;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanPaymentTransactionRepository;
import com.bhawana.lms.repo.LoanRepaymentScheduleInstallmentRepository;
import com.bhawana.lms.service.LoanRepaymentScheduleService.InstallmentDraft;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LoanRepaymentScheduleServiceTest {

    private static final Instant APPROVED_AT = Instant.parse("2026-01-15T10:00:00Z");
    private static final LocalDate APPROVAL_DATE = LocalDate.of(2026, 1, 15);

    @Mock
    private LoanApplicationQueryService loanApplicationQueryService;

    @Mock
    private LoanAccountRepository loanAccountRepository;

    @Mock
    private LoanRepaymentScheduleInstallmentRepository loanRepaymentScheduleInstallmentRepository;

    @Mock
    private LoanPaymentTransactionRepository loanPaymentTransactionRepository;

    @Mock
    private LspValidationAuditService lspValidationAuditService;

    private ScheduleValidationProperties scheduleValidationProperties;
    private LoanRepaymentScheduleService scheduleService;

    @BeforeEach
    void setUp() {
        scheduleValidationProperties = new ScheduleValidationProperties();
        scheduleService = new LoanRepaymentScheduleService(
                loanApplicationQueryService,
                loanAccountRepository,
                loanRepaymentScheduleInstallmentRepository,
                loanPaymentTransactionRepository,
                lspValidationAuditService,
                scheduleValidationProperties
        );
    }

    @Test
    void generateIfAbsentSkipsWhenInstallmentsAlreadyExist() {
        UUID accountId = UUID.randomUUID();
        LoanAccount loanAccount = mock(LoanAccount.class);
        when(loanAccount.getId()).thenReturn(accountId);
        when(loanRepaymentScheduleInstallmentRepository.findByLoanAccount_IdOrderByInstallmentNumberAsc(accountId))
                .thenReturn(List.of(new LoanRepaymentScheduleInstallment(
                        loanAccount,
                        1,
                        java.time.LocalDate.now(),
                        new BigDecimal("50000.00"),
                        new BigDecimal("1000.00"),
                        new BigDecimal("750.00"),
                        new BigDecimal("1750.00"),
                        new BigDecimal("49000.00")
                )));

        scheduleService.generateIfAbsent(loanAccount);

        verify(loanRepaymentScheduleInstallmentRepository, never()).saveAll(any());
    }

    @Test
    void generateIfAbsentUsesBigDecimalEmiAndClosesPrincipalOnFinalInstallment() {
        UUID accountId = UUID.randomUUID();
        BigDecimal principal = new BigDecimal("99999.00");
        int tenureMonths = 18;
        BigDecimal annualRate = new BigDecimal("23.99");
        LoanAccount loanAccount = loanAccount(accountId, principal, tenureMonths, annualRate);

        when(loanRepaymentScheduleInstallmentRepository.findByLoanAccount_IdOrderByInstallmentNumberAsc(accountId))
                .thenReturn(List.of());
        when(loanRepaymentScheduleInstallmentRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        scheduleService.generateIfAbsent(loanAccount);

        ArgumentCaptor<List<LoanRepaymentScheduleInstallment>> captor = ArgumentCaptor.captor();
        verify(loanRepaymentScheduleInstallmentRepository).saveAll(captor.capture());
        List<LoanRepaymentScheduleInstallment> installments = captor.getValue();

        assertEquals(tenureMonths, installments.size());
        BigDecimal monthlyRate = annualRate.divide(BigDecimal.valueOf(1200), 10, RoundingMode.HALF_UP);
        BigDecimal expectedEmi = expectedBigDecimalEmi(principal, monthlyRate, tenureMonths);
        assertEquals(expectedEmi, installments.get(0).getInstallmentAmount());
        assertEquals(0, installments.get(tenureMonths - 1).getClosingPrincipal().compareTo(BigDecimal.ZERO.setScale(2)));
        assertEquals(
                principal.setScale(2, RoundingMode.HALF_UP),
                installments.stream()
                        .map(LoanRepaymentScheduleInstallment::getPrincipalDue)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .setScale(2, RoundingMode.HALF_UP)
        );
    }

    @Test
    void generatedSchedulePassesExtendedValidationUnchanged() {
        UUID lspId = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();
        LoanAccount loanAccount = loanAccount(
                UUID.randomUUID(),
                new BigDecimal("45000.00"),
                12,
                new BigDecimal("18.50")
        );
        stubMutableLoanAccount(lspId, applicationId, loanAccount);
        when(loanRepaymentScheduleInstallmentRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        List<InstallmentDraft> generated = scheduleService.projectGeneratedInstallmentDrafts(loanAccount);

        scheduleService.replaceWithProvidedScheduleForLsp(lspId, applicationId, generated);

        verify(loanRepaymentScheduleInstallmentRepository).saveAll(any());
    }

    @Test
    void rejectsPastFirstDueDate() {
        assertViolationType(
                mutateGenerated(drafts -> drafts.set(0, withDueDate(drafts.get(0), APPROVAL_DATE.minusDays(1)))),
                "SCHEDULE_FIRST_DUE_OUT_OF_WINDOW"
        );
    }

    @Test
    void rejectsFirstDueBeyondWindow() {
        assertViolationType(
                mutateGenerated(drafts -> drafts.set(0, withDueDate(drafts.get(0), APPROVAL_DATE.plusDays(61)))),
                "SCHEDULE_FIRST_DUE_OUT_OF_WINDOW"
        );
    }

    @Test
    void rejectsHundredYearCadenceSpan() {
        assertViolationType(
                mutateGenerated(drafts -> {
                    for (int i = 0; i < drafts.size(); i++) {
                        drafts.set(i, withDueDate(drafts.get(i), APPROVAL_DATE.plusMonths(1).plusYears(i * 8L)));
                    }
                }),
                "SCHEDULE_CADENCE_VIOLATION"
        );
    }

    @Test
    void rejectsCadenceDriftBeyondTolerance() {
        assertViolationType(
                mutateGenerated(drafts -> {
                    LocalDate firstDue = drafts.get(0).dueDate();
                    drafts.set(1, withDueDate(drafts.get(1), firstDue.plusMonths(1).plusDays(8)));
                }),
                "SCHEDULE_CADENCE_VIOLATION"
        );
    }

    @Test
    void acceptsCadenceDriftWithinTolerance() {
        UUID lspId = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();
        LoanAccount loanAccount = loanAccount(
                UUID.randomUUID(),
                new BigDecimal("45000.00"),
                12,
                new BigDecimal("18.50")
        );
        stubMutableLoanAccount(lspId, applicationId, loanAccount);
        when(loanRepaymentScheduleInstallmentRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        List<InstallmentDraft> drafts = new ArrayList<>(scheduleService.projectGeneratedInstallmentDrafts(loanAccount));
        LocalDate firstDue = drafts.get(0).dueDate();
        drafts.set(1, withDueDate(drafts.get(1), firstDue.plusMonths(1).plusDays(6)));

        scheduleService.replaceWithProvidedScheduleForLsp(lspId, applicationId, drafts);

        verify(loanRepaymentScheduleInstallmentRepository).saveAll(any());
    }

    @Test
    void rejectsZeroInterestOnPositiveRateProduct() {
        assertViolationType(
                mutateGenerated(drafts -> {
                    for (int i = 0; i < drafts.size(); i++) {
                        InstallmentDraft row = drafts.get(i);
                        drafts.set(i, new InstallmentDraft(
                                row.installmentNumber(),
                                row.dueDate(),
                                row.openingPrincipal(),
                                row.principalDue(),
                                BigDecimal.ZERO.setScale(2),
                                row.principalDue(),
                                row.closingPrincipal()
                        ));
                    }
                }),
                "SCHEDULE_INTEREST_ROW_MISMATCH"
        );
    }

    @Test
    void rejectsInterestInflatedBeyondRowTolerance() {
        assertViolationType(
                mutateGenerated(drafts -> {
                    InstallmentDraft row = drafts.get(0);
                    BigDecimal inflated = row.interestDue()
                            .multiply(new BigDecimal("1.05"))
                            .setScale(2, RoundingMode.HALF_UP);
                    drafts.set(0, new InstallmentDraft(
                            row.installmentNumber(),
                            row.dueDate(),
                            row.openingPrincipal(),
                            row.principalDue(),
                            inflated,
                            row.principalDue().add(inflated).setScale(2, RoundingMode.HALF_UP),
                            row.closingPrincipal()
                    ));
                }),
                "SCHEDULE_INTEREST_ROW_MISMATCH"
        );
    }

    @Test
    void acceptsInterestWithinRowTolerance() {
        UUID lspId = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();
        LoanAccount loanAccount = loanAccount(
                UUID.randomUUID(),
                new BigDecimal("45000.00"),
                12,
                new BigDecimal("18.50")
        );
        stubMutableLoanAccount(lspId, applicationId, loanAccount);
        when(loanRepaymentScheduleInstallmentRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        List<InstallmentDraft> drafts = new ArrayList<>(scheduleService.projectGeneratedInstallmentDrafts(loanAccount));
        InstallmentDraft row = drafts.get(0);
        BigDecimal nudged = row.interestDue().add(new BigDecimal("5.00")).setScale(2, RoundingMode.HALF_UP);
        drafts.set(0, new InstallmentDraft(
                row.installmentNumber(),
                row.dueDate(),
                row.openingPrincipal(),
                row.principalDue(),
                nudged,
                row.principalDue().add(nudged).setScale(2, RoundingMode.HALF_UP),
                row.closingPrincipal()
        ));

        scheduleService.replaceWithProvidedScheduleForLsp(lspId, applicationId, drafts);

        verify(loanRepaymentScheduleInstallmentRepository).saveAll(any());
    }

    @Test
    void acceptsZeroInterestWhenProductRateIsZero() {
        UUID lspId = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();
        LoanAccount loanAccount = loanAccount(
                UUID.randomUUID(),
                new BigDecimal("12000.00"),
                12,
                BigDecimal.ZERO.setScale(2)
        );
        stubMutableLoanAccount(lspId, applicationId, loanAccount);
        when(loanRepaymentScheduleInstallmentRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        List<InstallmentDraft> generated = scheduleService.projectGeneratedInstallmentDrafts(loanAccount);
        assertTrue(generated.stream().allMatch(row -> row.interestDue().compareTo(BigDecimal.ZERO) == 0));

        scheduleService.replaceWithProvidedScheduleForLsp(lspId, applicationId, generated);

        verify(loanRepaymentScheduleInstallmentRepository).saveAll(any());
    }

    @Test
    void disbursementRevalidationRejectsStoredCadenceViolation() {
        LoanAccount loanAccount = loanAccount(
                UUID.randomUUID(),
                new BigDecimal("45000.00"),
                12,
                new BigDecimal("18.50")
        );
        List<InstallmentDraft> drafts = new ArrayList<>(scheduleService.projectGeneratedInstallmentDrafts(loanAccount));
        for (int i = 0; i < drafts.size(); i++) {
            drafts.set(i, withDueDate(drafts.get(i), APPROVAL_DATE.plusMonths(1).plusYears(i * 8L)));
        }
        List<LoanRepaymentScheduleInstallment> stored = drafts.stream()
                .map(draft -> new LoanRepaymentScheduleInstallment(
                        loanAccount,
                        draft.installmentNumber(),
                        draft.dueDate(),
                        draft.openingPrincipal(),
                        draft.principalDue(),
                        draft.interestDue(),
                        draft.installmentAmount(),
                        draft.closingPrincipal()
                ))
                .toList();
        when(loanRepaymentScheduleInstallmentRepository.findByLoanAccount_IdOrderByInstallmentNumberAsc(loanAccount.getId()))
                .thenReturn(stored);

        BusinessRuleViolationException exception = assertThrows(
                BusinessRuleViolationException.class,
                () -> scheduleService.validatePersistedScheduleForDisbursement(loanAccount)
        );

        assertEquals("DISBURSEMENT_VALIDATION_FAILED", exception.getErrorCode());
        assertEquals("SCHEDULE_CADENCE_VIOLATION", exception.getFieldErrors().get("violationType"));
    }

    private void assertViolationType(List<InstallmentDraft> drafts, String expectedViolationType) {
        UUID lspId = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();
        LoanAccount loanAccount = loanAccount(
                UUID.randomUUID(),
                new BigDecimal("45000.00"),
                12,
                new BigDecimal("18.50")
        );
        stubMutableLoanAccount(lspId, applicationId, loanAccount);

        BusinessRuleViolationException exception = assertThrows(
                BusinessRuleViolationException.class,
                () -> scheduleService.replaceWithProvidedScheduleForLsp(lspId, applicationId, drafts)
        );

        assertEquals("REPAYMENT_SCHEDULE_INVALID", exception.getErrorCode());
        assertEquals(expectedViolationType, exception.getFieldErrors().get("violationType"));
        verify(loanRepaymentScheduleInstallmentRepository, never()).saveAll(any());
    }

    private List<InstallmentDraft> mutateGenerated(java.util.function.Consumer<List<InstallmentDraft>> mutator) {
        LoanAccount loanAccount = loanAccount(
                UUID.randomUUID(),
                new BigDecimal("45000.00"),
                12,
                new BigDecimal("18.50")
        );
        List<InstallmentDraft> drafts = new ArrayList<>(scheduleService.projectGeneratedInstallmentDrafts(loanAccount));
        mutator.accept(drafts);
        return drafts;
    }

    private void stubMutableLoanAccount(UUID lspId, UUID applicationId, LoanAccount loanAccount) {
        LoanApplication application = mock(LoanApplication.class);
        when(loanApplicationQueryService.getApplicationForLsp(lspId, applicationId)).thenReturn(application);
        when(loanAccountRepository.findByLoanApplication_Id(applicationId)).thenReturn(Optional.of(loanAccount));
        when(loanAccount.getStatus()).thenReturn(LoanAccountStatus.PENDING_DISBURSEMENT);
        when(loanPaymentTransactionRepository.existsByLoanAccount_Id(loanAccount.getId())).thenReturn(false);
        lenient().when(loanRepaymentScheduleInstallmentRepository.deleteByLoanAccountId(loanAccount.getId())).thenReturn(0L);
    }

    private static InstallmentDraft withDueDate(InstallmentDraft row, LocalDate dueDate) {
        return new InstallmentDraft(
                row.installmentNumber(),
                dueDate,
                row.openingPrincipal(),
                row.principalDue(),
                row.interestDue(),
                row.installmentAmount(),
                row.closingPrincipal()
        );
    }

    private static LoanAccount loanAccount(UUID accountId, BigDecimal principal, int tenureMonths, BigDecimal annualRate) {
        LoanProductVersion productVersion = mock(LoanProductVersion.class);
        lenient().when(productVersion.getInterestRate()).thenReturn(annualRate);

        LoanAccount loanAccount = mock(LoanAccount.class);
        lenient().when(loanAccount.getId()).thenReturn(accountId);
        when(loanAccount.getPrincipalAmount()).thenReturn(principal);
        when(loanAccount.getTenureMonths()).thenReturn(tenureMonths);
        when(loanAccount.getLoanProductVersion()).thenReturn(productVersion);
        when(loanAccount.getApprovedAt()).thenReturn(APPROVED_AT);
        return loanAccount;
    }

    private static BigDecimal expectedBigDecimalEmi(BigDecimal principal, BigDecimal monthlyRate, int tenureMonths) {
        BigDecimal onePlusRate = BigDecimal.ONE.add(monthlyRate);
        BigDecimal compounded = onePlusRate.pow(tenureMonths, MathContext.DECIMAL64);
        BigDecimal numerator = principal.multiply(monthlyRate).multiply(compounded);
        BigDecimal denominator = compounded.subtract(BigDecimal.ONE);
        return numerator.divide(denominator, 2, RoundingMode.HALF_UP);
    }
}
