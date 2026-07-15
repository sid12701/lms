package com.bhawana.lms.service;

import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.tenant.AdminScopedTransactionExecutor;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class SystemContextService {

    private final AppUserRepository appUserRepository;
    private final AdminScopedTransactionExecutor adminScopedTransactionExecutor;

    public SystemContextService(
            AppUserRepository appUserRepository,
            AdminScopedTransactionExecutor adminScopedTransactionExecutor
    ) {
        this.appUserRepository = appUserRepository;
        this.adminScopedTransactionExecutor = adminScopedTransactionExecutor;
    }

    /**
     * Resolves the app-user id for any authenticated principal, including LSP-scoped ones.
     * The admin scope must be active before the transaction acquires its connection —
     * an outer {@code @Transactional} would bind a tenant-routed connection for LSP
     * requests and the {@code app_user} lookup would fail on permissions.
     */
    public UUID resolveUserId(String username) {
        return adminScopedTransactionExecutor.call(() -> appUserRepository.findByUsername(username)
                .map(AppUser::getId)
                .orElseGet(() -> deterministicBootstrapId(username)));
    }

    private static UUID deterministicBootstrapId(String username) {
        return UUID.nameUUIDFromBytes(("lms-bootstrap:" + username).getBytes(StandardCharsets.UTF_8));
    }
}
