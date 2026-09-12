package com.bhawana.lms.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * Nonsecret policy epoch bound to every new human session. Derived from the current
 * signing key, issuer and both local audiences, so replacing the key (A to B) or either
 * audience changes the epoch and old opaque refresh tokens cannot mint new-policy
 * credentials without reauth. The epoch is a one-way hash: it is stored on the session
 * and refresh rows for equality checks and never exposes key material as a credential
 * or session diagnostic.
 */
@Component
public class SessionPolicyEpochProvider {

    private final SecurityProperties securityProperties;

    public SessionPolicyEpochProvider(SecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
    }

    public String currentEpoch() {
        String material = securityProperties.getJwt().getSecret()
                + "|" + securityProperties.getJwt().getIssuer()
                + "|" + securityProperties.getJwt().getHumanAudience()
                + "|" + securityProperties.getJwt().getMachineAudience();
        return sha256Hex(material);
    }

    public boolean matches(String issuedEpochOrNull) {
        return issuedEpochOrNull != null && issuedEpochOrNull.equals(currentEpoch());
    }

    static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm not available.", exception);
        }
    }
}
