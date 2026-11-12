package com.mdoc.verifier.service;

import java.util.List;
import java.util.Map;

import com.authlete.cbor.CBORByteArray;
import com.authlete.cbor.CBORInteger;
import com.authlete.cbor.CBORItem;
import com.authlete.cbor.CBORItemList;
import com.authlete.cbor.CBORLong;
import com.authlete.cbor.CBORNull;
import com.authlete.cbor.CBORPair;
import com.authlete.cbor.CBORString;
import com.authlete.cbor.CBORTaggedItem;
import com.mdoc.verifier.enums.ErrorCode;
import com.mdoc.verifier.exception.MdocDecodeException;
import com.mdoc.verifier.util.CborJsonConverter;
import com.mdoc.verifier.util.CborParser;
import com.mdoc.verifier.util.CborUtil;
import com.mdoc.verifier.util.DecodeWarnings;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * IssuerSigned.issuerAuth (ISO 18013-5 §9.1.2.4), a COSE_Sign1 (RFC 9052 §4.2):
 * <pre>
 * COSE_Sign1 = [ protected: bstr .cbor header_map, unprotected: header_map, payload: bstr / nil, signature: bstr ]
 * payload    = MobileSecurityObjectBytes = #6.24(bstr .cbor MobileSecurityObject)
 * </pre>
 * Decode only; the signature is not verified here. A payload that is the tag-24 item itself (no outer bstr)
 * is tolerated and reported in the warnings.
 */
@Service
@AllArgsConstructor
public class IssuerAuthService {

    private static final long TAG_COSE_SIGN1 = 18;   // RFC 9052 §2
    private static final long TAG_ENCODED_CBOR = 24; // RFC 8949 §3.4.5.1
    private static final int LABEL_X5CHAIN = 33;     // RFC 9360 §2

    // Common header parameters (RFC 9052 §3.1) and x5chain (RFC 9360), shown by name instead of number.
    private static final Map<Integer, String> HEADER_LABELS = Map.of(
            1, "alg", 2, "crit", 3, "content type", 4, "kid", 5, "IV", 6, "Partial IV", LABEL_X5CHAIN, "x5chain");

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final CborParser cborParser;

    public ObjectNode decode(final CBORItem issuerAuth, final String path, final DecodeWarnings warnings) {
        // ISO 18013-5 uses the untagged form; the tagged COSE_Sign1_Tagged form (tag 18) is accepted too.
        final CBORItem coseSign1 = CborUtil.hasTag(issuerAuth, TAG_COSE_SIGN1)
                ? ((CBORTaggedItem) issuerAuth).getTagContent()
                : issuerAuth;

        final List<? extends CBORItem> parts = CborUtil.array(coseSign1, path);
        if (parts.size() != 4) {
            throw new MdocDecodeException(ErrorCode.INVALID_VALUE, path);
        }

        final ObjectNode result = JSON.objectNode();
        result.set("protectedHeader", decodeProtectedHeader(parts.get(0), CborUtil.index(path, 0)));
        result.set("unprotectedHeader", decodeHeader(parts.get(1), CborUtil.index(path, 1)));
        result.set("payload", decodePayload(parts.get(2), CborUtil.index(path, 2), warnings));
        result.set("signature", CborJsonConverter.bytesToJson(CborUtil.bytes(parts.get(3), CborUtil.index(path, 3))));
        return result;
    }

    // The protected header is a bstr holding an encoded map; an empty bstr means "no protected headers" (RFC 9052 §3).
    private ObjectNode decodeProtectedHeader(final CBORItem item, final String path) {
        final byte[] encoded = CborUtil.bytes(item, path);
        return encoded.length == 0 ? JSON.objectNode() : decodeHeader(cborParser.decode(encoded, path), path);
    }

    // header_map = { * label => values }, label = int / tstr (RFC 9052 §3)
    private ObjectNode decodeHeader(final CBORItem item, final String path) {
        final ObjectNode result = JSON.objectNode();
        for (final CBORPair pair : CborUtil.pairs(item, path)) {
            final CBORItem label = pair.getKey();
            final String name = labelName(label, path);
            if (result.has(name)) {
                throw new MdocDecodeException(ErrorCode.DUPLICATE_KEY, CborUtil.child(path, name));
            }
            final String valuePath = CborUtil.child(path, name);
            final boolean isX5chain = label instanceof CBORInteger number && number.getValue() == LABEL_X5CHAIN;
            result.set(name, isX5chain
                    ? decodeX5chain(pair.getValue(), valuePath)
                    : CborJsonConverter.toJson(pair.getValue(), valuePath));
        }
        return result;
    }

    private static String labelName(final CBORItem label, final String path) {
        return switch (label) {
            case CBORString text -> text.getValue();
            case CBORInteger number -> HEADER_LABELS.getOrDefault(number.getValue(), number.getValue().toString());
            case CBORLong number -> number.getValue().toString();
            default -> throw CborUtil.typeError(path);
        };
    }

    // x5chain = bstr / [ 2* bstr ] (RFC 9360 §2): one DER certificate or a chain. Always returned as an array.
    private ArrayNode decodeX5chain(final CBORItem item, final String path) {
        final ArrayNode result = JSON.arrayNode();
        if (item instanceof CBORByteArray single) {
            result.add(CborJsonConverter.bytesToJson(single.getValue()));
            return result;
        }
        if (!(item instanceof CBORItemList)) {
            throw CborUtil.typeError(path);
        }
        final List<? extends CBORItem> certificates = CborUtil.array(item, path);
        for (int i = 0; i < certificates.size(); i++) {
            result.add(CborJsonConverter.bytesToJson(CborUtil.bytes(certificates.get(i), CborUtil.index(path, i))));
        }
        return result;
    }

    // Spec form, two layers: the COSE payload bstr holds #6.24(bstr), and that inner bstr holds the MSO.
    // Some issuers drop the outer bstr and put the tag-24 item straight into the array. Their signature is
    // computed over that same form, so it is decoded anyway and the non-conformance goes into the warnings.
    private JsonNode decodePayload(final CBORItem item, final String path, final DecodeWarnings warnings) {
        final CBORItem msoBytes = switch (item) {
            case CBORByteArray payload -> cborParser.decode(payload.getValue(), path);
            case CBORTaggedItem tagged when CborUtil.hasTag(tagged, TAG_ENCODED_CBOR) -> {
                warnings.add(ErrorCode.COSE_PAYLOAD_NOT_BSTR, path);
                yield tagged;
            }
            case CBORNull nil -> throw new MdocDecodeException(ErrorCode.INVALID_VALUE, path);
            default -> throw CborUtil.typeError(path);
        };
        final byte[] encodedMso = CborUtil.bytes(CborUtil.untag(msoBytes, TAG_ENCODED_CBOR, path), path);
        final CBORItem mso = cborParser.decode(encodedMso, path);

        CborUtil.textKeyMap(mso, path); // the MSO is a map with text keys: version, digestAlgorithm, valueDigests, ...
        return CborJsonConverter.toJson(mso, path);
    }
}
