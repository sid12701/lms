package com.bhawana.lms.security;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitProperties {

    private boolean enabled = true;
    private List<RateLimitRule> rules = new ArrayList<>();

    /**
     * M10: cooldown between attempts to (re)establish the rate-limit Redis connection after a
     * connect failure. While down, matched routes apply their per-rule
     * {@code on-store-failure} policy instead of hammering Redis per request.
     */
    private Duration reconnectInterval = Duration.ofSeconds(5);

    /** Retry-After seconds sent with the bounded 503 of a FAIL_CLOSED route during an outage. */
    private long unavailableRetryAfterSeconds = 30;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<RateLimitRule> getRules() {
        return rules;
    }

    public void setRules(List<RateLimitRule> rules) {
        this.rules = rules == null ? new ArrayList<>() : rules;
    }

    public Duration getReconnectInterval() {
        return reconnectInterval;
    }

    public void setReconnectInterval(Duration reconnectInterval) {
        if (reconnectInterval != null) {
            this.reconnectInterval = reconnectInterval;
        }
    }

    public long getUnavailableRetryAfterSeconds() {
        return unavailableRetryAfterSeconds;
    }

    public void setUnavailableRetryAfterSeconds(long unavailableRetryAfterSeconds) {
        this.unavailableRetryAfterSeconds = unavailableRetryAfterSeconds;
    }

    @PostConstruct
    void validate() {
        if (reconnectInterval.isZero() || reconnectInterval.isNegative()) {
            throw new IllegalStateException("app.rate-limit.reconnect-interval must be positive.");
        }
        if (unavailableRetryAfterSeconds < 1) {
            throw new IllegalStateException("app.rate-limit.unavailable-retry-after-seconds must be positive.");
        }
        Set<String> ids = new HashSet<>();
        for (RateLimitRule rule : rules) {
            if (rule.getId() == null || rule.getId().isBlank()) {
                throw new IllegalStateException("app.rate-limit.rules entry is missing id.");
            }
            if (!ids.add(rule.getId())) {
                throw new IllegalStateException("Duplicate app.rate-limit.rules id: " + rule.getId());
            }
            if (rule.getPath() == null || rule.getPath().isBlank()) {
                throw new IllegalStateException("app.rate-limit.rules[" + rule.getId() + "] is missing path.");
            }
            if (rule.getMethods() == null || rule.getMethods().isEmpty()) {
                throw new IllegalStateException("app.rate-limit.rules[" + rule.getId() + "] is missing methods.");
            }
            if (rule.getKey() == null) {
                throw new IllegalStateException("app.rate-limit.rules[" + rule.getId() + "] is missing key.");
            }
            if (rule.getKey() == KeyStrategy.SUBJECT_AND_APPLICATION) {
                if (rule.getPermitsSubject() < 1 || rule.getPermitsApplication() < 1) {
                    throw new IllegalStateException(
                            "app.rate-limit.rules[" + rule.getId() + "] requires permitsSubject and permitsApplication."
                    );
                }
            } else if (rule.getPermitsPerMinute() < 1) {
                throw new IllegalStateException(
                        "app.rate-limit.rules[" + rule.getId() + "] requires permitsPerMinute."
                );
            }
        }
    }
}
