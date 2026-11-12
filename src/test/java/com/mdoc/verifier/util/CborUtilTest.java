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

import com.authlete.cbor.CBORBigInteger;
import com.authlete.cbor.CBORItem;
import com.authlete.cbor.CBORLong;
import com.mdoc.verifier.enums.ErrorCode;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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
            assertDecodeError(() -> CborUtil.textKeyMap(map(integer(1), integer(2)), PATH), ErrorCode.UNEXPECTED_TYPE, PATH);
        }

        @Test
        void rejectsDuplicateKey() {
            assertDecodeError(() -> CborUtil.textKeyMap(textMap("a", integer(1), "a", integer(2)), PATH),
                    ErrorCode.DUPLICATE_KEY, PATH + ".a");
        }

        @Test
        void rejectsNonMap() {
            assertDecodeError(() -> CborUtil.textKeyMap(array(), PATH), ErrorCode.UNEXPECTED_TYPE, PATH);
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
        assertDecodeError(() -> CborUtil.text(bytes(0x01), PATH), ErrorCode.UNEXPECTED_TYPE, PATH);
        assertDecodeError(() -> CborUtil.bytes(text("a"), PATH), ErrorCode.UNEXPECTED_TYPE, PATH);
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
        assertDecodeError(() -> CborUtil.untag(tag(25, text("a")), 24, PATH), ErrorCode.UNEXPECTED_TYPE, PATH);
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

    @Test
    void bracketKeysAreEscapedLikeJsonPathStringLiterals() {
        assertThat(CborUtil.child("$", "a\"b")).isEqualTo("$[\"a\\\"b\"]");       // $["a\"b"]
        assertThat(CborUtil.child("$", "a\\b")).isEqualTo("$[\"a\\\\b\"]");       // $["a\\b"]
        assertThat(CborUtil.child("$", "a\nb")).isEqualTo("$[\"a\\u000ab\"]");    // newline as a hex escape
        assertThat(CborUtil.child("$", "név")).isEqualTo("$[\"név\"]");            // non-ASCII stays as it is
    }

}
