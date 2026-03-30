package com.bhawana.lms.service;

import com.bhawana.lms.domain.AppRole;
import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.domain.RoleCode;
import com.bhawana.lms.domain.UserStatus;
import com.bhawana.lms.repo.AppRoleRepository;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.repo.LspRepository;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminDirectoryService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final LspRepository lspRepository;
    private final AppRoleRepository appRoleRepository;
    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminDirectoryService(
            LspRepository lspRepository,
            AppRoleRepository appRoleRepository,
            AppUserRepository appUserRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.lspRepository = lspRepository;
        this.appRoleRepository = appRoleRepository;
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public Lsp createLsp(String code, String name, LspStatus status) {
        if (lspRepository.existsByCodeIgnoreCase(code)) {
            throw new IllegalArgumentException("LSP code already exists: " + code);
        }
        return lspRepository.save(new Lsp(code.trim().toUpperCase(), name.trim(), status));
    }

    @Transactional(readOnly = true)
    public List<Lsp> listLsps() {
        return lspRepository.findAll().stream()
                .sorted(java.util.Comparator.comparing(Lsp::getCode))
                .toList();
    }

    @Transactional
    public AppUser createUser(
            String username,
            String email,
            String rawPassword,
            UserStatus status,
            UUID lspId,
            Set<RoleCode> roleCodes
    ) {
        if (appUserRepository.existsByUsernameIgnoreCase(username)) {
            throw new IllegalArgumentException("Username already exists: " + username);
        }
        if (appUserRepository.existsByEmailIgnoreCase(email)) {
            throw new IllegalArgumentException("Email already exists: " + email);
        }

        List<AppRole> roles = appRoleRepository.findByCodeIn(roleCodes);
        if (roles.size() != roleCodes.size()) {
            throw new IllegalArgumentException("One or more requested roles are not available.");
        }

        Lsp lsp = null;
        if (lspId != null) {
            lsp = lspRepository.findById(lspId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown LSP id: " + lspId));
        }

        AppUser user = new AppUser(
                username.trim(),
                email.trim().toLowerCase(),
                passwordEncoder.encode(rawPassword),
                status,
                lsp,
                new LinkedHashSet<>(roles)
        );

        return appUserRepository.save(user);
    }

    @Transactional(readOnly = true)
    public List<AppUser> listUsers() {
        return appUserRepository.findAll().stream()
                .sorted(java.util.Comparator.comparing(AppUser::getUsername))
                .toList();
    }

    @Transactional
    public ResetPasswordResult resetUserPassword(UUID userId) {
        AppUser user = appUserRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown user id: " + userId));

        String temporaryPassword = generateTemporaryPassword();
        user.requirePasswordChange(passwordEncoder.encode(temporaryPassword));
        appUserRepository.save(user);

        return new ResetPasswordResult(user, temporaryPassword);
    }

    private static String generateTemporaryPassword() {
        byte[] randomBytes = new byte[18];
        SECURE_RANDOM.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    public record ResetPasswordResult(AppUser user, String temporaryPassword) {
    }
}
