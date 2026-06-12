package com.bhawana.lms.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TenantDataAccessContextHolderTest {

    @AfterEach
    void tearDown() {
        TenantDataAccessContextHolder.clear();
    }

    @Test
    void getModeThrowsWhenNoContextSet() {
        TenantDataAccessContextHolder.clear();
        assertThrows(MissingTenantContextException.class, TenantDataAccessContextHolder::getMode);
    }

    @Test
    void getCurrentLspIdThrowsWhenNoContextSet() {
        TenantDataAccessContextHolder.clear();
        assertThrows(MissingTenantContextException.class, TenantDataAccessContextHolder::getCurrentLspId);
    }

    @Test
    void snapshotReturnsNullWhenNoContextSet() {
        TenantDataAccessContextHolder.clear();
        assertNull(TenantDataAccessContextHolder.snapshot());
    }

    @Test
    void restoreNullClearsContext() {
        TenantDataAccessContextHolder.useAdmin();
        TenantDataAccessContextHolder.restore(null);
        assertThrows(MissingTenantContextException.class, TenantDataAccessContextHolder::getMode);
    }

    @Test
    void restoreReinstallsPriorTenantScope() {
        UUID lspId = UUID.randomUUID();
        TenantDataAccessContextHolder.useTenant(lspId);
        TenantAccessContext previous = TenantDataAccessContextHolder.snapshot();

        TenantDataAccessContextHolder.useAdmin();
        TenantDataAccessContextHolder.restore(previous);

        assertEquals(TenantDataAccessMode.TENANT, TenantDataAccessContextHolder.getMode());
        assertEquals(lspId, TenantDataAccessContextHolder.getCurrentLspId());
    }

    @Test
    void runAsAdminRestoresPriorScopeAfterSuccessfulSupplier() {
        UUID lspId = UUID.randomUUID();
        TenantDataAccessContextHolder.useTenant(lspId);

        String result = TenantDataAccessContextHolder.runAsAdmin(() -> {
            assertEquals(TenantDataAccessMode.ADMIN, TenantDataAccessContextHolder.getMode());
            return "done";
        });

        assertEquals("done", result);
        assertEquals(TenantDataAccessMode.TENANT, TenantDataAccessContextHolder.getMode());
        assertEquals(lspId, TenantDataAccessContextHolder.getCurrentLspId());
    }

    @Test
    void runAsAdminRestoresPriorScopeWhenSupplierThrows() {
        UUID lspId = UUID.randomUUID();
        TenantDataAccessContextHolder.useTenant(lspId);

        assertThrows(IllegalStateException.class, () -> TenantDataAccessContextHolder.runAsAdmin(() -> {
            assertEquals(TenantDataAccessMode.ADMIN, TenantDataAccessContextHolder.getMode());
            throw new IllegalStateException("borrower dedupe failed");
        }));

        assertEquals(TenantDataAccessMode.TENANT, TenantDataAccessContextHolder.getMode());
        assertEquals(lspId, TenantDataAccessContextHolder.getCurrentLspId());
    }
}
