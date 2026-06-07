package com.bhawana.lms.support;

import com.bhawana.lms.tenant.TenantDataAccessContextHolder;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

/**
 * Gives {@code @SpringBootTest} fixture setup a default admin datasource scope.
 * Tests that assert missing context opt out via {@link RequiresEmptyTenantContext}.
 */
public class TenantContextTestExecutionListener extends AbstractTestExecutionListener {

    @Override
    public int getOrder() {
        return org.springframework.core.Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void beforeTestMethod(TestContext testContext) {
        if (requiresEmptyTenantContext(testContext)) {
            TenantDataAccessContextHolder.clear();
        } else {
            TenantDataAccessContextHolder.useAdmin();
        }
    }

    @Override
    public void afterTestMethod(TestContext testContext) {
        TenantDataAccessContextHolder.clear();
    }

    private static boolean requiresEmptyTenantContext(TestContext testContext) {
        if (AnnotatedElementUtils.hasAnnotation(testContext.getTestMethod(), RequiresEmptyTenantContext.class)) {
            return true;
        }
        return AnnotatedElementUtils.hasAnnotation(testContext.getTestClass(), RequiresEmptyTenantContext.class);
    }
}
