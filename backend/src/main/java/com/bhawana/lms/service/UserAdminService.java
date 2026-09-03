package com.bhawana.lms.service;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.domain.AppRole;
import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.domain.AppUserAuditEvent;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.RevocationSource;
import com.bhawana.lms.domain.RoleCode;
import com.bhawana.lms.domain.UserStatus;
import com.bhawana.lms.repo.AppRoleRepository;
import com.bhawana.lms.repo.AppUserAuditEventRepository;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.repo.LspRepository;
import com.bhawana.lms.security.AuthPrincipalCache;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bhawana.lms.common.api.error.ApiConflictException;
import com.bhawana.lms.common.api.error.BusinessRuleViolationException;
import com.bhawana.lms.common.api.error.ResourceNotFoundException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserAdminService {

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

    private final LspRepository lspRepository;
    private final AppRoleRepository appRoleRepository;
    private final AppUserRepository appUserRepository;
    private final AppUserAuditEventRepository appUserAuditEventRepository;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;
    private final SessionRevocationService sessionRevocationService;
    private final AuthPrincipalCache authPrincipalCache;

    public UserAdminService(
            LspRepository lspRepository,
            AppRoleRepository appRoleRepository,
            AppUserRepository appUserRepository,
            AppUserAuditEventRepository appUserAuditEventRepository,
            PasswordEncoder passwordEncoder,
            ObjectMapper objectMapper,
            SessionRevocationService sessionRevocationService,
            AuthPrincipalCache authPrincipalCache
    ) {
        this.lspRepository = lspRepository;
        this.appRoleRepository = appRoleRepository;
        this.appUserRepository = appUserRepository;
        this.appUserAuditEventRepository = appUserAuditEventRepository;
        this.passwordEncoder = passwordEncoder;
        this.objectMapper = objectMapper;
        this.sessionRevocationService = sessionRevocationService;
        this.authPrincipalCache = authPrincipalCache;
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
        if (appUserRepository.existsByUsername(username)) {
            throw new ApiConflictException("USERNAME_EXISTS", "Username already exists: " + username);
        }
        if (appUserRepository.existsByEmail(email)) {
            throw new ApiConflictException("EMAIL_EXISTS", "Email already exists: " + email);
        }

        List<AppRole> roles = appRoleRepository.findByCodeIn(roleCodes);
        if (roles.size() != roleCodes.size()) {
            throw new BusinessRuleViolationException(
                    "ROLE_UNAVAILABLE",
                    "One or more requested roles are not available.",
                    Map.of("roles", "one or more requested roles are not available")
            );
        }

        Lsp lsp = null;
        if (lspId != null) {
            lsp = lspRepository.findById(lspId)
                    .orElseThrow(() -> new ResourceNotFoundException("Unknown LSP id: " + lspId));
        }
        validateRoleLspConsistency(roleCodes, lsp);

        // F-11: canonicalise username + email to lowercase so the unique indexes
        // can satisfy the new raw-equality lookups in AppUserRepository.
        String encodedPassword = passwordEncoder.encode(rawPassword);
        AppUser user = new AppUser(
                username.trim().toLowerCase(),
                email.trim().toLowerCase(),
                encodedPassword,
                status,
                lsp,
                new LinkedHashSet<>(roles)
        );
        user.requirePasswordChange(encodedPassword);

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
            String actorIp,
            String email,
            UserStatus status,
            UUID lspId,
            Set<RoleCode> roleCodes
    ) {
        AppUser user = appUserRepository.findDetailedById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Unknown user id: " + userId));

        UserAuditSnapshot beforeSnapshot = toAuditSnapshot(user);

        String resolvedEmail = email == null ? user.getEmail() : email.trim().toLowerCase();
        if (!resolvedEmail.equalsIgnoreCase(user.getEmail())
                && appUserRepository.existsByEmailAndIdNot(resolvedEmail, userId)) {
            throw new ApiConflictException("EMAIL_EXISTS", "Email already exists: " + resolvedEmail);
        }

        UserStatus resolvedStatus = status == null ? user.getStatus() : status;
        Set<RoleCode> resolvedRoleCodes = roleCodes == null
                ? user.getRoles().stream().map(AppRole::getCode).collect(Collectors.toCollection(LinkedHashSet::new))
                : new LinkedHashSet<>(roleCodes);

        if (resolvedRoleCodes.isEmpty()) {
            throw new BusinessRuleViolationException(
                    "ROLES_REQUIRED",
                    "At least one role is required.",
                    Map.of("roles", "at least one role is required")
            );
        }

        List<AppRole> resolvedRoles = appRoleRepository.findByCodeIn(resolvedRoleCodes);
        if (resolvedRoles.size() != resolvedRoleCodes.size()) {
            throw new BusinessRuleViolationException(
                    "ROLE_UNAVAILABLE",
                    "One or more requested roles are not available.",
                    Map.of("roles", "one or more requested roles are not available")
            );
        }

        Lsp resolvedLsp = user.getLsp();
        if (lspId != null) {
            resolvedLsp = lspRepository.findById(lspId)
                    .orElseThrow(() -> new ResourceNotFoundException("Unknown LSP id: " + lspId));
        } else if (roleCodes != null && resolvedRoleCodes.stream().noneMatch(LSP_ROLE_CODES::contains)) {
            resolvedLsp = null;
        }
        validateRoleLspConsistency(resolvedRoleCodes, resolvedLsp);

        boolean rolesChanged = roleCodes != null
                && !user.getRoles().stream().map(AppRole::getCode).collect(Collectors.toSet()).equals(resolvedRoleCodes);
        boolean statusChanged = resolvedStatus != user.getStatus();

        enforceSelfEditGuards(user, actorUsername, resolvedStatus, resolvedRoleCodes);

        user.updateManagedProfile(
                resolvedEmail,
                resolvedLsp,
                new LinkedHashSet<>(resolvedRoles)
        );
        if (statusChanged) {
            user.changeStatus(resolvedStatus);
        }
        AppUser saved = appUserRepository.save(user);

        UserAuditSnapshot afterSnapshot = toAuditSnapshot(saved);
        writeUserAuditEvent(
                saved,
                actorUsername,
                actorIp,
                CorrelationIdHolder.get(),
                beforeSnapshot,
                afterSnapshot,
                null
        );

        if (rolesChanged) {
            sessionRevocationService.revokeAllSessions(
                    saved,
                    actorUsername,
                    "Role change",
                    actorIp,
                    CorrelationIdHolder.get(),
                    RevocationSource.ROLE_CHANGE
            );
        }

        if (statusChanged) {
            sessionRevocationService.revokeAllSessions(
                    saved,
                    actorUsername,
                    "Status change",
                    actorIp,
                    CorrelationIdHolder.get(),
                    RevocationSource.STATUS_CHANGE
            );
        }

        return saved;
    }

    @Transactional
    public AppUser completeRequiredPasswordChange(String username, String newPassword) {
        AppUser user = appUserRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Password changes are only supported for managed users."));

        if (!user.isPasswordChangeRequired()) {
            throw new IllegalArgumentException("Password change is not required for this account.");
        }

        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("New password must differ from the temporary password.");
        }

        user.changePassword(passwordEncoder.encode(newPassword));
        AppUser saved = appUserRepository.save(user);
        // changePassword bumps the token version / passwordChangedAt; drop the cached snapshot so the
        // freshly minted token validates against current state instead of the pre-change snapshot.
        authPrincipalCache.evictAppUser(saved.getUsername());
        return saved;
    }

    @Transactional
    public ResetPasswordResult resetUserPassword(
            UUID userId,
            String actorUsername,
            String actorIp,
            String correlationId
    ) {
        AppUser user = appUserRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Unknown user id: " + userId));

        UserAuditSnapshot beforeSnapshot = toAuditSnapshot(user);

        boolean unlockedFromAutoLockout = user.isLocked();
        String priorLockReason = user.getLockReason();
        if (unlockedFromAutoLockout) {
            user.unlockForReset();
        }

        String temporaryPassword = generateTemporaryPassword();
        user.requirePasswordChange(passwordEncoder.encode(temporaryPassword));
        AppUser saved = appUserRepository.save(user);

        UserAuditSnapshot afterSnapshot = toAuditSnapshot(saved);
        writeUserAuditEvent(
                saved,
                actorUsername,
                actorIp,
                correlationId,
                beforeSnapshot,
                afterSnapshot,
                "PASSWORD_RESET_BY_ADMIN",
                unlockedFromAutoLockout,
                priorLockReason
        );

        sessionRevocationService.revokeAllSessions(
                saved,
                actorUsername,
                "Password reset by admin",
                actorIp,
                correlationId,
                RevocationSource.ADMIN_RESET_PASSWORD
        );

        return new ResetPasswordResult(saved, temporaryPassword);
    }

    @Transactional
    public SessionRevocationService.RevocationResult revokeUserSessions(
            UUID userId,
            String actorUsername,
            String reasonOrNull,
            String actorIp,
            String correlationId
    ) {
        AppUser user = appUserRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Unknown user id: " + userId));
        return sessionRevocationService.revokeAllSessions(
                user,
                actorUsername,
                reasonOrNull,
                actorIp,
                correlationId,
                RevocationSource.ADMIN_EXPLICIT
        );
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
            throw new BusinessRuleViolationException(
                    "SELF_DISABLE_FORBIDDEN",
                    "You cannot disable your own account.",
                    Map.of("status", "cannot disable your own account")
            );
        }

        boolean retainsSystemAdmin = resolvedRoleCodes.contains(RoleCode.SYSTEM_ADMIN);
        if (!retainsSystemAdmin
                && user.getRoles().stream().anyMatch(role -> role.getCode() == RoleCode.SYSTEM_ADMIN)
                && appUserRepository.countActiveUsersWithRoleExcluding(RoleCode.SYSTEM_ADMIN, user.getId()) == 0) {
            throw new BusinessRuleViolationException(
                    "LAST_SYSTEM_ADMIN",
                    "You cannot remove the SYSTEM_ADMIN role while you are the last active system administrator.",
                    Map.of("roles", "cannot remove the last active SYSTEM_ADMIN role from yourself")
            );
        }
    }

    private static void validateRoleLspConsistency(Set<RoleCode> roleCodes, Lsp lsp) {
        boolean hasInternalRole = roleCodes.stream().anyMatch(INTERNAL_ROLE_CODES::contains);
        boolean hasLspRole = roleCodes.stream().anyMatch(LSP_ROLE_CODES::contains);
        if (hasInternalRole && hasLspRole) {
            throw new BusinessRuleViolationException(
                    "ROLE_SCOPE_CONFLICT",
                    "Internal roles cannot be combined with LSP-scoped roles.",
                    Map.of("roles", "internal and LSP-scoped roles cannot be combined")
            );
        }
        if (hasInternalRole && lsp != null) {
            throw new BusinessRuleViolationException(
                    "ROLE_SCOPE_CONFLICT",
                    "Internal roles must not be assigned to an LSP.",
                    Map.of("lspId", "internal roles must not be assigned to an LSP")
            );
        }
        if (hasLspRole && lsp == null) {
            throw new BusinessRuleViolationException(
                    "ROLE_SCOPE_CONFLICT",
                    "LSP-scoped roles require an LSP assignment.",
                    Map.of("lspId", "required for LSP-scoped roles")
            );
        }
    }

    private static UserAuditSnapshot toAuditSnapshot(AppUser user) {
        return new UserAuditSnapshot(
                user.getEmail(),
                user.getStatus().name(),
                user.getLsp() == null ? null : user.getLsp().getId().toString(),
                user.getRoles().stream().map(role -> role.getCode().name()).sorted().toList(),
                user.isPasswordChangeRequired()
        );
    }

    private void writeUserAuditEvent(
            AppUser user,
            String actorUsername,
            String actorIp,
            String correlationId,
            UserAuditSnapshot beforeSnapshot,
            UserAuditSnapshot afterSnapshot,
            String eventType
    ) {
        writeUserAuditEvent(
                user,
                actorUsername,
                actorIp,
                correlationId,
                beforeSnapshot,
                afterSnapshot,
                eventType,
                false,
                null
        );
    }

    private void writeUserAuditEvent(
            AppUser user,
            String actorUsername,
            String actorIp,
            String correlationId,
            UserAuditSnapshot beforeSnapshot,
            UserAuditSnapshot afterSnapshot,
            String eventType,
            boolean unlockedFromAutoLockout,
            String priorLockReason
    ) {
        appUserAuditEventRepository.save(new AppUserAuditEvent(
                user,
                actorUsername,
                serializeAuditSnapshot(beforeSnapshot),
                serializeAuditPayload(afterSnapshot, eventType, unlockedFromAutoLockout, priorLockReason),
                correlationId,
                actorIp
        ));
    }

    private String serializeAuditSnapshot(UserAuditSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize user audit snapshot.", exception);
        }
    }

    private String serializeAuditPayload(
            UserAuditSnapshot snapshot,
            String eventType,
            boolean unlockedFromAutoLockout,
            String priorLockReason
    ) {
        try {
            ObjectNode node = objectMapper.valueToTree(snapshot);
            if (eventType != null && !eventType.isBlank()) {
                node.put("eventType", eventType);
            }
            if ("PASSWORD_RESET_BY_ADMIN".equals(eventType)) {
                node.put("unlockedFromAutoLockout", unlockedFromAutoLockout);
                if (priorLockReason != null && !priorLockReason.isBlank()) {
                    node.put("priorLockReason", priorLockReason);
                }
            }
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize user audit payload.", exception);
        }
    }

    private record UserAuditSnapshot(
            String email,
            String status,
            String lspId,
            List<String> roles,
            boolean passwordChangeRequired
    ) {
    }

    private static String generateTemporaryPassword() {
        byte[] randomBytes = new byte[18];
        SECURE_RANDOM.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    public record ResetPasswordResult(AppUser user, String temporaryPassword) {
    }
}
