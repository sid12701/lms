package com.bhawana.lms.service;

import com.bhawana.lms.domain.AppRole;
import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.domain.Borrower;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.domain.RoleCode;
import com.bhawana.lms.domain.UserStatus;
import com.bhawana.lms.domain.WebhookEventType;
import com.bhawana.lms.repo.AppRoleRepository;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.repo.BorrowerRepository;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LspRepository;
import com.bhawana.lms.security.SsrfSafeUrlValidator;
import java.security.SecureRandom;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminDirectoryService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final List<LoanApplicationStatus> APPROVED_PORTFOLIO_STATUSES = List.of(
            LoanApplicationStatus.APPROVED_PENDING_DISBURSAL,
            LoanApplicationStatus.DISBURSED,
            LoanApplicationStatus.UNDER_REPAYMENT,
            LoanApplicationStatus.CLOSED
    );

    private final LspRepository lspRepository;
    private final AppRoleRepository appRoleRepository;
    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final LoanApplicationRepository loanApplicationRepository;
    private final LoanAccountRepository loanAccountRepository;
    private final BorrowerRepository borrowerRepository;

    public AdminDirectoryService(
            LspRepository lspRepository,
            AppRoleRepository appRoleRepository,
            AppUserRepository appUserRepository,
            PasswordEncoder passwordEncoder,
            LoanApplicationRepository loanApplicationRepository,
            LoanAccountRepository loanAccountRepository,
            BorrowerRepository borrowerRepository
    ) {
        this.lspRepository = lspRepository;
        this.appRoleRepository = appRoleRepository;
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.loanApplicationRepository = loanApplicationRepository;
        this.loanAccountRepository = loanAccountRepository;
        this.borrowerRepository = borrowerRepository;
    }

    @Transactional
    public Lsp createLsp(String code, String name, LspStatus status) {
        if (lspRepository.existsByCodeIgnoreCase(code)) {
            throw new IllegalArgumentException("LSP code already exists: " + code);
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
                .orElseThrow(() -> new IllegalArgumentException("Unknown LSP id: " + lspId));

        List<AppUser> users = appUserRepository.findByLsp_IdOrderByUsernameAsc(lspId);
        AdminDirectoryService.LspPortfolioSummary portfolioSummary = buildPortfolioSummary(
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

    @Transactional
    public Lsp updateWebhookSubscription(
            UUID lspId,
            boolean enabled,
            String endpointUrl,
            String signingSecret,
            List<WebhookEventType> eventTypes
    ) {
        Lsp lsp = lspRepository.findById(lspId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown LSP id: " + lspId));

        String normalizedEndpointUrl = normalizeOptional(endpointUrl);
        String normalizedSigningSecret = normalizeOptional(signingSecret);
        List<WebhookEventType> normalizedEventTypes = eventTypes == null ? List.of() : eventTypes.stream().distinct().toList();

        if (enabled) {
            if (normalizedEndpointUrl == null) {
                throw new IllegalArgumentException("Webhook endpoint URL is required when the subscription is enabled.");
            }
            if (!normalizedEndpointUrl.startsWith("http://") && !normalizedEndpointUrl.startsWith("https://")) {
                throw new IllegalArgumentException("Webhook endpoint URL must start with http:// or https://.");
            }
            SsrfSafeUrlValidator.validate(normalizedEndpointUrl);
            if (normalizedSigningSecret == null) {
                throw new IllegalArgumentException("Webhook signing secret is required when the subscription is enabled.");
            }
            if (normalizedEventTypes.isEmpty()) {
                throw new IllegalArgumentException("At least one webhook event must be selected when the subscription is enabled.");
            }
        }

        lsp.updateWebhookSubscription(enabled, normalizedEndpointUrl, normalizedSigningSecret, normalizedEventTypes);
        return lspRepository.save(lsp);
    }

    @Transactional
    public AppUser createUser(
            String username,
            String email,
            String rawPassword,
            UserStatus status,
            UUID lspId,
            Set<RoleCode> roleCodes
    ) {
        if (appUserRepository.existsByUsernameIgnoreCase(username)) {
            throw new IllegalArgumentException("Username already exists: " + username);
        }
        if (appUserRepository.existsByEmailIgnoreCase(email)) {
            throw new IllegalArgumentException("Email already exists: " + email);
        }

        List<AppRole> roles = appRoleRepository.findByCodeIn(roleCodes);
        if (roles.size() != roleCodes.size()) {
            throw new IllegalArgumentException("One or more requested roles are not available.");
        }

        Lsp lsp = null;
        if (lspId != null) {
            lsp = lspRepository.findById(lspId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown LSP id: " + lspId));
        }

        AppUser user = new AppUser(
                username.trim(),
                email.trim().toLowerCase(),
                passwordEncoder.encode(rawPassword),
                status,
                lsp,
                new LinkedHashSet<>(roles)
        );

        return appUserRepository.save(user);
    }

    @Transactional(readOnly = true)
    public List<AppUser> listUsers() {
        return appUserRepository.findAllByOrderByUsernameAsc();
    }

    @Transactional
    public ResetPasswordResult resetUserPassword(UUID userId) {
        AppUser user = appUserRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown user id: " + userId));

        String temporaryPassword = generateTemporaryPassword();
        user.requirePasswordChange(passwordEncoder.encode(temporaryPassword));
        appUserRepository.save(user);

        return new ResetPasswordResult(user, temporaryPassword);
    }

    private static String generateTemporaryPassword() {
        byte[] randomBytes = new byte[18];
        SECURE_RANDOM.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }

        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
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

    @Transactional(readOnly = true)
    public BorrowerDetailView getBorrowerDetail(UUID borrowerId) {
        Borrower borrower = borrowerRepository.findById(borrowerId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown borrower id: " + borrowerId));

        List<LoanAccount> loans = loanAccountRepository.findDetailedByBorrower_Id(borrowerId).stream()
                .sorted(Comparator.comparing(LoanAccount::getCreatedAt).reversed())
                .toList();

        List<BorrowerLoanView> loanViews = loans.stream()
                .map(account -> new BorrowerLoanView(
                        account.getId(),
                        account.getLoanApplication() == null ? null : account.getLoanApplication().getId(),
                        account.getAccountNumber(),
                        account.getLsp() == null ? null : account.getLsp().getId(),
                        account.getLsp() == null ? null : account.getLsp().getCode(),
                        account.getLsp() == null ? null : account.getLsp().getName(),
                        account.getLoanProduct() == null ? null : account.getLoanProduct().getCode(),
                        account.getStatus(),
                        account.getPrincipalAmount(),
                        account.getTenureMonths(),
                        account.getApprovedAt(),
                        account.getDisbursedAt(),
                        account.getClosureReason() == null ? null : account.getClosureReason().name(),
                        account.getClosedAt(),
                        account.getClosedByUsername(),
                        account.getCreatedAt()
                ))
                .toList();

        return new BorrowerDetailView(borrower, loanViews);
    }

    public record ResetPasswordResult(AppUser user, String temporaryPassword) {
    }

    public record BorrowerDetailView(
            Borrower borrower,
            List<BorrowerLoanView> loans
    ) {
    }

    public record BorrowerLoanView(
            UUID loanAccountId,
            UUID applicationId,
            String accountNumber,
            UUID lspId,
            String lspCode,
            String lspName,
            String loanProductCode,
            LoanAccountStatus status,
            BigDecimal principalAmount,
            int tenureMonths,
            java.time.Instant approvedAt,
            java.time.Instant disbursedAt,
            String closureReason,
            java.time.Instant closedAt,
            String closedByUsername,
            java.time.Instant createdAt
    ) {
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
