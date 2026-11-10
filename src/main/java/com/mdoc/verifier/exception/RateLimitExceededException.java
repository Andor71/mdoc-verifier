package com.mdoc.verifier.exception;

import lombok.Getter;

/** The client used up its token bucket. Becomes 429 with a Retry-After header. */
@Getter
public class RateLimitExceededException extends RuntimeException {

    private final long retryAfterSeconds;

    public RateLimitExceededException(final long retryAfterSeconds) {
        super("Too many requests, retry after " + retryAfterSeconds + " seconds");
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
