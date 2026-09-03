package com.bhawana.lms.service;

import com.bhawana.lms.domain.AppRole;
import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.domain.RoleCode;
import com.bhawana.lms.domain.UserStatus;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LspRepository;
import com.bhawana.lms.common.api.error.ApiConflictException;
import com.bhawana.lms.common.api.error.ResourceNotFoundException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LspDirectoryService {

    private static final List<LoanApplicationStatus> APPROVED_PORTFOLIO_STATUSES = List.of(
            LoanApplicationStatus.APPROVED_PENDING_DISBURSAL,
            LoanApplicationStatus.DISBURSED,
            LoanApplicationStatus.UNDER_REPAYMENT,
            LoanApplicationStatus.CLOSED,
            LoanApplicationStatus.FORECLOSED
    );

    private final LspRepository lspRepository;
    private final AppUserRepository appUserRepository;
    private final LoanApplicationRepository loanApplicationRepository;
    private final LoanAccountRepository loanAccountRepository;

    public LspDirectoryService(
            LspRepository lspRepository,
            AppUserRepository appUserRepository,
            LoanApplicationRepository loanApplicationRepository,
            LoanAccountRepository loanAccountRepository
    ) {
        this.lspRepository = lspRepository;
        this.appUserRepository = appUserRepository;
        this.loanApplicationRepository = loanApplicationRepository;
        this.loanAccountRepository = loanAccountRepository;
    }

    @Transactional
    public Lsp createLsp(String code, String name, LspStatus status) {
        if (lspRepository.existsByCodeIgnoreCase(code)) {
            throw new ApiConflictException("LSP_CODE_EXISTS", "LSP code already exists: " + code);
        }
        return lspRepository.save(new Lsp(code.trim().toUpperCase(), name.trim(), status));
    }

    @Transactional(readOnly = true)
    public List<Lsp> listLsps() {
        return lspRepository.findAllByOrderByCodeAsc();
    }

    @Transactional(readOnly = true)
    public List<LspDirectoryView> listLspDirectoryViews() {
        Map<UUID, Long> userCounts = appUserRepository.countUsersByLsp().stream()
                .collect(Collectors.toMap(
                        AppUserRepository.LspUserCountProjection::getLspId,
                        AppUserRepository.LspUserCountProjection::getUserCount
                ));
        Map<UUID, LoanApplicationRepository.LspApplicationSummaryProjection> applicationSummaries =
                loanApplicationRepository.summarizeApplicationsByLsp(APPROVED_PORTFOLIO_STATUSES).stream()
                        .collect(Collectors.toMap(
                                LoanApplicationRepository.LspApplicationSummaryProjection::getLspId,
                                Function.identity()
                        ));
        Map<UUID, LoanAccountRepository.LspAccountSummaryProjection> accountSummaries =
                loanAccountRepository.summarizeAccountsByLsp().stream()
                        .collect(Collectors.toMap(
                                LoanAccountRepository.LspAccountSummaryProjection::getLspId,
                                Function.identity()
                        ));

        return listLsps().stream()
                .map(lsp -> new LspDirectoryView(
                        lsp,
                        Math.toIntExact(userCounts.getOrDefault(lsp.getId(), 0L)),
                        buildPortfolioSummary(
                                applicationSummaries.get(lsp.getId()),
                                accountSummaries.get(lsp.getId())
                        )
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public LspDetailView getLspDetail(UUID lspId) {
        Lsp lsp = lspRepository.findById(lspId)
                .orElseThrow(() -> new ResourceNotFoundException("Unknown LSP id: " + lspId));

        List<AppUser> users = appUserRepository.findByLsp_IdOrderByUsernameAsc(lspId);
        LspPortfolioSummary portfolioSummary = buildPortfolioSummary(
                loanApplicationRepository
                        .summarizeApplicationsForLsp(lspId, APPROVED_PORTFOLIO_STATUSES)
                        .orElse(null),
                loanAccountRepository.summarizeAccountsForLsp(lspId).orElse(null)
        );

        return new LspDetailView(
                lsp,
                users.stream()
                        .map(user -> new LspUserView(
                                user.getId(),
                                user.getUsername(),
                                user.getEmail(),
                                user.getStatus(),
                                user.getRoles().stream().map(AppRole::getCode).sorted().toList()
                        ))
                        .toList(),
                portfolioSummary
        );
    }

    private static LspPortfolioSummary buildPortfolioSummary(
            LoanApplicationRepository.LspApplicationSummaryProjection applicationSummary,
            LoanAccountRepository.LspAccountSummaryProjection accountSummary
    ) {
        BigDecimal totalDisbursedAmount = accountSummary == null || accountSummary.getTotalDisbursedAmount() == null
                ? BigDecimal.ZERO.setScale(2)
                : accountSummary.getTotalDisbursedAmount();
        LocalDate latestDisbursalDate = accountSummary == null || accountSummary.getLatestDisbursalAt() == null
                ? null
                : accountSummary.getLatestDisbursalAt().atZone(ZoneOffset.UTC).toLocalDate();

        return new LspPortfolioSummary(
                applicationSummary == null ? 0 : Math.toIntExact(applicationSummary.getLoanApplicationCount()),
                applicationSummary == null ? 0 : Math.toIntExact(applicationSummary.getApprovedLoanCount()),
                accountSummary == null ? 0 : Math.toIntExact(accountSummary.getDisbursedLoanCount()),
                totalDisbursedAmount,
                latestDisbursalDate
        );
    }

    public record LspDirectoryView(
            Lsp lsp,
            int userCount,
            LspPortfolioSummary portfolioSummary
    ) {
    }

    public record LspDetailView(
            Lsp lsp,
            List<LspUserView> users,
            LspPortfolioSummary portfolioSummary
    ) {
    }

    public record LspUserView(
            UUID id,
            String username,
            String email,
            UserStatus status,
            List<RoleCode> roles
    ) {
    }

    public record LspPortfolioSummary(
            int loanApplicationCount,
            int approvedLoanCount,
            int disbursedLoanCount,
            BigDecimal totalDisbursedAmount,
            LocalDate latestDisbursalDate
    ) {
    }
}
