package com.mdoc.verifier.exception;

import com.mdoc.verifier.enums.ErrorCode;
import lombok.Getter;

/**
 * Decode failure with a machine-readable code and the location in the input,
 * e.g. {@code $.documents[0].issuerSigned.nameSpaces["org.iso.18013.5.1"][2].elementValue}.
 */
@Getter
public class MdocDecodeException extends RuntimeException {

    private final ErrorCode code;
    private final String path;

    public MdocDecodeException(final ErrorCode code, final String path, final String message) {
        super(message);
        this.code = code;
        this.path = path;
    }

    public MdocDecodeException(final ErrorCode code, final String path, final String message, final Throwable cause) {
        super(message, cause);
        this.code = code;
        this.path = path;
    }
}
