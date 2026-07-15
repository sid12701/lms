package com.bhawana.lms.common.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a request DTO whose JSON body must not contain unknown fields.
 *
 * <p>The global ObjectMapper stays lenient (Spring Boot default, so internal
 * Admin/Ops payloads keep tolerating additive fields), while
 * {@link StrictJsonUnknownPropertyHandler} turns unknown properties on annotated
 * types into an {@code UnrecognizedPropertyException}, which
 * {@code GlobalExceptionHandler} maps to {@code 400 INVALID_REQUEST} with an
 * "Unknown field 'x' is not permitted." message. Applied to every LSP-facing
 * request body so a partner's typo'd field fails loudly instead of being
 * silently dropped. Note {@code @JsonIgnoreProperties(ignoreUnknown = false)}
 * cannot do this: it only refrains from suppressing a failure the mapper-level
 * {@code FAIL_ON_UNKNOWN_PROPERTIES} feature (disabled here) would have raised.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface StrictJson {
}
