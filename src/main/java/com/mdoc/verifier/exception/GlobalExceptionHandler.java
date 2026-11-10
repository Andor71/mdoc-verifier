package com.mdoc.verifier.exception;

import java.util.stream.Collectors;

import com.mdoc.verifier.enums.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Every error response is RFC 9457 problem+json with a machine-readable {@code code}, never a stack trace.
 * The request filters forward their rejections here too, so the format lives in one place.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String CODE = "code";

    /** Bad client input: 400 as RFC 9457 problem+json, with code and path, never a stack trace. */
    @ExceptionHandler(MdocDecodeException.class)
    public ProblemDetail handleDecodeFailure(final MdocDecodeException e) {
        log.info("mDoc decode failed: {} at {}", e.getCode(), e.getPath());

        final ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("mDoc decode failed");
        problem.setProperty(CODE, e.getCode().name());
        problem.setProperty("path", e.getPath());
        return problem;
    }

    /** Bean Validation on the request DTO failed: 400 naming each field, e.g. "mdoc: ... cannot exceed ...". */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleInvalidRequest(final MethodArgumentNotValidException e) {
        final String detail = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));

        final ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle("Invalid request");
        problem.setProperty(CODE, ErrorCode.INVALID_REQUEST.name());
        return problem;
    }

    @ExceptionHandler(RequestTooLargeException.class)
    public ProblemDetail handleTooLarge(final RequestTooLargeException e) {
        final ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE, e.getMessage());
        problem.setTitle("Request too large");
        problem.setProperty(CODE, ErrorCode.REQUEST_TOO_LARGE.name());
        return problem;
    }

    /** 429 with Retry-After (RFC 6585 §4, RFC 9110 §10.2.3). Not logged per request: under attack that floods the log. */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleRateLimited(final RateLimitExceededException e) {
        final ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
        problem.setTitle("Too many requests");
        problem.setProperty(CODE, ErrorCode.RATE_LIMITED.name());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(e.getRetryAfterSeconds()))
                .body(problem);
    }
}
