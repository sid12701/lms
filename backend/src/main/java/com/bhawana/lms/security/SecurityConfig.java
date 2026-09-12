package com.bhawana.lms.security;

import com.bhawana.lms.domain.AppRole;
import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.domain.UserStatus;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.tenant.TenantScopedExecution;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Authentication beans: the password encoder, the tenant-aware {@link UserDetailsService} backed
 * exclusively by managed {@code app_user} rows, and the {@link AuthenticationManager}. JWT beans
 * live in {@link JwtSecurityBeans}; the HTTP filter chain lives in
 * {@link SecurityFilterChainConfig}.
 *
 * <p>There is no configuration-password fallback: a username with no managed row (deleted,
 * never created, or merely matching the configured bootstrap name) cannot authenticate by
 * password. The bootstrap row is created at startup by LocalBootstrapAdminSyncService, so the
 * fallback is unnecessary; keeping it would let a deleted bootstrap subject mint fresh tokens
 * through the login surface. Recovery after deletion requires another active SYSTEM_ADMIN (peer
 * bootstrap-sync restore or admin password reset), never self-service by the deleted subject.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(AppUserRepository appUserRepository) {
        return username -> TenantScopedExecution.callAsAdmin(() -> appUserRepository.findByUsername(username)
                .map(SecurityConfig::appUserDetails)
                .orElseThrow(() -> new UsernameNotFoundException(username)));
    }

    @Bean
    AuthenticationManager authenticationManager(UserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    private static UserDetails appUserDetails(AppUser appUser) {
        List<String> roles = appUser.getRoles().stream()
                .map(AppRole::getCode)
                .map(Enum::name)
                .toList();

        boolean enabled = appUser.getStatus() == UserStatus.ACTIVE;
        return User.builder()
                .username(appUser.getUsername())
                .password(appUser.getPasswordHash())
                .roles(roles.toArray(String[]::new))
                .disabled(!enabled)
                .accountExpired(false)
                .accountLocked(false)
                .credentialsExpired(false)
                .build();
    }
}
