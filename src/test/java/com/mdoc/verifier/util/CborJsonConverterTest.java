package com.mdoc.verifier.util;

import static com.mdoc.verifier.support.Cbor.array;
import static com.mdoc.verifier.support.Cbor.bytes;
import static com.mdoc.verifier.support.Cbor.integer;
import static com.mdoc.verifier.support.Cbor.map;
import static com.mdoc.verifier.support.Cbor.tag;
import static com.mdoc.verifier.support.Cbor.text;
import static com.mdoc.verifier.support.Cbor.textMap;
import static com.mdoc.verifier.support.MdocFixtures.assertDecodeError;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.stream.Stream;

import com.authlete.cbor.CBORBigInteger;
import com.authlete.cbor.CBORBoolean;
import com.authlete.cbor.CBORDouble;
import com.authlete.cbor.CBORFloat;
import com.authlete.cbor.CBORItem;
import com.authlete.cbor.CBORLong;
import com.authlete.cbor.CBORNull;
import com.authlete.cbor.CBORSimpleValue;
import com.authlete.cbor.CBORUndefined;
import com.mdoc.verifier.enums.ErrorCode;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

class CborJsonConverterTest {

    private static final String PATH = "$.v";

    private static String json(final CBORItem item) {
        return CborJsonConverter.toJson(item, PATH).toString();
    }

    @ParameterizedTest
    @MethodSource("convertible")
    void convertsEveryTypeWithAJsonForm(final CBORItem item, final String expectedJson) {
        assertThat(json(item)).isEqualTo(expectedJson);
    }

    static Stream<Arguments> convertible() {
        return Stream.of(
                Arguments.of(text("Péter"), "\"Péter\""),
                Arguments.of(bytes(0x01, 0x02, 0xff), "\"AQL/\""),                 // Base64, RFC 4648 §4
                Arguments.of(integer(-7), "-7"),
                Arguments.of(new CBORLong(5_000_000_000L), "5000000000"),
                Arguments.of(new CBORBigInteger(BigInteger.TWO.pow(70)), "1180591620717411303424"),
                Arguments.of(new CBORDouble(1.5), "1.5"),
                Arguments.of(new CBORFloat(0.25f), "0.25"),
                Arguments.of(CBORBoolean.TRUE, "true"),
                Arguments.of(CBORNull.INSTANCE, "null"),
                Arguments.of(array(integer(1), array(text("a"))), "[1,[\"a\"]]"),
                Arguments.of(textMap("a", integer(1)), "{\"a\":1}"),
                Arguments.of(map(integer(1), text("x"), new CBORLong(5_000_000_000L), text("y"),
                        new CBORBigInteger(BigInteger.TWO.pow(70)), text("z")),
                        "{\"1\":\"x\",\"5000000000\":\"y\",\"1180591620717411303424\":\"z\"}"),
                Arguments.of(tag(0, text("2024-01-12T00:10:05Z")), "\"2024-01-12T00:10:05Z\""),
                Arguments.of(tag(1004, text("1990-05-17")), "\"1990-05-17\""),
                Arguments.of(tag(2, bytes(0x01, 0x00)), "256"),                          // positive bignum
                Arguments.of(tag(3, bytes(0x00)), "-1"),                                 // negative bignum: -1 - 0
                Arguments.of(tag(3, bytes(0xff)), "-256"));
    }

    @Test
    void bytesToJsonIsStandardBase64() {
        final JsonNode node = CborJsonConverter.bytesToJson(new byte[] {(byte) 0xfb, (byte) 0xff});
        assertThat(node.asString()).isEqualTo("+/8=");
    }

    @Nested
    class Rejects {

        @Test
        void duplicateKeyAfterConversion() {
            // text "1" and integer 1 both become the JSON key "1"
            assertDecodeError(() -> json(map(text("1"), integer(1), integer(1), integer(2))), ErrorCode.DUPLICATE_KEY, PATH + "[\"1\"]");
        }

        @Test
        void keyWithoutJsonForm() {
            assertDecodeError(() -> json(map(bytes(0x01), integer(1))), ErrorCode.UNSUPPORTED_CBOR, PATH);
        }

        @Test
        void nanAndInfinity() {
            assertDecodeError(() -> json(CBORDouble.NaN), ErrorCode.UNSUPPORTED_CBOR, PATH);
            assertDecodeError(() -> json(CBORDouble.POSITIVE_INFINITY), ErrorCode.UNSUPPORTED_CBOR, PATH);
            assertDecodeError(() -> json(CBORFloat.NEGATIVE_INFINITY), ErrorCode.UNSUPPORTED_CBOR, PATH);
        }

        @Test
        void undefinedAndSimpleValues() {
            assertDecodeError(() -> json(CBORUndefined.INSTANCE), ErrorCode.UNSUPPORTED_CBOR, PATH);
            assertDecodeError(() -> json(new CBORSimpleValue(16)), ErrorCode.UNSUPPORTED_CBOR, PATH);
        }

        @Test
        void unknownTag() {
            assertDecodeError(() -> json(tag(37, bytes(0x01))), ErrorCode.UNSUPPORTED_CBOR, PATH);
        }

        @Test
        void invalidDates() {
            assertDecodeError(() -> json(tag(0, text("2024-13-01T00:00:00Z"))), ErrorCode.INVALID_VALUE, PATH);
            assertDecodeError(() -> json(tag(1004, text("17/05/1990"))), ErrorCode.INVALID_VALUE, PATH);
        }

        @Test
        void wrongContentInsideTag() {
            assertDecodeError(() -> json(tag(0, integer(1))), ErrorCode.UNEXPECTED_TYPE, PATH);
            assertDecodeError(() -> json(tag(2, text("1"))), ErrorCode.UNEXPECTED_TYPE, PATH);
            assertDecodeError(() -> json(tag(3, text("1"))), ErrorCode.UNEXPECTED_TYPE, PATH);
        }

        @Test
        void reportsThePathOfTheNestedValue() {
            assertDecodeError(() -> json(textMap("a", array(integer(1), CBORDouble.NaN))), ErrorCode.UNSUPPORTED_CBOR, "$.v.a[1]");
        }
    }
}
