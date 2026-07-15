package com.bhawana.lms.service;

import com.bhawana.lms.common.api.error.BusinessRuleViolationException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

public final class AuditExplorerCursorCodec {

    private AuditExplorerCursorCodec() {
    }

    public static String encode(AuditExplorerCursor cursor, ObjectMapper objectMapper) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(cursor);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to encode audit explorer cursor.", exception);
        }
    }

    public static AuditExplorerCursor decode(String encodedCursor, ObjectMapper objectMapper) {
        if (encodedCursor == null || encodedCursor.isBlank()) {
            throw new BusinessRuleViolationException(
                    "INVALID_CURSOR",
                    "cursor must not be blank",
                    Map.of("cursor", "must not be blank")
            );
        }
        try {
            byte[] json = Base64.getUrlDecoder().decode(encodedCursor);
            return objectMapper.readValue(new String(json, StandardCharsets.UTF_8), AuditExplorerCursor.class);
        } catch (IllegalArgumentException | JsonProcessingException exception) {
            throw new BusinessRuleViolationException(
                    "INVALID_CURSOR",
                    "cursor is not valid",
                    Map.of("cursor", "is not valid")
            );
        }
    }

    public record AuditExplorerCursor(Instant occurredAt, String stream, UUID nativeId) {
    }
}
