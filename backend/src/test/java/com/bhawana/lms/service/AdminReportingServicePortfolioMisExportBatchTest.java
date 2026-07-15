package com.bhawana.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bhawana.lms.config.BusinessCalendar;
import com.bhawana.lms.domain.Borrower;
import com.bhawana.lms.domain.BorrowerProfile;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanProduct;
import com.bhawana.lms.domain.LoanProductStatus;
import com.bhawana.lms.domain.LoanProductVersion;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.repo.LoanForeclosureQuoteRepository;
import com.bhawana.lms.repo.LoanRepaymentScheduleInstallmentRepository;
import com.bhawana.lms.repo.LspRepository;
import com.bhawana.lms.repo.PortfolioMisReadRepository;
import com.bhawana.lms.support.LoanProductVersionTestSupport;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminReportingServicePortfolioMisExportBatchTest {

    @Test
    void exportUsesThreeBatchesForTwoThousandFiveHundredAccounts() {
        PortfolioMisReadRepository repository = mock(PortfolioMisReadRepository.class);
        LoanRepaymentScheduleInstallmentRepository installmentRepository = mock(LoanRepaymentScheduleInstallmentRepository.class);
        LoanForeclosureQuoteRepository foreclosureQuoteRepository = mock(LoanForeclosureQuoteRepository.class);
        LspRepository lspRepositoryMock = mock(LspRepository.class);
        BusinessCalendar calendar = mock(BusinessCalendar.class);

        when(calendar.today()).thenReturn(LocalDate.of(2026, 7, 6));
        when(repository.findMaxInstallmentCountForExport(isNull(), isNull(), isNull())).thenReturn(0);

        List<UUID> batchOne = ids(1, 1000);
        List<UUID> batchTwo = ids(1001, 1000);
        List<UUID> batchThree = ids(2001, 500);

        when(repository.findAccountIdsForExportBatch(isNull(), isNull(), isNull(), isNull(), eq(1000)))
                .thenReturn(batchOne);
        when(repository.findAccountIdsForExportBatch(isNull(), isNull(), isNull(), eq(batchOne.getLast()), eq(1000)))
                .thenReturn(batchTwo);
        when(repository.findAccountIdsForExportBatch(isNull(), isNull(), isNull(), eq(batchTwo.getLast()), eq(1000)))
                .thenReturn(batchThree);

        when(repository.findAccountsByIds(any())).thenAnswer(invocation -> {
            List<UUID> accountIds = invocation.getArgument(0);
            return accountIds.stream().map(this::minimalAccount).toList();
        });
        when(installmentRepository.findByLoanAccount_IdIn(any())).thenReturn(List.of());
        when(foreclosureQuoteRepository.findByStatusAndLoanAccount_IdIn(any(), any())).thenReturn(List.of());

        AdminReportingService service = new AdminReportingService(
                repository,
                installmentRepository,
                foreclosureQuoteRepository,
                lspRepositoryMock,
                calendar
        );

        AdminReportingService.GeneratedReport report = service.generatePortfolioMisCsv(null, null, null);
        String csv = new String(report.content(), StandardCharsets.UTF_8);
        long dataRows = csv.lines().skip(1).count();

        assertThat(dataRows).isEqualTo(2500);

        ArgumentCaptor<UUID> lastIdCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(repository, atLeastOnce())
                .findAccountIdsForExportBatch(isNull(), isNull(), isNull(), lastIdCaptor.capture(), eq(1000));
        assertThat(lastIdCaptor.getAllValues())
                .containsExactly(null, batchOne.getLast(), batchTwo.getLast());
        verify(repository).findAccountsByIds(batchOne);
        verify(repository).findAccountsByIds(batchTwo);
        verify(repository).findAccountsByIds(batchThree);
    }

    private List<UUID> ids(int startInclusive, int count) {
        List<UUID> accountIds = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            accountIds.add(new UUID(0, startInclusive + index));
        }
        return accountIds;
    }

    private LoanAccount minimalAccount(UUID id) {
        Lsp lsp = new Lsp("LSP", "LSP Name", LspStatus.ACTIVE);
        LoanProduct product = new LoanProduct(
                "PROD",
                "Product",
                new BigDecimal("1000.00"),
                new BigDecimal("100000.00"),
                new BigDecimal("12.00"),
                new BigDecimal("1.00"),
                6,
                12,
                LoanProductStatus.ACTIVE
        );
        LoanProductVersion version = LoanProductVersionTestSupport.versionOne(product);
        Borrower borrower = new Borrower(BorrowerProfile.builder()
                        .fullName("Borrower " + id)
                        .panNumber("ABCDE1234F")
                        .mobileNumber("9000000000")
                        .emailAddress("borrower@example.com")
                        .dateOfBirth(LocalDate.of(1990, 1, 1))
                        .addressCity("Mumbai")
                        .addressState("Maharashtra")
                        .employmentStatus("SALARIED")
                        .monthlyIncome(new BigDecimal("50000.00"))
                        .build()
        );
        LoanApplication application = new LoanApplication(
                borrower,
                lsp,
                product,
                version,
                "EXT-" + id,
                "API",
                new BigDecimal("10000.00"),
                12,
                LoanApplicationStatus.DISBURSED
        );
        setCreatedAt(application, Instant.parse("2026-03-01T00:00:00Z"));
        LoanAccount account = new LoanAccount(
                application,
                borrower,
                lsp,
                product,
                version,
                "ACCT-" + id,
                new BigDecimal("10000.00"),
                12,
                LoanAccountStatus.DISBURSED,
                Instant.parse("2026-03-01T00:00:00Z")
        );
        account.updateDisbursementStatus(
                LoanAccountStatus.DISBURSED,
                Instant.parse("2026-03-01T00:00:00Z")
        );
        return account;
    }

    private static void setCreatedAt(LoanApplication application, Instant createdAt) {
        try {
            var field = LoanApplication.class.getDeclaredField("createdAt");
            field.setAccessible(true);
            field.set(application, createdAt);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
