package com.bhawana.lms.common.web;

/**
 * Forwarding attribution is malformed or ambiguous for a trusted peer (for example a
 * non-literal hop, an overlong chain, or duplicate {@code X-Forwarded-For} fields), so the
 * request must be rejected before protected routing instead of falling back to the peer.
 */
public class AmbiguousForwardingException extends RuntimeException {

    public AmbiguousForwardingException(String message) {
        super(message);
    }
}
