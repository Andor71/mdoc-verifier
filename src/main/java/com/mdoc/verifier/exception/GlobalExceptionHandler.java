package com.mdoc.verifier.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Bad client input: 400 as RFC 9457 problem+json, with code and path, never a stack trace. */
    @ExceptionHandler(MdocDecodeException.class)
    public ProblemDetail handleDecodeFailure(final MdocDecodeException e) {
        log.info("mDoc decode failed: {} at {}", e.getCode(), e.getPath());

        final ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("mDoc decode failed");
        problem.setProperty("code", e.getCode().name());
        problem.setProperty("path", e.getPath());
        return problem;
    }
}
