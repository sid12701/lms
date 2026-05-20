package com.bhawana.lms.service;

import com.bhawana.lms.common.web.ApiConflictException;
import com.bhawana.lms.domain.LspApiIdempotencyRecord;
import com.bhawana.lms.repo.LspApiIdempotencyRecordRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LspApiIdempotencyService {

    private static final HexFormat HEX = HexFormat.of();

    private final LspApiIdempotencyRecordRepository lspApiIdempotencyRecordRepository;
    private final ObjectMapper objectMapper;

    public LspApiIdempotencyService(
            LspApiIdempotencyRecordRepository lspApiIdempotencyRecordRepository,
            ObjectMapper objectMapper
    ) {
        this.lspApiIdempotencyRecordRepository = lspApiIdempotencyRecordRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public <T> T execute(
            UUID lspId,
            String operationKey,
            String idempotencyKey,
            Object requestFingerprintSource,
            Class<T> responseType,
            Supplier<T> action
    ) {
        String normalizedKey = requireUuidV4(idempotencyKey);
        String requestFingerprint = fingerprint(requestFingerprintSource);

        LspApiIdempotencyRecord existingRecord = lspApiIdempotencyRecordRepository
                .findByLspIdAndOperationKeyAndIdempotencyKey(lspId, operationKey, normalizedKey)
                .orElse(null);
        if (existingRecord != null) {
            if (!existingRecord.getRequestFingerprint().equals(requestFingerprint)) {
                throw new ApiConflictException(
                        "IDEMPOTENCY_CONFLICT",
                        "Idempotency-Key has already been used for a different request."
                );
            }
            return deserialize(existingRecord.getResponseBody(), responseType);
        }

        T response = action.get();
        String serializedBody = serialize(response);
        lspApiIdempotencyRecordRepository.save(new LspApiIdempotencyRecord(
                lspId,
                operationKey,
                normalizedKey,
                requestFingerprint,
                200,
                serializedBody
        ));
        return response;
    }

    public String requireUuidV4(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.trim().isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required.");
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(idempotencyKey.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Idempotency-Key must be a UUID v4 value.");
        }
        if (uuid.version() != 4) {
            throw new IllegalArgumentException("Idempotency-Key must be a UUID v4 value.");
        }
        return uuid.toString();
    }

    private String fingerprint(Object requestFingerprintSource) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] payload = objectMapper.writeValueAsString(requestFingerprintSource)
                    .getBytes(StandardCharsets.UTF_8);
            return HEX.formatHex(digest.digest(payload));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize idempotency fingerprint payload.", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is not available.", exception);
        }
    }

    private String serialize(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize idempotent response.", exception);
        }
    }

    private <T> T deserialize(String responseBody, Class<T> responseType) {
        try {
            return objectMapper.readValue(responseBody, responseType);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to deserialize idempotent response.", exception);
        }
    }
}
