package com.mdoc.verifier.controller;

import static com.mdoc.verifier.support.Cbor.array;
import static com.mdoc.verifier.support.Cbor.bytes;
import static com.mdoc.verifier.support.Cbor.embedded;
import static com.mdoc.verifier.support.Cbor.integer;
import static com.mdoc.verifier.support.Cbor.map;
import static com.mdoc.verifier.support.Cbor.raw;
import static com.mdoc.verifier.support.Cbor.tag;
import static com.mdoc.verifier.support.Cbor.text;
import static com.mdoc.verifier.support.Cbor.textMap;
import static com.mdoc.verifier.support.MdocFixtures.CERT;
import static com.mdoc.verifier.support.MdocFixtures.DOC_TYPE;
import static com.mdoc.verifier.support.MdocFixtures.NAME_SPACE;
import static com.mdoc.verifier.support.MdocFixtures.SIGNATURE;
import static com.mdoc.verifier.support.MdocFixtures.deviceResponse;
import static com.mdoc.verifier.support.MdocFixtures.document;
import static com.mdoc.verifier.support.MdocFixtures.issuerAuth;
import static com.mdoc.verifier.support.MdocFixtures.issuerSignedItem;
import static com.mdoc.verifier.support.MdocFixtures.mso;
import static com.mdoc.verifier.support.MdocFixtures.msoPayload;
import static com.mdoc.verifier.support.MdocFixtures.nameSpaces;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.stream.Stream;

import com.authlete.cbor.CBORItem;
import com.authlete.cbor.CBORNull;
import com.authlete.cbor.CBORPair;
import com.authlete.cbor.CBORPairList;
import com.authlete.cbor.CBORString;
import com.authlete.cbor.CBORUndefined;
import com.mdoc.verifier.enums.ErrorCode;
import com.mdoc.verifier.enums.InputType;
import com.mdoc.verifier.support.Cbor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the frontend sees when an mDoc cannot be decoded: real Tomcat, real JSON on the wire, read the way
 * {@code mdoc-frontend/src/api.ts} reads it. The frontend shows {@code code}, {@code path} and {@code detail}, and
 * takes {@code path} only when {@code code} is a string, so those three are pinned exactly, from the root down.
 * <p>
 * The service tests already check each rule with a relative path; this checks that the full path and the message
 * survive the whole chain: base64/hex -> CBOR -> nested tag-24 items -> exception -> problem+json -> HTTP.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        // ~30 requests from 127.0.0.1 in one run; the default burst of 10 would turn the rest into 429.
        "mdoc.limits.rate-limit.capacity=1000",
        "mdoc.limits.rate-limit.refill-tokens=1000"})
class DecodeErrorResponseIT {

    private static final String DOC = "$.documents[0]";
    private static final String ITEMS = DOC + ".issuerSigned.nameSpaces[\"" + NAME_SPACE + "\"]";
    private static final String ISSUER_AUTH = DOC + ".issuerSigned.issuerAuth";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    /** One broken mDoc and the error the frontend must get for it. {@code toString} is the test name. */
    record Case(String name, String mdoc, InputType inputType, ErrorCode code, String path, String detail,
                boolean detailIsPrefix) {

        @Override
        public String toString() {
            return name;
        }
    }

    private static Case exact(final String name, final CBORItem mdoc, final ErrorCode code, final String path,
                              final String detail) {
        return new Case(name, Cbor.hex(mdoc), InputType.HEX, code, path, detail, false);
    }

    private static Case exact(final String name, final String hex, final ErrorCode code, final String path,
                              final String detail) {
        return new Case(name, hex, InputType.HEX, code, path, detail, false);
    }

    /** For messages whose tail comes from the JDK or depends on where the input was cut. */
    private static Case prefix(final String name, final String mdoc, final InputType inputType, final ErrorCode code,
                               final String path, final String detailPrefix) {
        return new Case(name, mdoc, inputType, code, path, detailPrefix, true);
    }

    static Stream<Case> brokenMdocs() throws IOException {
        final String realMdl = resource("/vectors/mdl-device-response.hex");
        final CBORItem validItem = embedded(issuerSignedItem(0, "given_name", text("Alma")));

        return Stream.of(
                // --- Input encoding: nothing decoded yet, so the path is the root ---
                prefix("odd-length hex", "abc", InputType.HEX,
                        ErrorCode.INVALID_INPUT_ENCODING, "$", "Input is not valid HEX: "),
                prefix("non-base64 characters", "@@@@", InputType.BASE64,
                        ErrorCode.INVALID_INPUT_ENCODING, "$", "Input is not valid BASE64: "),
                prefix("standard Base64 sent as BASE64_URL", Base64.getEncoder().encodeToString(raw(0xfb, 0xff)),
                        InputType.BASE64_URL, ErrorCode.INVALID_INPUT_ENCODING, "$", "Input is not valid BASE64_URL: "),

                // --- CBOR well-formedness of the outer item ---
                prefix("real mDL vector cut in half", realMdl.substring(0, realMdl.length() / 2 & ~1), InputType.HEX,
                        ErrorCode.CBOR_MALFORMED, "$", "Malformed CBOR at byte offset "),
                exact("reserved additional information", "1c",
                        ErrorCode.CBOR_MALFORMED, "$",
                        "Malformed CBOR at byte offset 0: additional information 28 is not valid for major type 0"),
                exact("bytes after the DeviceResponse", Cbor.hex(deviceResponse(document())) + "00",
                        ErrorCode.CBOR_TRAILING_DATA, "$", "Unexpected bytes after the CBOR data item"),
                exact("33 nested arrays", "81".repeat(33) + "00",
                        ErrorCode.CBOR_TOO_DEEP, "$", "CBOR nested deeper than 32 levels at byte offset 32"),

                // --- DeviceResponse (ISO 18013-5 §8.3.2.1.2.2) ---
                exact("root is an array", array(),
                        ErrorCode.UNEXPECTED_TYPE, "$", "Expected map, got array"),
                exact("status missing", textMap("version", text("1.0")),
                        ErrorCode.MISSING_FIELD, "$", "Missing required field 'status'"),
                exact("version twice", textMap("version", text("1.0"), "version", text("1.0"), "status", integer(0)),
                        ErrorCode.DUPLICATE_KEY, "$", "Duplicate map key 'version'"),
                exact("negative status", textMap("version", text("1.0"), "status", integer(-1)),
                        ErrorCode.INVALID_VALUE, "$.status", "Expected an unsigned integer, got -1"),
                exact("empty documents", textMap("version", text("1.0"), "documents", array(), "status", integer(0)),
                        ErrorCode.INVALID_VALUE, "$.documents", "Array must contain at least one element"),

                // --- Document: the index says which one ---
                exact("second document's docType is an integer", deviceResponse(document(), textMap("docType", integer(1))),
                        ErrorCode.UNEXPECTED_TYPE, "$.documents[1].docType", "Expected text string, got integer"),
                exact("issuerSigned missing", deviceResponse(textMap("docType", text(DOC_TYPE))),
                        ErrorCode.MISSING_FIELD, DOC, "Missing required field 'issuerSigned'"),

                // --- nameSpaces: IssuerSignedItemBytes = #6.24(bstr .cbor IssuerSignedItem) ---
                exact("item without the tag-24 wrapper", withItems(issuerSignedItem(0, "given_name", text("Alma"))),
                        ErrorCode.UNEXPECTED_TYPE, ITEMS + "[0]", "Expected tag 24, got map"),
                // The offset is inside the embedded bstr, not the whole input: the path says which bstr.
                exact("tag-24 bstr holds truncated CBOR", withItems(tag(24, bytes(raw(0x62, 'A')))),
                        ErrorCode.CBOR_MALFORMED, ITEMS + "[0]",
                        "Malformed CBOR at byte offset 0: string of 2 bytes, only 1 left"),
                exact("second item's digestID is negative",
                        withItems(validItem, embedded(issuerSignedItem(-1, "family_name", text("Kovács")))),
                        ErrorCode.INVALID_VALUE, ITEMS + "[1].digestID", "Expected an unsigned integer, got -1"),
                exact("bad full-date deep inside driving_privileges",
                        withItems(embedded(issuerSignedItem(0, "driving_privileges", array(textMap(
                                "vehicle_category_code", text("B"),
                                "issue_date", tag(1004, text("2026-13-01"))))))),
                        ErrorCode.INVALID_VALUE, ITEMS + "[0].elementValue[0].issue_date",
                        "'2026-13-01' is not a valid RFC 3339 full-date"),
                exact("elementValue is undefined", withItems(embedded(issuerSignedItem(0, "x", CBORUndefined.INSTANCE))),
                        ErrorCode.UNSUPPORTED_CBOR, ITEMS + "[0].elementValue", "undefined has no JSON representation"),
                exact("non-ASCII key twice in elementValue",
                        withItems(embedded(issuerSignedItem(0, "x", textMap("név", text("a"), "név", text("b"))))),
                        ErrorCode.DUPLICATE_KEY, ITEMS + "[0].elementValue", "Map key 'név' occurs more than once"),
                // Keys come from the input. A quote must not end the bracket early: $["org."x""] is no path at all.
                exact("namespace name with quotes", withNameSpaces(textMap("org.\"x\"", array())),
                        ErrorCode.INVALID_VALUE, DOC + ".issuerSigned.nameSpaces[\"org.\\\"x\\\"\"]",
                        "Array must contain at least one element"),

                // --- issuerAuth: COSE_Sign1 (RFC 9052 §4.2) ---
                exact("COSE_Sign1 with 3 elements", withIssuerAuth(array(bytes(), map(), msoPayload())),
                        ErrorCode.INVALID_VALUE, ISSUER_AUTH, "COSE_Sign1 must have 4 elements, got 3"),
                exact("protected header bstr holds truncated CBOR",
                        withIssuerAuth(array(bytes(raw(0x62, 'A')), map(), msoPayload(), bytes(SIGNATURE))),
                        ErrorCode.CBOR_MALFORMED, ISSUER_AUTH + "[0]",
                        "Malformed CBOR at byte offset 0: string of 2 bytes, only 1 left"),
                exact("x5chain with a text entry (RFC 9360 §2)", withIssuerAuth(array(
                                bytes(map(integer(1), integer(-7)).encode()),
                                map(integer(33), array(bytes(CERT), text("x"))),
                                msoPayload(), bytes(SIGNATURE))),
                        ErrorCode.UNEXPECTED_TYPE, ISSUER_AUTH + "[1].x5chain[1]", "Expected byte string, got text string"),
                exact("detached payload", withIssuerAuth(issuerAuth(CBORNull.INSTANCE)),
                        ErrorCode.INVALID_VALUE, ISSUER_AUTH + "[2]", "issuerAuth must not have a detached payload"),
                exact("payload bstr holds the MSO without tag 24", withIssuerAuth(issuerAuth(bytes(mso().encode()))),
                        ErrorCode.UNEXPECTED_TYPE, ISSUER_AUTH + "[2]", "Expected tag 24, got map"),

                // --- MSO inside payload: the path goes on through the two embedded layers ---
                exact("validUntil is a full-date, not a tdate", withMsoValidity(textMap(
                                "signed", tag(0, text("2026-01-01T00:00:00Z")),
                                "validFrom", tag(0, text("2026-01-01T00:00:00Z")),
                                "validUntil", tag(0, text("2027-01-01")))),
                        ErrorCode.INVALID_VALUE, ISSUER_AUTH + "[2].validityInfo.validUntil",
                        "'2027-01-01' is not a valid RFC 3339 date-time"),
                exact("signed as epoch time (tag 1)", withMsoValidity(textMap(
                                "signed", tag(1, integer(1_767_225_600)),
                                "validFrom", tag(0, text("2026-01-01T00:00:00Z")),
                                "validUntil", tag(0, text("2027-01-01T00:00:00Z")))),
                        ErrorCode.UNSUPPORTED_CBOR, ISSUER_AUTH + "[2].validityInfo.signed",
                        "CBOR tag 1 is not supported"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("brokenMdocs")
    void brokenMdocGivesProblemJsonWithPathAndMessage(final Case c) throws Exception {
        final HttpResponse<String> response = postDecode(c.mdoc(), c.inputType());
        final JsonNode body = JSON.readTree(response.body());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/problem+json"));
        assertThat(body.get("title").asString()).isEqualTo("mDoc decode failed");
        assertThat(body.get("status").asInt()).isEqualTo(400);

        // The frontend's guards: typeof code === 'string', typeof path === 'string', typeof detail === 'string'
        assertThat(body.get("code").isString()).as("code is a string").isTrue();
        assertThat(body.get("path").isString()).as("path is a string").isTrue();
        assertThat(body.get("detail").isString()).as("detail is a string").isTrue();

        assertThat(body.get("code").asString()).isEqualTo(c.code().name());
        assertThat(body.get("path").asString()).isEqualTo(c.path());
        if (c.detailIsPrefix()) {
            assertThat(body.get("detail").asString()).startsWith(c.detail());
        } else {
            assertThat(body.get("detail").asString()).isEqualTo(c.detail());
        }
        assertThat(body.has("trace")).as("no stack trace").isFalse();
    }

    @Test
    void warningReachesTheFrontendWithPathAndMessage() throws Exception {
        // Spec form is bstr(24(<<MSO>>)); here the tag-24 item sits in the array without the bstr.
        final HttpResponse<String> response = postDecode(Cbor.hex(withIssuerAuth(issuerAuth(embedded(mso())))), InputType.HEX);
        final JsonNode warnings = JSON.readTree(response.body()).get("warnings");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(warnings.size()).isEqualTo(1);
        assertThat(warnings.get(0).get("code").asString()).isEqualTo(ErrorCode.COSE_PAYLOAD_NOT_BSTR.name());
        assertThat(warnings.get(0).get("path").asString()).isEqualTo(ISSUER_AUTH + "[2]");
        // "§" checks the UTF-8 round trip as well.
        assertThat(warnings.get(0).get("message").asString()).isEqualTo(
                "COSE_Sign1 payload must be a byte string (RFC 9052 §4.2), got MobileSecurityObjectBytes "
                        + "(tag 24) without the bstr wrapper; decoded anyway, a strict verifier rejects it");
    }

    private HttpResponse<String> postDecode(final String mdoc, final InputType inputType) throws Exception {
        // Built with the mapper, the way the frontend's JSON.stringify does it: quotes and \ in mdoc stay valid JSON.
        final String body = JSON.writeValueAsString(JSON.createObjectNode()
                .put("mdoc", mdoc)
                .put("inputType", inputType.name()));
        final HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/decode"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static CBORPairList withNameSpaces(final CBORItem nameSpaces) {
        return deviceResponse(textMap(
                "docType", text(DOC_TYPE),
                "issuerSigned", textMap("nameSpaces", nameSpaces, "issuerAuth", issuerAuth())));
    }

    private static CBORPairList withItems(final CBORItem... items) {
        return withNameSpaces(textMap(NAME_SPACE, array(items)));
    }

    private static CBORPairList withIssuerAuth(final CBORItem issuerAuth) {
        return deviceResponse(textMap(
                "docType", text(DOC_TYPE),
                "issuerSigned", textMap("nameSpaces", nameSpaces(), "issuerAuth", issuerAuth)));
    }

    /** The fixture MSO with its validityInfo replaced, in the spec payload form bstr(24(<<MSO>>)). */
    private static CBORPairList withMsoValidity(final CBORPairList validityInfo) {
        final CBORPairList mso = new CBORPairList(mso().getPairs().stream()
                .map(pair -> pair.getKey() instanceof CBORString key && key.getValue().equals("validityInfo")
                        ? new CBORPair(key, validityInfo)
                        : pair)
                .toList());
        return withIssuerAuth(issuerAuth(bytes(embedded(mso).encode())));
    }

    private static String resource(final String name) throws IOException {
        try (InputStream in = DecodeErrorResponseIT.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Missing test resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.US_ASCII).strip();
        }
    }
}
