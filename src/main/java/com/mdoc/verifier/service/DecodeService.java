package com.mdoc.verifier.service;

import java.io.IOException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

import com.authlete.cbor.CBORItem;
import com.authlete.cbor.CBORParser;
import com.mdoc.verifier.enums.InputType;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import com.mdoc.verifier.dto.mDocInputDto;
import tools.jackson.databind.ObjectMapper;

@Service
@Slf4j
@AllArgsConstructor
public class DecodeService {

    private final ObjectMapper objectMapper;

    public JsonNode decode(final mDocInputDto inputDto) {
        return objectMapper.readTree(objectMapper.writeValueAsString(parseCBOR(inputDto))); // Placeholder for the actual decoded result
    }

    private Object parseCBOR(final mDocInputDto inputDto){

        try{
            final byte[] decodedBytes = decodeInputString(inputDto.getMdoc(), inputDto.getInputType());
            final CBORParser parser = new CBORParser(decodedBytes);

            return parser.next();
        }catch (IOException e){
            log.error("Error parsing CBOR data: {}", e.getMessage());
            throw new RuntimeException("Failed to parse CBOR data", e);
        }
    }

    private byte[] decodeInputString(final String encodedString, final InputType inputType) {
        return switch (inputType) {
            case InputType.BASE64 -> Base64.getDecoder().decode(encodedString);
            case InputType.BASE64_URL -> Base64.getUrlDecoder().decode(encodedString);
            case InputType.HEX -> HexFormat.of().parseHex(encodedString);
            default -> throw new IllegalArgumentException("Unsupported input type: " + inputType);
        };
    }
}
