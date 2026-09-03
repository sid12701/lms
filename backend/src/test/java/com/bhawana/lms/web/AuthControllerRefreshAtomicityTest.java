package com.bhawana.lms.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.domain.AppRole;
import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.domain.RefreshToken;
import com.bhawana.lms.domain.RoleCode;
import com.bhawana.lms.domain.UserStatus;
import com.bhawana.lms.repo.ApiClientAuditEventRepository;
import com.bhawana.lms.repo.ApiClientRepository;
import com.bhawana.lms.repo.AppRoleRepository;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.repo.AuthEventAuditRepository;
import com.bhawana.lms.repo.LspRepository;
import com.bhawana.lms.repo.RefreshTokenRepository;
import com.bhawana.lms.service.ApiClientManagementService;
import com.bhawana.lms.service.AuthAuditService;
import com.bhawana.lms.support.IntegrationTestDatabaseCleaner;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class AuthControllerRefreshAtomicityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private AppRoleRepository appRoleRepository;

    @Autowired
    private AuthEventAuditRepository authEventAuditRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private LspRepository lspRepository;

    @Autowired
    private ApiClientRepository apiClientRepository;

    @Autowired
    private ApiClientAuditEventRepository apiClientAuditEventRepository;

    @Autowired
    private ApiClientManagementService apiClientManagementService;

    @Autowired
    private IntegrationTestDatabaseCleaner integrationTestDatabaseCleaner;

    @MockitoBean
    private JwtEncoder jwtEncoder;

    @MockitoBean
    private AuthAuditService authAuditService;

    @BeforeEach
    void setUpUser() {
        integrationTestDatabaseCleaner.cleanIntegrationTestData();

        AppRole opsUserRole = appRoleRepository.findByCodeIn(List.of(RoleCode.OPS_USER)).stream()
                .findFirst()
                .orElseThrow();

        appUserRepository.save(new AppUser(
                "test.user",
                "test.user@bhawana.local",
                passwordEncoder.encode("TestPassword123!"),
                UserStatus.ACTIVE,
                null,
                Set.of(opsUserRole)
        ));
    }

    @Test
    void refreshThrowsDuringMintAndOldTokenRemainsUnrevoked() throws Exception {
        Cookie refreshCookie = seedAppUserRefreshCookie("test.user");

        when(jwtEncoder.encode(any(JwtEncoderParameters.class)))
                .thenThrow(new RuntimeException("mint failure"));

        mockMvc.perform(post("/api/v1/auth/refresh").cookie(refreshCookie))
                .andExpect(status().isInternalServerError());

        RefreshToken existing = refreshTokenRepository.findByTokenHash(sha256Hex(refreshCookie.getValue()))
                .orElseThrow();
        assertFalse(existing.isRevoked());

        when(jwtEncoder.encode(any(JwtEncoderParameters.class)))
                .thenReturn(encodedJwt("replacement-access-token"));

        mockMvc.perform(post("/api/v1/auth/refresh").cookie(refreshCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("replacement-access-token"));
    }

    @Test
    void refreshThrowsDuringAuditSuccessWriteAndOldTokenRemainsUnrevoked() throws Exception {
        Cookie refreshCookie = seedAppUserRefreshCookie("test.user");

        when(jwtEncoder.encode(any(JwtEncoderParameters.class)))
                .thenReturn(encodedJwt("replacement-access-token"));
        doThrow(new RuntimeException("audit failure"))
                .when(authAuditService)
                .recordTokenRefreshSuccess(any(), any(), any(), any(), any());

        mockMvc.perform(post("/api/v1/auth/refresh").cookie(refreshCookie))
                .andExpect(status().isInternalServerError());

        RefreshToken existing = refreshTokenRepository.findByTokenHash(sha256Hex(refreshCookie.getValue()))
                .orElseThrow();
        assertFalse(existing.isRevoked());
    }

    @Test
    void apiClientRefreshThrowsDuringMintAndOldTokenRemainsUnrevoked() throws Exception {
        Lsp lsp = lspRepository.save(new Lsp("APEX-MACHINE", "Apex Machine Tenant", LspStatus.ACTIVE));
        ApiClientManagementService.CreatedApiClient created = apiClientManagementService.createClient(
                "Apex Machine Client",
                null,
                lsp.getId(),
                com.bhawana.lms.domain.ApiClientStatus.ACTIVE,
                "test.setup",
                null
        );

        String rawToken = "seeded-api-client-refresh-token";
        refreshTokenRepository.save(new RefreshToken(
                sha256Hex(rawToken),
                created.client(),
                Instant.now().plusSeconds(3600)
        ));
        Cookie refreshCookie = new Cookie("lms-refresh", rawToken);

        when(jwtEncoder.encode(any(JwtEncoderParameters.class)))
                .thenThrow(new RuntimeException("mint failure"));

        mockMvc.perform(post("/api/v1/auth/refresh").cookie(refreshCookie))
                .andExpect(status().isInternalServerError());

        RefreshToken existing = refreshTokenRepository.findByTokenHash(sha256Hex(refreshCookie.getValue()))
                .orElseThrow();
        assertFalse(existing.isRevoked());

        when(jwtEncoder.encode(any(JwtEncoderParameters.class)))
                .thenReturn(encodedJwt("replacement-api-access-token"));

        mockMvc.perform(post("/api/v1/auth/refresh").cookie(refreshCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("replacement-api-access-token"));
    }

    private Cookie seedAppUserRefreshCookie(String username) {
        AppUser user = appUserRepository.findByUsername(username).orElseThrow();
        String rawToken = "seeded-refresh-token-" + username;
        refreshTokenRepository.save(new RefreshToken(
                sha256Hex(rawToken),
                user,
                Instant.now().plusSeconds(3600)
        ));
        return new Cookie("lms-refresh", rawToken);
    }

    private static Jwt encodedJwt(String tokenValue) {
        return Jwt.withTokenValue(tokenValue)
                .header("alg", "none")
                .subject("test.user")
                .build();
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
