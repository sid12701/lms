package com.bhawana.lms.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.security")
public class SecurityProperties {

    @Valid
    private final BootstrapUser bootstrapUser = new BootstrapUser();

    @Valid
    private final Jwt jwt = new Jwt();

    @Valid
    private final EntraMachineIdentity entraMachineIdentity = new EntraMachineIdentity();

    @Valid
    private final Cors cors = new Cors();

    /**
     * Per-process JWT principal snapshot TTL (L04). Revocation paths evict explicitly
     * on the mutating instance; every other instance converges within this bound.
     * The documented maximum cross-instance revocation delay for principal state
     * (token version, status, lockout) is this TTL.
     */
    @NotNull
    private Duration principalCacheTtl = Duration.ofSeconds(30);

    /**
     * Per-process LSP IP-allowlist snapshot TTL (L04). An allowlist change made on one
     * instance can take up to this long to take effect on another instance — the
     * documented maximum revocation/enforcement delay for allowlist state.
     */
    @NotNull
    private Duration lspIpAllowlistCacheTtl = Duration.ofSeconds(60);

    @Valid
    private final RefreshTokenRetention refreshTokenRetention = new RefreshTokenRetention();

    public BootstrapUser getBootstrapUser() {
        return bootstrapUser;
    }

    public Jwt getJwt() {
        return jwt;
    }

    public EntraMachineIdentity getEntraMachineIdentity() {
        return entraMachineIdentity;
    }

    public Cors getCors() {
        return cors;
    }

    public Duration getPrincipalCacheTtl() {
        return principalCacheTtl;
    }

    public void setPrincipalCacheTtl(Duration principalCacheTtl) {
        this.principalCacheTtl = principalCacheTtl;
    }

    public Duration getLspIpAllowlistCacheTtl() {
        return lspIpAllowlistCacheTtl;
    }

    public void setLspIpAllowlistCacheTtl(Duration lspIpAllowlistCacheTtl) {
        this.lspIpAllowlistCacheTtl = lspIpAllowlistCacheTtl;
    }

    public RefreshTokenRetention getRefreshTokenRetention() {
        return refreshTokenRetention;
    }

    /**
     * Retention policy for {@code refresh_token} rows (L04). Only rows past
     * {@code expires_at + expiredRetentionDays} are purged, in bounded batches: a
     * revoked-but-unexpired row is the evidence family reuse detection needs
     * (replayed presentation → family revoke), so the predicate is expiry-based
     * and never touches anything a client could still present successfully.
     */
    public static class RefreshTokenRetention {

        private boolean enabled = true;

        /** Post-expiry forensic margin before a dead refresh row is purged. */
        @Min(0)
        private int expiredRetentionDays = 30;

        @Min(1)
        private long purgeFixedDelayMs = 3_600_000L;

        /** Rows deleted per transaction — keeps each purge transaction short. */
        @Min(1)
        private int batchSize = 500;

        /** Cap on batches per run; a larger backlog drains over subsequent runs. */
        @Min(1)
        private int maxBatchesPerRun = 40;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getExpiredRetentionDays() {
            return expiredRetentionDays;
        }

        public void setExpiredRetentionDays(int expiredRetentionDays) {
            this.expiredRetentionDays = expiredRetentionDays;
        }

        public long getPurgeFixedDelayMs() {
            return purgeFixedDelayMs;
        }

        public void setPurgeFixedDelayMs(long purgeFixedDelayMs) {
            this.purgeFixedDelayMs = purgeFixedDelayMs;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getMaxBatchesPerRun() {
            return maxBatchesPerRun;
        }

        public void setMaxBatchesPerRun(int maxBatchesPerRun) {
            this.maxBatchesPerRun = maxBatchesPerRun;
        }
    }

    public static class BootstrapUser {

        /**
         * No code defaults. The bootstrap identity must be configured explicitly
         * (application-local.yml carries the explicit local example; every other profile sets
         * APP_SECURITY_BOOTSTRAP_* via the secret store). Missing values fail closed at bind
         * time and again in UnsafeDeploymentConfigurationValidator outside dev-exempt profiles.
         */
        @NotBlank
        private String username;

        @NotBlank
        private String email;

        @NotBlank
        private String password;

        @NotEmpty
        private List<String> roles = new ArrayList<>();

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        /**
         * Email the bootstrap admin signs in with. Explicitly configured; no derived default so
         * production-like profiles cannot inherit a personal/example identity.
         */
        public String getEmail() {
            return email == null ? null : email.trim().toLowerCase();
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public List<String> getRoles() {
            return roles;
        }

        public void setRoles(List<String> roles) {
            this.roles = roles;
        }
    }

    public static class Jwt {

        @NotBlank
        @Size(min = 32, message = "JWT secret must be at least 32 characters (256 bits) for HMAC-SHA256")
        private String secret;

        @NotBlank
        private String issuer = "bhawana-lms";

        /**
         * Audience minted into locally issued human tokens and required back at
         * verification. Distinct from {@link #machineAudience} and from any future Entra
         * audience. Changing the secret or the audience invalidates outstanding ACCESS tokens
         * with no compatibility window (see JwtSecurityBeans); opaque refresh rows survive and
         * re-mint under the current policy, so secret replacement alone does NOT force full
         * reauthentication — that cutover is the pending refresh-family/policy epoch,
         * explicitly out of scope here.
         */
        @NotBlank
        private String humanAudience = "bhawana-lms-human";

        /**
         * Audience minted into locally issued machine (API-client) tokens and required back
         * at verification on the machine branch only. Never accept this audience on the human
         * branch or vice versa. Must differ from {@link #humanAudience}: equal audiences fail
         * configuration validation (see {@link #isAudiencesDistinct} and the startup check in
         * {@code UnsafeDeploymentConfigurationValidator}), because a shared audience would let
         * one branch's tokens verify on the other.
         */
        @NotBlank
        private String machineAudience = "bhawana-lms-machine";

        private Duration ttl = Duration.ofMinutes(30);

        private Duration refreshTtl = Duration.ofDays(7);

        private boolean secureCookies = true;

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public String getHumanAudience() {
            return humanAudience;
        }

        public void setHumanAudience(String humanAudience) {
            this.humanAudience = humanAudience;
        }

        public String getMachineAudience() {
            return machineAudience;
        }

        public void setMachineAudience(String machineAudience) {
            this.machineAudience = machineAudience;
        }

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration ttl) {
            this.ttl = ttl;
        }

        public Duration getRefreshTtl() {
            return refreshTtl;
        }

        public void setRefreshTtl(Duration refreshTtl) {
            this.refreshTtl = refreshTtl;
        }

        public boolean isSecureCookies() {
            return secureCookies;
        }

        public void setSecureCookies(boolean secureCookies) {
            this.secureCookies = secureCookies;
        }

        /**
         * The human and machine audiences denote distinct intended recipients, so equal
         * values fail bind-time configuration validation. Operators who need to rotate an
         * audience set exactly one of the two properties.
         */
        @AssertTrue(message = "app.security.jwt.human-audience and app.security.jwt.machine-audience must differ")
        public boolean isAudiencesDistinct() {
            return humanAudience != null && machineAudience != null && !humanAudience.equals(machineAudience);
        }
    }

    /**
     * Entra v2 app-only machine identity. Disabled by default; enabling requires explicit
     * trusted tenant/issuer, API audience, JWKS location and at least one verified mapping to an
     * enabled local api_client + ACTIVE LSP. No invented tenant IDs or credentials.
     */
    public static class EntraMachineIdentity {

        private boolean enabled;

        private String trustedTenantId;

        /** Entra v2 issuer, e.g. {@code https://login.microsoftonline.com/{tenant}/v2.0}. */
        private String issuer;

        /** LMS API application (resource) audience — the v2 access-token aud claim. */
        private String apiAudience;

        /** Fixed trusted JWKS URL; token {@code jku}/{@code x5u} are never consulted. */
        private String jwksUri;

        /** Assigned app role (or permission) required on the access token. */
        private String requiredAppRole;

        private Duration connectTimeout = Duration.ofSeconds(2);

        private Duration readTimeout = Duration.ofSeconds(2);

        private Duration jwksCacheTtl = Duration.ofMinutes(10);

        private Duration unknownKidMinInterval = Duration.ofSeconds(30);

        private List<Mapping> mappings = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getTrustedTenantId() {
            return trustedTenantId;
        }

        public void setTrustedTenantId(String trustedTenantId) {
            this.trustedTenantId = trustedTenantId;
        }

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public String getApiAudience() {
            return apiAudience;
        }

        public void setApiAudience(String apiAudience) {
            this.apiAudience = apiAudience;
        }

        public String getJwksUri() {
            return jwksUri;
        }

        public void setJwksUri(String jwksUri) {
            this.jwksUri = jwksUri;
        }

        public String getRequiredAppRole() {
            return requiredAppRole;
        }

        public void setRequiredAppRole(String requiredAppRole) {
            this.requiredAppRole = requiredAppRole;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }

        public Duration getJwksCacheTtl() {
            return jwksCacheTtl;
        }

        public void setJwksCacheTtl(Duration jwksCacheTtl) {
            this.jwksCacheTtl = jwksCacheTtl;
        }

        public Duration getUnknownKidMinInterval() {
            return unknownKidMinInterval;
        }

        public void setUnknownKidMinInterval(Duration unknownKidMinInterval) {
            this.unknownKidMinInterval = unknownKidMinInterval;
        }

        public List<Mapping> getMappings() {
            return mappings;
        }

        public void setMappings(List<Mapping> mappings) {
            this.mappings = mappings == null ? new ArrayList<>() : mappings;
        }

        public boolean matchesIssuer(String tokenIssuer) {
            return enabled && issuer != null && issuer.equals(tokenIssuer);
        }

        @AssertTrue(message = "entra-machine-identity requires trusted-tenant-id, issuer, api-audience and jwks-uri when enabled")
        public boolean isCompleteWhenEnabled() {
            if (!enabled) {
                return true;
            }
            return trustedTenantId != null && !trustedTenantId.isBlank()
                    && issuer != null && !issuer.isBlank()
                    && apiAudience != null && !apiAudience.isBlank()
                    && jwksUri != null && !jwksUri.isBlank()
                    && requiredAppRole != null && !requiredAppRole.isBlank();
        }

        @AssertTrue(message = "JWKS connect/read timeouts must be between 1ms and 2147483647ms when enabled")
        public boolean isFiniteJwksTimeoutConfiguration() {
            return !enabled || (isFiniteHttpTimeout(connectTimeout) && isFiniteHttpTimeout(readTimeout));
        }

        private static boolean isFiniteHttpTimeout(Duration timeout) {
            return timeout != null
                    && timeout.compareTo(Duration.ofMillis(1)) >= 0
                    && timeout.compareTo(Duration.ofMillis(Integer.MAX_VALUE)) <= 0;
        }

        public static class Mapping {

            private String externalTenantId;

            private String externalClientId;

            private UUID localApiClientId;

            private boolean enabled = true;

            private Instant notBefore;

            /** Non-null means the mapping is revoked for every token until explicitly cleared. */
            private Instant revokedAt;

            public String getExternalTenantId() {
                return externalTenantId;
            }

            public void setExternalTenantId(String externalTenantId) {
                this.externalTenantId = externalTenantId;
            }

            public String getExternalClientId() {
                return externalClientId;
            }

            public void setExternalClientId(String externalClientId) {
                this.externalClientId = externalClientId;
            }

            public UUID getLocalApiClientId() {
                return localApiClientId;
            }

            public void setLocalApiClientId(UUID localApiClientId) {
                this.localApiClientId = localApiClientId;
            }

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public Instant getNotBefore() {
                return notBefore;
            }

            public void setNotBefore(Instant notBefore) {
                this.notBefore = notBefore;
            }

            public Instant getRevokedAt() {
                return revokedAt;
            }

            public void setRevokedAt(Instant revokedAt) {
                this.revokedAt = revokedAt;
            }
        }
    }

    /**
     * Credentialed cross-origin browser allowlist for the separately hosted SPA.
     *
     * <p>Bound from {@code app.security.cors.allowed-origins} (env
     * {@code APP_SECURITY_CORS_ALLOWED_ORIGINS}, comma separated). No code defaults: an
     * empty list fails closed — every cross-origin browser call is denied — which is the
     * correct posture for a deployment that never configured an SPA origin. Local
     * dev-server origins live only in {@code application-local.yml}; real deployments set
     * the env var explicitly. Wildcard origins are rejected where the CORS source is built
     * ({@code SecurityFilterChainConfig}) because {@code allowCredentials=true} requires
     * explicit origins. See {@code docs/spa-deployment-contract.md}.
     */
    public static class Cors {

        private List<String> allowedOrigins = new ArrayList<>();

        public List<String> getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(List<String> allowedOrigins) {
            if (allowedOrigins == null) {
                this.allowedOrigins = new ArrayList<>();
                return;
            }
            // Drop blank entries so an unset env placeholder binds to "no allowed origins"
            // instead of a phantom blank origin (same convention as EdgeProperties).
            List<String> cleaned = new ArrayList<>();
            for (String entry : allowedOrigins) {
                if (entry != null && !entry.isBlank()) {
                    cleaned.add(entry.trim());
                }
            }
            this.allowedOrigins = cleaned;
        }
    }
}
