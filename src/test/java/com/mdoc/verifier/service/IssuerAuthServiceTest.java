package com.mdoc.verifier.service;

import static com.mdoc.verifier.support.Cbor.array;
import static com.mdoc.verifier.support.Cbor.bytes;
import static com.mdoc.verifier.support.Cbor.embedded;
import static com.mdoc.verifier.support.Cbor.integer;
import static com.mdoc.verifier.support.Cbor.map;
import static com.mdoc.verifier.support.Cbor.raw;
import static com.mdoc.verifier.support.Cbor.tag;
import static com.mdoc.verifier.support.Cbor.text;
import static com.mdoc.verifier.support.MdocFixtures.CERT;
import static com.mdoc.verifier.support.MdocFixtures.DOC_TYPE;
import static com.mdoc.verifier.support.MdocFixtures.PARSER;
import static com.mdoc.verifier.support.MdocFixtures.SIGNATURE;
import static com.mdoc.verifier.support.MdocFixtures.assertDecodeError;
import static com.mdoc.verifier.support.MdocFixtures.issuerAuth;
import static com.mdoc.verifier.support.MdocFixtures.mso;
import static com.mdoc.verifier.support.MdocFixtures.msoPayload;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;

import com.authlete.cbor.CBORItem;
import com.authlete.cbor.CBORLong;
import com.authlete.cbor.CBORNull;
import com.mdoc.verifier.enums.ErrorCode;
import com.mdoc.verifier.util.DecodeWarnings;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class IssuerAuthServiceTest {

    private static final String PATH = "$.ia";

    private final IssuerAuthService service = new IssuerAuthService(PARSER);
    private final DecodeWarnings warnings = new DecodeWarnings();

    private JsonNode decode(final CBORItem issuerAuth) {
        return service.decode(issuerAuth, PATH, warnings);
    }

    private static String base64(final byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }

    /** COSE_Sign1 with the given protected bstr and unprotected map, spec payload and dummy signature. */
    private static CBORItem withHeaders(final CBORItem protectedHeader, final CBORItem unprotectedHeader) {
        return array(protectedHeader, unprotectedHeader, msoPayload(), bytes(SIGNATURE));
    }

    @Test
    void decodesAllFourParts() {
        final JsonNode result = decode(issuerAuth());

        assertThat(result.get("protectedHeader").toString()).isEqualTo("{\"alg\":-7}");
        assertThat(result.at("/unprotectedHeader/x5chain/0").asString()).isEqualTo(base64(CERT));
        assertThat(result.at("/payload/docType").asString()).isEqualTo(DOC_TYPE);
        assertThat(result.at("/payload/validityInfo/validUntil").asString()).isEqualTo("2027-01-01T00:00:00Z");
        assertThat(result.get("signature").asString()).isEqualTo(base64(SIGNATURE));
        assertThat(warnings.toJson().isEmpty()).isTrue();
    }

    @Nested
    class Structure {

        @Test
        void tag18FormIsAccepted() {
            assertThat(decode(tag(18, issuerAuth())).at("/payload/docType").asString()).isEqualTo(DOC_TYPE);
        }

        @Test
        void otherTagIsRejected() {
            assertDecodeError(() -> decode(tag(98, issuerAuth())), ErrorCode.UNEXPECTED_TYPE, PATH)
                    .hasMessage("Expected array, got tag 98");
        }

        @Test
        void mustBeAnArrayOfFour() {
            assertDecodeError(() -> decode(map()), ErrorCode.UNEXPECTED_TYPE, PATH);
            assertDecodeError(() -> decode(array(bytes(), map(), msoPayload())), ErrorCode.INVALID_VALUE, PATH)
                    .hasMessage("COSE_Sign1 must have 4 elements, got 3");
        }

        @Test
        void signatureMustBeAByteString() {
            assertDecodeError(() -> decode(array(bytes(), map(), msoPayload(), text("sig"))), ErrorCode.UNEXPECTED_TYPE, "$.ia[3]");
        }
    }

    @Nested
    class Headers {

        @Test
        void emptyProtectedBstrMeansNoHeaders() {
            assertThat(decode(withHeaders(bytes(), map())).get("protectedHeader").isEmpty()).isTrue();
        }

        @Test
        void protectedHeaderMustBeABstrWithCbor() {
            assertDecodeError(() -> decode(withHeaders(map(integer(1), integer(-7)), map())), ErrorCode.UNEXPECTED_TYPE, "$.ia[0]");
            assertDecodeError(() -> decode(withHeaders(bytes(0x62, 'A'), map())), ErrorCode.CBOR_MALFORMED, "$.ia[0]");
        }

        @Test
        void unprotectedHeaderMustBeAMap() {
            assertDecodeError(() -> decode(withHeaders(bytes(), array())), ErrorCode.UNEXPECTED_TYPE, "$.ia[1]");
        }

        @Test
        void knownLabelsGetNamesTheRestKeepTheirLabel() {
            final JsonNode header = decode(withHeaders(bytes(), map(
                    integer(1), integer(-7), integer(2), array(integer(1)), integer(3), text("application/cbor"),
                    integer(4), bytes(0x0a), integer(5), bytes(0x0b), integer(6), bytes(0x0c),
                    integer(99), text("x"), text("custom"), text("y"), new CBORLong(5_000_000_000L), text("z"))))
                    .get("unprotectedHeader");

            assertThat(header.properties()).extracting(e -> e.getKey()).containsExactly(
                    "alg", "crit", "content type", "kid", "IV", "Partial IV", "99", "custom", "5000000000");
        }

        @Test
        void labelMustBeIntOrText() {
            assertDecodeError(() -> decode(withHeaders(bytes(), map(bytes(0x01), integer(1)))), ErrorCode.UNEXPECTED_TYPE, "$.ia[1]")
                    .hasMessage("Expected int or text string header label, got byte string");
        }

        @Test
        void sameLabelTwiceIsADuplicate() {
            // 1 and "alg" are the same header parameter
            assertDecodeError(() -> decode(withHeaders(bytes(), map(integer(1), integer(-7), text("alg"), integer(-7)))),
                    ErrorCode.DUPLICATE_KEY, "$.ia[1]");
        }

        @Test
        void x5chainIsAlwaysAnArray() {
            final JsonNode chain = decode(withHeaders(bytes(), map(integer(33), array(bytes(CERT), bytes(0x01)))))
                    .at("/unprotectedHeader/x5chain");

            assertThat(chain.size()).isEqualTo(2);
            assertThat(chain.get(1).asString()).isEqualTo("AQ==");
        }

        @Test
        void x5chainMustHoldByteStrings() {
            assertDecodeError(() -> decode(withHeaders(bytes(), map(integer(33), integer(1)))), ErrorCode.UNEXPECTED_TYPE, "$.ia[1].x5chain")
                    .hasMessage("Expected byte string or array of byte strings, got integer");
            assertDecodeError(() -> decode(withHeaders(bytes(), map(integer(33), array(bytes(CERT), text("x"))))),
                    ErrorCode.UNEXPECTED_TYPE, "$.ia[1].x5chain[1]");
        }
    }

    @Nested
    class Payload {

        private static final String PAYLOAD_PATH = "$.ia[2]";

        @Test
        void tag24WithoutBstrIsDecodedWithWarning() {
            final JsonNode result = decode(issuerAuth(embedded(mso())));

            assertThat(result.at("/payload/docType").asString()).isEqualTo(DOC_TYPE);
            final JsonNode warning = warnings.toJson().get(0);
            assertThat(warning.get("code").asString()).isEqualTo(ErrorCode.COSE_PAYLOAD_NOT_BSTR.name());
            assertThat(warning.get("path").asString()).isEqualTo(PAYLOAD_PATH);
        }

        @Test
        void detachedPayloadIsRejected() {
            assertDecodeError(() -> decode(issuerAuth(CBORNull.INSTANCE)), ErrorCode.INVALID_VALUE, PAYLOAD_PATH);
        }

        @Test
        void otherTypesAreRejected() {
            assertDecodeError(() -> decode(issuerAuth(tag(25, bytes(mso().encode())))), ErrorCode.UNEXPECTED_TYPE, PAYLOAD_PATH)
                    .hasMessage("Expected byte string, got tag 25");
            assertDecodeError(() -> decode(issuerAuth(mso())), ErrorCode.UNEXPECTED_TYPE, PAYLOAD_PATH);
        }

        @Test
        void bstrMustHoldTag24() {
            // bstr(<<MSO>>) without the tag 24 layer
            assertDecodeError(() -> decode(issuerAuth(bytes(mso().encode()))), ErrorCode.UNEXPECTED_TYPE, PAYLOAD_PATH)
                    .hasMessage("Expected tag 24, got map");
        }

        @Test
        void bstrMustHoldWellFormedCbor() {
            assertDecodeError(() -> decode(issuerAuth(bytes(raw(0xd8, 0x18)))), ErrorCode.CBOR_MALFORMED, PAYLOAD_PATH);
        }

        @Test
        void msoMustBeATextKeyMap() {
            assertDecodeError(() -> decode(issuerAuth(bytes(embedded(array()).encode()))), ErrorCode.UNEXPECTED_TYPE, PAYLOAD_PATH);
            assertDecodeError(() -> decode(issuerAuth(bytes(embedded(map(integer(1), integer(2))).encode()))),
                    ErrorCode.UNEXPECTED_TYPE, PAYLOAD_PATH);
        }
    }
}
