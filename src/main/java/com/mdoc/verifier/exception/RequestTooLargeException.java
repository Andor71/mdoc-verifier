package com.mdoc.verifier.exception;

/** The request body is over the configured limit. Becomes 413. */
public class RequestTooLargeException extends RuntimeException {

    public RequestTooLargeException(final long maxBytes) {
        super("Request body is larger than " + maxBytes + " bytes");
    }
}
