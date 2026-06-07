package com.bhawana.lms.security;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitPropertiesValidationTest {

    @Test
    void rejectsSubjectAndApplicationRuleWithoutDualPermits() {
        RateLimitProperties properties = new RateLimitProperties();
        RateLimitRule rule = new RateLimitRule();
        rule.setId("mock-outcome");
        rule.setPath("/api/v1/internal/ops/loan-applications/*/disbursement-requests/mock-outcome");
        rule.setMethods(List.of("POST"));
        rule.setKey(KeyStrategy.SUBJECT_AND_APPLICATION);
        rule.setPermitsSubject(0);
        rule.setPermitsApplication(0);
        properties.setRules(List.of(rule));

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("permitsSubject");
    }
}
