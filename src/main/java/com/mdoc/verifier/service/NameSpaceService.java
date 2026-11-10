package com.mdoc.verifier.service;

import java.util.List;

import com.authlete.cbor.CBORItem;
import com.mdoc.verifier.enums.ErrorCode;
import com.mdoc.verifier.exception.MdocDecodeException;
import com.mdoc.verifier.util.CborJsonConverter;
import com.mdoc.verifier.util.CborMap;
import com.mdoc.verifier.util.CborUtil;
import org.springframework.stereotype.Service;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * IssuerSigned.nameSpaces (ISO 18013-5 §8.3.2.1.2.2):
 * <pre>
 * IssuerNameSpaces      = { + NameSpace => [ + IssuerSignedItemBytes ] }
 * IssuerSignedItemBytes = #6.24(bstr .cbor IssuerSignedItem)
 * IssuerSignedItem      = { "digestID": uint, "random": bstr, "elementIdentifier": tstr, "elementValue": any }
 * </pre>
 * Generic: which elements a namespace should hold (e.g. mDL) is not checked, only the types.
 */
@Service
public class NameSpaceService {

    private static final long TAG_ENCODED_CBOR = 24; // RFC 8949 §3.4.5.1

    private static final String DIGEST_ID_KEY = "digestID";
    private static final String RANDOM_KEY = "random";
    private static final String ELEMENT_IDENTIFIER_KEY = "elementIdentifier";
    private static final String ELEMENT_VALUE_KEY = "elementValue";

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    public ObjectNode decode(final CBORItem nameSpaces, final String path) {
        final CborMap byNameSpace = CborUtil.textKeyMap(nameSpaces, path);
        if (byNameSpace.fields().isEmpty()) {
            throw new MdocDecodeException(ErrorCode.INVALID_VALUE, path, "nameSpaces must contain at least one namespace");
        }
        final ObjectNode result = JSON.objectNode();
        byNameSpace.fields().forEach((nameSpace, items) ->
                result.set(nameSpace, decodeItems(items, byNameSpace.pathOf(nameSpace))));
        return result;
    }

    private ArrayNode decodeItems(final CBORItem items, final String path) {
        final List<? extends CBORItem> list = CborUtil.nonEmptyArray(items, path);
        final ArrayNode result = JSON.arrayNode(list.size());
        for (int i = 0; i < list.size(); i++) {
            result.add(decodeItem(list.get(i), CborUtil.index(path, i)));
        }
        return result;
    }

    private ObjectNode decodeItem(final CBORItem itemBytes, final String path) {
        // Two layers: tag 24 wraps a bstr, and the bstr holds the encoded IssuerSignedItem.
        final byte[] encodedItem = CborUtil.bytes(CborUtil.untag(itemBytes, TAG_ENCODED_CBOR, path), path);
        final CborMap item = CborUtil.textKeyMap(CborUtil.decode(encodedItem, path), path);

        final ObjectNode result = JSON.objectNode();
        result.set(DIGEST_ID_KEY, JSON.numberNode(item.uint(DIGEST_ID_KEY)));
        result.set(RANDOM_KEY, CborJsonConverter.bytesToJson(item.bytes(RANDOM_KEY)));
        result.put(ELEMENT_IDENTIFIER_KEY, item.text(ELEMENT_IDENTIFIER_KEY));
        result.set(ELEMENT_VALUE_KEY,
                CborJsonConverter.toJson(item.required(ELEMENT_VALUE_KEY), item.pathOf(ELEMENT_VALUE_KEY)));
        return result;
    }
}
