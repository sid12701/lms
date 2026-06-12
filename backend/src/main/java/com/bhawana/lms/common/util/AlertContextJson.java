package com.bhawana.lms.common.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;

public final class AlertContextJson {

    private AlertContextJson() {
    }

    public static String serialize(ObjectMapper objectMapper, Logger log, Object context) {
        try {
            return objectMapper.writeValueAsString(context);
        } catch (JsonProcessingException exception) {
            log.warn("Failed to serialize alert context; storing alert without context", exception);
            return null;
        }
    }
}
