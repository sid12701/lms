package com.bhawana.lms.web;

import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.security.SecurityProperties;
import com.bhawana.lms.service.ApiClientAuthenticationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
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
    private final ApiClientAuthenticationService apiClientAuthenticationService;

    public AuthController(
            AuthenticationManager authenticationManager,
            JwtEncoder jwtEncoder,
            SecurityProperties securityProperties,
            AppUserRepository appUserRepository,
            PasswordEncoder passwordEncoder,
            ApiClientAuthenticationService apiClientAuthenticationService
    ) {
        this.authenticationManager = authenticationManager;
        this.jwtEncoder = jwtEncoder;
        this.securityProperties = securityProperties;
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.apiClientAuthenticationService = apiClientAuthenticationService;
    }

    @PostMapping("/token")
    @ResponseStatus(HttpStatus.OK)
    public TokenResponse token(@RequestBody TokenRequest request) {
        String grantType = normalizeGrantType(request.grantType());
        return switch (grantType) {
            case "password" -> issuePasswordToken(request);
            case "client_credentials" -> issueClientCredentialsToken(request);
            default -> throw new IllegalArgumentException("Unsupported grantType: " + request.grantType());
        };
    }

    @PostMapping("/refresh")
    @ResponseStatus(HttpStatus.OK)
    public TokenResponse refresh(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwtAuthenticationToken) {
            return mintTokenResponse(jwtAuthenticationToken.getToken());
        }
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

    public record ClientCredentialsRequest(
            @NotBlank String clientId,
            @NotBlank String clientSecret
    ) {
    }

    public record TokenRequest(
            String grantType,
            String username,
            String password,
            String clientId,
            String clientSecret
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
        return mintTokenResponse(
                authentication.getName(),
                extractRoles(authentication),
                loadManagedUserState(authentication.getName()),
                loadManagedUserClaims(authentication.getName())
        );
    }

    private TokenResponse mintTokenResponse(Jwt jwt) {
        Map<String, Object> extraClaims = new LinkedHashMap<>();
        extraClaims.put("authType", jwt.getClaimAsString("authType"));
        extraClaims.put("clientId", jwt.getClaimAsString("clientId"));
        extraClaims.put("clientName", jwt.getClaimAsString("clientName"));
        extraClaims.put("lspId", jwt.getClaimAsString("lspId"));
        extraClaims.put("lspCode", jwt.getClaimAsString("lspCode"));
        extraClaims.put("lspName", jwt.getClaimAsString("lspName"));
        return mintTokenResponse(
                jwt.getSubject(),
                jwt.getClaimAsStringList("roles"),
                new ManagedUserState(
                        Boolean.TRUE.equals(jwt.getClaim("pwdchg")),
                        readPasswordVersion(jwt)
                ),
                extraClaims
        );
    }

    private TokenResponse mintTokenResponse(
            String subject,
            List<String> roles,
            ManagedUserState managedUserState,
            Map<String, Object> extraClaims
    ) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(securityProperties.getJwt().getTtl());

        JwtClaimsSet.Builder claimsBuilder = JwtClaimsSet.builder()
                .issuer(securityProperties.getJwt().getIssuer())
                .subject(subject)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim("roles", roles)
                .claim("pwdchg", managedUserState.passwordChangeRequired())
                .claim("pwdv", managedUserState.passwordChangedAt().toEpochMilli());
        extraClaims.forEach((key, value) -> {
            if (value != null) {
                claimsBuilder.claim(key, value);
            }
        });

        JwsHeader jwsHeader = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(jwsHeader, claimsBuilder.build())).getTokenValue();
        return new TokenResponse(
                token,
                "Bearer",
                expiresAt.getEpochSecond() - issuedAt.getEpochSecond(),
                managedUserState.passwordChangeRequired()
        );
    }

    private TokenResponse issuePasswordToken(TokenRequest request) {
        requireField(request.username(), "username");
        requireField(request.password(), "password");
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password())
        );
        return mintTokenResponse(authentication);
    }

    private TokenResponse issueClientCredentialsToken(TokenRequest request) {
        ApiClientAuthenticationService.AuthenticatedApiClient apiClient = apiClientAuthenticationService.authenticate(
                request.clientId(),
                request.clientSecret()
        );
        return mintTokenResponse(
                apiClient.clientId(),
                List.of("LSP_API_CLIENT"),
                new ManagedUserState(false, Instant.EPOCH),
                Map.of(
                        "authType", "API_CLIENT",
                        "clientId", apiClient.clientId(),
                        "clientName", apiClient.clientName(),
                        "lspId", apiClient.lspId().toString(),
                        "lspCode", apiClient.lspCode()
                )
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

    private Map<String, Object> loadManagedUserClaims(String username) {
        return appUserRepository.findByUsernameIgnoreCase(username)
                .map(user -> {
                    Map<String, Object> claims = new LinkedHashMap<>();
                    if (user.getLsp() != null) {
                        claims.put("lspId", user.getLsp().getId().toString());
                        claims.put("lspCode", user.getLsp().getCode());
                        claims.put("lspName", user.getLsp().getName());
                    }
                    return claims;
                })
                .orElseGet(Map::of);
    }

    private static String normalizeGrantType(String grantType) {
        if (grantType == null || grantType.trim().isBlank()) {
            return "password";
        }
        return grantType.trim().toLowerCase();
    }

    private static Instant readPasswordVersion(Jwt jwt) {
        Long passwordVersion = jwt.getClaim("pwdv");
        if (passwordVersion == null) {
            return Instant.EPOCH;
        }
        return Instant.ofEpochMilli(passwordVersion);
    }

    private static String requireField(String value, String fieldName) {
        if (value == null || value.trim().isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required.");
        }
        return value.trim();
    }

    private record ManagedUserState(boolean passwordChangeRequired, Instant passwordChangedAt) {
    }
}
