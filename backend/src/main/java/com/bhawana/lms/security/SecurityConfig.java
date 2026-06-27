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
 * Authentication beans: the password encoder, the tenant-aware {@link UserDetailsService} (with the
 * configured bootstrap user), and the {@link AuthenticationManager}. JWT beans live in
 * {@link JwtSecurityBeans}; the HTTP filter chain lives in {@link SecurityFilterChainConfig}.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(
            SecurityProperties securityProperties,
            AppUserRepository appUserRepository,
            PasswordEncoder passwordEncoder
    ) {
        SecurityProperties.BootstrapUser bootstrapUser = securityProperties.getBootstrapUser();
        UserDetails bootstrapUserDetails = bootstrapUserDetails(bootstrapUser, passwordEncoder);

        return username -> TenantScopedExecution.callAsAdmin(() -> appUserRepository.findByUsername(username)
                .map(SecurityConfig::appUserDetails)
                .orElseGet(() -> {
                    if (bootstrapUser.getUsername().equalsIgnoreCase(username)) {
                        return bootstrapUserDetails;
                    }
                    throw new UsernameNotFoundException(username);
                }));
    }

    @Bean
    AuthenticationManager authenticationManager(UserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    private static UserDetails bootstrapUserDetails(SecurityProperties.BootstrapUser bootstrapUser, PasswordEncoder passwordEncoder) {
        List<String> roles = bootstrapUser.getRoles().stream()
                .map(role -> role.startsWith("ROLE_") ? role.substring("ROLE_".length()) : role)
                .toList();

        return User.builder()
                .username(bootstrapUser.getUsername())
                .password(passwordEncoder.encode(bootstrapUser.getPassword()))
                .roles(roles.toArray(String[]::new))
                .disabled(false)
                .accountExpired(false)
                .accountLocked(false)
                .credentialsExpired(false)
                .build();
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
