package com.bhawana.lms.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.domain.AppRole;
import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.domain.RefreshToken;
import com.bhawana.lms.domain.RoleCode;
import com.bhawana.lms.domain.UserStatus;
import com.bhawana.lms.repo.AppRoleRepository;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.repo.AuthEventAuditRepository;
import com.bhawana.lms.repo.RefreshTokenRepository;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class AuthControllerRefreshFailureBodyTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUpUser() {
        authEventAuditRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        appUserRepository.deleteAll();

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
    void refresh401WithMissingCookieReturnsCodeMissingRefreshCookieInBody() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MISSING_REFRESH_COOKIE"))
                .andExpect(jsonPath("$.message").value("Refresh cookie is missing"));
    }

    @Test
    void refresh401WithUnknownCookieReturnsCodeTokenExpiredInBody() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("lms-refresh", "unknown-refresh-token-value")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"))
                .andExpect(jsonPath("$.message").value("Refresh token has expired"));
    }

    @Test
    void refresh401WithRevokedCookieReturnsCodeTokenRevokedInBody() throws Exception {
        Cookie refreshCookie = loginAndCaptureRefreshCookie();

        RefreshToken existing = refreshTokenRepository.findByTokenHash(sha256Hex(refreshCookie.getValue()))
                .orElseThrow();
        existing.revoke();
        refreshTokenRepository.save(existing);

        mockMvc.perform(post("/api/v1/auth/refresh").cookie(refreshCookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_REVOKED"))
                .andExpect(jsonPath("$.message").value("Refresh token was revoked"));
    }

    @Test
    void refresh401WithExpiredCookieReturnsCodeTokenExpiredInBody() throws Exception {
        AppUser user = appUserRepository.findByUsername("test.user").orElseThrow();
        String rawToken = "expired-refresh-token-" + UUID.randomUUID();
        refreshTokenRepository.save(new RefreshToken(
                sha256Hex(rawToken),
                user,
                Instant.now().minusSeconds(60)
        ));

        mockMvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("lms-refresh", rawToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"))
                .andExpect(jsonPath("$.message").value("Refresh token has expired"));
    }

    @Test
    void refresh401WithOrphanTokenReturnsCodeRefreshInvalidInBody() throws Exception {
        String rawToken = "orphan-refresh-token-" + UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update(
                """
                        INSERT INTO refresh_token (
                            id, token_hash, auth_type, expires_at, revoked, created_at, app_user_id, api_client_id
                        ) VALUES (?, ?, ?, ?, ?, ?, NULL, NULL)
                        """,
                tokenId,
                sha256Hex(rawToken),
                RefreshToken.AUTH_TYPE_PASSWORD,
                now.plusSeconds(3600),
                false,
                now
        );

        mockMvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("lms-refresh", rawToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_INVALID"))
                .andExpect(jsonPath("$.message").value("Refresh token is invalid"));
    }

    private Cookie loginAndCaptureRefreshCookie() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AuthController.LoginRequest("test.user", "TestPassword123!"))))
                .andExpect(status().isOk())
                .andReturn();

        Cookie refreshCookie = loginResult.getResponse().getCookie("lms-refresh");
        if (refreshCookie == null) {
            throw new IllegalStateException("Login response must include lms-refresh cookie");
        }
        return refreshCookie;
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
