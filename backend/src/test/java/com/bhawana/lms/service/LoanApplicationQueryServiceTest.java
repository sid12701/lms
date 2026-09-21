package com.bhawana.lms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bhawana.lms.common.api.PagedResult;
import com.bhawana.lms.config.BusinessCalendar;
import com.bhawana.lms.config.TimeConfig;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.repo.LoanApplicationReadRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * M09 — the disbursal-date filter receives business calendar dates, so the
 * instant boundaries handed to the repository must be drawn in the business
 * zone (Asia/Kolkata). A UTC boundary would bucket 00:00–05:30 IST disbursals
 * into the previous day.
 */
@ExtendWith(MockitoExtension.class)
class LoanApplicationQueryServiceTest {

    @Mock
    private LoanApplicationReadRepository loanApplicationReadRepository;

    @Mock
    private LoanApplicationRepository loanApplicationRepository;

    private LoanApplicationQueryService service;

    @BeforeEach
    void setUp() {
        service = new LoanApplicationQueryService(
                loanApplicationReadRepository,
                loanApplicationRepository,
                new BusinessCalendar(Clock.system(TimeConfig.BUSINESS_ZONE))
        );
    }

    @Test
    void disbursalDateFilterMapsToBusinessDayInstantBoundaries() {
        stubFindApplications();

        service.listApplicationsPage(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                LocalDate.of(2026, 3, 11),
                LocalDate.of(2026, 3, 11),
                0,
                50,
                false
        );

        // 2026-03-11 in Asia/Kolkata spans [2026-03-10T18:30Z, 2026-03-11T18:30Z);
        // UTC boundaries would have been [2026-03-11T00:00Z, 2026-03-12T00:00Z).
        assertEquals(Instant.parse("2026-03-10T18:30:00Z"), capturedFrom());
        assertEquals(Instant.parse("2026-03-11T18:30:00Z"), capturedTo());
    }

    @Test
    void disbursalDateRangeSpansBothBusinessDays() {
        stubFindApplications();

        service.listApplicationsPage(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 3, 31),
                0,
                50,
                false
        );

        assertEquals(Instant.parse("2026-02-28T18:30:00Z"), capturedFrom());
        assertEquals(Instant.parse("2026-03-31T18:30:00Z"), capturedTo());
    }

    @Test
    void absentDisbursalDatesPassNullBounds() {
        stubFindApplications();

        service.listApplicationsPage(
                null, null, null, null, null, null, null, null, null, 0, 50, false);

        assertEquals(null, capturedFrom());
        assertEquals(null, capturedTo());
    }

    private void stubFindApplications() {
        when(loanApplicationReadRepository.findApplications(
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        any(),
                        any(),
                        eq(true),
                        anyInt(),
                        anyInt(),
                        anyBoolean()))
                .thenReturn(new PagedResult<LoanApplication>(List.of(), 0, 0, 50));
    }

    private Instant capturedFrom() {
        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        verify(loanApplicationReadRepository).findApplications(
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                captor.capture(), any(), eq(true), anyInt(), anyInt(), anyBoolean());
        return captor.getValue();
    }

    private Instant capturedTo() {
        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        verify(loanApplicationReadRepository).findApplications(
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                any(), captor.capture(), eq(true), anyInt(), anyInt(), anyBoolean());
        return captor.getValue();
    }
}
