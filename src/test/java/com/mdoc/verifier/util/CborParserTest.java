package com.mdoc.verifier.util;

import static com.mdoc.verifier.support.Cbor.raw;
import static com.mdoc.verifier.support.MdocFixtures.MAX_CBOR_DEPTH;
import static com.mdoc.verifier.support.MdocFixtures.assertDecodeError;
import static org.assertj.core.api.Assertions.assertThat;

import com.authlete.cbor.CBORByteArray;
import com.authlete.cbor.CBORItem;
import com.authlete.cbor.CBORItemList;
import com.authlete.cbor.CBORPairList;
import com.authlete.cbor.CBORString;
import com.mdoc.verifier.config.LimitProperties;
import com.mdoc.verifier.enums.ErrorCode;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CborParserTest {

    private static final String PATH = "$.x";

    private final CborParser parser = new CborParser(MAX_CBOR_DEPTH);

    /** {@code levels} copies of one container header, then the integer 0 as the innermost item. */
    private static byte[] nested(final int levels, final int header) {
        final byte[] result = new byte[levels + 1];
        java.util.Arrays.fill(result, 0, levels, (byte) header);
        return result; // last byte stays 0x00
    }

    @Nested
    class Decode {

        @Test
        void decodesExactlyOneItem() {
            final CBORItem item = parser.decode(raw(0x64, 'A', 'l', 'm', 'a'), PATH); // "Alma"

            assertThat(item).isInstanceOf(CBORString.class);
            assertThat(((CBORString) item).getValue()).isEqualTo("Alma");
        }

        @Test
        void emptyInputIsMalformed() {
            assertDecodeError(() -> parser.decode(new byte[0], PATH), ErrorCode.CBOR_MALFORMED, PATH)
                    .hasMessage("Empty CBOR input");
        }

        @Test
        void truncatedItemIsMalformedWithOffset() {
            // 62 = text of 2 bytes, only 1 follows
            assertDecodeError(() -> parser.decode(raw(0x62, 'A'), PATH), ErrorCode.CBOR_MALFORMED, PATH)
                    .hasMessageContaining("byte offset");
        }

        @Test
        void secondItemIsTrailingData() {
            assertDecodeError(() -> parser.decode(raw(0x01, 0x02), PATH), ErrorCode.CBOR_TRAILING_DATA, PATH);
        }

        @Test
        void brokenBytesAfterTheItemAreTrailingData() {
            assertDecodeError(() -> parser.decode(raw(0x01, 0x62), PATH), ErrorCode.CBOR_TRAILING_DATA, PATH);
        }
    }

    /** The header walk that runs before the recursive decoder (depth and declared lengths). */
    @Nested
    class StructureCheck {

        @Test
        void acceptsTheMaximumDepth() {
            assertThat(parser.decode(nested(MAX_CBOR_DEPTH, 0x81), PATH)).isInstanceOf(CBORItemList.class);
        }

        @Test
        void rejectsOneLevelMore() {
            assertDecodeError(() -> parser.decode(nested(MAX_CBOR_DEPTH + 1, 0x81), PATH),
                    ErrorCode.CBOR_TOO_DEEP, PATH).hasMessageContaining("byte offset 32");
        }

        @Test
        void nestingBombIsRejectedInsteadOfOverflowingTheStack() {
            // 5000 x [ ... ]: 10 000 hex characters, the old request limit; crashed the decoder with StackOverflowError
            assertDecodeError(() -> parser.decode(nested(5000, 0x81), PATH), ErrorCode.CBOR_TOO_DEEP, PATH);
        }

        @Test
        void mapsTagsAndIndefiniteContainersCountAsLevels() {
            // 40, not 33: a map of 1 pair needs 2 bytes after its header, or the count check fires first
            assertDecodeError(() -> parser.decode(nested(40, 0xa1), PATH), ErrorCode.CBOR_TOO_DEEP, PATH); // {{...: ?}: ?}
            assertDecodeError(() -> parser.decode(nested(40, 0xc0), PATH), ErrorCode.CBOR_TOO_DEEP, PATH); // tag(tag(...
            assertDecodeError(() -> parser.decode(nested(40, 0x9f), PATH), ErrorCode.CBOR_TOO_DEEP, PATH); // [_ [_ ...
        }

        @Test
        void acceptsIndefiniteLengthItems() {
            assertThat(parser.decode(raw(0x9f, 0x01, 0x81, 0x02, 0xff), PATH)).isInstanceOf(CBORItemList.class); // [_ 1, [2]]
            assertThat(parser.decode(raw(0x7f, 0x61, 'A', 0x61, 'B', 0xff), PATH)).isInstanceOf(CBORString.class); // (_ "A", "B")
            // Both ends of the major types (2..5) that may be indefinite-length:
            assertThat(parser.decode(raw(0x5f, 0x41, 0x01, 0x41, 0x02, 0xff), PATH)).isInstanceOf(CBORByteArray.class); // (_ h'01', h'02')
            assertThat(parser.decode(raw(0xbf, 0x61, 'a', 0x01, 0xff), PATH)).isInstanceOf(CBORPairList.class);          // {_ "a": 1}
        }

        @Test
        void stringLongerThanTheInputIsMalformed() {
            // 5a 7f ff ff ff: byte string of 2 147 483 647 bytes, none follow
            assertDecodeError(() -> parser.decode(raw(0x5a, 0x7f, 0xff, 0xff, 0xff), PATH), ErrorCode.CBOR_MALFORMED, PATH)
                    .hasMessageContaining("byte offset 0");
        }

        @Test
        void largestArgumentIsNotMistakenForIndefiniteLength() {
            // 5b ff..ff: length 2^64-1, which is -1 as a Java long
            assertDecodeError(() -> parser.decode(raw(0x5b, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff), PATH),
                    ErrorCode.CBOR_MALFORMED, PATH);
        }

        @Test
        void countBeyondTheInputIsMalformed() {
            // The truncation check would reject these too, but later and with a vaguer reason. The exact message
            // pins the count check itself: it names the lying header at offset 0.
            assertDecodeError(() -> parser.decode(raw(0x9a, 0x00, 0x01, 0x00, 0x00), PATH), ErrorCode.CBOR_MALFORMED, PATH)
                    .hasMessage("Malformed CBOR at byte offset 0: array of 65536 items, only 0 bytes left");
            assertDecodeError(() -> parser.decode(raw(0xa2, 0x01, 0x02, 0x03), PATH), ErrorCode.CBOR_MALFORMED, PATH)
                    .hasMessage("Malformed CBOR at byte offset 0: map of 2 pairs, only 3 bytes left"); // needs 4 items
        }

        @Test
        void truncatedArgumentIsMalformed() {
            assertDecodeError(() -> parser.decode(raw(0x19, 0x01), PATH), ErrorCode.CBOR_MALFORMED, PATH); // uint, 1 of 2 bytes
        }

        @Test
        void reservedAdditionalInfoIsMalformed() {
            assertDecodeError(() -> parser.decode(raw(0x1c), PATH), ErrorCode.CBOR_MALFORMED, PATH);       // ai 28
            assertDecodeError(() -> parser.decode(raw(0x3f), PATH), ErrorCode.CBOR_MALFORMED, PATH);       // negative int, ai 31
            assertDecodeError(() -> parser.decode(raw(0xdf, 0x00), PATH), ErrorCode.CBOR_MALFORMED, PATH); // tag, ai 31
        }

        @Test
        void indefiniteItemWithoutBreakIsMalformed() {
            assertDecodeError(() -> parser.decode(raw(0x9f, 0x01), PATH), ErrorCode.CBOR_MALFORMED, PATH) // [_ 1
                    .hasMessage("Malformed CBOR at byte offset 2: input ends inside a data item");
        }

        @Test
        void breakOutsideAnIndefiniteItemIsMalformed() {
            assertDecodeError(() -> parser.decode(raw(0xff), PATH), ErrorCode.CBOR_MALFORMED, PATH);
            assertDecodeError(() -> parser.decode(raw(0x81, 0xff), PATH), ErrorCode.CBOR_MALFORMED, PATH);
        }
    }

    /** The limit is the constructor argument, in production mdoc.limits.max-cbor-depth. */
    @Nested
    class ConfiguredDepth {

        @Test
        void limitComesFromTheConstructor() {
            final CborParser shallow = new CborParser(3);

            assertThat(shallow.decode(nested(3, 0x81), PATH)).isInstanceOf(CBORItemList.class);
            assertDecodeError(() -> shallow.decode(nested(4, 0x81), PATH), ErrorCode.CBOR_TOO_DEEP, PATH)
                    .hasMessage("CBOR nested deeper than 3 levels at byte offset 3");
        }

        @Test
        void highestAllowedSettingDoesNotOverflowTheStack() {
            // The recursive steps after the header walk: authlete decoder and JSON conversion.
            final int ceiling = LimitProperties.MAX_CBOR_DEPTH_CEILING;
            final CBORItem item = new CborParser(ceiling).decode(nested(ceiling, 0x81), PATH);

            assertThat(CborJsonConverter.toJson(item, PATH).toString()).startsWith("[[[");
        }
    }
}
