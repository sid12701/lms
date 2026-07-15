package com.bhawana.lms.service;

import com.bhawana.lms.common.api.CursorPagedResult;
import com.bhawana.lms.repo.AuditExplorerRepository;
import com.bhawana.lms.repo.AuditExplorerRepository.UnifiedAuditEventRow;
import com.bhawana.lms.service.AuditExplorerCursorCodec.AuditExplorerCursor;
import com.bhawana.lms.service.AuditExplorerQuery.AuditStream;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gap #3 — Unified cross-domain audit search.
 *
 * <p>Orchestrates the native UNION ALL query and projects each row into the
 * stable {@link AuditExplorerEvent} envelope. The INTAKE stream's raw payload
 * is masked (aadhaar redacted) before leaving the service boundary; the raw
 * payload remains in DB unchanged for true forensics.
 */
@Service
public class AuditExplorerService {

    private static final List<String> AADHAAR_FIELD_NAMES = List.of(
            "borrowerAadharNumber",
            "borrowerAadhaarNumber",
            "aadharNumber",
            "aadhaarNumber",
            "aadhar"
    );

    private final AuditExplorerRepository repository;
    private final ObjectMapper objectMapper;

    public AuditExplorerService(AuditExplorerRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public CursorPagedResult<AuditExplorerEvent> search(AuditExplorerQuery query) {
        AuditExplorerCursor cursor = query.cursor() == null
                ? null
                : AuditExplorerCursorCodec.decode(query.cursor(), objectMapper);
        CursorPagedResult<UnifiedAuditEventRow> raw = repository.search(query, cursor);
        List<AuditExplorerEvent> projected = new ArrayList<>(raw.items().size());
        for (UnifiedAuditEventRow row : raw.items()) {
            projected.add(project(row));
        }
        String nextCursor = null;
        if (raw.nextCursor() != null && !raw.items().isEmpty()) {
            UnifiedAuditEventRow last = raw.items().get(raw.items().size() - 1);
            nextCursor = AuditExplorerCursorCodec.encode(
                    new AuditExplorerCursor(last.occurredAt(), last.stream(), last.nativeId()),
                    objectMapper
            );
        }
        return new CursorPagedResult<>(projected, nextCursor, raw.limit());
    }

    @Transactional(readOnly = true)
    public List<DocumentAccessDocumentTypeCount> countDocumentAccessByDocumentType(Instant since, Instant until) {
        return repository.countDocumentAccessByDocumentType(since, until).stream()
                .map(row -> new DocumentAccessDocumentTypeCount(row.documentType(), row.count()))
                .toList();
    }

    private AuditExplorerEvent project(UnifiedAuditEventRow row) {
        AuditStream stream = AuditStream.valueOf(row.stream());
        String compositeId = stream.name() + ":" + row.nativeId();
        Map<String, Object> detail = buildDetail(stream, row);
        String action = resolveAction(stream, row);
        String summary = buildSummary(stream, row, action);
        return new AuditExplorerEvent(
                compositeId,
                stream.name(),
                row.occurredAt(),
                row.actorUsername(),
                stringOrNull(row.loanApplicationId()),
                stringOrNull(row.borrowerId()),
                stringOrNull(row.lspId()),
                stringOrNull(row.productId()),
                action,
                summary,
                detail,
                row.correlationId()
        );
    }

    private Map<String, Object> buildDetail(AuditStream stream, UnifiedAuditEventRow row) {
        return switch (stream) {
            case APPLICATION -> {
                LinkedHashMap<String, Object> map = new LinkedHashMap<>();
                if (row.fromStatus() != null) {
                    map.put("fromStatus", row.fromStatus());
                }
                if (row.toStatus() != null) {
                    map.put("toStatus", row.toStatus());
                }
                if (row.reasonCode() != null) {
                    map.put("reasonCode", row.reasonCode());
                }
                if (row.summary() != null && !row.summary().isBlank()) {
                    map.put("note", row.summary());
                }
                yield map;
            }
            case INTAKE -> {
                LinkedHashMap<String, Object> map = new LinkedHashMap<>();
                map.put("payload", maskIntakePayload(row.payloadJson()));
                yield map;
            }
            case DOCUMENT_ACCESS -> {
                LinkedHashMap<String, Object> map = new LinkedHashMap<>();
                if (row.documentTypes() != null && !row.documentTypes().isBlank()) {
                    map.put("documentTypes", splitDocumentTypes(row.documentTypes()));
                }
                yield map;
            }
            case PRODUCT -> Map.of();
            case APP_USER -> buildAppUserDetail(row.payloadJson());
            case API_CLIENT -> buildApiClientDetail(row.payloadJson());
            case DISBURSEMENT -> buildDisbursementDetail(row.payloadJson());
            case REPORT_ACCESS -> buildReportAccessDetail(row.payloadJson());
        };
    }

    private String resolveAction(AuditStream stream, UnifiedAuditEventRow row) {
        if (stream == AuditStream.APP_USER) {
            String fromPayload = readNestedJsonTextField(row.payloadJson(), "afterState", "eventType");
            if (fromPayload != null && !fromPayload.isBlank()) {
                return fromPayload;
            }
        }
        return row.action() == null ? streamFallbackAction(stream) : row.action();
    }

    private LinkedHashMap<String, Object> buildAppUserDetail(String payloadJson) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        JsonNode root = parsePayloadRoot(payloadJson);
        if (root == null) {
            return map;
        }
        putIfPresent(map, "userId", textOrNull(root, "userId"));
        putIfPresent(map, "username", textOrNull(root, "username"));
        JsonNode afterState = root.get("afterState");
        if (afterState != null && !afterState.isNull()) {
            map.put("afterState", objectMapper.convertValue(afterState, Map.class));
        }
        JsonNode beforeState = root.get("beforeState");
        if (beforeState != null && !beforeState.isNull()) {
            map.put("beforeState", objectMapper.convertValue(beforeState, Map.class));
        }
        return map;
    }

    private LinkedHashMap<String, Object> buildApiClientDetail(String payloadJson) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        JsonNode root = parsePayloadRoot(payloadJson);
        if (root == null) {
            return map;
        }
        putIfPresent(map, "apiClientId", textOrNull(root, "apiClientId"));
        putIfPresent(map, "clientId", textOrNull(root, "clientId"));
        JsonNode details = root.get("details");
        if (details != null && !details.isNull()) {
            map.put("details", objectMapper.convertValue(details, Map.class));
        }
        return map;
    }

    private LinkedHashMap<String, Object> buildDisbursementDetail(String payloadJson) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        JsonNode root = parsePayloadRoot(payloadJson);
        if (root == null) {
            return map;
        }
        putIfPresent(map, "source", textOrNull(root, "source"));
        putIfPresent(map, "outcome", textOrNull(root, "outcome"));
        putIfPresent(map, "providerRequestId", textOrNull(root, "providerRequestId"));
        putIfPresent(map, "actorIp", textOrNull(root, "actorIp"));
        return map;
    }

    private LinkedHashMap<String, Object> buildReportAccessDetail(String payloadJson) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        JsonNode root = parsePayloadRoot(payloadJson);
        if (root == null) {
            return map;
        }
        putIfPresent(map, "reportType", textOrNull(root, "reportType"));
        putIfPresent(map, "actorIp", textOrNull(root, "actorIp"));
        putIfPresent(map, "reportRequestId", textOrNull(root, "reportRequestId"));
        JsonNode byteCount = root.get("byteCount");
        if (byteCount != null && !byteCount.isNull()) {
            map.put("byteCount", byteCount.asLong());
        }
        JsonNode filterPayload = root.get("filterPayload");
        if (filterPayload != null && !filterPayload.isNull()) {
            map.put("filterPayload", objectMapper.convertValue(filterPayload, Map.class));
        }
        return map;
    }

    private JsonNode parsePayloadRoot(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(payloadJson);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private String readNestedJsonTextField(String payloadJson, String objectField, String textField) {
        JsonNode root = parsePayloadRoot(payloadJson);
        if (root == null) {
            return null;
        }
        JsonNode objectNode = root.get(objectField);
        if (objectNode == null || objectNode.isNull()) {
            return null;
        }
        JsonNode value = objectNode.get(textField);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String textOrNull(JsonNode root, String field) {
        JsonNode value = root.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static void putIfPresent(LinkedHashMap<String, Object> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    private List<String> splitDocumentTypes(String raw) {
        List<String> out = new ArrayList<>();
        for (String value : raw.split(",")) {
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private String buildSummary(AuditStream stream, UnifiedAuditEventRow row, String action) {
        return switch (stream) {
            case APPLICATION -> {
                if (row.fromStatus() != null && row.toStatus() != null
                        && !row.fromStatus().equals(row.toStatus())) {
                    yield row.toStatus() + " (from " + row.fromStatus() + ")";
                }
                if (row.summary() != null && !row.summary().isBlank()) {
                    yield row.summary();
                }
                yield humanizeAction(action);
            }
            case INTAKE -> "Intake snapshot recorded";
            case DOCUMENT_ACCESS -> row.summary() != null && !row.summary().isBlank()
                    ? row.summary()
                    : humanizeAction(action);
            case PRODUCT -> row.summary() != null && !row.summary().isBlank()
                    ? row.summary()
                    : humanizeAction(action);
            case APP_USER -> buildAppUserSummary(row.payloadJson(), action);
            case API_CLIENT -> buildApiClientSummary(row.payloadJson(), action);
            case DISBURSEMENT -> buildDisbursementSummary(row.payloadJson(), action);
            case REPORT_ACCESS -> buildReportAccessSummary(row.payloadJson(), action);
        };
    }

    private String buildAppUserSummary(String payloadJson, String action) {
        JsonNode root = parsePayloadRoot(payloadJson);
        String username = root == null ? null : textOrNull(root, "username");
        if ("PASSWORD_RESET_BY_ADMIN".equals(action)) {
            return username == null
                    ? "Admin password reset"
                    : "Password reset for user " + username;
        }
        if ("USER_UPDATED".equals(action)) {
            return username == null
                    ? "User updated"
                    : "Updated user " + username;
        }
        return username == null ? humanizeAction(action) : humanizeAction(action) + " for " + username;
    }

    private String buildApiClientSummary(String payloadJson, String action) {
        JsonNode root = parsePayloadRoot(payloadJson);
        String clientId = root == null ? null : textOrNull(root, "clientId");
        String label = humanizeAction(action);
        return clientId == null ? label : label + " (" + clientId + ")";
    }

    private String buildDisbursementSummary(String payloadJson, String action) {
        JsonNode root = parsePayloadRoot(payloadJson);
        String outcome = root == null ? null : textOrNull(root, "outcome");
        if (outcome != null && !outcome.isBlank()) {
            return "Mock disbursement outcome: " + humanizeAction(outcome);
        }
        return humanizeAction(action);
    }

    private String buildReportAccessSummary(String payloadJson, String action) {
        JsonNode root = parsePayloadRoot(payloadJson);
        String reportType = root == null ? null : textOrNull(root, "reportType");
        long byteCount = root == null || !root.has("byteCount") || root.get("byteCount").isNull()
                ? -1L
                : root.get("byteCount").asLong();
        String label = switch (action) {
            case "MIS_CSV_DOWNLOADED" -> "Portfolio MIS CSV downloaded";
            case "MIS_REQUEST_DOWNLOADED" -> "Portfolio MIS report downloaded";
            default -> humanizeAction(action);
        };
        if (reportType != null && !"PORTFOLIO_MIS".equals(reportType)) {
            label = humanizeAction(reportType) + " downloaded";
        }
        if (byteCount >= 0) {
            return label + " (" + formatByteCount(byteCount) + ")";
        }
        return label;
    }

    private static String formatByteCount(long byteCount) {
        if (byteCount < 1024) {
            return byteCount + " bytes";
        }
        if (byteCount < 1024 * 1024) {
            return (byteCount / 1024) + " KB";
        }
        return String.format("%.1f MB", byteCount / (1024.0 * 1024.0));
    }

    private String streamFallbackAction(AuditStream stream) {
        return switch (stream) {
            case INTAKE -> "INTAKE_RECORDED";
            case APPLICATION -> "APPLICATION_EVENT";
            case DOCUMENT_ACCESS -> "DOCUMENT_ACCESS";
            case PRODUCT -> "PRODUCT_EVENT";
            case APP_USER -> "USER_UPDATED";
            case API_CLIENT -> "API_CLIENT_EVENT";
            case DISBURSEMENT -> "DISBURSEMENT_OUTCOME";
            case REPORT_ACCESS -> "REPORT_DOWNLOAD";
        };
    }

    private static String humanizeAction(String action) {
        if (action == null || action.isBlank()) {
            return "Audit event";
        }
        String lowered = action.replace('_', ' ').toLowerCase();
        return Character.toUpperCase(lowered.charAt(0)) + lowered.substring(1);
    }

    /**
     * Parse the intake payload JSON and aadhaar-mask any well-known field
     * before exposing through the unified audit response. Returns {@code null}
     * when the input is null/blank; returns a {@code {"raw": "..."}} placeholder
     * when the payload is non-JSON.
     */
    Object maskIntakePayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(payloadJson);
            if (root instanceof ObjectNode obj) {
                for (String field : AADHAAR_FIELD_NAMES) {
                    if (obj.has(field) && !obj.get(field).isNull()) {
                        obj.put(field, maskAadhaar(obj.get(field).asText()));
                    }
                }
                return objectMapper.convertValue(obj, Map.class);
            }
            return objectMapper.convertValue(root, Object.class);
        } catch (JsonProcessingException ex) {
            return Map.of("raw", payloadJson);
        }
    }

    static String maskAadhaar(String value) {
        if (value == null) {
            return null;
        }
        String digits = value.replaceAll("\\s", "");
        if (digits.length() < 4) {
            return "XXXXXXXX";
        }
        return "XXXXXXXX" + digits.substring(digits.length() - 4);
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : value.toString();
    }

    /**
     * Response envelope returned by the controller — kept stable for the FE.
     */
    public record AuditExplorerEvent(
            String id,
            String stream,
            Instant occurredAt,
            String actorUsername,
            String loanApplicationId,
            String borrowerId,
            String lspId,
            String productId,
            String action,
            String summary,
            Map<String, Object> detail,
            String correlationId
    ) {
    }

    public record DocumentAccessDocumentTypeCount(String documentType, long count) {
    }
}
