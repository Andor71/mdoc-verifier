package com.mdoc.verifier.support;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import com.authlete.cbor.CBORByteArray;
import com.authlete.cbor.CBORInteger;
import com.authlete.cbor.CBORItem;
import com.authlete.cbor.CBORItemList;
import com.authlete.cbor.CBORPair;
import com.authlete.cbor.CBORPairList;
import com.authlete.cbor.CBORString;
import com.authlete.cbor.CBORTaggedItem;

/** Short builders for authlete CBOR items, so a test reads like CBOR diagnostic notation. */
public final class Cbor {

    private Cbor() {
    }

    public static CBORString text(final String value) {
        return new CBORString(value);
    }

    public static CBORInteger integer(final int value) {
        return new CBORInteger(value);
    }

    public static CBORByteArray bytes(final byte[] value) {
        return new CBORByteArray(value);
    }

    /** bytes(0x01, 0x02) = h'0102' */
    public static CBORByteArray bytes(final int... values) {
        return new CBORByteArray(raw(values));
    }

    public static byte[] raw(final int... values) {
        final byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (byte) values[i];
        }
        return result;
    }

    public static CBORItemList array(final CBORItem... items) {
        return new CBORItemList(List.of(items));
    }

    /** Alternating keys and values with any key type: map(integer(1), integer(-7)) = {1: -7} */
    public static CBORPairList map(final CBORItem... keysAndValues) {
        final List<CBORPair> pairs = new ArrayList<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            pairs.add(new CBORPair(keysAndValues[i], keysAndValues[i + 1]));
        }
        return new CBORPairList(pairs);
    }

    /** Alternating text keys and values: textMap("a", integer(1)) = {"a": 1} */
    public static CBORPairList textMap(final Object... keysAndValues) {
        final List<CBORPair> pairs = new ArrayList<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            pairs.add(new CBORPair(text((String) keysAndValues[i]), (CBORItem) keysAndValues[i + 1]));
        }
        return new CBORPairList(pairs);
    }

    public static CBORTaggedItem tag(final long number, final CBORItem content) {
        return new CBORTaggedItem(number, content);
    }

    /** #6.24(bstr .cbor item) = 24(<<item>>) */
    public static CBORTaggedItem embedded(final CBORItem item) {
        return tag(24, bytes(item.encode()));
    }

    public static String hex(final CBORItem item) {
        return HexFormat.of().formatHex(item.encode());
    }
}
