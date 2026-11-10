package com.mdoc.verifier.service;

import static com.mdoc.verifier.support.Cbor.array;
import static com.mdoc.verifier.support.Cbor.bytes;
import static com.mdoc.verifier.support.Cbor.embedded;
import static com.mdoc.verifier.support.Cbor.integer;
import static com.mdoc.verifier.support.Cbor.raw;
import static com.mdoc.verifier.support.Cbor.tag;
import static com.mdoc.verifier.support.Cbor.text;
import static com.mdoc.verifier.support.Cbor.textMap;
import static com.mdoc.verifier.support.MdocFixtures.PARSER;
import static com.mdoc.verifier.support.MdocFixtures.assertDecodeError;
import static com.mdoc.verifier.support.MdocFixtures.issuerSignedItem;
import static org.assertj.core.api.Assertions.assertThat;

import com.authlete.cbor.CBORBoolean;
import com.authlete.cbor.CBORItem;
import com.authlete.cbor.CBORUndefined;
import com.mdoc.verifier.enums.ErrorCode;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class NameSpaceServiceTest {

    private static final String PATH = "$.ns";
    private static final String ITEM_PATH = "$.ns[\"org.example\"][0]";

    private final NameSpaceService service = new NameSpaceService(PARSER);

    private JsonNode decode(final CBORItem nameSpaces) {
        return service.decode(nameSpaces, PATH);
    }

    /** One namespace "org.example" with the given single IssuerSignedItemBytes. */
    private JsonNode decodeItem(final CBORItem itemBytes) {
        return decode(textMap("org.example", array(itemBytes)));
    }

    @Test
    void decodesEveryNamespaceAndItem() {
        final JsonNode result = decode(textMap(
                "org.example", array(
                        embedded(issuerSignedItem(0, "given_name", text("Alma"))),
                        embedded(issuerSignedItem(7, "birth_date", tag(1004, text("1990-05-17"))))),
                "org.other", array(
                        embedded(issuerSignedItem(1, "age_over_18", CBORBoolean.TRUE)))));

        assertThat(result.toString()).isEqualTo("""
                {"org.example":[\
                {"digestID":0,"random":"AAAAAAAAAAAAAAAAAAAAAA==","elementIdentifier":"given_name","elementValue":"Alma"},\
                {"digestID":7,"random":"AAAAAAAAAAAAAAAAAAAAAA==","elementIdentifier":"birth_date","elementValue":"1990-05-17"}],\
                "org.other":[\
                {"digestID":1,"random":"AAAAAAAAAAAAAAAAAAAAAA==","elementIdentifier":"age_over_18","elementValue":true}]}""");
    }

    @Test
    void nameSpacesMustBeANonEmptyMap() {
        assertDecodeError(() -> decode(array()), ErrorCode.UNEXPECTED_TYPE, PATH);
        assertDecodeError(() -> decode(textMap()), ErrorCode.INVALID_VALUE, PATH);
    }

    @Test
    void itemsMustBeANonEmptyArray() {
        assertDecodeError(() -> decode(textMap("org.example", text("x"))), ErrorCode.UNEXPECTED_TYPE, "$.ns[\"org.example\"]");
        assertDecodeError(() -> decode(textMap("org.example", array())), ErrorCode.INVALID_VALUE, "$.ns[\"org.example\"]");
    }

    @Test
    void itemMustBeTag24() {
        // the IssuerSignedItem map directly, without 24(<<...>>)
        assertDecodeError(() -> decodeItem(issuerSignedItem(0, "a", text("b"))), ErrorCode.UNEXPECTED_TYPE, ITEM_PATH)
                .hasMessage("Expected tag 24, got map");
        assertDecodeError(() -> decodeItem(tag(25, bytes(issuerSignedItem(0, "a", text("b")).encode()))),
                ErrorCode.UNEXPECTED_TYPE, ITEM_PATH);
    }

    @Test
    void tag24MustHoldAByteStringWithOneMap() {
        assertDecodeError(() -> decodeItem(tag(24, issuerSignedItem(0, "a", text("b")))), ErrorCode.UNEXPECTED_TYPE, ITEM_PATH)
                .hasMessage("Expected byte string, got map");
        assertDecodeError(() -> decodeItem(tag(24, bytes(raw(0x62, 'A')))), ErrorCode.CBOR_MALFORMED, ITEM_PATH);
        assertDecodeError(() -> decodeItem(embedded(array())), ErrorCode.UNEXPECTED_TYPE, ITEM_PATH);
    }

    @Test
    void itemFieldsAreChecked() {
        assertDecodeError(() -> decodeItem(embedded(textMap("digestID", integer(0)))), ErrorCode.MISSING_FIELD, ITEM_PATH);
        assertDecodeError(() -> decodeItem(embedded(issuerSignedItem(-1, "a", text("b")))),
                ErrorCode.INVALID_VALUE, ITEM_PATH + ".digestID");
        assertDecodeError(() -> decodeItem(embedded(textMap(
                        "digestID", integer(0), "random", text("not bytes"),
                        "elementIdentifier", text("a"), "elementValue", text("b")))),
                ErrorCode.UNEXPECTED_TYPE, ITEM_PATH + ".random");
        assertDecodeError(() -> decodeItem(embedded(issuerSignedItem(0, "a", CBORUndefined.INSTANCE))),
                ErrorCode.UNSUPPORTED_CBOR, ITEM_PATH + ".elementValue");
    }
}
