package com.bhawana.lms.service;

import com.bhawana.lms.common.web.PagedResult;
import com.bhawana.lms.domain.ApiClient;
import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.domain.AuthEventAudit;
import com.bhawana.lms.domain.AuthEventFailureReason;
import com.bhawana.lms.domain.AuthEventType;
import com.bhawana.lms.domain.RevocationSource;
import com.bhawana.lms.repo.ApiClientRepository;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.repo.AuthEventAuditRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.AccountStatusException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthAuditService {

    public static final String ANONYMOUS_USERNAME = "<anonymous>";
    public static final String UNKNOWN_USERNAME = "<unknown>";

    private final AuthEventAuditRepository authEventAuditRepository;
    private final AppUserRepository appUserRepository;
    private final ApiClientRepository apiClientRepository;
    private final ObjectMapper objectMapper;

    public AuthAuditService(
            AuthEventAuditRepository authEventAuditRepository,
            AppUserRepository appUserRepository,
            ApiClientRepository apiClientRepository,
            ObjectMapper objectMapper
    ) {
        this.authEventAuditRepository = authEventAuditRepository;
        this.appUserRepository = appUserRepository;
        this.apiClientRepository = apiClientRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public PagedResult<AuthEventAudit> search(
            String username,
            AuthEventType eventType,
            int offset,
            int limit,
            boolean includeTotalCount
    ) {
        int resolvedLimit = Math.max(1, Math.min(limit, 500));
        int resolvedOffset = Math.max(0, offset);
        int pageIndex = resolvedOffset / resolvedLimit;

        Page<AuthEventAudit> page = authEventAuditRepository.search(
                blankToNull(username),
                eventType,
                PageRequest.of(pageIndex, resolvedLimit)
        );

        long totalCount = includeTotalCount ? page.getTotalElements() : -1L;
        return new PagedResult<>(page.getContent(), totalCount, resolvedOffset, resolvedLimit);
    }

    @Transactional
    public void recordLoginSuccess(String username, AppUser user, String actorIp, String correlationId) {
        save(username, userIdOf(user), null, AuthEventType.LOGIN_SUCCEEDED, null, actorIp, correlationId);
    }

    @Transactional
    public void recordLoginFailure(
            String username,
            AuthEventFailureReason failureReason,
            String actorIp,
            String correlationId
    ) {
        save(username, resolveUserId(username), null, AuthEventType.LOGIN_FAILED, failureReason, actorIp, correlationId);
    }

    @Transactional
    public void recordLoginFailureFromException(
            String username,
            AuthenticationException exception,
            String actorIp,
            String correlationId
    ) {
        recordLoginFailure(username, mapAuthenticationFailure(exception), actorIp, correlationId);
    }

    @Transactional
    public void recordApiClientTokenSuccess(ApiClient apiClient, String actorIp, String correlationId) {
        save(
                apiClient.getClientId(),
                null,
                apiClient.getId(),
                AuthEventType.API_CLIENT_TOKEN_SUCCEEDED,
                null,
                actorIp,
                correlationId
        );
    }

    @Transactional
    public void recordApiClientTokenFailure(
            String clientId,
            AuthEventFailureReason failureReason,
            String actorIp,
            String correlationId
    ) {
        UUID apiClientId = apiClientRepository.findByClientId(clientId.trim())
                .map(ApiClient::getId)
                .orElse(null);
        save(clientId.trim(), null, apiClientId, AuthEventType.API_CLIENT_TOKEN_FAILED, failureReason, actorIp, correlationId);
    }

    @Transactional
    public void recordTokenRefreshSuccess(
            String username,
            UUID userId,
            UUID apiClientId,
            String actorIp,
            String correlationId
    ) {
        save(username, userId, apiClientId, AuthEventType.TOKEN_REFRESH_SUCCEEDED, null, actorIp, correlationId);
    }

    @Transactional
    public void recordTokenRefreshFailure(
            String username,
            AuthEventFailureReason failureReason,
            String actorIp,
            String correlationId
    ) {
        save(username, resolveUserId(username), null, AuthEventType.TOKEN_REFRESH_FAILED, failureReason, actorIp, correlationId);
    }

    @Transactional
    public void recordLogout(String username, UUID userId, String actorIp, String correlationId) {
        save(username, userId, null, AuthEventType.LOGOUT, null, actorIp, correlationId);
    }

    @Transactional
    public void recordPasswordChanged(AppUser user, String actorIp, String correlationId) {
        save(user.getUsername(), user.getId(), null, AuthEventType.PASSWORD_CHANGED, null, actorIp, correlationId);
    }

    @Transactional
    public void recordSessionsRevoked(
            AppUser targetUser,
            String actorUsername,
            String reasonOrNull,
            String actorIp,
            String correlationId,
            RevocationSource source,
            long previousTokenVersion,
            long newTokenVersion,
            int refreshTokensRevoked
    ) {
        ObjectNode details = objectMapper.createObjectNode();
        details.put("source", source.name());
        details.put("actorUsername", actorUsername);
        if (reasonOrNull != null && !reasonOrNull.isBlank()) {
            details.put("reason", reasonOrNull.trim());
        }
        details.put("previousTokenVersion", previousTokenVersion);
        details.put("newTokenVersion", newTokenVersion);
        details.put("refreshTokensRevoked", refreshTokensRevoked);

        authEventAuditRepository.save(new AuthEventAudit(
                targetUser.getUsername(),
                targetUser.getId(),
                null,
                AuthEventType.SESSIONS_REVOKED_BY_ADMIN,
                null,
                actorIp,
                correlationId,
                details
        ));
    }

    private void save(
            String username,
            UUID userId,
            UUID apiClientId,
            AuthEventType eventType,
            AuthEventFailureReason failureReason,
            String actorIp,
            String correlationId
    ) {
        authEventAuditRepository.save(new AuthEventAudit(
                username,
                userId,
                apiClientId,
                eventType,
                failureReason,
                actorIp,
                correlationId
        ));
    }

    private static UUID userIdOf(AppUser user) {
        return user != null ? user.getId() : null;
    }

    private UUID resolveUserId(String username) {
        if (username == null || username.isBlank() || ANONYMOUS_USERNAME.equals(username) || UNKNOWN_USERNAME.equals(username)) {
            return null;
        }
        return appUserRepository.findByUsername(username).map(AppUser::getId).orElse(null);
    }

    private static AuthEventFailureReason mapAuthenticationFailure(AuthenticationException exception) {
        if (exception instanceof BadCredentialsException) {
            return AuthEventFailureReason.INVALID_CREDENTIALS;
        }
        if (exception instanceof LockedException) {
            return AuthEventFailureReason.ACCOUNT_LOCKED;
        }
        if (exception instanceof DisabledException) {
            return AuthEventFailureReason.ACCOUNT_DISABLED;
        }
        if (exception instanceof AccountStatusException) {
            return AuthEventFailureReason.OTHER;
        }
        if (exception instanceof AuthenticationServiceException) {
            return AuthEventFailureReason.OTHER;
        }
        return AuthEventFailureReason.OTHER;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
