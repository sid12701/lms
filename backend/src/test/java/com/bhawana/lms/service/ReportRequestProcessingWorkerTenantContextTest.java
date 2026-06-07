package com.bhawana.lms.service;

import com.bhawana.lms.support.TenantContextTestExecutionListener;
import org.springframework.test.context.TestExecutionListeners;

import static org.junit.jupiter.api.Assertions.assertNull;

import com.bhawana.lms.support.RequiresEmptyTenantContext;
import com.bhawana.lms.tenant.TenantDataAccessContextHolder;
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
class ReportRequestProcessingWorkerTenantContextTest {

    @Autowired
    private ReportRequestProcessingWorker reportRequestProcessingWorker;

    @AfterEach
    void tearDown() {
        TenantDataAccessContextHolder.clear();
    }

    @Test
    void processPendingRequestsRestoresEmptyTenantContextOnWorkerThread() {
        TenantDataAccessContextHolder.clear();
        assertNull(TenantDataAccessContextHolder.snapshot());

        reportRequestProcessingWorker.processPendingRequests();

        assertNull(TenantDataAccessContextHolder.snapshot());
    }
}
