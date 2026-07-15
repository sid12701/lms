package com.bhawana.lms.common.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import java.io.IOException;
import java.util.List;

/**
 * Rejects unknown JSON properties for {@link StrictJson}-annotated target types;
 * every other type keeps the lenient mapper default. Registered on the shared
 * ObjectMapper in JacksonConfig.
 */
public class StrictJsonUnknownPropertyHandler extends DeserializationProblemHandler {

    @Override
    public boolean handleUnknownProperty(
            DeserializationContext ctxt,
            JsonParser p,
            JsonDeserializer<?> deserializer,
            Object beanOrClass,
            String propertyName
    ) throws IOException {
        if (beanOrClass == null) {
            return false;
        }
        Class<?> targetType = beanOrClass instanceof Class<?> clazz ? clazz : beanOrClass.getClass();
        if (targetType.isAnnotationPresent(StrictJson.class)) {
            throw UnrecognizedPropertyException.from(p, beanOrClass, propertyName, List.of());
        }
        return false;
    }
}
