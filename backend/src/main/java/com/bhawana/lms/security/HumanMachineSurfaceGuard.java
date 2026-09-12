package com.bhawana.lms.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Explicit human/machine surface guard.
 *
 * <p>Human and machine tokens share the local issuer, HS256 algorithm and signing key, so role
 * checks alone cannot separate the surfaces: a machine {@code API_CLIENT} token whose
 * {@code clientId} collides with a human username would otherwise satisfy the generic
 * {@code .authenticated()} route guard on human-only endpoints such as
 * {@code POST /api/v1/auth/password} (which resolves the caller via
 * {@code authentication.getName()}), and a human-audience token minted for a managed row that
 * stores the {@code LSP_API_CLIENT} role would otherwise satisfy {@code hasRole('LSP_API_CLIENT')}
 * on the machine API. Both directions are closed here:
 *
 * <ul>
 *   <li>Human-only endpoints call {@link #requireHuman}: any {@code API_CLIENT} token is denied
 *       before it can act as the colliding human subject.</li>
 *   <li>The machine direction is closed at authority mapping: the converter in
 *       {@link JwtSecurityBeans} never grants {@code ROLE_LSP_API_CLIENT} to a human-audience
 *       token, so even pre-existing managed rows storing that role cannot cross the machine API.
 *       That exclusion (rather than banning new assignments) is what handles existing rows.</li>
 * </ul>
 *
 * <p>{@code GET /api/v1/internal/system/context} keeps its human-role {@code @PreAuthorize} (a
 * machine token authenticates but owns only the machine role, so the method guard denies it);
 * {@code POST /api/v1/auth/password} has no such method guard and relies on
 * {@link #requireHuman} instead. The distinction is deliberate and covered by signed-token HTTP
 * tests, not by role-separation reasoning alone.
 */
public final class HumanMachineSurfaceGuard {

    private HumanMachineSurfaceGuard() {
    }

    /**
     * True when the authentication carries a machine ({@code API_CLIENT}) token. Detection is by
     * the typed {@code authType} claim, never by comparing the subject against human usernames.
     */
    public static boolean isMachineAuthentication(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return false;
        }
        if (ApiClientJwtSessionValidator.AUTH_TYPE_API_CLIENT.equals(
                jwt.getClaimAsString(ApiClientJwtSessionValidator.AUTH_TYPE_CLAIM))) {
            return true;
        }
        return MachinePrincipalLspResolver.isEntraMachineTokenStatic(jwt);
    }

    /**
     * Denies machine tokens on human-only surfaces (for example the password-change endpoint)
     * before the caller can act as the human subject sharing its name.
     *
     * @throws AccessDeniedException when the caller authenticates with a machine token.
     */
    public static void requireHuman(Authentication authentication) {
        if (isMachineAuthentication(authentication)) {
            throw new AccessDeniedException("Machine tokens cannot use this human-only endpoint.");
        }
    }
}
