package com.mdoc.verifier.util;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.authlete.cbor.CBORBigInteger;
import com.authlete.cbor.CBORBoolean;
import com.authlete.cbor.CBORByteArray;
import com.authlete.cbor.CBORDecoder;
import com.authlete.cbor.CBORDecoderException;
import com.authlete.cbor.CBORDecoderOptions;
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
 */
public final class CborUtil {

    public static final String ROOT = "$";

    // No tag processors: every tag (0, 24, 1004, 18, ...) stays a CBORTaggedItem, so our code decides
    // what each tag means. The library default would e.g. turn tag 18 into a COSE object behind our back.
    private static final CBORDecoderOptions DECODER_OPTIONS = new CBORDecoderOptions();

    private static final Pattern SIMPLE_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * Arrays, maps and tags nested deeper than this are rejected. A DeviceResponse needs about 6 levels per
     * decode call (tag-24 contents are decoded, and checked, separately), so 32 is generous, and far below
     * the few thousand levels at which the recursive authlete decoder overflows the call stack.
     */
    public static final int MAX_DEPTH = 32;

    private static final int BREAK = 0xff;              // RFC 8949 §3.2.1: ends an indefinite-length item
    private static final int INDEFINITE = -1;           // container counter: no count, closed by BREAK
    private static final int AI_ONE_BYTE = 24;          // additional info 24..27: argument in 1, 2, 4, 8 bytes
    private static final int AI_RESERVED = 28;          // 28..30: reserved, not well-formed
    private static final int AI_INDEFINITE = 31;

    private CborUtil() {
    }

    /** Decodes exactly one CBOR data item (RFC 8949 §5.1); anything after it is an error. */
    public static CBORItem decode(final byte[] bytes, final String path) {
        if (bytes.length == 0) {
            throw new MdocDecodeException(ErrorCode.CBOR_MALFORMED, path, "Empty CBOR input");
        }
        checkStructure(bytes, path); // before the recursive decoder: depth and declared lengths
        final CBORDecoder decoder = new CBORDecoder(bytes, DECODER_OPTIONS);
        final CBORItem item;
        try {
            item = decoder.next();
        } catch (final CBORDecoderException e) {
            throw malformed(path, e.getOffset(), e.getMessage(), e);
        } catch (final IOException e) {
            throw new MdocDecodeException(ErrorCode.CBOR_MALFORMED, path, "Malformed CBOR: " + e.getMessage(), e);
        }
        if (hasMoreData(decoder)) {
            throw new MdocDecodeException(ErrorCode.CBOR_TRAILING_DATA, path, "Unexpected bytes after the CBOR data item");
        }
        return item;
    }

    private static boolean hasMoreData(final CBORDecoder decoder) {
        try {
            return decoder.next() != null;
        } catch (final IOException e) {
            return true; // leftover bytes that are not even valid CBOR
        }
    }

    /**
     * Walks the headers of the first data item (RFC 8949 §3) without building anything. Checks that every
     * declared length and count fits in the remaining input, and that containers nest at most {@link #MAX_DEPTH}
     * deep. Iterative, with its own counter per open container instead of recursion, so no input can overflow
     * the call stack here. Trailing bytes are left to the decoder.
     */
    static void checkStructure(final byte[] bytes, final String path) {
        final int[] remaining = new int[MAX_DEPTH]; // per open container: items still to come, or INDEFINITE
        int depth = 0;
        int pos = 0;
        do {
            if (pos >= bytes.length) {
                throw malformed(path, pos, "input ends inside a data item", null);
            }
            final int headerOffset = pos;
            final int initialByte = bytes[pos++] & 0xff; // & 0xff: a Java byte is signed

            if (initialByte == BREAK) {
                if (depth == 0 || remaining[depth - 1] != INDEFINITE) {
                    throw malformed(path, headerOffset, "break outside an indefinite-length item", null);
                }
                depth--; // closes the indefinite container; its slot in the parent was counted when it opened
            } else {
                final int major = initialByte >>> 5;  // top 3 bits: the type
                final int ai = initialByte & 0x1f;    // low 5 bits: where the argument is

                // A flag, not a sentinel argument: an 8-byte argument ff..ff is -1 as a Java long.
                final boolean indefinite = ai == AI_INDEFINITE;
                final long argument;
                if (ai < AI_ONE_BYTE) {
                    argument = ai;                     // 0..23: the value itself
                } else if (ai < AI_RESERVED) {
                    final int size = 1 << (ai - AI_ONE_BYTE); // 24..27 -> 1, 2, 4, 8 bytes follow
                    if (size > bytes.length - pos) {
                        throw malformed(path, headerOffset, "header argument is truncated", null);
                    }
                    long value = 0;
                    for (int i = 0; i < size; i++) {
                        value = (value << 8) | (bytes[pos++] & 0xff); // big-endian, unsigned up to 2^64-1
                    }
                    argument = value;
                } else if (indefinite && major >= 2 && major <= 5) {
                    argument = 0;                      // strings, arrays, maps may be indefinite-length; unused
                } else {
                    throw malformed(path, headerOffset,
                            "additional information " + ai + " is not valid for major type " + major, null);
                }

                // Every item, container or not, fills one slot of the container it sits in.
                if (depth > 0 && remaining[depth - 1] != INDEFINITE) {
                    remaining[depth - 1]--;
                }

                final long available = bytes.length - pos;
                switch (major) {
                    // byte / text string: the argument is the content length; the content is data, skip it
                    case 2, 3 -> {
                        if (indefinite) {
                            depth = open(remaining, depth, INDEFINITE, path, headerOffset); // chunks until BREAK
                        } else if (Long.compareUnsigned(argument, available) > 0) {
                            throw malformed(path, headerOffset, "string of " + Long.toUnsignedString(argument)
                                    + " bytes, only " + available + " left", null);
                        } else {
                            pos += (int) argument;
                        }
                    }
                    // array / map: the argument is the item / pair count. Each item takes at least one byte,
                    // so a count beyond the remaining bytes cannot be true.
                    case 4, 5 -> {
                        final long maxCount = major == 4 ? available : available / 2;
                        if (indefinite) {
                            depth = open(remaining, depth, INDEFINITE, path, headerOffset);
                        } else if (Long.compareUnsigned(argument, maxCount) > 0) {
                            throw malformed(path, headerOffset, (major == 4 ? "array of " : "map of ")
                                    + Long.toUnsignedString(argument) + (major == 4 ? " items" : " pairs")
                                    + ", only " + available + " bytes left", null);
                        } else if (argument > 0) {
                            final int items = (int) (major == 4 ? argument : 2 * argument); // map: key + value
                            depth = open(remaining, depth, items, path, headerOffset);
                        }
                    }
                    // tag: the argument is the tag number; the content is always exactly one item
                    case 6 -> depth = open(remaining, depth, 1, path, headerOffset);
                    // 0, 1 integers and 7 float / simple: the argument is the value, no content follows
                    default -> {
                    }
                }
            }

            // Close every container whose last item this was; [[[1]]] closes three at once.
            while (depth > 0 && remaining[depth - 1] == 0) {
                depth--;
            }
        } while (depth > 0);
    }

    private static int open(final int[] remaining, final int depth, final int items, final String path,
                            final int offset) {
        if (depth == MAX_DEPTH) {
            throw new MdocDecodeException(ErrorCode.CBOR_TOO_DEEP, path,
                    "CBOR nested deeper than " + MAX_DEPTH + " levels at byte offset " + offset);
        }
        remaining[depth] = items;
        return depth + 1;
    }

    private static MdocDecodeException malformed(final String path, final int offset, final String reason,
                                                 final Throwable cause) {
        return new MdocDecodeException(ErrorCode.CBOR_MALFORMED, path,
                "Malformed CBOR at byte offset " + offset + ": " + reason, cause);
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
