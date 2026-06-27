package com.bhawana.lms.common.api;

/**
 * Token-mint result returned by the auth endpoints. Lives in {@code common.api} so the
 * service layer (which mints it) does not depend on the {@code web} package.
 */
public record TokenResponse(
        String accessToken,
        String tokenType,
        long expiresInSeconds,
        boolean passwordChangeRequired
) {
}
