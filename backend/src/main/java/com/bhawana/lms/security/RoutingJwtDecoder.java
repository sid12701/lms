package com.bhawana.lms.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Route HS256 locally minted tokens to the HMAC decoder and RS256 Entra tokens to the
 * trusted-JWKS decoder when enabled. No algorithm or issuer fallback confusion.
 */
public class RoutingJwtDecoder implements JwtDecoder {

    private final JwtDecoder localDecoder;
    private final JwtDecoder entraDecoder;
    private final SecurityProperties securityProperties;

    public RoutingJwtDecoder(
            JwtDecoder localDecoder,
            JwtDecoder entraDecoder,
            SecurityProperties securityProperties
    ) {
        this.localDecoder = localDecoder;
        this.entraDecoder = entraDecoder;
        this.securityProperties = securityProperties;
    }

    @Override
    public Jwt decode(String token) throws JwtException {
        String algorithm = readAlgorithm(token);
        if (JWSAlgorithm.HS256.getName().equals(algorithm)) {
            return localDecoder.decode(token);
        }
        if (securityProperties.getEntraMachineIdentity().isEnabled()
                && JWSAlgorithm.RS256.getName().equals(algorithm)) {
            return entraDecoder.decode(token);
        }
        throw new JwtException("Unsupported or disabled JWT algorithm: " + algorithm);
    }

    private static String readAlgorithm(String token) {
        try {
            return SignedJWT.parse(token).getHeader().getAlgorithm().getName();
        } catch (ParseException exception) {
            throw new JwtException("Malformed JWT.", exception);
        }
    }
}
