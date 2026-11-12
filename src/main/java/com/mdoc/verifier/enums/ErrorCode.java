package com.mdoc.verifier.enums;

import lombok.Getter;

/**
 * Machine-readable error and warning codes, each with its fixed RFC 9457 {@code title} and {@code detail} text.
 * Exceptions carry only the code; GlobalExceptionHandler turns it into problem+json.
 */
@Getter
public enum ErrorCode {
    /** The input string is not valid base64 / base64url / hex. */
    INVALID_INPUT_ENCODING("Invalid input encoding", "Input is not valid for its inputType (BASE64, BASE64_URL or HEX)"),
    /** The bytes are not well-formed CBOR (truncated, bad header, ...). */
    CBOR_MALFORMED("Malformed CBOR", "The bytes are not well-formed CBOR"),
    /** Bytes left over after the CBOR data item. */
    CBOR_TRAILING_DATA("Trailing CBOR data", "Unexpected bytes after the CBOR data item"),
    /** Arrays, maps and tags nested deeper than the limit; rejected before the recursive decoder runs. */
    CBOR_TOO_DEEP("CBOR nested too deep", "Arrays, maps and tags are nested deeper than the limit"),
    /** A different CBOR type or tag than the structure requires. */
    UNEXPECTED_TYPE("Unexpected type", "The CBOR type or tag is not the one the structure requires"),
    /** A required map key is missing; the path points at the missing key. */
    MISSING_FIELD("Missing field", "A required field is missing"),
    /** The same key occurs twice in one map; the path points at the key. */
    DUPLICATE_KEY("Duplicate key", "The map key occurs more than once"),
    /** The type is right but the value is not (negative uint, bad date, wrong array size, ...). */
    INVALID_VALUE("Invalid value", "The value is not allowed here (negative uint, bad date, wrong size, empty, ...)"),
    /** Valid CBOR without a faithful JSON form (unknown tag, undefined, NaN, ...). */
    UNSUPPORTED_CBOR("Unsupported CBOR", "The value cannot be converted to JSON (unknown tag, undefined, NaN, ...)"),
    /** Warning: the COSE_Sign1 payload is a tag-24 item instead of a bstr (RFC 9052 §4.2). Decoded, not conformant. */
    COSE_PAYLOAD_NOT_BSTR("COSE payload not a byte string",
            "COSE_Sign1 payload must be a byte string (RFC 9052 §4.2), got MobileSecurityObjectBytes "
                    + "(tag 24) without the bstr wrapper; decoded anyway, a strict verifier rejects it"),
    /** The JSON request fails validation (missing field, value too long, ...). */
    INVALID_REQUEST("Invalid request", "Request validation failed"),
    /** The request body is larger than the limit (413). */
    REQUEST_TOO_LARGE("Request too large", "Request body is larger than the limit"),
    /** Too many requests from this client (429). */
    RATE_LIMITED("Too many requests", "Too many requests from this client");

    private final String title;
    private final String message;

    ErrorCode(final String title, final String message) {
        this.title = title;
        this.message = message;
    }
}
