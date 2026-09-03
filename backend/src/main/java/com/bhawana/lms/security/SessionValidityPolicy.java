package com.bhawana.lms.security;

import com.bhawana.lms.domain.ApiClient;
import com.bhawana.lms.domain.ApiClientStatus;
import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.domain.AuthEventFailureReason;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.domain.UserStatus;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class SessionValidityPolicy {

    public enum InvalidReason {
        PASSWORD_CHANGED,
        SESSION_REVOKED,
        LSP_TOKEN_VERSION_STALE,
        API_CLIENT_TOKEN_VERSION_STALE,
        USER_INACTIVE,
        LSP_INACTIVE,
        API_CLIENT_INACTIVE,
        SUBJECT_MISSING
    }

    public record SessionClaims(
            Long passwordVersionMillis,
            Long tokenVersion,
            Long lspTokenVersion,
            Long apiClientTokenVersion,
            boolean checkVersions
    ) {
        public static SessionClaims forManagedUser(Jwt jwt) {
            return new SessionClaims(jwt.getClaim("pwdv"), jwt.getClaim("tv"), null, null, true);
        }

        public static SessionClaims forApiClient(Jwt jwt) {
            return new SessionClaims(
                    null,
                    null,
                    longClaim(jwt, ApiClientJwtSessionValidator.TV_LSP_CLAIM),
                    longClaim(jwt, ApiClientJwtSessionValidator.TV_API_CLIENT_CLAIM),
                    true
            );
        }

        public static SessionClaims statusOnly() {
            return new SessionClaims(null, null, null, null, false);
        }

        private static long longClaim(Jwt jwt, String claimName) {
            Long claim = jwt.getClaim(claimName);
            return claim == null ? 0L : claim.longValue();
        }
    }

    public record SubjectSnapshot(
            AuthPrincipalCache.AppUserSnapshot appUser,
            AuthPrincipalCache.ApiClientSnapshot apiClient
    ) {
        public static SubjectSnapshot of(AuthPrincipalCache.AppUserSnapshot snapshot) {
            return new SubjectSnapshot(snapshot, null);
        }

        public static SubjectSnapshot of(AuthPrincipalCache.ApiClientSnapshot snapshot) {
            return new SubjectSnapshot(null, snapshot);
        }
    }

    public record Result(boolean valid, InvalidReason reason) {
        public static Result ok() {
            return new Result(true, null);
        }

        public static Result invalid(InvalidReason reason) {
            return new Result(false, reason);
        }
    }

    public Result validate(SessionClaims claims, SubjectSnapshot subject) {
        if (subject.appUser() != null) {
            return validateManagedUser(claims, subject.appUser());
        }
        if (subject.apiClient() != null) {
            return validateApiClient(claims, subject.apiClient());
        }
        return Result.invalid(InvalidReason.SUBJECT_MISSING);
    }

    public static AuthPrincipalCache.AppUserSnapshot appUserSnapshot(AppUser appUser) {
        return new AuthPrincipalCache.AppUserSnapshot(
                appUser.getTokenVersion(),
                appUser.getPasswordChangedAt().toEpochMilli(),
                appUser.isPasswordChangeRequired(),
                appUser.getStatus(),
                appUser.getLsp() != null ? appUser.getLsp().getStatus() : null
        );
    }

    public static AuthPrincipalCache.ApiClientSnapshot apiClientSnapshot(ApiClient apiClient) {
        return new AuthPrincipalCache.ApiClientSnapshot(
                apiClient.getLsp().getTokenVersion(),
                apiClient.getTokenVersion(),
                apiClient.getLsp().getStatus(),
                apiClient.getStatus()
        );
    }

    public AuthEventFailureReason toAuthEventFailureReason(InvalidReason reason) {
        return switch (reason) {
            case USER_INACTIVE -> AuthEventFailureReason.USER_INACTIVE;
            case LSP_INACTIVE -> AuthEventFailureReason.LSP_INACTIVE;
            case PASSWORD_CHANGED, SESSION_REVOKED, LSP_TOKEN_VERSION_STALE, API_CLIENT_TOKEN_VERSION_STALE,
                 API_CLIENT_INACTIVE -> AuthEventFailureReason.SESSION_INVALID_STATUS;
            case SUBJECT_MISSING -> AuthEventFailureReason.OTHER;
        };
    }

    public OAuth2TokenValidatorResult toOAuth2Failure(InvalidReason reason) {
        return switch (reason) {
            case PASSWORD_CHANGED -> oauth2Failure("invalid_token", "Password has changed");
            case SESSION_REVOKED -> oauth2Failure("invalid_token", "Session is no longer valid");
            case LSP_TOKEN_VERSION_STALE -> oauth2Failure("LSP_TOKEN_REVOKED", "LSP session is no longer valid.");
            case API_CLIENT_TOKEN_VERSION_STALE -> oauth2Failure("API_CLIENT_TOKEN_REVOKED", "API client session is no longer valid.");
            case USER_INACTIVE -> oauth2Failure("USER_INACTIVE", "User is not active.");
            case LSP_INACTIVE -> oauth2Failure("LSP_INACTIVE", "LSP is not active.");
            case API_CLIENT_INACTIVE -> oauth2Failure("API_CLIENT_INACTIVE", "API client is not active.");
            case SUBJECT_MISSING -> oauth2Failure("invalid_token", "Session subject is missing.");
        };
    }

    private Result validateManagedUser(SessionClaims claims, AuthPrincipalCache.AppUserSnapshot snapshot) {
        if (claims.checkVersions()) {
            if (claims.passwordVersionMillis() == null
                    || claims.passwordVersionMillis().longValue() != snapshot.passwordChangedAtMillis()) {
                return Result.invalid(InvalidReason.PASSWORD_CHANGED);
            }

            long effectiveTokenSessionVersion = claims.tokenVersion() == null ? 0L : claims.tokenVersion().longValue();
            if (effectiveTokenSessionVersion != snapshot.tokenVersion()) {
                return Result.invalid(InvalidReason.SESSION_REVOKED);
            }
        }

        return validateSubjectStatus(snapshot.status(), snapshot.lspStatus());
    }

    private Result validateApiClient(SessionClaims claims, AuthPrincipalCache.ApiClientSnapshot snapshot) {
        if (claims.checkVersions()) {
            long tokenLspVersion = claims.lspTokenVersion() == null ? 0L : claims.lspTokenVersion();
            if (tokenLspVersion != snapshot.lspTokenVersion()) {
                return Result.invalid(InvalidReason.LSP_TOKEN_VERSION_STALE);
            }

            long tokenClientVersion = claims.apiClientTokenVersion() == null ? 0L : claims.apiClientTokenVersion();
            if (tokenClientVersion != snapshot.apiClientTokenVersion()) {
                return Result.invalid(InvalidReason.API_CLIENT_TOKEN_VERSION_STALE);
            }
        }

        if (snapshot.lspStatus() != LspStatus.ACTIVE) {
            return Result.invalid(InvalidReason.LSP_INACTIVE);
        }
        if (snapshot.apiClientStatus() != ApiClientStatus.ACTIVE) {
            return Result.invalid(InvalidReason.API_CLIENT_INACTIVE);
        }
        return Result.ok();
    }

    private Result validateSubjectStatus(UserStatus userStatus, LspStatus lspStatus) {
        if (userStatus != UserStatus.ACTIVE) {
            return Result.invalid(InvalidReason.USER_INACTIVE);
        }
        if (lspStatus != null && lspStatus != LspStatus.ACTIVE) {
            return Result.invalid(InvalidReason.LSP_INACTIVE);
        }
        return Result.ok();
    }

    private static OAuth2TokenValidatorResult oauth2Failure(String code, String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(code, description, null));
    }
}
