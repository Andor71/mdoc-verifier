package com.mdoc.verifier.util;

import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

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
import com.authlete.cbor.CBORUndefined;
import com.mdoc.verifier.enums.ErrorCode;
import com.mdoc.verifier.exception.MdocDecodeException;

/**
 * Typed access to authlete CBOR items. Every method takes the item's location in the input
 * (JSONPath-like) and throws {@link MdocDecodeException} with that location when the type is wrong.
 * Bytes to items: {@link CborParser}.
 */
public final class CborUtil {

    public static final String ROOT = "$";

    private static final Pattern SIMPLE_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private CborUtil() {
    }

    /** A map whose keys must all be text strings, without duplicates. */
    public static CborMap textKeyMap(final CBORItem item, final String path) {
        final Map<String, CBORItem> fields = new LinkedHashMap<>();
        for (final CBORPair pair : pairs(item, path)) {
            if (!(pair.getKey() instanceof CBORString key)) {
                throw typeError(path, "text string map key", pair.getKey());
            }
            if (fields.putIfAbsent(key.getValue(), pair.getValue()) != null) {
                throw new MdocDecodeException(ErrorCode.DUPLICATE_KEY, path, "Duplicate map key '" + key.getValue() + "'");
            }
        }
        return new CborMap(Collections.unmodifiableMap(fields), path);
    }

    public static List<? extends CBORPair> pairs(final CBORItem item, final String path) {
        if (item instanceof CBORPairList map) {
            return map.getPairs();
        }
        throw typeError(path, "map", item);
    }

    public static List<? extends CBORItem> array(final CBORItem item, final String path) {
        if (item instanceof CBORItemList list) {
            return list.getItems();
        }
        throw typeError(path, "array", item);
    }

    /** For CDDL {@code [+ X]}: at least one element. */
    public static List<? extends CBORItem> nonEmptyArray(final CBORItem item, final String path) {
        final List<? extends CBORItem> items = array(item, path);
        if (items.isEmpty()) {
            throw new MdocDecodeException(ErrorCode.INVALID_VALUE, path, "Array must contain at least one element");
        }
        return items;
    }

    public static String text(final CBORItem item, final String path) {
        if (item instanceof CBORString text) {
            return text.getValue();
        }
        throw typeError(path, "text string", item);
    }

    public static byte[] bytes(final CBORItem item, final String path) {
        if (item instanceof CBORByteArray bytes) {
            return bytes.getValue();
        }
        throw typeError(path, "byte string", item);
    }

    /** CDDL {@code uint}: a non-negative integer. */
    public static BigInteger uint(final CBORItem item, final String path) {
        final BigInteger value = switch (item) {
            case CBORInteger number -> BigInteger.valueOf(number.getValue());
            case CBORLong number -> BigInteger.valueOf(number.getValue());
            case CBORBigInteger number -> number.getValue();
            default -> throw typeError(path, "unsigned integer", item);
        };
        if (value.signum() < 0) {
            throw new MdocDecodeException(ErrorCode.INVALID_VALUE, path, "Expected an unsigned integer, got " + value);
        }
        return value;
    }

    public static boolean hasTag(final CBORItem item, final long tag) {
        return item instanceof CBORTaggedItem tagged && tagged.getTagNumber().longValue() == tag;
    }

    /** Checks the tag number and returns the tag's content. */
    public static CBORItem untag(final CBORItem item, final long tag, final String path) {
        if (hasTag(item, tag)) {
            return ((CBORTaggedItem) item).getTagContent();
        }
        throw typeError(path, "tag " + tag, item);
    }

    /** {@code $.docType} for plain keys, {@code $["org.iso.18013.5.1"]} for keys with dots, spaces, ... */
    public static String child(final String path, final String key) {
        return SIMPLE_KEY.matcher(key).matches() ? path + "." + key : path + "[\"" + key + "\"]";
    }

    public static String index(final String path, final int index) {
        return path + "[" + index + "]";
    }

    public static MdocDecodeException typeError(final String path, final String expected, final CBORItem actual) {
        return new MdocDecodeException(ErrorCode.UNEXPECTED_TYPE, path, "Expected " + expected + ", got " + typeName(actual));
    }

    public static String typeName(final CBORItem item) {
        return switch (item) {
            case null -> "nothing";
            case CBORPairList map -> "map";
            case CBORItemList list -> "array";
            case CBORString text -> "text string";
            case CBORByteArray bytes -> "byte string";
            case CBORTaggedItem tagged -> "tag " + tagged.getTagNumber();
            case CBORDouble number -> "float";
            case CBORFloat number -> "float";
            case CBORInteger number -> "integer";
            case CBORLong number -> "integer";
            case CBORBigInteger number -> "integer";
            case CBORBoolean bool -> "boolean";
            case CBORNull nil -> "null";
            case CBORUndefined undefined -> "undefined";
            default -> "simple value";
        };
    }
}
