package com.bhawana.lms.tenant;

import com.bhawana.lms.support.TenantContextTestExecutionListener;
import org.springframework.test.context.TestExecutionListeners;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.support.RequiresEmptyTenantContext;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@RequiresEmptyTenantContext
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class TenantContextRegressionTest {

    @Autowired
    private LoanApplicationRepository loanApplicationRepository;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void tearDown() {
        TenantDataAccessContextHolder.clear();
    }

    @Test
    void holderAccessWithoutTenantContextThrows() {
        TenantDataAccessContextHolder.clear();

        assertThrows(MissingTenantContextException.class, TenantDataAccessContextHolder::getMode);
    }

    @Test
    void repositoryAccessWithoutTenantContextThrows() {
        TenantDataAccessContextHolder.clear();

        Exception thrown = org.junit.jupiter.api.Assertions.assertThrows(
                Exception.class,
                () -> {
                    loanApplicationRepository.count();
                    dataSource.getConnection().close();
                }
        );

        assertNotNull(findMissingTenantContextCause(thrown));
    }

    private static MissingTenantContextException findMissingTenantContextCause(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof MissingTenantContextException missingTenantContext) {
                return missingTenantContext;
            }
            current = current.getCause();
        }
        return null;
    }

    @Test
    void callAsAdminAllowsRepositoryAccess() {
        TenantDataAccessContextHolder.clear();

        long count = TenantScopedExecution.callAsAdmin(loanApplicationRepository::count);

        assertTrue(count >= 0);
        assertNull(TenantDataAccessContextHolder.snapshot());
    }
}
