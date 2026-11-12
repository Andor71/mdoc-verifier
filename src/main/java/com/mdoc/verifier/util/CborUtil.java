package com.mdoc.verifier.util;

import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.authlete.cbor.CBORBigInteger;
import com.authlete.cbor.CBORByteArray;
import com.authlete.cbor.CBORInteger;
import com.authlete.cbor.CBORItem;
import com.authlete.cbor.CBORItemList;
import com.authlete.cbor.CBORLong;
import com.authlete.cbor.CBORPair;
import com.authlete.cbor.CBORPairList;
import com.authlete.cbor.CBORString;
import com.authlete.cbor.CBORTaggedItem;
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
                throw typeError(path);
            }
            if (fields.putIfAbsent(key.getValue(), pair.getValue()) != null) {
                throw new MdocDecodeException(ErrorCode.DUPLICATE_KEY, child(path, key.getValue()));
            }
        }
        return new CborMap(Collections.unmodifiableMap(fields), path);
    }

    public static List<? extends CBORPair> pairs(final CBORItem item, final String path) {
        if (item instanceof CBORPairList map) {
            return map.getPairs();
        }
        throw typeError(path);
    }

    public static List<? extends CBORItem> array(final CBORItem item, final String path) {
        if (item instanceof CBORItemList list) {
            return list.getItems();
        }
        throw typeError(path);
    }

    /** For CDDL {@code [+ X]}: at least one element. */
    public static List<? extends CBORItem> nonEmptyArray(final CBORItem item, final String path) {
        final List<? extends CBORItem> items = array(item, path);
        if (items.isEmpty()) {
            throw new MdocDecodeException(ErrorCode.INVALID_VALUE, path);
        }
        return items;
    }

    public static String text(final CBORItem item, final String path) {
        if (item instanceof CBORString text) {
            return text.getValue();
        }
        throw typeError(path);
    }

    public static byte[] bytes(final CBORItem item, final String path) {
        if (item instanceof CBORByteArray bytes) {
            return bytes.getValue();
        }
        throw typeError(path);
    }

    /** CDDL {@code uint}: a non-negative integer. */
    public static BigInteger uint(final CBORItem item, final String path) {
        final BigInteger value = switch (item) {
            case CBORInteger number -> BigInteger.valueOf(number.getValue());
            case CBORLong number -> BigInteger.valueOf(number.getValue());
            case CBORBigInteger number -> number.getValue();
            default -> throw typeError(path);
        };
        if (value.signum() < 0) {
            throw new MdocDecodeException(ErrorCode.INVALID_VALUE, path);
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
        throw typeError(path);
    }

    /** {@code $.docType} for plain keys, {@code $["org.iso.18013.5.1"]} for keys with dots, spaces, ... */
    public static String child(final String path, final String key) {
        return SIMPLE_KEY.matcher(key).matches() ? path + "." + key : path + "[" + quote(key) + "]";
    }

    // Keys come from the input, so they may hold " or \. Escaped as an RFC 9535 §2.3.1.1 string literal, otherwise
    // a key like org."x" would close the bracket early. Control characters (not allowed unescaped) become a 4-digit hex escape.
    private static String quote(final String key) {
        final StringBuilder quoted = new StringBuilder(key.length() + 2).append('"');
        key.chars().forEach(c -> {
            if (c == '"' || c == '\\') {
                quoted.append('\\').append((char) c);
            } else if (c < 0x20) {
                quoted.append(String.format("\\u%04x", c));
            } else {
                quoted.append((char) c);
            }
        });
        return quoted.append('"').toString();
    }

    public static String index(final String path, final int index) {
        return path + "[" + index + "]";
    }

    public static MdocDecodeException typeError(final String path) {
        return new MdocDecodeException(ErrorCode.UNEXPECTED_TYPE, path);
    }

}
