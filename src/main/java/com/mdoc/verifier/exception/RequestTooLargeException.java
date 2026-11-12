package com.mdoc.verifier.exception;

import com.mdoc.verifier.enums.ErrorCode;

/** The request body is over the configured limit. Becomes 413. */
public class RequestTooLargeException extends RuntimeException {

    public RequestTooLargeException(final long maxBytes) {
        super(ErrorCode.REQUEST_TOO_LARGE.getMessage() + " of " + maxBytes + " bytes");
    }
}
