package com.mdoc.verifier.service;

import static com.mdoc.verifier.support.Cbor.array;
import static com.mdoc.verifier.support.Cbor.integer;
import static com.mdoc.verifier.support.Cbor.text;
import static com.mdoc.verifier.support.Cbor.textMap;
import static com.mdoc.verifier.support.MdocFixtures.DOC_TYPE;
import static com.mdoc.verifier.support.MdocFixtures.assertDecodeError;
import static com.mdoc.verifier.support.MdocFixtures.deviceResponse;
import static com.mdoc.verifier.support.MdocFixtures.document;
import static com.mdoc.verifier.support.MdocFixtures.hexInput;
import static com.mdoc.verifier.support.MdocFixtures.input;
import static com.mdoc.verifier.support.MdocFixtures.issuerAuth;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.authlete.cbor.CBORItem;
import com.mdoc.verifier.dto.mDocInputDto;
import com.mdoc.verifier.enums.ErrorCode;
import com.mdoc.verifier.enums.InputType;
import com.mdoc.verifier.exception.MdocDecodeException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class DecodeServiceTest {

    private static final String PAYLOAD_PATH = "$.documents[0].issuerSigned.issuerAuth[2]";

    // Public sample (com.example doctype). The issuerAuth payload is 24(<<MSO>>) without the outer bstr, and the
    // ES256 signature is computed over exactly that form (checked with openssl against the x5chain key).
    // Its x5chain certificate expired on 2024-09-27, so it is not a positive vector for the later validity checks.
    private static final String TAG24_PAYLOAD_SAMPLE =
            // DeviceResponse {version: "1.0", documents: [{docType, issuerSigned: {nameSpaces: {namespace1: [
              "a36776657273696f6e63312e3069646f63756d656e747381a267646f635479706573636f6d2e6578616d706c652e646f"
            + "63747970656c6973737565725369676e6564a26a6e616d65537061636573a276636f6d2e6578616d706c652e6e616d65"
            + "73706163653181"
            // 24(<<IssuerSignedItem digestID 1>>)
            + "d8185859a4686469676573744944016672616e646f6d5036ee89ca566e176e37c61918210c27e771656c656d656e7449"
            + "64656e7469666965726a636c61696d4e616d65316c656c656d656e7456616c75656b636c61696d56616c756531"
            // ], namespace2: [
            + "76636f6d2e6578616d706c652e6e616d6573706163653281"
            // 24(<<IssuerSignedItem digestID 2>>)
            + "d8185859a4686469676573744944026672616e646f6d5034def125c6bd9a51e19828b8156ba34771656c656d656e7449"
            + "64656e7469666965726a636c61696d4e616d65326c656c656d656e7456616c75656b636c61696d56616c756532"
            // ]}, issuerAuth: [  (84 = array of 4)
            + "6a6973737565724175746884"
            // [0] protected: h'a10126' = {1: -7} ES256
            + "43a10126"
            // [1] unprotected: {33: x5chain DER certificate}
            + "a118215901633082015f30820104a0030201020206018c29038fda300a06082a8648ce3d040302303631343032060355"
            + "04030c2b335f775177637431776f314330524f715f59746a49344775306a55746255324241766d78496d43356a4b7330"
            + "1e170d3233313230323035333232385a170d3234303932373035333232385a30363134303206035504030c2b335f7751"
            + "77637431776f314330524f715f59746a49344775306a55746255324241766d78496d43356a4b733059301306072a8648"
            + "ce3d020106082a8648ce3d03010703420004430ef7ebb3e3230535edc917fc6e19a8b5b61233c6d1e7d5d1c63386fab6"
            + "5239329ab737dd156480413aabd88001e2e197ce48ada0334f7f528ce055d442aede300a06082a8648ce3d0403020349"
            + "003046022100a384ecbb10e5e7edde129e921c3ac1567d6ba8e90618ba0eba42617a59dd1aa2022100a9a1cae3583c2b"
            + "59ec6841938eaf09c461106699f85a3e8af63990ab6cfdd4eb"
            // [2] payload: d8 18 59 01 31 = tag 24 directly, NO outer bstr
            + "d818590131a56776657273696f6e63312e306f646967657374416c676f726974686d675348412d3235366c76616c7565"
            + "44696765737473a276636f6d2e6578616d706c652e6e616d65737061636531a10158200a14a20d631c341cc0c4ce88f8"
            + "2b947b43f5e41230b74e8e22235890dc884f1476636f6d2e6578616d706c652e6e616d65737061636532a10258207f3a"
            + "b6742ba562ef81f78833fc42d46fee738a6c6d3bc69e94eb12d1d96338c567646f635479706573636f6d2e6578616d70"
            + "6c652e646f63747970656c76616c6964697479496e666fa3667369676e6564c074323032332d31322d30345431323a34"
            + "393a34315a6976616c696446726f6dc074323032332d31322d30345431323a34393a34315a6a76616c6964556e74696c"
            + "c074323033332d31322d30345431323a34393a34315a"
            // [3] signature: 58 40 = bstr of 64 bytes, raw r||s
            + "584004ea32fbc549e145bc5dd4b36af71040f06fd15c6a8585cd4694a0126ec7eeecc93e5096733ef1e2b3bccc45f625"
            + "d8634d8224cdfb36e3157ff920374b8b48eb"
            // ]}}], status: 0
            + "6673746174757300";

    // Start of the payload item at byte offset 690: tag 24 (d8 18), bstr of 0x131 = 305 bytes (59 01 31), MSO map (a5).
    private static final String PAYLOAD_HEAD = "d818590131a5";

    // The same document in the spec form: the tag-24 item wrapped in a bstr of 0x136 = 310 bytes
    // (2 bytes tag head + 3 bytes bstr head + 305 bytes MSO). Array and map heads count items, not bytes,
    // so nothing else changes. Decoding does not check the signature, so this is fine for decode tests.
    private static final String CONFORMANT_SAMPLE = replaceOnce(TAG24_PAYLOAD_SAMPLE, PAYLOAD_HEAD, "590136" + PAYLOAD_HEAD);

    private final DecodeService decodeService = new DecodeService(new NameSpaceService(), new IssuerAuthService());

    @Test
    void tag24PayloadWithoutBstrIsDecodedWithWarning() {
        final JsonNode result = decodeService.decode(hexText(TAG24_PAYLOAD_SAMPLE));

        assertThat(result.at("/documents/0/issuerSigned/issuerAuth/payload/docType").asString())
                .isEqualTo("com.example.doctype");
        assertThat(result.at("/documents/0/issuerSigned/issuerAuth/payload/valueDigests/com.example.namespace1/1").asString())
                .isEqualTo("ChSiDWMcNBzAxM6I+CuUe0P15BIwt06OIiNYkNyITxQ=");
        final JsonNode warnings = result.get("warnings");
        assertThat(warnings.size()).isEqualTo(1);
        assertThat(warnings.get(0).get("code").asString()).isEqualTo(ErrorCode.COSE_PAYLOAD_NOT_BSTR.name());
        assertThat(warnings.get(0).get("path").asString()).isEqualTo(PAYLOAD_PATH);
    }

    @Test
    void bstrWrappedPayloadHasNoWarning() {
        final JsonNode result = decodeService.decode(hexText(CONFORMANT_SAMPLE));

        assertThat(result.at("/documents/0/issuerSigned/issuerAuth/payload/docType").asString())
                .isEqualTo("com.example.doctype");
        assertThat(result.get("warnings").isEmpty()).isTrue();
    }

    @Test
    void generatedConformantMdlIsDecodedWithoutWarnings() throws IOException {
        final JsonNode result = decodeService.decode(hexText(resource("/vectors/mdl-device-response.hex")));

        final JsonNode items = result.at("/documents/0/issuerSigned/nameSpaces/org.iso.18013.5.1");
        assertThat(items.size()).isEqualTo(11);
        assertThat(items.get(1).get("elementIdentifier").asString()).isEqualTo("given_name");
        assertThat(items.get(1).get("elementValue").asString()).isEqualTo("Péter");
        assertThat(items.get(2).get("elementValue").asString()).isEqualTo("1990-05-17"); // tag 1004 full-date
        assertThat(items.get(8).get("elementValue").asBoolean()).isTrue();             // age_over_18

        final JsonNode mso = result.at("/documents/0/issuerSigned/issuerAuth/payload");
        assertThat(mso.get("docType").asString()).isEqualTo("org.iso.18013.5.1.mDL");
        assertThat(mso.at("/valueDigests/org.iso.18013.5.1").size()).isEqualTo(11);
        assertThat(mso.at("/validityInfo/validUntil").asString()).isEqualTo("2031-11-08T18:00:20Z");
        assertThat(result.get("warnings").isEmpty()).isTrue();
    }

    @Test
    void payloadWithOtherTagIsRejected() {
        final String tag25Payload = replaceOnce(TAG24_PAYLOAD_SAMPLE, PAYLOAD_HEAD, "d819590131a5");

        assertThatThrownBy(() -> decodeService.decode(hexText(tag25Payload)))
                .isInstanceOfSatisfying(MdocDecodeException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCode.UNEXPECTED_TYPE);
                    assertThat(e.getPath()).isEqualTo(PAYLOAD_PATH);
                    assertThat(e.getMessage()).isEqualTo("Expected byte string, got tag 25");
                });
    }

    @Nested
    class Structure {

        private JsonNode decode(final CBORItem deviceResponse) {
            return decodeService.decode(hexInput(deviceResponse));
        }

        @Test
        void decodesEveryDocument() {
            final JsonNode result = decode(deviceResponse(document(), document()));

            assertThat(result.get("version").asString()).isEqualTo("1.0");
            assertThat(result.get("documents").size()).isEqualTo(2);
            assertThat(result.at("/documents/1/docType").asString()).isEqualTo(DOC_TYPE);
            assertThat(result.at("/documents/0/issuerSigned/nameSpaces/org.example.ns/0/elementValue").asString()).isEqualTo("Alma");
            assertThat(result.at("/documents/0/issuerSigned/issuerAuth/payload/docType").asString()).isEqualTo(DOC_TYPE);
            assertThat(result.get("status").asInt()).isZero();
        }

        @Test
        void documentsAndNameSpacesAreOptional() {
            final JsonNode empty = decode(textMap("version", text("1.0"), "status", integer(10)));
            assertThat(empty.has("documents")).isFalse();
            assertThat(empty.get("status").asInt()).isEqualTo(10);
            assertThat(empty.get("warnings").isEmpty()).isTrue();

            final JsonNode issuerSigned = decode(deviceResponse(
                    textMap("docType", text(DOC_TYPE), "issuerSigned", textMap("issuerAuth", issuerAuth()))))
                    .at("/documents/0/issuerSigned");
            assertThat(issuerSigned.has("nameSpaces")).isFalse();
            assertThat(issuerSigned.has("issuerAuth")).isTrue();
        }

        @Test
        void acceptsBase64AndBase64Url() {
            final byte[] encoded = deviceResponse(document()).encode();

            assertThat(decodeService.decode(input(Base64.getEncoder().encodeToString(encoded), InputType.BASE64))
                    .at("/documents/0/docType").asString()).isEqualTo(DOC_TYPE);
            assertThat(decodeService.decode(input(Base64.getUrlEncoder().withoutPadding().encodeToString(encoded), InputType.BASE64_URL))
                    .at("/documents/0/docType").asString()).isEqualTo(DOC_TYPE);
        }

        @Test
        void invalidEncodingIsReportedAtTheRoot() {
            assertDecodeError(() -> decodeService.decode(input("zz", InputType.HEX)), ErrorCode.INVALID_INPUT_ENCODING, "$")
                    .hasMessageStartingWith("Input is not valid HEX");
        }

        @Test
        void rootMustBeAMapWithVersionAndStatus() {
            assertDecodeError(() -> decode(array()), ErrorCode.UNEXPECTED_TYPE, "$");
            assertDecodeError(() -> decode(textMap("status", integer(0))), ErrorCode.MISSING_FIELD, "$");
            assertDecodeError(() -> decode(textMap("version", text("1.0"))), ErrorCode.MISSING_FIELD, "$");
            assertDecodeError(() -> decode(textMap("version", integer(1), "status", integer(0))), ErrorCode.UNEXPECTED_TYPE, "$.version");
            assertDecodeError(() -> decode(textMap("version", text("1.0"), "status", integer(-1))), ErrorCode.INVALID_VALUE, "$.status");
        }

        @Test
        void documentsMustBeANonEmptyArray() {
            assertDecodeError(() -> decode(textMap("version", text("1.0"), "documents", array(), "status", integer(0))),
                    ErrorCode.INVALID_VALUE, "$.documents");
            assertDecodeError(() -> decode(textMap("version", text("1.0"), "documents", textMap(), "status", integer(0))),
                    ErrorCode.UNEXPECTED_TYPE, "$.documents");
        }

        @Test
        void documentErrorsCarryTheDocumentIndex() {
            assertDecodeError(() -> decode(deviceResponse(document(), textMap("docType", integer(1)))),
                    ErrorCode.UNEXPECTED_TYPE, "$.documents[1].docType");
            assertDecodeError(() -> decode(deviceResponse(textMap("docType", text(DOC_TYPE)))),
                    ErrorCode.MISSING_FIELD, "$.documents[0]");
            assertDecodeError(() -> decode(deviceResponse(textMap("docType", text(DOC_TYPE), "issuerSigned", array()))),
                    ErrorCode.UNEXPECTED_TYPE, "$.documents[0].issuerSigned");
            assertDecodeError(() -> decode(deviceResponse(textMap("docType", text(DOC_TYPE), "issuerSigned", textMap()))),
                    ErrorCode.MISSING_FIELD, "$.documents[0].issuerSigned");
        }
    }

    private static mDocInputDto hexText(final String hex) {
        final mDocInputDto input = new mDocInputDto();
        input.setMdoc(hex);
        input.setInputType(InputType.HEX);
        return input;
    }

    private static String resource(final String name) throws IOException {
        try (InputStream in = DecodeServiceTest.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Missing test resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.US_ASCII).strip();
        }
    }

    private static String replaceOnce(final String text, final String target, final String replacement) {
        final int index = text.indexOf(target);
        if (index < 0 || text.indexOf(target, index + 1) >= 0) {
            throw new IllegalStateException("'" + target + "' must occur exactly once");
        }
        return text.substring(0, index) + replacement + text.substring(index + target.length());
    }
}
