package com.mdoc.verifier.util;

import static com.mdoc.verifier.support.Cbor.array;
import static com.mdoc.verifier.support.Cbor.bytes;
import static com.mdoc.verifier.support.Cbor.integer;
import static com.mdoc.verifier.support.Cbor.map;
import static com.mdoc.verifier.support.Cbor.raw;
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
import com.authlete.cbor.CBORItemList;
import com.authlete.cbor.CBORLong;
import com.authlete.cbor.CBORNull;
import com.authlete.cbor.CBORSimpleValue;
import com.authlete.cbor.CBORString;
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
    class Decode {

        @Test
        void decodesExactlyOneItem() {
            final CBORItem item = CborUtil.decode(raw(0x64, 'A', 'l', 'm', 'a'), PATH); // "Alma"

            assertThat(item).isInstanceOf(CBORString.class);
            assertThat(((CBORString) item).getValue()).isEqualTo("Alma");
        }

        @Test
        void emptyInputIsMalformed() {
            assertDecodeError(() -> CborUtil.decode(new byte[0], PATH), ErrorCode.CBOR_MALFORMED, PATH)
                    .hasMessage("Empty CBOR input");
        }

        @Test
        void truncatedItemIsMalformedWithOffset() {
            // 62 = text of 2 bytes, only 1 follows
            assertDecodeError(() -> CborUtil.decode(raw(0x62, 'A'), PATH), ErrorCode.CBOR_MALFORMED, PATH)
                    .hasMessageContaining("byte offset");
        }

        @Test
        void secondItemIsTrailingData() {
            assertDecodeError(() -> CborUtil.decode(raw(0x01, 0x02), PATH), ErrorCode.CBOR_TRAILING_DATA, PATH);
        }

        @Test
        void brokenBytesAfterTheItemAreTrailingData() {
            assertDecodeError(() -> CborUtil.decode(raw(0x01, 0x62), PATH), ErrorCode.CBOR_TRAILING_DATA, PATH);
        }
    }

    /** The header walk that runs before the recursive decoder (depth and declared lengths). */
    @Nested
    class StructureCheck {

        /** {@code levels} copies of one container header, then the integer 0 as the innermost item. */
        private static byte[] nested(final int levels, final int header) {
            final byte[] result = new byte[levels + 1];
            java.util.Arrays.fill(result, 0, levels, (byte) header);
            return result; // last byte stays 0x00
        }

        @Test
        void acceptsTheMaximumDepth() {
            assertThat(CborUtil.decode(nested(CborUtil.MAX_DEPTH, 0x81), PATH)).isInstanceOf(CBORItemList.class);
        }

        @Test
        void rejectsOneLevelMore() {
            assertDecodeError(() -> CborUtil.decode(nested(CborUtil.MAX_DEPTH + 1, 0x81), PATH),
                    ErrorCode.CBOR_TOO_DEEP, PATH).hasMessageContaining("byte offset 32");
        }

        @Test
        void nestingBombIsRejectedInsteadOfOverflowingTheStack() {
            // 5000 x [ ... ]: 10 000 hex characters, the old request limit; crashed the decoder with StackOverflowError
            assertDecodeError(() -> CborUtil.decode(nested(5000, 0x81), PATH), ErrorCode.CBOR_TOO_DEEP, PATH);
        }

        @Test
        void mapsTagsAndIndefiniteContainersCountAsLevels() {
            // 40, not 33: a map of 1 pair needs 2 bytes after its header, or the count check fires first
            assertDecodeError(() -> CborUtil.decode(nested(40, 0xa1), PATH), ErrorCode.CBOR_TOO_DEEP, PATH); // {{...: ?}: ?}
            assertDecodeError(() -> CborUtil.decode(nested(40, 0xc0), PATH), ErrorCode.CBOR_TOO_DEEP, PATH); // tag(tag(...
            assertDecodeError(() -> CborUtil.decode(nested(40, 0x9f), PATH), ErrorCode.CBOR_TOO_DEEP, PATH); // [_ [_ ...
        }

        @Test
        void acceptsIndefiniteLengthItems() {
            assertThat(CborUtil.decode(raw(0x9f, 0x01, 0x81, 0x02, 0xff), PATH)).isInstanceOf(CBORItemList.class); // [_ 1, [2]]
            assertThat(CborUtil.decode(raw(0x7f, 0x61, 'A', 0x61, 'B', 0xff), PATH)).isInstanceOf(CBORString.class); // (_ "A", "B")
        }

        @Test
        void stringLongerThanTheInputIsMalformed() {
            // 5a 7f ff ff ff: byte string of 2 147 483 647 bytes, none follow
            assertDecodeError(() -> CborUtil.decode(raw(0x5a, 0x7f, 0xff, 0xff, 0xff), PATH), ErrorCode.CBOR_MALFORMED, PATH)
                    .hasMessageContaining("byte offset 0");
        }

        @Test
        void largestArgumentIsNotMistakenForIndefiniteLength() {
            // 5b ff..ff: length 2^64-1, which is -1 as a Java long
            assertDecodeError(() -> CborUtil.decode(raw(0x5b, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff), PATH),
                    ErrorCode.CBOR_MALFORMED, PATH);
        }

        @Test
        void countBeyondTheInputIsMalformed() {
            assertDecodeError(() -> CborUtil.decode(raw(0x9a, 0x00, 0x01, 0x00, 0x00), PATH), // array of 65 536 items
                    ErrorCode.CBOR_MALFORMED, PATH);
            assertDecodeError(() -> CborUtil.decode(raw(0xa2, 0x01, 0x02, 0x03), PATH), // 2 pairs need 4 items, 3 bytes left
                    ErrorCode.CBOR_MALFORMED, PATH);
        }

        @Test
        void truncatedArgumentIsMalformed() {
            assertDecodeError(() -> CborUtil.decode(raw(0x19, 0x01), PATH), ErrorCode.CBOR_MALFORMED, PATH); // uint, 1 of 2 bytes
        }

        @Test
        void reservedAdditionalInfoIsMalformed() {
            assertDecodeError(() -> CborUtil.decode(raw(0x1c), PATH), ErrorCode.CBOR_MALFORMED, PATH);       // ai 28
            assertDecodeError(() -> CborUtil.decode(raw(0xdf, 0x00), PATH), ErrorCode.CBOR_MALFORMED, PATH); // tag, ai 31
        }

        @Test
        void breakOutsideAnIndefiniteItemIsMalformed() {
            assertDecodeError(() -> CborUtil.decode(raw(0xff), PATH), ErrorCode.CBOR_MALFORMED, PATH);
            assertDecodeError(() -> CborUtil.decode(raw(0x81, 0xff), PATH), ErrorCode.CBOR_MALFORMED, PATH);
        }
    }

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
