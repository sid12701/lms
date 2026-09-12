package com.bhawana.lms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bhawana.lms.domain.AdminApiIdempotencyRecord;
import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.domain.AppUserAuditEvent;
import com.bhawana.lms.domain.AuthEventAudit;
import com.bhawana.lms.domain.RoleCode;
import com.bhawana.lms.domain.UserStatus;
import com.bhawana.lms.repo.AdminApiIdempotencyRecordRepository;
import com.bhawana.lms.repo.AppRoleRepository;
import com.bhawana.lms.repo.AppUserAuditEventRepository;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.repo.AuthEventAuditRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Server-generated credentials for user creation.
 *
 * <p>Covers the red-before/green-after slice: the browser no longer mints passwords
 * (see frontend {@code api.ts}), creation without a {@code password} field mints a
 * SecureRandom temporary password server-side, persists only its hash, and reveals it
 * exactly once. Replays under the same {@code Idempotency-Key} return a safe response
 * with a null credential and must NOT rotate the password; a different payload under
 * the same key conflicts. Caller-supplied passwords remain available only as an
 * explicit compatibility mode that reveals nothing.
 *
 * <p>Isolation: this class never wipes shared tables. Every fixture uses a unique
 * {@code t04-*} identity/key, tracked and deleted in {@code tearDown} (audit rows,
 * users — refresh tokens cascade — idempotency records, auth audit rows), so shared
 * suites and any bootstrap rows are untouched.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class UserAdminCreateServerCredentialTest {

    private static final String USER_CREATE = "USER_CREATE";
    private static final String USER_RESET_PASSWORD = "USER_RESET_PASSWORD";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private AppRoleRepository appRoleRepository;

    @Autowired
    private AppUserAuditEventRepository appUserAuditEventRepository;

    @Autowired
    private AdminApiIdempotencyRecordRepository adminApiIdempotencyRecordRepository;

    @Autowired
    private AuthEventAuditRepository authEventAuditRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private EntityManager entityManager;

    private final List<UUID> ownedUserIds = new ArrayList<>();
    private final List<String> ownedUsernames = new ArrayList<>();
    private final List<String[]> ownedIdempotencyKeys = new ArrayList<>();
    private ListAppender<ILoggingEvent> logCapture;

    @BeforeEach
    void attachLogCapture() {
        logCapture = new ListAppender<>();
        logCapture.start();
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(logCapture);
    }

    @AfterEach
    void tearDownOwnedFixtures() {
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(logCapture);
        logCapture.stop();

        for (String[] operationAndKey : ownedIdempotencyKeys) {
            adminApiIdempotencyRecordRepository
                    .findByOperationKeyAndIdempotencyKey(operationAndKey[0], operationAndKey[1])
                    .ifPresent(adminApiIdempotencyRecordRepository::delete);
        }
        ownedIdempotencyKeys.clear();

        List<AppUserAuditEvent> ownedAudits = appUserAuditEventRepository.findAll().stream()
                .filter(event -> ownedUserIds.contains(event.getUserId()))
                .toList();
        appUserAuditEventRepository.deleteAll(ownedAudits);

        for (String username : ownedUsernames) {
            List<AuthEventAudit> ownedAuthAudits = authEventAuditRepository
                    .search(username, null, Pageable.unpaged()).getContent();
            authEventAuditRepository.deleteAll(ownedAuthAudits);
        }

        for (UUID userId : ownedUserIds) {
            appUserRepository.findById(userId).ifPresent(appUserRepository::delete);
        }
        ownedUserIds.clear();
        ownedUsernames.clear();
        entityManager.clear();
    }

    @Test
    void generatedModeMintsServerPasswordAndEnforcesFirstLoginChange() throws Exception {
        String username = uniqueName("srv");
        String email = username + "@bhawana.local";

        MvcResult createResult = mockMvc.perform(post("/api/v1/internal/admin/users")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(generatedCreateBody(username, email).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.passwordChangeRequired").value(true))
                .andExpect(jsonPath("$.temporaryPassword").isString())
                .andReturn();

        String temporaryPassword = readTemporaryPassword(createResult);
        trackCreatedUser(createResult, username);
        assertNotNull(temporaryPassword);
        assertTrue(temporaryPassword.length() >= 12, "generated credential must satisfy the min-length-12 policy");
        assertTrue(temporaryPassword.matches("[A-Za-z0-9\\-_]+"), "generated credential must be URL-safe");

        entityManager.clear();
        AppUser stored = appUserRepository.findByUsername(username).orElseThrow();
        assertTrue(stored.isPasswordChangeRequired());
        assertTrue(passwordEncoder.matches(temporaryPassword, stored.getPasswordHash()));

        // No audit row, error payload, or persisted hash may carry the cleartext value.
        assertOwnedAuditPayloadsDoNotContain(temporaryPassword);

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AuthApiResponses.LoginRequest(email, temporaryPassword))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.passwordChangeRequired").value(true))
                .andReturn();
        String accessToken = objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .get("accessToken").asText();

        mockMvc.perform(get("/api/v1/internal/admin/users")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));

        mockMvc.perform(post("/api/v1/auth/password")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AuthApiResponses.ChangePasswordRequest("ReplacementPassword456!"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordChangeRequired").value(false));

        entityManager.clear();
        assertFalse(appUserRepository.findByUsername(username).orElseThrow().isPasswordChangeRequired());
        assertNoLogContains(temporaryPassword);
    }

    @Test
    void sameKeyCreatesExactlyOnceAndReplayRevealsNothingWithoutRotation() throws Exception {
        String username = uniqueName("idmp");
        String email = username + "@bhawana.local";
        String key = trackKey(USER_CREATE);
        String requestBody = objectMapper.writeValueAsString(Map.of(
                "username", username,
                "email", email,
                "status", "ACTIVE",
                "roles", List.of("OPS_USER")
        ));

        MvcResult first = mockMvc.perform(post("/api/v1/internal/admin/users")
                        .with(systemAdmin())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.temporaryPassword").isString())
                .andReturn();
        String firstId = objectMapper.readTree(first.getResponse().getContentAsString()).get("id").asText();
        String firstPassword = readTemporaryPassword(first);
        trackCreatedUser(first, username);

        entityManager.clear();
        String hashAfterFirst = appUserRepository.findByUsername(username)
                .orElseThrow().getPasswordHash();

        MvcResult replay = mockMvc.perform(post("/api/v1/internal/admin/users")
                        .with(systemAdmin())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(firstId))
                .andReturn();

        JsonNode replayJson = objectMapper.readTree(replay.getResponse().getContentAsString());
        assertTrue(replayJson.get("temporaryPassword") == null || replayJson.get("temporaryPassword").isNull(),
                "replay must return a safe response with the credential unavailable");

        entityManager.clear();
        assertEquals(1, appUserRepository.findAll().stream()
                .filter(user -> username.equals(user.getUsername()))
                .count());
        assertEquals(hashAfterFirst, appUserRepository.findByUsername(username)
                .orElseThrow().getPasswordHash(), "replay must not rotate the credential");

        // The persisted idempotent response must not contain the cleartext credential.
        AdminApiIdempotencyRecord record = adminApiIdempotencyRecordRepository
                .findByOperationKeyAndIdempotencyKey(USER_CREATE, key)
                .orElseThrow();
        assertFalse(record.getResponseBody().contains(firstPassword));

        // The original credential still authenticates exactly once-issued value.
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AuthApiResponses.LoginRequest(
                                email, firstPassword))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordChangeRequired").value(true));

        assertNoLogContains(firstPassword);
    }

    @Test
    void resetFlowNeverLogsCleartextCredentialOnFirstReplayOrFailure() throws Exception {
        String username = uniqueName("rst");
        String email = username + "@bhawana.local";

        MvcResult createResult = mockMvc.perform(post("/api/v1/internal/admin/users")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(generatedCreateBody(username, email).toString()))
                .andExpect(status().isOk())
                .andReturn();
        String createPassword = readTemporaryPassword(createResult);
        trackCreatedUser(createResult, username);

        entityManager.clear();
        UUID userId = appUserRepository.findByUsername(username).orElseThrow().getId();
        String resetKey = trackKey(USER_RESET_PASSWORD);

        MvcResult firstReset = mockMvc.perform(
                        post("/api/v1/internal/admin/users/{userId}/reset-password", userId)
                                .with(systemAdmin())
                                .header("Idempotency-Key", resetKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.temporaryPassword").isString())
                .andReturn();
        String resetPassword = readTemporaryPassword(firstReset);
        assertNotEquals(createPassword, resetPassword);

        MvcResult replayReset = mockMvc.perform(
                        post("/api/v1/internal/admin/users/{userId}/reset-password", userId)
                                .with(systemAdmin())
                                .header("Idempotency-Key", resetKey))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode replayJson = objectMapper.readTree(replayReset.getResponse().getContentAsString());
        assertTrue(replayJson.get("temporaryPassword") == null || replayJson.get("temporaryPassword").isNull(),
                "reset replay must not reveal or rotate the credential");

        mockMvc.perform(post("/api/v1/internal/admin/users/{userId}/reset-password",
                        UUID.fromString("00000000-0000-0000-0000-000000000099"))
                        .with(systemAdmin()))
                .andExpect(status().isNotFound());

        // The superseded create credential no longer authenticates; the reset one does.
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AuthApiResponses.LoginRequest(email, createPassword))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AuthApiResponses.LoginRequest(email, resetPassword))))
                .andExpect(status().isOk());

        assertNoLogContains(createPassword, resetPassword);
    }

    @Test
    void sameKeyWithDifferentPayloadConflicts() throws Exception {
        String username = uniqueName("cfl");
        String key = trackKey(USER_CREATE);

        MvcResult first = mockMvc.perform(post("/api/v1/internal/admin/users")
                        .with(systemAdmin())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "email", username + "@bhawana.local",
                                "roles", List.of("OPS_USER")))))
                .andExpect(status().isOk())
                .andReturn();
        String firstPassword = readTemporaryPassword(first);
        trackCreatedUser(first, username);

        mockMvc.perform(post("/api/v1/internal/admin/users")
                        .with(systemAdmin())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "email", "different.email@bhawana.local",
                                "roles", List.of("OPS_USER")))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        assertNoLogContains(firstPassword);
    }

    @Test
    void compatibilityModeWithCallerPasswordPersistsHashAndRevealsNothing() throws Exception {
        String username = uniqueName("cmp");
        String callerPassword = "CallerSupplied123!";
        String key = trackKey(USER_CREATE);

        MvcResult createResult = mockMvc.perform(post("/api/v1/internal/admin/users")
                        .with(systemAdmin())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "email", username + "@bhawana.local",
                                "password", callerPassword,
                                "roles", List.of("OPS_USER")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordChangeRequired").value(true))
                .andReturn();
        trackCreatedUser(createResult, username);

        JsonNode createJson = objectMapper.readTree(createResult.getResponse().getContentAsString());
        assertTrue(createJson.get("temporaryPassword") == null || createJson.get("temporaryPassword").isNull(),
                "compatibility mode reveals nothing: the caller already holds the secret");

        entityManager.clear();
        AppUser stored = appUserRepository.findByUsername(username).orElseThrow();
        assertTrue(passwordEncoder.matches(callerPassword, stored.getPasswordHash()));
        assertNotEquals(callerPassword, stored.getPasswordHash());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AuthApiResponses.LoginRequest(username + "@bhawana.local", callerPassword))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordChangeRequired").value(true));

        AdminApiIdempotencyRecord record = adminApiIdempotencyRecordRepository
                .findByOperationKeyAndIdempotencyKey(USER_CREATE, key)
                .orElseThrow();
        assertFalse(record.getResponseBody().contains(callerPassword));

        assertNoLogContains(callerPassword);
    }

    @Test
    void errorResponsesNeverCarryCleartextCredential() throws Exception {
        String username = uniqueName("err");

        MvcResult createResult = mockMvc.perform(post("/api/v1/internal/admin/users")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(generatedCreateBody(username, username + "@bhawana.local").toString()))
                .andExpect(status().isOk())
                .andReturn();
        String temporaryPassword = readTemporaryPassword(createResult);
        trackCreatedUser(createResult, username);

        MvcResult conflict = mockMvc.perform(post("/api/v1/internal/admin/users")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(generatedCreateBody(username, "other.email@bhawana.local").toString()))
                .andExpect(status().isConflict())
                .andReturn();
        assertFalse(conflict.getResponse().getContentAsString().contains(temporaryPassword));

        MvcResult validation = mockMvc.perform(post("/api/v1/internal/admin/users")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "",
                                "email", "not-an-email",
                                "roles", List.of()))))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertFalse(validation.getResponse().getContentAsString().contains(temporaryPassword));

        MvcResult list = mockMvc.perform(get("/api/v1/internal/admin/users").with(systemAdmin()))
                .andExpect(status().isOk())
                .andReturn();
        assertFalse(list.getResponse().getContentAsString().contains(temporaryPassword));

        assertOwnedAuditPayloadsDoNotContain(temporaryPassword);
        assertNoLogContains(temporaryPassword);
    }

    @Test
    void nonSystemAdminCannotCreateOrListUsers() throws Exception {
        String username = uniqueName("den");

        mockMvc.perform(post("/api/v1/internal/admin/users")
                        .with(opsUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(generatedCreateBody(username, username + "@bhawana.local").toString()))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/internal/admin/users").with(opsUser()))
                .andExpect(status().isForbidden());

        assertTrue(appUserRepository.findByUsername(username).isEmpty());
    }

    private static String uniqueName(String tag) {
        return "t04-" + tag + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String trackKey(String operationKey) {
        String key = UUID.randomUUID().toString();
        ownedIdempotencyKeys.add(new String[]{operationKey, key});
        return key;
    }

    private void trackCreatedUser(MvcResult createResult, String username) throws Exception {
        UUID id = UUID.fromString(
                objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText());
        ownedUserIds.add(id);
        ownedUsernames.add(username);
    }

    private ObjectNode generatedCreateBody(String username, String email) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("username", username);
        body.put("email", email);
        body.set("roles", objectMapper.createArrayNode().add("OPS_USER"));
        return body;
    }

    private String readTemporaryPassword(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("temporaryPassword").asText();
    }

    private void assertOwnedAuditPayloadsDoNotContain(String cleartext) {
        for (AppUserAuditEvent event : appUserAuditEventRepository.findAll()) {
            if (!ownedUserIds.contains(event.getUserId())) {
                continue;
            }
            assertFalse(event.getBeforeStateJson().toString().contains(cleartext));
            assertFalse(event.getAfterStateJson().toString().contains(cleartext));
        }
    }

    /**
     * Audit requirement: the cleartext credential must never reach the captured
     * application log — not on success, replay, or failure paths.
     */
    private void assertNoLogContains(String... secrets) {
        for (ILoggingEvent event : logCapture.list) {
            String formatted = event.getFormattedMessage();
            if (formatted == null) {
                continue;
            }
            for (String secret : secrets) {
                assertFalse(formatted.contains(secret),
                        "application log must not carry the cleartext credential: " + formatted);
            }
        }
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor systemAdmin() {
        return jwt().jwt(jwt -> jwt.subject("ops.admin").claim("roles", List.of("SYSTEM_ADMIN")))
                .authorities(() -> "ROLE_SYSTEM_ADMIN");
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor opsUser() {
        return jwt().jwt(jwt -> jwt.subject("ops.user").claim("roles", List.of("OPS_USER")))
                .authorities(() -> "ROLE_OPS_USER");
    }
}
