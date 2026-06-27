package com.bhawana.lms.common.api.error;

/**
 * Raised when a request body exceeds the maximum permitted size while it is being read. Used by
 * payload-size guards that cannot trust the declared {@code Content-Length} (e.g. chunked uploads)
 * and must enforce the cap as the stream is consumed. Mapped to {@code 413 PAYLOAD_TOO_LARGE}.
 */
public class PayloadTooLargeException extends RuntimeException {

    public PayloadTooLargeException(String message) {
        super(message);
    }
}
