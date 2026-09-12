package com.bhawana.lms.service;

import com.bhawana.lms.common.util.AlertContextJson;
import com.bhawana.lms.common.util.Strings;
import com.bhawana.lms.domain.AlertRule;
import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.domain.UserStatus;
import com.bhawana.lms.domain.AuthEventFailureReason;
import com.bhawana.lms.domain.OpsAlertSeverity;
import com.bhawana.lms.domain.OpsAlertType;
import com.bhawana.lms.repo.AlertRuleRepository;
import com.bhawana.lms.repo.AppUserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Immediate human login brute-force counter and lockout.
 *
 * <p>Failed attempts are persisted in a committed {@code REQUIRES_NEW} transaction so the counter,
 * audit row and lockout survive the rollback of the enclosing failed authentication — the same
 * pattern as {@link ApiClientLockoutService}.
 */
@Service
public class HumanLoginLockoutService {

    private static final Logger log = LoggerFactory.getLogger(HumanLoginLockoutService.class);
    static final String AUTO_LOCKOUT_ACTOR = "SYSTEM_AUTO_LOCKOUT";

    private final AppUserRepository appUserRepository;
    private final AuthAuditService authAuditService;
    private final SessionRevocationService sessionRevocationService;
    private final AlertRuleProperties alertRuleProperties;
    private final OpsAlertService opsAlertService;
    private final AlertRuleRepository alertRuleRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate requiresNewTransactionTemplate;

    /**
     * Test-only seam: runs before {@code findByIdForUpdate} inside the committed failure fence
     * (before-lock overlap proof; after-lock would block the second thread on {@code FOR UPDATE}).
     */
    volatile Runnable preCounterLockHook;

    public HumanLoginLockoutService(
            AppUserRepository appUserRepository,
            AuthAuditService authAuditService,
            SessionRevocationService sessionRevocationService,
            AlertRuleProperties alertRuleProperties,
            OpsAlertService opsAlertService,
            AlertRuleRepository alertRuleRepository,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager
    ) {
        this.appUserRepository = appUserRepository;
        this.authAuditService = authAuditService;
        this.sessionRevocationService = sessionRevocationService;
        this.alertRuleProperties = alertRuleProperties;
        this.opsAlertService = opsAlertService;
        this.alertRuleRepository = alertRuleRepository;
        this.objectMapper = objectMapper;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void setPreCounterLockHookForTest(Runnable hook) {
        this.preCounterLockHook = hook;
    }

    public void registerFailedLogin(
            UUID userId,
            String username,
            AuthEventFailureReason failureReason,
            String actorIp,
            String correlationId
    ) {
        requiresNewTransactionTemplate.executeWithoutResult(status -> {
            Runnable hook = preCounterLockHook;
            if (hook != null) {
                hook.run();
            }
            AppUser locked = appUserRepository.findByIdForUpdate(userId).orElse(null);
            if (locked == null) {
                authAuditService.recordLoginFailure(username, failureReason, actorIp, correlationId);
                return;
            }
            // Only actual credential failures against an active, unlocked principal count.
            // Spring account-status/precondition failures still retain their audit record.
            if (failureReason != AuthEventFailureReason.INVALID_CREDENTIALS
                    || locked.getStatus() != UserStatus.ACTIVE || locked.isLocked()) {
                authAuditService.recordLoginFailure(username, failureReason, actorIp, correlationId);
                return;
            }
            Instant now = Instant.now();
            Duration window = Duration.ofMinutes(alertRuleProperties.getAuthBruteForceWindowMinutes());
            int threshold = alertRuleProperties.getAuthBruteForceThreshold();
            boolean thresholdCrossed = locked.registerFailedLogin(now, threshold, window);
            appUserRepository.save(locked);
            authAuditService.recordLoginFailure(username, failureReason, actorIp, correlationId);
            if (thresholdCrossed) {
                sessionRevocationService.applyBruteForceLockout(
                        userId,
                        AUTO_LOCKOUT_ACTOR,
                        AppUser.LOCK_REASON_BRUTE_FORCE,
                        actorIp,
                        correlationId);
                emitBruteForceAlertIfEnabled(locked, actorIp, locked.getFailedLoginAttempts(), now);
            }
        });
    }

    private void emitBruteForceAlertIfEnabled(
            AppUser user,
            String actorIp,
            int failureCount,
            Instant evaluatedAt
    ) {
        if (!isRuleEnabled("AUTH_BRUTE_FORCE")) {
            return;
        }
        String correlationId = "auth-brute-force:" + user.getId();
        String contextJson = AlertContextJson.serialize(
                objectMapper,
                log,
                Map.of(
                        "userId", user.getId().toString(),
                        "username", user.getUsername(),
                        "actorIp", actorIp,
                        "failureCount", failureCount,
                        "windowMinutes", alertRuleProperties.getAuthBruteForceWindowMinutes()
                ));
        opsAlertService.createAlertIfAbsent(
                OpsAlertType.AUTH_BRUTE_FORCE,
                OpsAlertSeverity.HIGH,
                "Auth brute-force lockout: " + user.getUsername(),
                "User "
                        + user.getUsername()
                        + " was locked after "
                        + failureCount
                        + " failed "
                        + Strings.pluralize(failureCount, "login")
                        + " from IP "
                        + actorIp
                        + " within "
                        + alertRuleProperties.getAuthBruteForceWindowMinutes()
                        + " "
                        + Strings.pluralize(alertRuleProperties.getAuthBruteForceWindowMinutes(), "minute")
                        + ".",
                "APP_USER",
                user.getId(),
                correlationId,
                contextJson
        );
    }

    private boolean isRuleEnabled(String code) {
        return alertRuleRepository.findByCode(code)
                .map(AlertRule::isEnabled)
                .orElse(false);
    }
}
