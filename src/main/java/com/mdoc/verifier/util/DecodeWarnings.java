package com.mdoc.verifier.util;

import com.mdoc.verifier.enums.ErrorCode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Non-conformances the decoder tolerates instead of rejecting. One instance per request; it goes into the
 * response as {@code warnings: [{code, path, message}]}, the same code + path model the errors use.
 */
public final class DecodeWarnings {

    private final ArrayNode warnings = JsonNodeFactory.instance.arrayNode();

    public void add(final ErrorCode code, final String path) {
        warnings.addObject()
                .put("code", code.name())
                .put("path", path)
                .put("message", code.getMessage());
    }

    public ArrayNode toJson() {
        return warnings.deepCopy();
    }
}
