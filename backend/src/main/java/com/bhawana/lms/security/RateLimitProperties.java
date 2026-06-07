package com.bhawana.lms.security;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitProperties {

    private boolean enabled = true;
    private List<RateLimitRule> rules = new ArrayList<>();

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

    @PostConstruct
    void validate() {
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
