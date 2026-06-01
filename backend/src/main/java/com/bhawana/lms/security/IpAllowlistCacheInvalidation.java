package com.bhawana.lms.security;

import com.bhawana.lms.domain.LspIpAllowlistSurface;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class IpAllowlistCacheInvalidation {

    private IpAllowlistCacheInvalidation() {
    }

    public static void afterCommit(LspSurfaceIpAllowlistFilter filter, UUID lspId, LspIpAllowlistSurface surface) {
        Runnable invalidate = () -> filter.invalidateCache(lspId, surface);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    invalidate.run();
                }
            });
        } else {
            invalidate.run();
        }
    }

    public static void afterCommitAllSurfaces(LspSurfaceIpAllowlistFilter filter, UUID lspId) {
        afterCommit(filter, lspId, LspIpAllowlistSurface.UI);
        afterCommit(filter, lspId, LspIpAllowlistSurface.API);
    }
}
