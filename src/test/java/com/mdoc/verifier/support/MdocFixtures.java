package com.mdoc.verifier.support;

import static com.mdoc.verifier.support.Cbor.array;
import static com.mdoc.verifier.support.Cbor.bytes;
import static com.mdoc.verifier.support.Cbor.embedded;
import static com.mdoc.verifier.support.Cbor.integer;
import static com.mdoc.verifier.support.Cbor.map;
import static com.mdoc.verifier.support.Cbor.tag;
import static com.mdoc.verifier.support.Cbor.text;
import static com.mdoc.verifier.support.Cbor.textMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.authlete.cbor.CBORItem;
import com.authlete.cbor.CBORItemList;
import com.authlete.cbor.CBORPairList;
import com.mdoc.verifier.dto.mDocInputDto;
import com.mdoc.verifier.enums.ErrorCode;
import com.mdoc.verifier.enums.InputType;
import com.mdoc.verifier.exception.MdocDecodeException;
import org.assertj.core.api.AbstractThrowableAssert;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

/**
 * Minimal valid mDoc structures (ISO 18013-5) to build tests on: each test breaks exactly one thing.
 * Signatures and certificates are dummy bytes, decoding does not check them.
 */
public final class MdocFixtures {

    public static final String DOC_TYPE = "org.example.doc";
    public static final String NAME_SPACE = "org.example.ns";
    public static final byte[] CERT = Cbor.raw(0x30, 0x03, 0x02, 0x01, 0x01);
    public static final byte[] SIGNATURE = new byte[64];

    private MdocFixtures() {
    }

    /** IssuerSignedItem = {digestID, random, elementIdentifier, elementValue} */
    public static CBORPairList issuerSignedItem(final int digestId, final String identifier, final CBORItem value) {
        return textMap(
                "digestID", integer(digestId),
                "random", bytes(new byte[16]),
                "elementIdentifier", text(identifier),
                "elementValue", value);
    }

    /** { NAME_SPACE: [24(<<{digestID: 0, ..., elementIdentifier: "given_name", elementValue: "Alma"}>>)] } */
    public static CBORPairList nameSpaces() {
        return textMap(NAME_SPACE, array(embedded(issuerSignedItem(0, "given_name", text("Alma")))));
    }

    public static CBORPairList mso() {
        return textMap(
                "version", text("1.0"),
                "digestAlgorithm", text("SHA-256"),
                "valueDigests", textMap(NAME_SPACE, map(integer(0), bytes(new byte[32]))),
                "docType", text(DOC_TYPE),
                "validityInfo", textMap(
                        "signed", tag(0, text("2026-01-01T00:00:00Z")),
                        "validFrom", tag(0, text("2026-01-01T00:00:00Z")),
                        "validUntil", tag(0, text("2027-01-01T00:00:00Z"))));
    }

    /** Spec form of the COSE payload: bstr(24(<<MSO>>)) */
    public static CBORItem msoPayload() {
        return bytes(embedded(mso()).encode());
    }

    /** [ <<{1: -7}>>, {33: CERT}, payload, SIGNATURE ] */
    public static CBORItemList issuerAuth(final CBORItem payload) {
        return array(bytes(map(integer(1), integer(-7)).encode()), map(integer(33), bytes(CERT)), payload, bytes(SIGNATURE));
    }

    public static CBORItemList issuerAuth() {
        return issuerAuth(msoPayload());
    }

    public static CBORPairList document() {
        return textMap(
                "docType", text(DOC_TYPE),
                "issuerSigned", textMap("nameSpaces", nameSpaces(), "issuerAuth", issuerAuth()));
    }

    public static CBORPairList deviceResponse(final CBORItem... documents) {
        return textMap("version", text("1.0"), "documents", array(documents), "status", integer(0));
    }

    public static mDocInputDto input(final String mdoc, final InputType type) {
        final mDocInputDto input = new mDocInputDto();
        input.setMdoc(mdoc);
        input.setInputType(type);
        return input;
    }

    public static mDocInputDto hexInput(final CBORItem item) {
        return input(Cbor.hex(item), InputType.HEX);
    }

    /** Runs the call, checks it failed with the given code at the given path; chain message checks on the result. */
    public static AbstractThrowableAssert<?, ? extends Throwable> assertDecodeError(
            final ThrowingCallable call, final ErrorCode code, final String path) {
        final Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(MdocDecodeException.class);
        final MdocDecodeException e = (MdocDecodeException) thrown;
        assertThat(e.getCode()).as("code, message: %s", e.getMessage()).isEqualTo(code);
        assertThat(e.getPath()).as("path, message: %s", e.getMessage()).isEqualTo(path);
        return assertThat(e);
    }
}
