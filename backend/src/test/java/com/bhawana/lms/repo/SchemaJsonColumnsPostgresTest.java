package com.bhawana.lms.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bhawana.lms.support.PostgresDataJpaTestSupport;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SchemaJsonColumnsPostgresTest extends PostgresDataJpaTestSupport {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID lspId;
    private UUID borrowerId;
    private UUID loanProductId;
    private UUID loanApplicationId;
    private UUID loanAccountId;
    private UUID appUserId;
    private UUID apiClientId;

    @BeforeEach
    void seedParents() {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toLowerCase();
        lspId = insertLsp("JSON-" + suffix);
        borrowerId = insertBorrower("PAN" + suffix.substring(0, 7).toUpperCase(), "98888" + suffix.substring(0, 5));
        loanProductId = insertLoanProduct("JSON-PROD-" + suffix);
        loanApplicationId = insertLoanApplication("JSON-EXT-" + suffix);
        loanAccountId = insertLoanAccount("JSON-ACC-" + suffix);
        appUserId = insertAppUser("json-user-" + suffix);
        apiClientId = insertApiClient("cli_json_" + suffix);
    }

    @ParameterizedTest
    @MethodSource("jsonColumns")
    void jsonPayloadColumnsAreBackedByPostgresJsonb(JsonColumn column) {
        String actualType = jdbcTemplate.queryForObject(
                "SELECT udt_name FROM information_schema.columns WHERE table_name = ? AND column_name = ?",
                String.class,
                column.table(),
                column.column()
        );

        assertThat(actualType).isEqualTo("jsonb");
    }

    @ParameterizedTest
    @MethodSource("jsonColumns")
    void jsonPayloadColumnsStoreQueryableObjects(JsonColumn column) {
        UUID rowId = insertRow(column, objectJson("kind", column.table() + "." + column.column()));

        String kind = jdbcTemplate.queryForObject(
                "SELECT " + column.column() + " ->> 'kind' FROM " + column.table() + " WHERE id = ?",
                String.class,
                rowId
        );
        Boolean nestedFlag = jdbcTemplate.queryForObject(
                "SELECT ((" + column.column() + " -> 'nested') ->> 'ok')::boolean FROM "
                        + column.table()
                        + " WHERE id = ?",
                Boolean.class,
                rowId
        );

        assertThat(kind).isEqualTo(column.table() + "." + column.column());
        assertThat(nestedFlag).isTrue();
    }

    @ParameterizedTest
    @MethodSource("jsonColumns")
    void jsonPayloadColumnsRejectMalformedJson(JsonColumn column) {
        UUID rowId = insertRow(column, objectJson("kind", "valid-before-malformed-update"));

        assertThatThrownBy(() -> updateJsonColumn(column, rowId, "{not-json"))
                .isInstanceOf(DataAccessException.class);
    }

    @ParameterizedTest
    @MethodSource("nonObjectJsonInputs")
    void jsonPayloadColumnsRejectNonObjectJson(JsonColumn column, String nonObjectJson) {
        UUID rowId = insertRow(column, objectJson("kind", "valid-before-non-object-update"));

        assertThatThrownBy(() -> updateJsonColumn(column, rowId, nonObjectJson))
                .isInstanceOf(DataAccessException.class);
    }

    @ParameterizedTest
    @MethodSource("nullableJsonColumns")
    void optionalJsonPayloadColumnsStillAllowNull(JsonColumn column) {
        UUID rowId = insertRow(column, objectJson("kind", "nullable-before-null-update"));

        jdbcTemplate.update("UPDATE " + column.table() + " SET " + column.column() + " = NULL WHERE id = ?", rowId);

        Integer present = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + column.table() + " WHERE id = ? AND " + column.column() + " IS NULL",
                Integer.class,
                rowId
        );
        assertThat(present).isEqualTo(1);
    }

    @Test
    void jsonColumnsRemainReadableAsTextForExistingAuditSurfaces() {
        JsonColumn intakePayload = new JsonColumn("loan_application_intake_audit", "payload_json");
        UUID rowId = insertRow(intakePayload, objectJson("kind", "intake-audit"));

        String payload = jdbcTemplate.queryForObject(
                "SELECT CAST(payload_json AS text) FROM loan_application_intake_audit WHERE id = ?",
                String.class,
                rowId
        );

        assertThat(payload).contains("\"kind\": \"intake-audit\"");
    }

    @ParameterizedTest
    @MethodSource("productionShapePayloads")
    void productionShapedPayloadsSatisfyJsonbObjectConstraint(JsonColumn column, String productionShapeJson) {
        UUID rowId = insertRow(column, productionShapeJson);

        Integer matches = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + column.table()
                        + " WHERE id = ? AND jsonb_typeof(" + column.column() + ") = 'object'",
                Integer.class,
                rowId
        );
        assertThat(matches).isEqualTo(1);
    }

    private static Stream<Arguments> productionShapePayloads() {
        // Shapes mirror what production services actually write today.
        // If V72 lands and these fail, the writing service is producing a non-object payload.
        String webhookEnvelope = "{\"eventId\":\"" + UUID.randomUUID()
                + "\",\"eventType\":\"LOAN_CREATED\",\"occurredAt\":\"2026-05-29T10:00:00Z\","
                + "\"data\":{\"loanApplicationId\":\"" + UUID.randomUUID() + "\",\"status\":\"INITIALIZED\"}}";
        String disbursementRequest = "{\"loanAccountId\":\"" + UUID.randomUUID()
                + "\",\"amount\":5000.00,\"provider\":\"MOCK\",\"providerRequestId\":\"req-1\"}";
        String disbursementResponse = "{\"providerStatus\":\"REQUESTED\",\"providerReference\":\"ref-1\",\"raw\":{\"ok\":true}}";
        String rejectionReason = "{\"failedRules\":[\"INCOME_TOO_LOW\",\"PAN_BLACKLISTED\"]}";
        // AlertRuleEvaluationWorker builds context_json by manual string concat.
        String opsAlertContext = "{\"eventId\":\"" + UUID.randomUUID()
                + "\",\"lspId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"LOAN_CREATED\"}";
        String userSnapshotBefore = "{\"username\":\"old.name\",\"email\":\"old@example.com\",\"status\":\"ACTIVE\",\"roles\":[\"OPS\"]}";
        String userSnapshotAfter = "{\"username\":\"new.name\",\"email\":\"new@example.com\",\"status\":\"ACTIVE\",\"roles\":[\"OPS\",\"ADMIN\"]}";
        String apiClientAuditDetails = "{\"clientId\":\"cli_abc\",\"changes\":{\"name\":\"renamed\"}}";
        String intakePayload = "{\"borrower\":{\"pan\":\"ABCDE1234F\",\"mobile\":\"9876543210\"},"
                + "\"requestedAmount\":5000.00,\"tenureMonths\":12,\"sourceChannel\":\"API\"}";

        return Stream.of(
                Arguments.of(new JsonColumn("webhook_event_outbox", "payload_json"), webhookEnvelope),
                Arguments.of(new JsonColumn("loan_disbursement_request_log", "request_payload_json"), disbursementRequest),
                Arguments.of(new JsonColumn("loan_disbursement_request_log", "response_payload_json"), disbursementResponse),
                Arguments.of(new JsonColumn("loan_application_status_transition", "rejection_reason_json"), rejectionReason),
                Arguments.of(new JsonColumn("ops_alert", "context_json"), opsAlertContext),
                Arguments.of(new JsonColumn("app_user_audit_event", "before_state_json"), userSnapshotBefore),
                Arguments.of(new JsonColumn("app_user_audit_event", "after_state_json"), userSnapshotAfter),
                Arguments.of(new JsonColumn("api_client_audit_event", "details_json"), apiClientAuditDetails),
                Arguments.of(new JsonColumn("loan_application_intake_audit", "payload_json"), intakePayload)
        );
    }

    private static Stream<JsonColumn> jsonColumns() {
        return Stream.of(
                new JsonColumn("webhook_event_outbox", "payload_json"),
                new JsonColumn("loan_disbursement_request_log", "request_payload_json"),
                new JsonColumn("loan_disbursement_request_log", "response_payload_json"),
                new JsonColumn("loan_application_status_transition", "rejection_reason_json"),
                new JsonColumn("ops_alert", "context_json"),
                new JsonColumn("app_user_audit_event", "before_state_json"),
                new JsonColumn("app_user_audit_event", "after_state_json"),
                new JsonColumn("api_client_audit_event", "details_json"),
                new JsonColumn("loan_application_intake_audit", "payload_json")
        );
    }

    private static Stream<JsonColumn> nullableJsonColumns() {
        return Stream.of(
                new JsonColumn("loan_application_status_transition", "rejection_reason_json"),
                new JsonColumn("ops_alert", "context_json")
        );
    }

    private static Stream<Arguments> nonObjectJsonInputs() {
        return jsonColumns().flatMap(column -> Stream.of(
                Arguments.of(column, "[]"),
                Arguments.of(column, "\"scalar\""),
                Arguments.of(column, "123")
        ));
    }

    private UUID insertRow(JsonColumn column, String json) {
        return switch (column.table()) {
            case "webhook_event_outbox" -> insertWebhookOutbox(json);
            case "loan_disbursement_request_log" -> insertDisbursementLog(column.column(), json);
            case "loan_application_status_transition" -> insertStatusTransition(json);
            case "ops_alert" -> insertOpsAlert(json);
            case "app_user_audit_event" -> insertAppUserAuditEvent(column.column(), json);
            case "api_client_audit_event" -> insertApiClientAuditEvent(json);
            case "loan_application_intake_audit" -> insertLoanApplicationIntakeAudit(json);
            default -> throw new IllegalArgumentException("Unsupported JSON column: " + column);
        };
    }

    private void updateJsonColumn(JsonColumn column, UUID rowId, String json) {
        jdbcTemplate.update(
                "UPDATE " + column.table() + " SET " + column.column() + " = ?::jsonb WHERE id = ?",
                json,
                rowId
        );
    }

    private UUID insertWebhookOutbox(String payloadJson) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO webhook_event_outbox (id, lsp_id, event_type, aggregate_type, aggregate_id, "
                        + "loan_application_id, status, payload_json, correlation_id, attempt_count, entity_version, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, 'LOAN_CREATED', 'LOAN_APPLICATION', ?, ?, 'PENDING', ?::jsonb, ?, 0, 0, NOW(), NOW())",
                id,
                lspId,
                loanApplicationId.toString(),
                loanApplicationId,
                payloadJson,
                "corr-" + id
        );
        return id;
    }

    private UUID insertDisbursementLog(String targetColumn, String json) {
        UUID id = UUID.randomUUID();
        String requestPayload = targetColumn.equals("request_payload_json")
                ? json
                : objectJson("kind", "default-request");
        String responsePayload = targetColumn.equals("response_payload_json")
                ? json
                : objectJson("kind", "default-response");
        jdbcTemplate.update(
                "INSERT INTO loan_disbursement_request_log (id, loan_account_id, actor_username, amount, "
                        + "provider_name, provider_request_id, provider_status, request_payload_json, "
                        + "response_payload_json, correlation_id, created_at, updated_at) "
                        + "VALUES (?, ?, 'system', 100.00, 'MOCK', ?, 'REQUESTED', ?::jsonb, ?::jsonb, ?, NOW(), NOW())",
                id,
                loanAccountId,
                "provider-" + id,
                requestPayload,
                responsePayload,
                "corr-" + id
        );
        return id;
    }

    private UUID insertStatusTransition(String rejectionReasonJson) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO loan_application_status_transition (id, loan_application_id, from_status, to_status, "
                        + "actor_username, note, reason_code, correlation_id, rejection_reason_json, created_at) "
                        + "VALUES (?, ?, 'INITIALIZED', 'REJECTED', 'system', 'Rejected by rule engine', "
                        + "'FAILED_VERIFICATION', ?, ?::jsonb, NOW())",
                id,
                loanApplicationId,
                "corr-" + id,
                rejectionReasonJson
        );
        return id;
    }

    private UUID insertOpsAlert(String contextJson) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO ops_alert (id, type, severity, status, title, message, subject_type, subject_id, "
                        + "correlation_id, context_json, created_at) "
                        + "VALUES (?, 'STALE_INTAKE', 'HIGH', 'NEW', 'Json alert', 'Json alert message', "
                        + "'LOAN_APPLICATION', ?, ?, ?::jsonb, NOW())",
                id,
                loanApplicationId,
                "corr-" + id,
                contextJson
        );
        return id;
    }

    private UUID insertAppUserAuditEvent(String targetColumn, String json) {
        UUID id = UUID.randomUUID();
        String beforeJson = targetColumn.equals("before_state_json")
                ? json
                : objectJson("kind", "default-before");
        String afterJson = targetColumn.equals("after_state_json")
                ? json
                : objectJson("kind", "default-after");
        jdbcTemplate.update(
                "INSERT INTO app_user_audit_event (id, user_id, actor_username, before_state_json, "
                        + "after_state_json, correlation_id, created_at) "
                        + "VALUES (?, ?, 'system', ?::jsonb, ?::jsonb, ?, NOW())",
                id,
                appUserId,
                beforeJson,
                afterJson,
                "corr-" + id
        );
        return id;
    }

    private UUID insertApiClientAuditEvent(String detailsJson) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO api_client_audit_event (id, api_client_id, actor_username, action, details_json, "
                        + "correlation_id, created_at) "
                        + "VALUES (?, ?, 'system', 'CREATE', ?::jsonb, ?, NOW())",
                id,
                apiClientId,
                detailsJson,
                "corr-" + id
        );
        return id;
    }

    private UUID insertLoanApplicationIntakeAudit(String payloadJson) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO loan_application_intake_audit (id, loan_application_id, actor_username, correlation_id, "
                        + "payload_json, created_at) "
                        + "VALUES (?, ?, 'system', ?, ?::jsonb, NOW())",
                id,
                loanApplicationId,
                "corr-" + id,
                payloadJson
        );
        return id;
    }

    private UUID insertLsp(String code) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO lsp (id, code, name, status) VALUES (?, ?, ?, 'ACTIVE')",
                id,
                code,
                "LSP " + code
        );
        return id;
    }

    private UUID insertBorrower(String pan, String mobile) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO borrower (id, full_name, pan, mobile) VALUES (?, ?, ?, ?)",
                id,
                "Borrower " + pan,
                pan,
                mobile
        );
        return id;
    }

    private UUID insertLoanProduct(String code) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO loan_product (id, code, name, min_principal, max_principal, interest_rate, "
                        + "processing_fee_rate, min_tenure_months, max_tenure_months) "
                        + "VALUES (?, ?, ?, 100.00, 100000.00, 10.00, 1.00, 6, 60)",
                id,
                code,
                "Product " + code
        );
        return id;
    }

    private UUID insertLoanApplication(String externalId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO loan_application (id, borrower_id, lsp_id, loan_product_id, external_loan_id, "
                        + "source_channel, requested_amount, tenure_months, status) "
                        + "VALUES (?, ?, ?, ?, ?, 'API', 5000.00, 12, 'INITIALIZED')",
                id,
                borrowerId,
                lspId,
                loanProductId,
                externalId
        );
        return id;
    }

    private UUID insertLoanAccount(String accountNumber) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO loan_account (id, loan_application_id, borrower_id, lsp_id, loan_product_id, "
                        + "account_number, principal_amount, tenure_months, status, approved_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 5000.00, 12, 'PENDING_DISBURSEMENT', NOW())",
                id,
                loanApplicationId,
                borrowerId,
                lspId,
                loanProductId,
                accountNumber
        );
        return id;
    }

    private UUID insertAppUser(String username) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO app_user (id, username, email, password_hash, status) VALUES (?, ?, ?, 'hash', 'ACTIVE')",
                id,
                username,
                username + "@example.com"
        );
        return id;
    }

    private UUID insertApiClient(String clientId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO api_client (id, client_id, lsp_id, name, secret_hash, status) "
                        + "VALUES (?, ?, ?, ?, 'hash', 'ACTIVE')",
                id,
                clientId,
                lspId,
                "API Client " + clientId
        );
        return id;
    }

    private static String objectJson(String key, String value) {
        return "{\"" + key + "\":\"" + value + "\",\"nested\":{\"ok\":true}}";
    }

    private record JsonColumn(String table, String column) {
        @Override
        public String toString() {
            return table + "." + column;
        }
    }
}
