package com.bhawana.lms.service;

import com.bhawana.lms.domain.AppRole;
import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.domain.RoleCode;
import com.bhawana.lms.domain.UserStatus;
import com.bhawana.lms.domain.WebhookEventType;
import com.bhawana.lms.repo.AppRoleRepository;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LspRepository;
import java.security.SecureRandom;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminDirectoryService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final LspRepository lspRepository;
    private final AppRoleRepository appRoleRepository;
    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final LoanApplicationRepository loanApplicationRepository;
    private final LoanAccountRepository loanAccountRepository;

    public AdminDirectoryService(
            LspRepository lspRepository,
            AppRoleRepository appRoleRepository,
            AppUserRepository appUserRepository,
            PasswordEncoder passwordEncoder,
            LoanApplicationRepository loanApplicationRepository,
            LoanAccountRepository loanAccountRepository
    ) {
        this.lspRepository = lspRepository;
        this.appRoleRepository = appRoleRepository;
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.loanApplicationRepository = loanApplicationRepository;
        this.loanAccountRepository = loanAccountRepository;
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
        return lspRepository.findAll().stream()
                .sorted(java.util.Comparator.comparing(Lsp::getCode))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<LspDirectoryView> listLspDirectoryViews() {
        List<AppUser> users = appUserRepository.findAll();
        List<LoanApplication> applications = loanApplicationRepository.findAllByOrderByCreatedAtDesc();
        List<LoanAccount> loanAccounts = loanAccountRepository.findAll();

        return listLsps().stream()
                .map(lsp -> new LspDirectoryView(
                        lsp,
                        countUsers(lsp.getId(), users),
                        buildPortfolioSummary(lsp.getId(), applications, loanAccounts)
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public LspDetailView getLspDetail(UUID lspId) {
        Lsp lsp = lspRepository.findById(lspId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown LSP id: " + lspId));

        List<AppUser> users = appUserRepository.findAll().stream()
                .filter(user -> user.getLsp() != null && user.getLsp().getId().equals(lspId))
                .sorted(Comparator.comparing(AppUser::getUsername, String.CASE_INSENSITIVE_ORDER))
                .toList();
        List<LoanApplication> applications = loanApplicationRepository.findAllByOrderByCreatedAtDesc();
        List<LoanAccount> loanAccounts = loanAccountRepository.findAll();

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
                buildPortfolioSummary(lspId, applications, loanAccounts)
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
        return appUserRepository.findAll().stream()
                .sorted(java.util.Comparator.comparing(AppUser::getUsername))
                .toList();
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

    private static int countUsers(UUID lspId, List<AppUser> users) {
        return (int) users.stream()
                .filter(user -> user.getLsp() != null && user.getLsp().getId().equals(lspId))
                .count();
    }

    private static LspPortfolioSummary buildPortfolioSummary(
            UUID lspId,
            List<LoanApplication> applications,
            List<LoanAccount> loanAccounts
    ) {
        List<LoanApplication> lspApplications = applications.stream()
                .filter(application -> application.getLsp().getId().equals(lspId))
                .toList();
        List<LoanAccount> lspLoanAccounts = loanAccounts.stream()
                .filter(account -> account.getLsp().getId().equals(lspId))
                .toList();

        BigDecimal totalDisbursedAmount = lspLoanAccounts.stream()
                .filter(account -> account.getDisbursedAt() != null)
                .map(LoanAccount::getPrincipalAmount)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        LocalDate latestDisbursalDate = lspLoanAccounts.stream()
                .map(LoanAccount::getDisbursedAt)
                .filter(java.util.Objects::nonNull)
                .map(instant -> instant.atZone(ZoneOffset.UTC).toLocalDate())
                .max(LocalDate::compareTo)
                .orElse(null);

        return new LspPortfolioSummary(
                lspApplications.size(),
                (int) lspApplications.stream()
                        .filter(application -> application.getStatus() == com.bhawana.lms.domain.LoanApplicationStatus.APPROVED_PENDING_DISBURSAL
                                || application.getStatus() == com.bhawana.lms.domain.LoanApplicationStatus.DISBURSED
                                || application.getStatus() == com.bhawana.lms.domain.LoanApplicationStatus.UNDER_REPAYMENT
                                || application.getStatus() == com.bhawana.lms.domain.LoanApplicationStatus.CLOSED)
                        .count(),
                (int) lspLoanAccounts.stream().filter(account -> account.getDisbursedAt() != null).count(),
                totalDisbursedAmount,
                latestDisbursalDate
        );
    }

    public record ResetPasswordResult(AppUser user, String temporaryPassword) {
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
