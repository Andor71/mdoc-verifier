package com.mdoc.verifier.service;

import java.util.List;

import com.authlete.cbor.CBORItem;
import com.mdoc.verifier.dto.mDocInputDto;
import com.mdoc.verifier.enums.ErrorCode;
import com.mdoc.verifier.exception.MdocDecodeException;
import com.mdoc.verifier.util.CborMap;
import com.mdoc.verifier.util.CborUtil;
import com.mdoc.verifier.util.DecodeUtil;
import com.mdoc.verifier.util.DecodeWarnings;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Walks a DeviceResponse (ISO 18013-5 §8.3.2.1.2.2) and hands the two building blocks of each document
 * to their own service: nameSpaces -> {@link NameSpaceService}, issuerAuth -> {@link IssuerAuthService}.
 * <pre>
 * DeviceResponse = { "version": tstr, ? "documents": [+ Document], ? "documentErrors": [+ DocumentError], "status": uint }
 * Document       = { "docType": tstr, "issuerSigned": IssuerSigned, "deviceSigned": DeviceSigned, ? "errors": Errors }
 * IssuerSigned   = { ? "nameSpaces": IssuerNameSpaces, "issuerAuth": IssuerAuth }
 * </pre>
 */
@Service
@Slf4j
@AllArgsConstructor
public class DecodeService {
    private static final String VERSION_KEY = "version";
    private static final String DOCUMENTS_KEY = "documents";
    private static final String STATUS_KEY = "status";
    private static final String DOC_TYPE_KEY = "docType";
    private static final String ISSUER_SIGNED_KEY = "issuerSigned";
    private static final String NAME_SPACES_KEY = "nameSpaces";
    private static final String ISSUER_AUTH_KEY = "issuerAuth";
    private static final String WARNINGS_KEY = "warnings";

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final NameSpaceService nameSpaceService;
    private final IssuerAuthService issuerAuthService;

    public JsonNode decode(final mDocInputDto inputDto) {
        final CBORItem root =CborUtil.decode(decodeInput(inputDto), CborUtil.ROOT);
        final CborMap deviceResponse = CborUtil.textKeyMap(root, CborUtil.ROOT);
        final DecodeWarnings warnings = new DecodeWarnings();

        final ObjectNode result = JSON.objectNode();
        result.put(VERSION_KEY, deviceResponse.text(VERSION_KEY));
        if (deviceResponse.has(DOCUMENTS_KEY)) {
            result.set(DOCUMENTS_KEY, decodeDocuments(
                    deviceResponse.required(DOCUMENTS_KEY), deviceResponse.pathOf(DOCUMENTS_KEY), warnings));
        }
        result.set(STATUS_KEY, JSON.numberNode(deviceResponse.uint(STATUS_KEY)));
        result.set(WARNINGS_KEY, warnings.toJson()); // always present, empty when the input is conformant
        return result;
    }

    private byte[] decodeInput(final mDocInputDto inputDto) {
        try {
            return DecodeUtil.decodeMdocString(inputDto.getMdoc(), inputDto.getInputType());
        } catch (final IllegalArgumentException e) {
            throw new MdocDecodeException(ErrorCode.INVALID_INPUT_ENCODING, CborUtil.ROOT,
                    "Input is not valid " + inputDto.getInputType() + ": " + e.getMessage(), e);
        }
    }

    private ArrayNode decodeDocuments(final CBORItem documents, final String path, final DecodeWarnings warnings) {
        final List<? extends CBORItem> list = CborUtil.nonEmptyArray(documents, path);
        final ArrayNode result = JSON.arrayNode(list.size());
        for (int i = 0; i < list.size(); i++) {
            result.add(decodeDocument(list.get(i), CborUtil.index(path, i), warnings));
        }
        return result;
    }

    private ObjectNode decodeDocument(final CBORItem item, final String path, final DecodeWarnings warnings) {
        final CborMap document = CborUtil.textKeyMap(item, path);
        final String docType = document.text(DOC_TYPE_KEY);
        final CborMap issuerSigned =
                CborUtil.textKeyMap(document.required(ISSUER_SIGNED_KEY), document.pathOf(ISSUER_SIGNED_KEY));

        final ObjectNode issuerSignedJson = JSON.objectNode();
        if (issuerSigned.has(NAME_SPACES_KEY)) {
            issuerSignedJson.set(NAME_SPACES_KEY, nameSpaceService.decode(
                    issuerSigned.required(NAME_SPACES_KEY), issuerSigned.pathOf(NAME_SPACES_KEY)));
        }
        issuerSignedJson.set(ISSUER_AUTH_KEY, issuerAuthService.decode(
                issuerSigned.required(ISSUER_AUTH_KEY), issuerSigned.pathOf(ISSUER_AUTH_KEY), warnings));

        final ObjectNode result = JSON.objectNode();
        result.put(DOC_TYPE_KEY, docType);
        result.set(ISSUER_SIGNED_KEY, issuerSignedJson);
        return result;
    }
}
