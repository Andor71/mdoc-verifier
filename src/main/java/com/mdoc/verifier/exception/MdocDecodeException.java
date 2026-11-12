package com.mdoc.verifier.exception;

import com.mdoc.verifier.enums.ErrorCode;
import lombok.Getter;

/**
 * Decode failure: a machine-readable code and the location in the input,
 * e.g. {@code $.documents[0].issuerSigned.nameSpaces["org.iso.18013.5.1"][2].elementValue}.
 */
@Getter
public class MdocDecodeException extends RuntimeException {

    private final ErrorCode code;
    private final String path;

    public MdocDecodeException(final ErrorCode code, final String path) {
        super(code.getMessage());
        this.code = code;
        this.path = path;
    }
}
