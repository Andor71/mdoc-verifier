package com.mdoc.verifier.enums;

public enum ErrorCode {
    /** The input string is not valid base64 / base64url / hex. */
    INVALID_INPUT_ENCODING,
    /** The bytes are not well-formed CBOR (truncated, bad header, ...). */
    CBOR_MALFORMED,
    /** Bytes left over after the CBOR data item. */
    CBOR_TRAILING_DATA,
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
    COSE_PAYLOAD_NOT_BSTR
}
