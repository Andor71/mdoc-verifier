package com.mdoc.verifier.util;

import java.math.BigInteger;
import java.util.Map;

import com.authlete.cbor.CBORItem;
import com.mdoc.verifier.enums.ErrorCode;
import com.mdoc.verifier.exception.MdocDecodeException;

/**
 * A CBOR map with text keys plus its location in the input. The typed getters check the value's type
 * and report errors with the field's own path, e.g. {@code $.documents[0].docType}.
 */
public record CborMap(Map<String, CBORItem> fields, String path) {

    public boolean has(final String key) {
        return fields.containsKey(key);
    }

    public CBORItem required(final String key) {
        final CBORItem value = fields.get(key);
        if (value == null) {
            throw new MdocDecodeException(ErrorCode.MISSING_FIELD, pathOf(key));
        }
        return value;
    }

    public String text(final String key) {
        return CborUtil.text(required(key), pathOf(key));
    }

    public byte[] bytes(final String key) {
        return CborUtil.bytes(required(key), pathOf(key));
    }

    public BigInteger uint(final String key) {
        return CborUtil.uint(required(key), pathOf(key));
    }

    public String pathOf(final String key) {
        return CborUtil.child(path, key);
    }
}
