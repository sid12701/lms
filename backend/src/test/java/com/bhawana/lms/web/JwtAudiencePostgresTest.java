package com.bhawana.lms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.domain.ApiClientStatus;
import com.bhawana.lms.domain.AppRole;
import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.domain.RoleCode;
import com.bhawana.lms.domain.UserStatus;
import com.bhawana.lms.repo.ApiClientRepository;
import com.bhawana.lms.repo.AppRoleRepository;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.repo.LspRepository;
import com.bhawana.lms.security.ApiClientJwtSessionValidator;
import com.bhawana.lms.security.AuthPrincipalCache;
import com.bhawana.lms.security.JwtAudienceValidator;
import com.bhawana.lms.security.JwtSecurityBeans;
import com.bhawana.lms.security.ManagedUserJwtPrincipalResolver;
import com.bhawana.lms.security.SecurityProperties;
import com.bhawana.lms.service.ApiClientManagementService;
import com.bhawana.lms.service.AuthTokenService;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import com.bhawana.lms.tenant.TenantScopedExecution;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Foundation regression: locally minted JWTs need explicit distinct human/machine audiences.
 *
 * <p>Tokens without an audience or with a foreign audience must fail verification; issuer, HS256
 * algorithm, signing key and expiry keep failing as before. Cross-audience negatives use VALID
 * live principals on both branches (so rejection proves audience/type separation, not a missing
 * subject), rotation is exercised through the actual production validator chain with an A and a B
 * key, and surface separation is proven over HTTP with real issued tokens — never {@code jwt()}
 * mocks, which bypass the decoder and cannot establish signed verification.
 *
 * <p>Rotation scope correction: replacing the signing secret invalidates outstanding ACCESS
 * tokens only. Opaque refresh rows survive and re-mint under the current policy, so secret
 * replacement alone does NOT force full reauthentication — that cutover is the pending session-family/policy-epoch cutover
 * dependency, explicitly not implemented here.
 *
 * <p>Surface separation is proven with real issued tokens in both directions: a signed machine
 * token whose clientId collides with a human username is denied on the human password endpoint
 * without resetting anything, and a human token minted for a managed row storing the machine
 * role is denied on the machine API. Timestamp negatives (missing exp, future nbf) run against
 * valid signed principals so rejection proves the contract, not a missing subject.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class JwtAudiencePostgresTest {

    private static final String EXPECTED_HUMAN_AUDIENCE = "bhawana-lms-human";
    private static final String EXPECTED_MACHINE_AUDIENCE = "bhawana-lms-machine";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AuthTokenService authTokenService;
    @Autowired private JwtDecoder jwtDecoder;
    @Autowired private JwtEncoder jwtEncoder;
    @Autowired private SecretKey jwtSigningKey;
    @Autowired private AppUserRepository appUserRepository;
    @Autowired private AppRoleRepository appRoleRepository;
    @Autowired private AuthPrincipalCache authPrincipalCache;
    @Autowired private ManagedUserJwtPrincipalResolver managedUserResolver;
    @Autowired private ApiClientJwtSessionValidator apiClientValidator;
    @Autowired private com.bhawana.lms.security.EntraMachineJwtValidator entraMachineJwtValidator;
    @Autowired private SecurityProperties securityProperties;
    @Autowired private LspRepository lspRepository;
    @Autowired private ApiClientRepository apiClientRepository;
    @Autowired private ApiClientManagementService apiClientManagementService;
    @Autowired private com.bhawana.lms.service.HumanSessionTestFixtures humanSessionTestFixtures;
    @Autowired private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @BeforeEach
    void ensureUser() {
        TenantScopedExecution.runAsAdmin(() -> {
            if (appUserRepository.findByUsername("t22.user").isEmpty()) {
                AppRole opsRole = appRoleRepository.findByCodeIn(List.of(RoleCode.OPS_USER)).stream()
                        .findFirst().orElseThrow();
                appUserRepository.save(new AppUser(
                        "t22.user",
                        "t22.user@bhawana.local",
                        passwordEncoder.encode("T22Password123!"),
                        UserStatus.ACTIVE,
                        null,
                        Set.of(opsRole)
                ));
            }
            authPrincipalCache.evictAppUser("t22.user");
        });
    }

    @Test
    void liveHumanAndMachineTokensDecodeWithDistinctAudiences() throws Exception {
        MachineFixture machine = createMachineFixture();
        String machineToken = issueMachineToken(machine);
        String humanToken = loginHumanToken("t22.user", "T22Password123!");

        Jwt decodedMachine = jwtDecoder.decode(machineToken);
        Jwt decodedHuman = jwtDecoder.decode(humanToken);

        assertTrue(decodedMachine.getAudience().contains(EXPECTED_MACHINE_AUDIENCE));
        assertTrue(!decodedMachine.getAudience().contains(EXPECTED_HUMAN_AUDIENCE));
        assertEquals(
                ApiClientJwtSessionValidator.AUTH_TYPE_API_CLIENT,
                decodedMachine.getClaimAsString(ApiClientJwtSessionValidator.AUTH_TYPE_CLAIM));

        assertTrue(decodedHuman.getAudience().contains(EXPECTED_HUMAN_AUDIENCE));
        assertTrue(!decodedHuman.getAudience().contains(EXPECTED_MACHINE_AUDIENCE));
    }

    @Test
    void legacyTokenWithoutAudienceIsRejected() {
        // Manual tokens bind a real valid family (sid) so rejection proves the
        // audience contract rather than sid absence.
        String token = mintManualHumanToken(null, currentUserState(), liveHumanSid());

        assertThrows(Exception.class, () -> jwtDecoder.decode(token));
    }

    @Test
    void foreignAudienceIsRejected() {
        String token = mintManualHumanToken(List.of("foreign-audience"), currentUserState(), liveHumanSid());

        assertThrows(Exception.class, () -> jwtDecoder.decode(token));
    }

    @Test
    void validMachineSessionWithHumanAudienceIsRejected() throws Exception {
        MachineFixture machine = createMachineFixture();
        String token = mintManualMachineToken(List.of(EXPECTED_HUMAN_AUDIENCE), machine);

        // The machine session itself is live (same claims decode when the audience is correct),
        // so rejection proves audience separation rather than a missing principal.
        assertThrows(Exception.class, () -> jwtDecoder.decode(token));
        assertThrows(Exception.class, () -> productionDecoder(jwtSigningKey).decode(token));
    }

    @Test
    void validHumanSessionWithMachineAudienceIsRejected() {
        String token = mintManualHumanToken(List.of(EXPECTED_MACHINE_AUDIENCE), currentUserState(), liveHumanSid());

        assertThrows(Exception.class, () -> jwtDecoder.decode(token));
    }

    @Test
    void validMachineSessionWithMachineAudienceDecodes() throws Exception {
        MachineFixture machine = createMachineFixture();

        Jwt decoded = jwtDecoder.decode(mintManualMachineToken(
                List.of(EXPECTED_MACHINE_AUDIENCE), machine));

        assertEquals(machine.clientId(), decoded.getSubject());
    }

    @Test
    void wrongIssuerIsRejected() {
        JwtClaimsSet claims = baseHumanClaimsBuilder(currentUserState(), liveHumanSid())
                .issuer("evil-issuer")
                .audience(List.of(EXPECTED_HUMAN_AUDIENCE))
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();

        assertThrows(Exception.class, () -> jwtDecoder.decode(token));
    }

    @Test
    void wrongKeyIsRejected() {
        byte[] otherKey = "another-test-secret-that-is-long-enough-xyz".getBytes(StandardCharsets.UTF_8);
        JwtEncoder foreignEncoder = new NimbusJwtEncoder(
                new ImmutableSecret<>(new SecretKeySpec(otherKey, "HmacSHA256")));
        String token = foreignEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                baseHumanClaimsBuilder(currentUserState(), liveHumanSid())
                        .audience(List.of(EXPECTED_HUMAN_AUDIENCE))
                        .build())).getTokenValue();

        assertThrows(Exception.class, () -> jwtDecoder.decode(token));
    }

    @Test
    void expiredTokenWithOtherwiseValidClaimsIsRejected() {
        AuthTokenService.ManagedUserState state = currentUserState();
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(securityProperties.getJwt().getIssuer())
                .subject("t22.user")
                .issuedAt(now.minusSeconds(3600))
                .expiresAt(now.minusSeconds(60))
                .id(UUID.randomUUID().toString())
                .audience(List.of(EXPECTED_HUMAN_AUDIENCE))
                .claim("roles", List.of("OPS_USER"))
                .claim("pwdchg", state.passwordChangeRequired())
                .claim("pwdv", state.passwordChangedAt().toEpochMilli())
                .claim("tv", state.tokenVersion())
                .claim("sid", liveHumanSid())
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();

        assertThrows(Exception.class, () -> jwtDecoder.decode(token));
    }

    @Test
    void missingExpiryWithOtherwiseValidSignedClaimsIsRejected() {
        // The default timestamp validator only checks expiry when present; the local
        // access-token contract requires bounded expiry, so a signed token without exp from a
        // live principal must still fail on the production chain.
        AuthTokenService.ManagedUserState state = currentUserState();
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(securityProperties.getJwt().getIssuer())
                .subject("t22.user")
                .issuedAt(now.minusSeconds(60))
                .id(UUID.randomUUID().toString())
                .audience(List.of(EXPECTED_HUMAN_AUDIENCE))
                .claim("roles", List.of("OPS_USER"))
                .claim("pwdchg", state.passwordChangeRequired())
                .claim("pwdv", state.passwordChangedAt().toEpochMilli())
                .claim("tv", state.tokenVersion())
                .claim("sid", liveHumanSid())
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();

        assertThrows(Exception.class, () -> jwtDecoder.decode(token));
    }

    @Test
    void futureNotBeforeWithOtherwiseValidSignedClaimsIsRejected() {
        AuthTokenService.ManagedUserState state = currentUserState();
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(securityProperties.getJwt().getIssuer())
                .subject("t22.user")
                .issuedAt(now)
                .notBefore(now.plusSeconds(600))
                .expiresAt(now.plusSeconds(1200))
                .id(UUID.randomUUID().toString())
                .audience(List.of(EXPECTED_HUMAN_AUDIENCE))
                .claim("roles", List.of("OPS_USER"))
                .claim("pwdchg", state.passwordChangeRequired())
                .claim("pwdv", state.passwordChangedAt().toEpochMilli())
                .claim("tv", state.tokenVersion())
                .claim("sid", liveHumanSid())
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();

        assertThrows(Exception.class, () -> jwtDecoder.decode(token));
    }

    @Test
    void tokenCarryingBothLocalAudiencesIsRejected() throws Exception {
        MachineFixture machine = createMachineFixture();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(securityProperties.getJwt().getIssuer())
                .subject(machine.clientId())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600))
                .id(UUID.randomUUID().toString())
                .audience(List.of(EXPECTED_HUMAN_AUDIENCE, EXPECTED_MACHINE_AUDIENCE))
                .claim("roles", List.of("LSP_API_CLIENT"))
                .claim(ApiClientJwtSessionValidator.AUTH_TYPE_CLAIM,
                        ApiClientJwtSessionValidator.AUTH_TYPE_API_CLIENT)
                .claim("clientId", machine.clientId())
                .claim("clientName", machine.clientName())
                .claim("lspId", machine.lspId())
                .claim("lspCode", machine.lspCode())
                .claim(ApiClientJwtSessionValidator.TV_LSP_CLAIM, machine.lspTokenVersion())
                .claim(ApiClientJwtSessionValidator.TV_API_CLIENT_CLAIM, machine.clientTokenVersion())
                .build();
        String token = mintWithKey(jwtSigningKey, claims);

        // The machine session itself is live (same claims with only the machine audience
        // decode), so rejection proves dual-audience inconsistency, not a missing principal.
        assertThrows(Exception.class, () -> jwtDecoder.decode(token));
        Jwt dualAudience = jwtWith(Map.of(
                "sub", machine.clientId(),
                ApiClientJwtSessionValidator.AUTH_TYPE_CLAIM,
                ApiClientJwtSessionValidator.AUTH_TYPE_API_CLIENT,
                "aud", List.of(EXPECTED_HUMAN_AUDIENCE, EXPECTED_MACHINE_AUDIENCE)));
        assertTrue(JwtAudienceValidator.validateNoConflictingLocalAudiences(
                dualAudience, EXPECTED_HUMAN_AUDIENCE, EXPECTED_MACHINE_AUDIENCE).hasErrors());
    }

    @Test
    void equalHumanAndMachineAudiencesFailConfigurationValidation() {
        // Production configuration contract, not a test-defaults assertion: equal audiences
        // would let one branch's tokens verify on the other.
        try (jakarta.validation.ValidatorFactory factory =
                     jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            SecurityProperties properties = new SecurityProperties();
            properties.getJwt().setSecret("configuration-test-secret-with-sufficient-length");
            properties.getJwt().setHumanAudience("bhawana-lms-shared");
            properties.getJwt().setMachineAudience("bhawana-lms-shared");

            assertTrue(!factory.getValidator().validate(properties.getJwt()).isEmpty());

            SecurityProperties distinct = new SecurityProperties();
            distinct.getJwt().setSecret("configuration-test-secret-with-sufficient-length");
            assertTrue(factory.getValidator().validate(distinct.getJwt()).isEmpty());
        }
    }

    @Test
    void unsignedTokenIsRejected() {
        String signed = TenantScopedExecution.callAsAdmin(
                () -> humanSessionTestFixtures.mintHumanTokenForUsername("t22.user").accessToken());
        String[] parts = signed.split("\\.");
        String noneHeader = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String unsigned = noneHeader + "." + parts[1] + ".";

        assertThrows(Exception.class, () -> jwtDecoder.decode(unsigned));
    }

    @Test
    void unexpectedSignedAlgorithmIsRejected() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        RSAKey rsaJwk = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey(keyPair.getPrivate())
                .keyID("t22-rsa")
                .build();
        JwtEncoder rsaEncoder = new NimbusJwtEncoder(
                new ImmutableJWKSet<>(new com.nimbusds.jose.jwk.JWKSet(rsaJwk)));
        String token = rsaEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).header("kid", "t22-rsa").build(),
                baseHumanClaimsBuilder(currentUserState(), liveHumanSid())
                        .audience(List.of(EXPECTED_HUMAN_AUDIENCE))
                        .build())).getTokenValue();

        // HS256-pinned production decoder: an RS256 signature from another key never verifies,
        // even with otherwise valid issuer/audience/session claims.
        assertThrows(Exception.class, () -> jwtDecoder.decode(token));
        assertThrows(Exception.class, () -> productionDecoder(jwtSigningKey).decode(token));
    }

    @Test
    void secretRotationInvalidatesOutstandingAccessTokensWithProductionValidators() {
        SecretKey keyB = new SecretKeySpec(
                "rotation-b-secret-that-is-long-enough-xyz".getBytes(StandardCharsets.UTF_8),
                "HmacSHA256");
        String tokenUnderA = TenantScopedExecution.callAsAdmin(
                () -> humanSessionTestFixtures.mintHumanTokenForUsername("t22.user").accessToken());
        String liveSid = jwtDecoder.decode(tokenUnderA).getClaimAsString("sid");
        String tokenUnderB = mintWithKey(keyB, baseHumanClaimsBuilder(currentUserState(), liveSid)
                .audience(List.of(EXPECTED_HUMAN_AUDIENCE))
                .build());

        // A-chain (production validators + key A): A token passes, B token fails.
        jwtDecoder.decode(tokenUnderA);
        assertThrows(Exception.class, () -> jwtDecoder.decode(tokenUnderB));

        // B-chain (same production validators + key B): B token passes, A token fails.
        JwtDecoder decoderB = productionDecoder(keyB);
        decoderB.decode(tokenUnderB);
        assertThrows(Exception.class, () -> decoderB.decode(tokenUnderA));
    }

    @Test
    void refreshFlowNeedsNoAccessTokenByDesignEpochActive() throws Exception {
        // Control proving the rotation scope: the opaque refresh cookie rotates without
        // presenting any access token. The family/policy epoch still gates the
        // rotation (same-policy refresh succeeds here); a cross-epoch refresh forces
        // reauth (covered in the session backend suite with a forged-epoch family row).
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(humanLoginBody("t22.user", "T22Password123!")))
                .andExpect(status().isOk())
                .andReturn();
        Cookie refreshCookie = login.getResponse().getCookie("lms-refresh");
        assertTrue(refreshCookie != null);

        mockMvc.perform(post("/api/v1/auth/refresh").cookie(refreshCookie))
                .andExpect(status().isOk());
    }

    @Test
    void humanTokenWorksOnHumanSurfaceButNotMachineAdminSurface() throws Exception {
        String humanToken = loginHumanToken("t22.user", "T22Password123!");

        mockMvc.perform(get("/api/v1/internal/system/context")
                        .header("Authorization", "Bearer " + humanToken))
                .andExpect(status().isOk());

        // OPS_USER human carries no LSP roles: the machine-facing LSP surface rejects it.
        mockMvc.perform(get("/api/v1/lsp/loan-applications")
                        .header("Authorization", "Bearer " + humanToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void machineTokenWorksOnMachineSurfaceButNotHumanAdminSurface() throws Exception {
        MachineFixture machine = createMachineFixture();
        String machineToken = issueMachineToken(machine);

        mockMvc.perform(get("/api/v1/lsp/loan-applications")
                        .header("Authorization", "Bearer " + machineToken))
                .andExpect(status().isOk());

        // The machine token authenticates but owns only the machine role: SYSTEM_ADMIN-only
        // human surface rejects it without writing anything.
        mockMvc.perform(post("/api/v1/internal/system/bootstrap-sync")
                        .header("Authorization", "Bearer " + machineToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void machineTokenCannotResetCollidingHumanPassword() throws Exception {
        // Positive collision control: a real signed API_CLIENT token whose clientId equals a
        // human username. Without the typed guard the password endpoint would resolve the
        // caller via authentication.getName() and reset the human's password (200); with the
        // guard the machine is denied (403) before any managed-user mutation.
        MachineFixture machine = createMachineFixture();
        String collidingUsername = machine.clientId();
        String humanPassword = "CollisionHuman123!";
        TenantScopedExecution.runAsAdmin(() -> {
            AppRole opsRole = appRoleRepository.findByCodeIn(List.of(RoleCode.OPS_USER)).stream()
                    .findFirst().orElseThrow();
            AppUser colliding = new AppUser(
                    collidingUsername,
                    collidingUsername + "@bhawana.local",
                    passwordEncoder.encode(humanPassword),
                    UserStatus.ACTIVE,
                    null,
                    Set.of(opsRole));
            colliding.requirePasswordChange(colliding.getPasswordHash());
            appUserRepository.save(colliding);
            authPrincipalCache.evictAppUser(collidingUsername);
        });
        String machineToken = issueMachineToken(machine);

        ObjectNode passwordBody = objectMapper.createObjectNode();
        passwordBody.put("newPassword", "MachineReset456!");
        mockMvc.perform(post("/api/v1/auth/password")
                        .header("Authorization", "Bearer " + machineToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordBody.toString()))
                .andExpect(status().isForbidden());

        // The human password is untouched: the original password still logs in and the account
        // still requires its own password change.
        ObjectNode loginBody = objectMapper.createObjectNode();
        loginBody.put("email", collidingUsername + "@bhawana.local");
        loginBody.put("password", humanPassword);
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody.toString()))
                .andExpect(status().isOk())
                .andReturn();
        assertTrue(objectMapper.readTree(login.getResponse().getContentAsString())
                .get("passwordChangeRequired").asBoolean());
    }

    @Test
    void humanTokenWithStoredMachineRoleCannotReachMachineSurface() throws Exception {
        // A managed row may already store LSP_API_CLIENT (existing rows are not covered by
        // banning new assignments). Its human-audience token must still be denied on the
        // machine API because authority mapping never grants the machine role to humans.
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String username = "t22.hmr." + suffix;
        TenantScopedExecution.runAsAdmin(() -> {
            Lsp lsp = lspRepository.save(new Lsp("T-HMR-" + suffix.toUpperCase(), "Test HMR " + suffix, LspStatus.ACTIVE));
            AppRole machineRole = appRoleRepository.findByCodeIn(List.of(RoleCode.LSP_API_CLIENT)).stream()
                    .findFirst().orElseThrow();
            appUserRepository.save(new AppUser(
                    username,
                    username + "@bhawana.local",
                    passwordEncoder.encode("MachineRole123!"),
                    UserStatus.ACTIVE,
                    lsp,
                    Set.of(machineRole)));
            authPrincipalCache.evictAppUser(username);
        });
        String humanToken = loginHumanToken(username, "MachineRole123!");

        mockMvc.perform(get("/api/v1/lsp/loan-applications")
                        .header("Authorization", "Bearer " + humanToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void unrecognizedTokenTypeIsRejectedOnBothBranches() {
        Jwt jwt = new Jwt(
                "unrecognized-type-token",
                Instant.now().minusSeconds(60),
                Instant.now().plusSeconds(600),
                Map.of("alg", "HS256"),
                Map.of(
                        "sub", "ghost.user",
                        ApiClientJwtSessionValidator.AUTH_TYPE_CLAIM, "SERVICE_ACCOUNT",
                        "aud", List.of(EXPECTED_HUMAN_AUDIENCE)));

        assertTrue(managedUserResolver.validateSession(jwt).hasErrors());
        assertTrue(apiClientValidator.validate(jwt).hasErrors());
    }

    @Test
    void missingSubjectHumanTokenIsRejected() {
        Jwt jwt = new Jwt(
                "missing-subject-token",
                Instant.now().minusSeconds(60),
                Instant.now().plusSeconds(600),
                Map.of("alg", "HS256"),
                Map.of("aud", List.of(EXPECTED_HUMAN_AUDIENCE)));

        assertTrue(managedUserResolver.validateSession(jwt).hasErrors());
    }

    @Test
    void humanAndMachineBranchesStaySeparateForKnownSubjects() {
        Jwt human = new Jwt(
                "human-branch-token",
                Instant.now().minusSeconds(60),
                Instant.now().plusSeconds(600),
                Map.of("alg", "HS256"),
                Map.of(
                        "sub", "t22.user",
                        "aud", List.of(EXPECTED_HUMAN_AUDIENCE),
                        "pwdv", 0L,
                        "tv", 0L));

        // API-client branch skips human tokens; the human branch resolves the managed user.
        assertTrue(!apiClientValidator.validate(human).hasErrors());
    }

    @Test
    void configuredAudiencesAreDistinctAndMatchContract() {
        assertTrue(!securityProperties.getJwt().getHumanAudience()
                .equals(securityProperties.getJwt().getMachineAudience()));
        assertEquals(EXPECTED_HUMAN_AUDIENCE, securityProperties.getJwt().getHumanAudience());
        assertEquals(EXPECTED_MACHINE_AUDIENCE, securityProperties.getJwt().getMachineAudience());
    }

    @Test
    void audienceValidatorMatrix() {
        Jwt human = jwtWith(Map.of("sub", "t22.user", "aud", List.of(EXPECTED_HUMAN_AUDIENCE)));
        assertTrue(!JwtAudienceValidator.validateHumanAudience(
                human, EXPECTED_HUMAN_AUDIENCE).hasErrors());
        assertTrue(!JwtAudienceValidator.validateMachineAudience(
                human, EXPECTED_MACHINE_AUDIENCE).hasErrors());

        Jwt noAudience = jwtWith(Map.of("sub", "t22.user"));
        assertTrue(JwtAudienceValidator.validateHumanAudience(
                noAudience, EXPECTED_HUMAN_AUDIENCE).hasErrors());

        Jwt foreign = jwtWith(Map.of("sub", "t22.user", "aud", List.of("foreign-audience")));
        assertTrue(JwtAudienceValidator.validateHumanAudience(
                foreign, EXPECTED_HUMAN_AUDIENCE).hasErrors());

        Jwt machine = jwtWith(Map.of(
                "sub", "some-client",
                ApiClientJwtSessionValidator.AUTH_TYPE_CLAIM,
                ApiClientJwtSessionValidator.AUTH_TYPE_API_CLIENT,
                "aud", List.of(EXPECTED_MACHINE_AUDIENCE)));
        assertTrue(!JwtAudienceValidator.validateMachineAudience(
                machine, EXPECTED_MACHINE_AUDIENCE).hasErrors());
        assertTrue(!JwtAudienceValidator.validateHumanAudience(
                machine, EXPECTED_HUMAN_AUDIENCE).hasErrors());

        Jwt crossedMachine = jwtWith(Map.of(
                "sub", "some-client",
                ApiClientJwtSessionValidator.AUTH_TYPE_CLAIM,
                ApiClientJwtSessionValidator.AUTH_TYPE_API_CLIENT,
                "aud", List.of(EXPECTED_HUMAN_AUDIENCE)));
        assertTrue(JwtAudienceValidator.validateMachineAudience(
                crossedMachine, EXPECTED_MACHINE_AUDIENCE).hasErrors());
    }

    @Test
    void humanMintBindsAllReservedClaimsFromTypedSpec() {
        // The production minter takes only an immutable typed spec (same-package,
        // fenced coordinator or test fixture) — there is no arbitrary claim map to
        // override identity, audience, versions or session binding. Every reserved claim
        // below is sourced from the spec on every mint.
        String token = TenantScopedExecution.callAsAdmin(
                () -> humanSessionTestFixtures.mintHumanTokenForUsername("t22.user").accessToken());
        Jwt decoded = jwtDecoder.decode(token);
        AuthTokenService.ManagedUserState state = currentUserState();

        assertEquals("t22.user", decoded.getSubject());
        assertEquals(securityProperties.getJwt().getIssuer(), decoded.getClaimAsString("iss"));
        assertTrue(decoded.getAudience().contains(EXPECTED_HUMAN_AUDIENCE));
        assertNotNull(decoded.getExpiresAt());
        assertNotNull(decoded.getId());
        assertEquals(List.of("OPS_USER"), decoded.getClaimAsStringList("roles"));
        assertEquals(state.passwordChangeRequired(), decoded.getClaims().get("pwdchg"));
        assertEquals(state.passwordChangedAt().toEpochMilli(),
                ((Number) decoded.getClaims().get("pwdv")).longValue());
        assertEquals(state.tokenVersion(), ((Number) decoded.getClaims().get("tv")).longValue());
        assertNotNull(decoded.getClaimAsString("sid"));
    }

    private static Jwt jwtWith(Map<String, Object> claims) {
        return new Jwt(
                "audience-matrix-token",
                Instant.now().minusSeconds(60),
                Instant.now().plusSeconds(600),
                Map.of("alg", "HS256"),
                claims);
    }

    /**
     * The actual production validator chain (issuer + bounded expiry + audience branches + both
     * session branches) rebuilt around the given key through the production
     * {@link JwtSecurityBeans#buildDecoder} factory, so rotation tests exercise configured
     * behavior rather than a bare signature check — and cannot drift from production or keep
     * passing if a production validator is removed.
     */
    private JwtDecoder productionDecoder(SecretKey key) {
        return JwtSecurityBeans.buildDecoder(
                key, securityProperties, managedUserResolver, apiClientValidator, entraMachineJwtValidator);
    }

    private String mintWithKey(SecretKey key, JwtClaimsSet claims) {
        JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    private AuthTokenService.ManagedUserState currentUserState() {
        return TenantScopedExecution.callAsAdmin(
                () -> authTokenService.loadManagedUserState("t22.user"));
    }

    private String mintManualHumanToken(List<String> audience, AuthTokenService.ManagedUserState state) {
        return mintManualHumanToken(audience, state, liveHumanSid());
    }

    private String mintManualHumanToken(List<String> audience, AuthTokenService.ManagedUserState state, String sid) {
        JwtClaimsSet.Builder builder = baseHumanClaimsBuilder(state, sid)
                .audience(audience == null ? List.of() : audience);
        return mintWithKey(jwtSigningKey, builder.build());
    }

    private String mintManualMachineToken(List<String> audience, MachineFixture machine) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(securityProperties.getJwt().getIssuer())
                .subject(machine.clientId())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600))
                .id(UUID.randomUUID().toString())
                .audience(audience)
                .claim("roles", List.of("LSP_API_CLIENT"))
                .claim(ApiClientJwtSessionValidator.AUTH_TYPE_CLAIM,
                        ApiClientJwtSessionValidator.AUTH_TYPE_API_CLIENT)
                .claim("clientId", machine.clientId())
                .claim("clientName", machine.clientName())
                .claim("lspId", machine.lspId())
                .claim("lspCode", machine.lspCode())
                .claim(ApiClientJwtSessionValidator.TV_LSP_CLAIM, machine.lspTokenVersion())
                .claim(ApiClientJwtSessionValidator.TV_API_CLIENT_CLAIM, machine.clientTokenVersion())
                .build();
        return mintWithKey(jwtSigningKey, claims);
    }

    private JwtClaimsSet.Builder baseHumanClaimsBuilder(AuthTokenService.ManagedUserState state, String sid) {
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
                .issuer(securityProperties.getJwt().getIssuer())
                .subject("t22.user")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600))
                .id(UUID.randomUUID().toString())
                .claim("roles", List.of("OPS_USER"))
                .claim("pwdchg", state.passwordChangeRequired())
                .claim("pwdv", state.passwordChangedAt().toEpochMilli())
                .claim("tv", state.tokenVersion());
        if (sid != null) {
            builder.claim("sid", sid);
        }
        return builder;
    }

    /**
     * A real valid family for the manual-token proofs above. Mints through the
     * production fenced path and returns its sid, so audience/expiry negatives prove
     * their own contract rather than failing for sid absence.
     */
    private String liveHumanSid() {
        return TenantScopedExecution.callAsAdmin(
                () -> humanSessionTestFixtures.liveFamilyIdForUsername("t22.user").toString());
    }

    private MachineFixture createMachineFixture() {
        return TenantScopedExecution.callAsAdmin(() -> {
            String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            Lsp lsp = lspRepository.save(new Lsp("T-" + suffix, "Test LSP " + suffix, LspStatus.ACTIVE));
            ApiClientManagementService.CreatedApiClient created = apiClientManagementService.createClient(
                    "Test Machine Client " + suffix,
                    null,
                    lsp.getId(),
                    ApiClientStatus.ACTIVE,
                    "t22.setup",
                    null
            );
            var client = apiClientRepository.findByClientId(created.client().getClientId()).orElseThrow();
            return new MachineFixture(
                    created.client().getClientId(),
                    created.rawSecret(),
                    client.getLsp().getId().toString(),
                    client.getLsp().getCode(),
                    "Test Machine Client " + suffix,
                    client.getLsp().getTokenVersion(),
                    client.getTokenVersion()
            );
        });
    }

    private String issueMachineToken(MachineFixture machine) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("clientId", machine.clientId());
        body.put("clientSecret", machine.rawSecret());
        MvcResult result = mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
    }

    private String loginHumanToken(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(humanLoginBody(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
    }

    private String humanLoginBody(String username, String password) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("email", username + "@bhawana.local");
        body.put("password", password);
        return body.toString();
    }

    private record MachineFixture(
            String clientId,
            String rawSecret,
            String lspId,
            String lspCode,
            String clientName,
            long lspTokenVersion,
            long clientTokenVersion) {
    }
}
