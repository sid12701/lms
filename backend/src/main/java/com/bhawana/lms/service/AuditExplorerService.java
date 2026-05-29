package com.bhawana.lms.service;

import com.bhawana.lms.common.web.PagedResult;
import com.bhawana.lms.repo.AuditExplorerRepository;
import com.bhawana.lms.repo.AuditExplorerRepository.UnifiedAuditEventRow;
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
    public PagedResult<AuditExplorerEvent> search(AuditExplorerQuery query) {
        PagedResult<UnifiedAuditEventRow> raw = repository.search(query);
        List<AuditExplorerEvent> projected = new ArrayList<>(raw.items().size());
        for (UnifiedAuditEventRow row : raw.items()) {
            projected.add(project(row));
        }
        return new PagedResult<>(projected, raw.totalCount(), raw.offset(), raw.limit());
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
        String action = row.action() == null ? streamFallbackAction(stream) : row.action();
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
        };
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
        };
    }

    private String streamFallbackAction(AuditStream stream) {
        return switch (stream) {
            case INTAKE -> "INTAKE_RECORDED";
            case APPLICATION -> "APPLICATION_EVENT";
            case DOCUMENT_ACCESS -> "DOCUMENT_ACCESS";
            case PRODUCT -> "PRODUCT_EVENT";
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
