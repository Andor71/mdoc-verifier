package com.mdoc.verifier.util;

import java.util.Base64;
import java.util.HexFormat;

import com.mdoc.verifier.enums.InputType;
import lombok.NoArgsConstructor;

@NoArgsConstructor
public final class DecodeUtil {

    public static byte[] decodeMdocString(final String encodedString, final InputType inputType) {
        return switch (inputType) {
            case InputType.BASE64 -> Base64.getDecoder().decode(encodedString);
            case InputType.BASE64_URL -> Base64.getUrlDecoder().decode(encodedString);
            case InputType.HEX -> HexFormat.of().parseHex(encodedString);
            default -> throw new IllegalArgumentException("Unsupported input type: " + inputType);
        };
    }
}
