package com.bhawana.lms.service;

import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.tenant.TenantScopedExecution;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SystemContextService {

    private final AppUserRepository appUserRepository;

    public SystemContextService(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
    }

    @Transactional(readOnly = true)
    public UUID resolveUserId(String username) {
        return TenantScopedExecution.callAsAdmin(() -> appUserRepository.findByUsername(username)
                .map(AppUser::getId)
                .orElseGet(() -> deterministicBootstrapId(username)));
    }

    private static UUID deterministicBootstrapId(String username) {
        return UUID.nameUUIDFromBytes(("lms-bootstrap:" + username).getBytes(StandardCharsets.UTF_8));
    }
}
