package com.mdoc.verifier.dto;

import com.mdoc.verifier.enums.InputType;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class mDocInputDto {

    /**
     * 100 000 characters = 75 KB of CBOR as base64, 50 KB as hex. Room for a portrait and an x5chain;
     * the sample vector without a portrait is 2.6 KB. Check against real samples and adjust.
     */
    public static final int MAX_MDOC_LENGTH = 100_000;

    @NotEmpty(message = "mdoc field cannot be empty") // rejects null too, so no @NotNull: one message, not two
    @Size(max = MAX_MDOC_LENGTH, message = "mdoc field cannot exceed {max} characters")
    private String mdoc;

    @NotNull(message = "inputType field is required")
    private InputType inputType;
}
