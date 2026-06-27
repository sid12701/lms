package com.bhawana.lms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.bhawana.lms.domain.LoanDelinquencyBucket;
import com.bhawana.lms.domain.LoanRepaymentScheduleInstallment;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LoanDelinquencySupportTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 14);

    private static LoanRepaymentScheduleInstallment installment(LocalDate dueDate, String outstanding) {
        LoanRepaymentScheduleInstallment inst = mock(LoanRepaymentScheduleInstallment.class);
        lenient().when(inst.getDueDate()).thenReturn(dueDate);
        lenient().when(inst.getOutstandingAmount()).thenReturn(new BigDecimal(outstanding));
        return inst;
    }

    @ParameterizedTest
    @CsvSource({
            "0, CURRENT",
            "1, DPD_1_30",
            "30, DPD_1_30",
            "31, DPD_31_60",
            "60, DPD_31_60",
            "61, DPD_61_90",
            "90, DPD_61_90",
            "91, DPD_90_PLUS",
            "365, DPD_90_PLUS"
    })
    void resolveDelinquencyBucketAtBoundaries(int daysPastDue, LoanDelinquencyBucket expected) {
        assertEquals(expected, LoanDelinquencySupport.resolveDelinquencyBucket(daysPastDue));
    }

    @Test
    void daysPastDueIsZeroForFutureDueDate() {
        assertEquals(0, LoanDelinquencySupport.calculateDaysPastDue(
                installment(TODAY.plusDays(5), "1000.00"), TODAY));
    }

    @Test
    void daysPastDueIsZeroOnTheDueDateItself() {
        assertEquals(0, LoanDelinquencySupport.calculateDaysPastDue(
                installment(TODAY, "1000.00"), TODAY));
    }

    @Test
    void daysPastDueCountsCalendarDaysWhenOutstanding() {
        assertEquals(10, LoanDelinquencySupport.calculateDaysPastDue(
                installment(TODAY.minusDays(10), "1000.00"), TODAY));
    }

    @Test
    void daysPastDueIsZeroWhenInstallmentIsFullyPaid() {
        assertEquals(0, LoanDelinquencySupport.calculateDaysPastDue(
                installment(TODAY.minusDays(40), "0.00"), TODAY));
    }

    @Test
    void summarizeReturnsEmptyForNoInstallments() {
        assertTrue(LoanDelinquencySupport.summarize(List.of(), TODAY).isEmpty());
    }

    @Test
    void summarizeAggregatesMaxDpdBucketCountAndOverdueAmount() {
        List<LoanRepaymentScheduleInstallment> installments = List.of(
                installment(TODAY.minusDays(45), "1000.00"),  // 45 dpd, overdue
                installment(TODAY.minusDays(5), "500.00"),    // 5 dpd, overdue
                installment(TODAY.plusDays(30), "750.00"),    // future, not overdue
                installment(TODAY.minusDays(70), "0.00")      // past but paid -> not overdue
        );

        Optional<LoanDelinquencySummary> summary = LoanDelinquencySupport.summarize(installments, TODAY);

        assertTrue(summary.isPresent());
        LoanDelinquencySummary s = summary.get();
        assertEquals(45, s.maxDaysPastDue());
        assertEquals(LoanDelinquencyBucket.DPD_31_60, s.bucket());
        assertEquals(2, s.overdueInstallmentCount());
        assertEquals(new BigDecimal("1500.00"), s.overdueAmount());
    }
}
