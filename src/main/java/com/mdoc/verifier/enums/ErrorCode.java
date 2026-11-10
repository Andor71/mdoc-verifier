package com.mdoc.verifier.enums;

public enum ErrorCode {
    /** The input string is not valid base64 / base64url / hex. */
    INVALID_INPUT_ENCODING,
    /** The bytes are not well-formed CBOR (truncated, bad header, ...). */
    CBOR_MALFORMED,
    /** Bytes left over after the CBOR data item. */
    CBOR_TRAILING_DATA,
    /** Arrays, maps and tags nested deeper than the limit; rejected before the recursive decoder runs. */
    CBOR_TOO_DEEP,
    /** A different CBOR type or tag than the structure requires. */
    UNEXPECTED_TYPE,
    /** A required map key is missing. */
    MISSING_FIELD,
    /** The same key occurs twice in one map. */
    DUPLICATE_KEY,
    /** The type is right but the value is not (negative uint, bad date, wrong array size, ...). */
    INVALID_VALUE,
    /** Valid CBOR without a faithful JSON form (unknown tag, undefined, NaN, ...). */
    UNSUPPORTED_CBOR,
    /** Warning: the COSE_Sign1 payload is a tag-24 item instead of a bstr (RFC 9052 §4.2). Decoded, not conformant. */
    COSE_PAYLOAD_NOT_BSTR,
    /** The JSON request fails validation (missing field, value too long, ...). */
    INVALID_REQUEST,
    /** The request body is larger than the limit (413). */
    REQUEST_TOO_LARGE,
    /** Too many requests from this client (429). */
    RATE_LIMITED
}
