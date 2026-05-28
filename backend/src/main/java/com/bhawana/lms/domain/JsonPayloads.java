package com.bhawana.lms.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class JsonPayloads {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private JsonPayloads() {
    }

    static JsonNode requiredObject(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must be a JSON object.");
        }
        return parseObject(value, fieldName);
    }

    static JsonNode optionalObject(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return parseObject(value, fieldName);
    }

    static String asString(JsonNode value) {
        return value == null ? null : value.toString();
    }

    private static JsonNode parseObject(String value, String fieldName) {
        try {
            JsonNode node = OBJECT_MAPPER.readTree(value);
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException(fieldName + " must be a JSON object.");
            }
            return node;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(fieldName + " must be valid JSON.", exception);
        }
    }
}
