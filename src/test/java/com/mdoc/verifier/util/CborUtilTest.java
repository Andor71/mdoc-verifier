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

class CborUtilTest {

    private static final String PATH = "$.x";

    @Nested
    class TextKeyMap {

        @Test
        void keepsOrderAndPath() {
            final CborMap result = CborUtil.textKeyMap(textMap("b", integer(1), "a", integer(2)), PATH);

            assertThat(result.fields()).containsOnlyKeys("b", "a");
            assertThat(result.fields().keySet()).containsExactly("b", "a");
            assertThat(result.path()).isEqualTo(PATH);
        }

        @Test
        void rejectsNonTextKey() {
            assertDecodeError(() -> CborUtil.textKeyMap(map(integer(1), integer(2)), PATH), ErrorCode.UNEXPECTED_TYPE, PATH)
                    .hasMessage("Expected text string map key, got integer");
        }

        @Test
        void rejectsDuplicateKey() {
            assertDecodeError(() -> CborUtil.textKeyMap(textMap("a", integer(1), "a", integer(2)), PATH),
                    ErrorCode.DUPLICATE_KEY, PATH);
        }

        @Test
        void rejectsNonMap() {
            assertDecodeError(() -> CborUtil.textKeyMap(array(), PATH), ErrorCode.UNEXPECTED_TYPE, PATH)
                    .hasMessage("Expected map, got array");
        }
    }

    @Test
    void arrayAcceptsOnlyArrays() {
        assertThat(CborUtil.array(array(integer(1)), PATH)).hasSize(1);
        assertDecodeError(() -> CborUtil.array(map(), PATH), ErrorCode.UNEXPECTED_TYPE, PATH);
    }

    @Test
    void nonEmptyArrayRejectsEmpty() {
        assertThat(CborUtil.nonEmptyArray(array(integer(1)), PATH)).hasSize(1);
        assertDecodeError(() -> CborUtil.nonEmptyArray(array(), PATH), ErrorCode.INVALID_VALUE, PATH);
    }

    @Test
    void textAndBytesCheckTheType() {
        assertThat(CborUtil.text(text("a"), PATH)).isEqualTo("a");
        assertThat(CborUtil.bytes(bytes(0x01), PATH)).containsExactly(0x01);
        assertDecodeError(() -> CborUtil.text(bytes(0x01), PATH), ErrorCode.UNEXPECTED_TYPE, PATH)
                .hasMessage("Expected text string, got byte string");
        assertDecodeError(() -> CborUtil.bytes(text("a"), PATH), ErrorCode.UNEXPECTED_TYPE, PATH)
                .hasMessage("Expected byte string, got text string");
    }

    @Nested
    class Uint {

        @Test
        void acceptsEveryIntegerSize() {
            assertThat(CborUtil.uint(integer(7), PATH)).isEqualTo(BigInteger.valueOf(7));
            assertThat(CborUtil.uint(new CBORLong(5_000_000_000L), PATH)).isEqualTo(BigInteger.valueOf(5_000_000_000L));
            final BigInteger huge = BigInteger.TWO.pow(64).subtract(BigInteger.ONE);
            assertThat(CborUtil.uint(new CBORBigInteger(huge), PATH)).isEqualTo(huge);
        }

        @Test
        void rejectsNegative() {
            assertDecodeError(() -> CborUtil.uint(integer(-1), PATH), ErrorCode.INVALID_VALUE, PATH);
        }

        @Test
        void rejectsNonInteger() {
            assertDecodeError(() -> CborUtil.uint(text("1"), PATH), ErrorCode.UNEXPECTED_TYPE, PATH);
        }
    }

    @Test
    void untagReturnsTheContentOfTheRightTag() {
        assertThat(CborUtil.hasTag(tag(24, bytes(0x01)), 24)).isTrue();
        assertThat(CborUtil.hasTag(tag(25, bytes(0x01)), 24)).isFalse();
        assertThat(CborUtil.hasTag(bytes(0x01), 24)).isFalse();
        final CBORItem content = text("a");
        assertThat(CborUtil.untag(tag(24, content), 24, PATH)).isSameAs(content); // authlete items have no equals()
        assertDecodeError(() -> CborUtil.untag(tag(25, text("a")), 24, PATH), ErrorCode.UNEXPECTED_TYPE, PATH)
                .hasMessage("Expected tag 24, got tag 25");
        assertDecodeError(() -> CborUtil.untag(text("a"), 24, PATH), ErrorCode.UNEXPECTED_TYPE, PATH);
    }

    @Test
    void pathsUseDotsForPlainKeysAndBracketsForTheRest() {
        assertThat(CborUtil.child("$", "docType")).isEqualTo("$.docType");
        assertThat(CborUtil.child("$", "org.iso.18013.5.1")).isEqualTo("$[\"org.iso.18013.5.1\"]");
        assertThat(CborUtil.child("$", "content type")).isEqualTo("$[\"content type\"]");
        assertThat(CborUtil.child("$", "1")).isEqualTo("$[\"1\"]");
        assertThat(CborUtil.index("$.documents", 2)).isEqualTo("$.documents[2]");
    }

    @ParameterizedTest
    @MethodSource("typeNames")
    void typeNameNamesEveryItemType(final CBORItem item, final String expected) {
        assertThat(CborUtil.typeName(item)).isEqualTo(expected);
    }

    static Stream<Arguments> typeNames() {
        return Stream.of(
                Arguments.of(null, "nothing"),
                Arguments.of(map(), "map"),
                Arguments.of(array(), "array"),
                Arguments.of(text("a"), "text string"),
                Arguments.of(bytes(0x01), "byte string"),
                Arguments.of(tag(18, array()), "tag 18"),
                Arguments.of(new CBORDouble(1.5), "float"),
                Arguments.of(new CBORFloat(1.5f), "float"),
                Arguments.of(integer(1), "integer"),
                Arguments.of(new CBORLong(5_000_000_000L), "integer"),
                Arguments.of(new CBORBigInteger(BigInteger.TWO.pow(70)), "integer"),
                Arguments.of(CBORBoolean.TRUE, "boolean"),
                Arguments.of(CBORNull.INSTANCE, "null"),
                Arguments.of(CBORUndefined.INSTANCE, "undefined"),
                Arguments.of(new CBORSimpleValue(16), "simple value"));
    }
}
