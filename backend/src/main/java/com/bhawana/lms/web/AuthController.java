package com.bhawana.lms.web;

import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.security.SecurityProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtEncoder jwtEncoder;
    private final SecurityProperties securityProperties;
    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthController(
            AuthenticationManager authenticationManager,
            JwtEncoder jwtEncoder,
            SecurityProperties securityProperties,
            AppUserRepository appUserRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.authenticationManager = authenticationManager;
        this.jwtEncoder = jwtEncoder;
        this.securityProperties = securityProperties;
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @PostMapping("/token")
    @ResponseStatus(HttpStatus.OK)
    public TokenResponse token(@Valid @RequestBody LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password())
        );
        return mintTokenResponse(authentication);
    }

    @PostMapping("/refresh")
    @ResponseStatus(HttpStatus.OK)
    public TokenResponse refresh(Authentication authentication) {
        return mintTokenResponse(authentication);
    }

    @PostMapping("/password")
    @ResponseStatus(HttpStatus.OK)
    public TokenResponse changePassword(Authentication authentication, @Valid @RequestBody ChangePasswordRequest request) {
        AppUser user = appUserRepository.findByUsernameIgnoreCase(authentication.getName())
                .orElseThrow(() -> new IllegalArgumentException("Password changes are only supported for managed users."));

        if (!user.isPasswordChangeRequired()) {
            throw new IllegalArgumentException("Password change is not required for this account.");
        }

        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("New password must differ from the temporary password.");
        }

        user.changePassword(passwordEncoder.encode(request.newPassword()));
        appUserRepository.save(user);
        return mintTokenResponse(authentication);
    }

    public record LoginRequest(
            @NotBlank String username,
            @NotBlank String password
    ) {
    }

    public record TokenResponse(
            String accessToken,
            String tokenType,
            long expiresInSeconds,
            boolean passwordChangeRequired
    ) {
    }

    public record ChangePasswordRequest(
            @NotBlank @Size(min = 12, max = 128) String newPassword
    ) {
    }

    private TokenResponse mintTokenResponse(Authentication authentication) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(securityProperties.getJwt().getTtl());
        List<String> roles = extractRoles(authentication);
        ManagedUserState managedUserState = loadManagedUserState(authentication.getName());

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(securityProperties.getJwt().getIssuer())
                .subject(authentication.getName())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim("roles", roles)
                .claim("pwdchg", managedUserState.passwordChangeRequired())
                .claim("pwdv", managedUserState.passwordChangedAt().toEpochMilli())
                .build();

        JwsHeader jwsHeader = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(jwsHeader, claims)).getTokenValue();
        return new TokenResponse(
                token,
                "Bearer",
                expiresAt.getEpochSecond() - issuedAt.getEpochSecond(),
                managedUserState.passwordChangeRequired()
        );
    }

    private static List<String> extractRoles(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> !"ROLE_PASSWORD_CHANGE_REQUIRED".equals(authority))
                .map(role -> role.startsWith("ROLE_") ? role.substring("ROLE_".length()) : role)
                .toList();
    }

    private ManagedUserState loadManagedUserState(String username) {
        return appUserRepository.findByUsernameIgnoreCase(username)
                .map(user -> new ManagedUserState(user.isPasswordChangeRequired(), user.getPasswordChangedAt()))
                .orElseGet(() -> new ManagedUserState(false, Instant.EPOCH));
    }

    private record ManagedUserState(boolean passwordChangeRequired, Instant passwordChangedAt) {
    }
}
