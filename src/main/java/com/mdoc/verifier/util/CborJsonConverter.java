package com.mdoc.verifier.util;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;

import com.authlete.cbor.CBORBigInteger;
import com.authlete.cbor.CBORBoolean;
import com.authlete.cbor.CBORByteArray;
import com.authlete.cbor.CBORDouble;
import com.authlete.cbor.CBORFloat;
import com.authlete.cbor.CBORInteger;
import com.authlete.cbor.CBORItem;
import com.authlete.cbor.CBORItemList;
import com.authlete.cbor.CBORLong;
import com.authlete.cbor.CBORNull;
import com.authlete.cbor.CBORPair;
import com.authlete.cbor.CBORPairList;
import com.authlete.cbor.CBORString;
import com.authlete.cbor.CBORTaggedItem;
import com.mdoc.verifier.enums.ErrorCode;
import com.mdoc.verifier.exception.MdocDecodeException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Generic CBOR -> JSON for values whose shape the spec leaves open (elementValue, the MSO body,
 * COSE header values). Converts what has a faithful JSON form and throws for the rest instead of guessing.
 */
public final class CborJsonConverter {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private static final long TAG_TDATE = 0;            // RFC 8949 §3.4.1: RFC 3339 date-time text
    private static final long TAG_POSITIVE_BIGNUM = 2;  // RFC 8949 §3.4.3
    private static final long TAG_NEGATIVE_BIGNUM = 3;  // RFC 8949 §3.4.3: value = -1 - n
    private static final long TAG_FULL_DATE = 1004;     // RFC 8943: RFC 3339 full-date text

    private CborJsonConverter() {
    }

    public static JsonNode toJson(final CBORItem item, final String path) {
        return switch (item) {
            case CBORPairList map -> mapToJson(map, path);
            case CBORItemList list -> arrayToJson(list, path);
            case CBORString text -> JSON.stringNode(text.getValue());
            case CBORByteArray bytes -> bytesToJson(bytes.getValue());
            case CBORInteger number -> JSON.numberNode(number.getValue().longValue());
            case CBORLong number -> JSON.numberNode(number.getValue().longValue());
            case CBORBigInteger number -> JSON.numberNode(number.getValue());
            case CBORDouble number -> floatToJson(number.getValue(), path);
            case CBORFloat number -> floatToJson(number.getValue(), path);
            case CBORBoolean bool -> JSON.booleanNode(bool.getValue());
            case CBORNull nil -> JSON.nullNode();
            case CBORTaggedItem tagged -> taggedToJson(tagged, path);
            default -> throw new MdocDecodeException(ErrorCode.UNSUPPORTED_CBOR, path);
        };
    }

    /** Byte strings become standard Base64 text (RFC 4648 §4). */
    public static JsonNode bytesToJson(final byte[] value) {
        return JSON.stringNode(Base64.getEncoder().encodeToString(value));
    }

    private static ObjectNode mapToJson(final CBORPairList map, final String path) {
        final ObjectNode node = JSON.objectNode();
        for (final CBORPair pair : map.getPairs()) {
            final String key = jsonKey(pair.getKey(), path);
            if (node.has(key)) {
                throw new MdocDecodeException(ErrorCode.DUPLICATE_KEY, CborUtil.child(path, key));
            }
            node.set(key, toJson(pair.getValue(), CborUtil.child(path, key)));
        }
        return node;
    }

    // JSON keys are strings: text keys stay, integer keys (digestIDs, COSE_Key labels) become decimal text.
    private static String jsonKey(final CBORItem key, final String path) {
        return switch (key) {
            case CBORString text -> text.getValue();
            case CBORInteger number -> number.getValue().toString();
            case CBORLong number -> number.getValue().toString();
            case CBORBigInteger number -> number.getValue().toString();
            default -> throw new MdocDecodeException(ErrorCode.UNSUPPORTED_CBOR, path);
        };
    }

    private static ArrayNode arrayToJson(final CBORItemList list, final String path) {
        final List<? extends CBORItem> items = list.getItems();
        final ArrayNode node = JSON.arrayNode(items.size());
        for (int i = 0; i < items.size(); i++) {
            node.add(toJson(items.get(i), CborUtil.index(path, i)));
        }
        return node;
    }

    private static JsonNode floatToJson(final double value, final String path) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new MdocDecodeException(ErrorCode.UNSUPPORTED_CBOR, path);
        }
        return JSON.numberNode(value);
    }

    private static JsonNode taggedToJson(final CBORTaggedItem tagged, final String path) {
        final long tag = tagged.getTagNumber().longValue();
        final CBORItem content = tagged.getTagContent();
        if (tag == TAG_TDATE) {
            return dateToJson(content, path, OffsetDateTime::parse);
        }
        if (tag == TAG_FULL_DATE) {
            return dateToJson(content, path, LocalDate::parse);
        }
        if (tag == TAG_POSITIVE_BIGNUM) {
            return JSON.numberNode(new BigInteger(1, CborUtil.bytes(content, path)));
        }
        if (tag == TAG_NEGATIVE_BIGNUM) {
            return JSON.numberNode(BigInteger.ONE.negate().subtract(new BigInteger(1, CborUtil.bytes(content, path))));
        }
        throw new MdocDecodeException(ErrorCode.UNSUPPORTED_CBOR, path);
    }

    // The JSON keeps the original text; parsing only checks that it is well-formed.
    private static JsonNode dateToJson(final CBORItem content, final String path, final Consumer<String> parser) {
        final String value = CborUtil.text(content, path);
        try {
            parser.accept(value);
        } catch (final DateTimeParseException ignored) {
            throw new MdocDecodeException(ErrorCode.INVALID_VALUE, path);
        }
        return JSON.stringNode(value);
    }
}
