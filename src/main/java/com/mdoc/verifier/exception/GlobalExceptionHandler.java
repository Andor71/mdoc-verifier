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
    private static final String PATH = "path";

    /** Bad client input: 400 as RFC 9457 problem+json, with code and path, never a stack trace. */
    @ExceptionHandler(MdocDecodeException.class)
    public ProblemDetail handleDecodeFailure(final MdocDecodeException e) {
        log.info("Request rejected: {} at {}", e.getCode(), e.getPath());
        final ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, e.getCode(), e.getMessage());
        problem.setProperty(PATH, e.getPath());
        return problem;
    }

    /** Bean Validation on the request DTO failed: 400 naming each field, e.g. "mdoc: ... cannot exceed ...". */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleInvalidRequest(final MethodArgumentNotValidException e) {
        final String detail = ErrorCode.INVALID_REQUEST.getMessage() + ": " + e.getBindingResult().getFieldErrors()
                .stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        logRejected(ErrorCode.INVALID_REQUEST, detail);
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST, detail);
    }

    @ExceptionHandler(RequestTooLargeException.class)
    public ProblemDetail handleTooLarge(final RequestTooLargeException e) {
        logRejected(ErrorCode.REQUEST_TOO_LARGE, e.getMessage());
        return problem(HttpStatus.CONTENT_TOO_LARGE, ErrorCode.REQUEST_TOO_LARGE, e.getMessage());
    }

    /**
     * 429 with Retry-After (RFC 6585 §4, RFC 9110 §10.2.3). Logged like the rest: one line per rejected request,
     * so a flood shows up in the log as a flood.
     * The only {@link ResponseEntity} here: a plain {@link ProblemDetail} return value cannot carry a header.
     */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleRateLimited(final RateLimitExceededException e) {
        logRejected(ErrorCode.RATE_LIMITED, e.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(e.getRetryAfterSeconds()))
                .body(problem(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.RATE_LIMITED, e.getMessage()));
    }

    private static void logRejected(final ErrorCode code, final String detail) {
        log.info("Request rejected: {}: {}", code, detail);
    }

    private static ProblemDetail problem(final HttpStatus status, final ErrorCode code, final String detail) {
        final ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(code.getTitle());
        problem.setProperty(CODE, code.name());
        return problem;
    }
}
