package com.bhawana.lms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanProduct;
import com.bhawana.lms.domain.LoanRepaymentScheduleInstallment;
import com.bhawana.lms.repo.LoanPaymentTransactionRepository;
import com.bhawana.lms.repo.LoanRepaymentScheduleInstallmentRepository;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LoanRepaymentScheduleServiceTest {

    @Mock
    private LoanApplicationQueryService loanApplicationQueryService;

    @Mock
    private LoanApplicationServicingReadService loanApplicationServicingReadService;

    @Mock
    private LoanRepaymentScheduleInstallmentRepository loanRepaymentScheduleInstallmentRepository;

    @Mock
    private LoanPaymentTransactionRepository loanPaymentTransactionRepository;

    @Mock
    private OpsAlertEmitters opsAlertEmitters;

    private LoanRepaymentScheduleService scheduleService;

    @BeforeEach
    void setUp() {
        scheduleService = new LoanRepaymentScheduleService(
                loanApplicationQueryService,
                loanApplicationServicingReadService,
                loanRepaymentScheduleInstallmentRepository,
                loanPaymentTransactionRepository,
                opsAlertEmitters
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

    private static LoanAccount loanAccount(UUID accountId, BigDecimal principal, int tenureMonths, BigDecimal annualRate) {
        LoanProduct product = mock(LoanProduct.class);
        lenient().when(product.getInterestRate()).thenReturn(annualRate);

        LoanAccount loanAccount = mock(LoanAccount.class);
        lenient().when(loanAccount.getId()).thenReturn(accountId);
        when(loanAccount.getPrincipalAmount()).thenReturn(principal);
        when(loanAccount.getTenureMonths()).thenReturn(tenureMonths);
        when(loanAccount.getLoanProduct()).thenReturn(product);
        when(loanAccount.getApprovedAt()).thenReturn(Instant.parse("2026-01-15T10:00:00Z"));
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
