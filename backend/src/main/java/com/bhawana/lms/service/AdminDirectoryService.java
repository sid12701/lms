package com.bhawana.lms.service;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.domain.AppRole;
import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.domain.AppUserAuditEvent;
import com.bhawana.lms.domain.Borrower;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanDelinquencyBucket;
import com.bhawana.lms.domain.LoanRepaymentScheduleInstallment;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.domain.RoleCode;
import com.bhawana.lms.domain.UserStatus;
import com.bhawana.lms.domain.WebhookEventType;
import com.bhawana.lms.repo.AppRoleRepository;
import com.bhawana.lms.repo.AppUserAuditEventRepository;
import com.bhawana.lms.repo.AppUserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bhawana.lms.repo.BorrowerRepository;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanRepaymentScheduleInstallmentRepository;
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

    private static final Set<RoleCode> INTERNAL_ROLE_CODES = Set.of(
            RoleCode.SYSTEM_ADMIN,
            RoleCode.OPS_USER,
            RoleCode.PRODUCT_ADMIN
    );
    private static final Set<RoleCode> LSP_ROLE_CODES = Set.of(
            RoleCode.LSP_UI_READ,
            RoleCode.LSP_UI_WRITE,
            RoleCode.LSP_API_CLIENT
    );

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final List<LoanApplicationStatus> APPROVED_PORTFOLIO_STATUSES = List.of(
            LoanApplicationStatus.APPROVED_PENDING_DISBURSAL,
            LoanApplicationStatus.DISBURSED,
            LoanApplicationStatus.UNDER_REPAYMENT,
            LoanApplicationStatus.CLOSED
    );

    private static final List<LoanAccountStatus> ACTIVE_LOAN_STATUSES = List.of(
            LoanAccountStatus.PENDING_DISBURSEMENT,
            LoanAccountStatus.DISBURSEMENT_REQUESTED,
            LoanAccountStatus.DISBURSEMENT_PENDING_RECONCILIATION,
            LoanAccountStatus.DISBURSEMENT_FAILED,
            LoanAccountStatus.DISBURSED
    );

    private final LspRepository lspRepository;
    private final AppRoleRepository appRoleRepository;
    private final AppUserRepository appUserRepository;
    private final AppUserAuditEventRepository appUserAuditEventRepository;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;
    private final LoanApplicationRepository loanApplicationRepository;
    private final LoanAccountRepository loanAccountRepository;
    private final BorrowerRepository borrowerRepository;
    private final LoanRepaymentScheduleInstallmentRepository loanRepaymentScheduleInstallmentRepository;

    public AdminDirectoryService(
            LspRepository lspRepository,
            AppRoleRepository appRoleRepository,
            AppUserRepository appUserRepository,
            AppUserAuditEventRepository appUserAuditEventRepository,
            PasswordEncoder passwordEncoder,
            ObjectMapper objectMapper,
            LoanApplicationRepository loanApplicationRepository,
            LoanAccountRepository loanAccountRepository,
            BorrowerRepository borrowerRepository,
            LoanRepaymentScheduleInstallmentRepository loanRepaymentScheduleInstallmentRepository
    ) {
        this.lspRepository = lspRepository;
        this.appRoleRepository = appRoleRepository;
        this.appUserRepository = appUserRepository;
        this.appUserAuditEventRepository = appUserAuditEventRepository;
        this.passwordEncoder = passwordEncoder;
        this.objectMapper = objectMapper;
        this.loanApplicationRepository = loanApplicationRepository;
        this.loanAccountRepository = loanAccountRepository;
        this.borrowerRepository = borrowerRepository;
        this.loanRepaymentScheduleInstallmentRepository = loanRepaymentScheduleInstallmentRepository;
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
    public AppUser updateUser(
            UUID userId,
            String actorUsername,
            String email,
            UserStatus status,
            UUID lspId,
            Set<RoleCode> roleCodes
    ) {
        AppUser user = appUserRepository.findDetailedById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown user id: " + userId));

        UserAuditSnapshot beforeSnapshot = toAuditSnapshot(user);

        String resolvedEmail = email == null ? user.getEmail() : email.trim().toLowerCase();
        if (!resolvedEmail.equalsIgnoreCase(user.getEmail())
                && appUserRepository.existsByEmailIgnoreCaseAndIdNot(resolvedEmail, userId)) {
            throw new IllegalArgumentException("Email already exists: " + resolvedEmail);
        }

        UserStatus resolvedStatus = status == null ? user.getStatus() : status;
        Set<RoleCode> resolvedRoleCodes = roleCodes == null
                ? user.getRoles().stream().map(AppRole::getCode).collect(Collectors.toCollection(LinkedHashSet::new))
                : new LinkedHashSet<>(roleCodes);

        if (resolvedRoleCodes.isEmpty()) {
            throw new IllegalArgumentException("At least one role is required.");
        }

        List<AppRole> resolvedRoles = appRoleRepository.findByCodeIn(resolvedRoleCodes);
        if (resolvedRoles.size() != resolvedRoleCodes.size()) {
            throw new IllegalArgumentException("One or more requested roles are not available.");
        }

        Lsp resolvedLsp = user.getLsp();
        if (lspId != null) {
            resolvedLsp = lspRepository.findById(lspId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown LSP id: " + lspId));
        } else if (roleCodes != null && resolvedRoleCodes.stream().noneMatch(LSP_ROLE_CODES::contains)) {
            resolvedLsp = null;
        }
        validateRoleLspConsistency(resolvedRoleCodes, resolvedLsp);

        boolean rolesChanged = roleCodes != null
                && !user.getRoles().stream().map(AppRole::getCode).collect(Collectors.toSet()).equals(resolvedRoleCodes);

        enforceSelfEditGuards(user, actorUsername, resolvedStatus, resolvedRoleCodes);

        user.updateManagedProfile(
                resolvedEmail,
                resolvedStatus,
                resolvedLsp,
                new LinkedHashSet<>(resolvedRoles),
                rolesChanged
        );
        AppUser saved = appUserRepository.save(user);

        UserAuditSnapshot afterSnapshot = toAuditSnapshot(saved);
        appUserAuditEventRepository.save(new AppUserAuditEvent(
                saved,
                actorUsername,
                serializeAuditSnapshot(beforeSnapshot),
                serializeAuditSnapshot(afterSnapshot),
                CorrelationIdHolder.get()
        ));

        return saved;
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

    private void enforceSelfEditGuards(
            AppUser user,
            String actorUsername,
            UserStatus resolvedStatus,
            Set<RoleCode> resolvedRoleCodes
    ) {
        if (!user.getUsername().equalsIgnoreCase(actorUsername)) {
            return;
        }

        if (resolvedStatus == UserStatus.INACTIVE) {
            throw new IllegalArgumentException("You cannot disable your own account.");
        }

        boolean retainsSystemAdmin = resolvedRoleCodes.contains(RoleCode.SYSTEM_ADMIN);
        if (!retainsSystemAdmin
                && user.getRoles().stream().anyMatch(role -> role.getCode() == RoleCode.SYSTEM_ADMIN)
                && appUserRepository.countActiveUsersWithRoleExcluding(RoleCode.SYSTEM_ADMIN, user.getId()) == 0) {
            throw new IllegalArgumentException(
                    "You cannot remove the SYSTEM_ADMIN role while you are the last active system administrator."
            );
        }
    }

    private static void validateRoleLspConsistency(Set<RoleCode> roleCodes, Lsp lsp) {
        boolean hasInternalRole = roleCodes.stream().anyMatch(INTERNAL_ROLE_CODES::contains);
        boolean hasLspRole = roleCodes.stream().anyMatch(LSP_ROLE_CODES::contains);
        if (hasInternalRole && hasLspRole) {
            throw new IllegalArgumentException("Internal roles cannot be combined with LSP-scoped roles.");
        }
        if (hasInternalRole && lsp != null) {
            throw new IllegalArgumentException("Internal roles must not be assigned to an LSP.");
        }
        if (hasLspRole && lsp == null) {
            throw new IllegalArgumentException("LSP-scoped roles require an LSP assignment.");
        }
    }

    private static UserAuditSnapshot toAuditSnapshot(AppUser user) {
        return new UserAuditSnapshot(
                user.getEmail(),
                user.getStatus().name(),
                user.getLsp() == null ? null : user.getLsp().getId().toString(),
                user.getRoles().stream().map(role -> role.getCode().name()).sorted().toList()
        );
    }

    private String serializeAuditSnapshot(UserAuditSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize user audit snapshot.", exception);
        }
    }

    private record UserAuditSnapshot(String email, String status, String lspId, List<String> roles) {
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

        BorrowerDelinquencyAggregate delinquency = computeDelinquencyAggregate(loans);
        return new BorrowerDetailView(borrower, loanViews, delinquency);
    }

    /**
     * Gap #6: server-side aggregate of delinquency across every active loan
     * for a borrower. "Active" means a loan account not in CLOSED, FORECLOSED,
     * or INVALID — i.e. one where future installments can still go overdue.
     *
     * `activeOverdueAmount` sums the outstanding portion of each installment
     * whose due-date is in the past and that has unpaid balance. `maxDaysPastDue`
     * is the worst DPD across those installments, and `bucket` is the
     * corresponding DPD bucket per `LoanApplicationService.resolveDelinquencyBucket`.
     * `overdueLoanCount` counts distinct active loans contributing at least
     * one overdue installment — useful for ops triage where the headline tile
     * is the count of stuck loans rather than the count of stuck installments.
     */
    private BorrowerDelinquencyAggregate computeDelinquencyAggregate(List<LoanAccount> loans) {
        LocalDate today = LoanApplicationService.currentBusinessDate();
        BigDecimal totalOverdue = BigDecimal.ZERO.setScale(2);
        int maxDpd = 0;
        int overdueLoanCount = 0;

        for (LoanAccount account : loans) {
            if (account.getStatus() == null || !ACTIVE_LOAN_STATUSES.contains(account.getStatus())) {
                continue;
            }
            List<LoanRepaymentScheduleInstallment> installments = loanRepaymentScheduleInstallmentRepository
                    .findByLoanAccount_IdOrderByInstallmentNumberAsc(account.getId());
            if (installments.isEmpty()) {
                continue;
            }
            BigDecimal loanOverdue = BigDecimal.ZERO.setScale(2);
            int loanMaxDpd = 0;
            for (LoanRepaymentScheduleInstallment installment : installments) {
                int dpd = LoanApplicationService.calculateDaysPastDue(installment, today);
                if (dpd > 0) {
                    loanOverdue = loanOverdue.add(installment.getOutstandingAmount());
                    if (dpd > loanMaxDpd) {
                        loanMaxDpd = dpd;
                    }
                }
            }
            if (loanOverdue.compareTo(BigDecimal.ZERO) > 0 || loanMaxDpd > 0) {
                overdueLoanCount++;
                totalOverdue = totalOverdue.add(loanOverdue);
                if (loanMaxDpd > maxDpd) {
                    maxDpd = loanMaxDpd;
                }
            }
        }

        LoanDelinquencyBucket bucket = LoanApplicationService.resolveDelinquencyBucket(maxDpd);
        return new BorrowerDelinquencyAggregate(
                totalOverdue.setScale(2),
                maxDpd,
                overdueLoanCount,
                bucket
        );
    }

    public record ResetPasswordResult(AppUser user, String temporaryPassword) {
    }

    public record BorrowerDetailView(
            Borrower borrower,
            List<BorrowerLoanView> loans,
            BorrowerDelinquencyAggregate delinquency
    ) {
    }

    /**
     * Gap #6: aggregate delinquency across the borrower's active loans.
     * Authoritative server-side calculation; avoids client/server drift on
     * bucket definitions.
     */
    public record BorrowerDelinquencyAggregate(
            BigDecimal activeOverdueAmount,
            int maxDaysPastDue,
            int overdueLoanCount,
            LoanDelinquencyBucket bucket
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
