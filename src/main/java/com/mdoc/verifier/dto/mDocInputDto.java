package com.mdoc.verifier.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NonNull;

@Data
public class mDocInputDto {

    @NotNull(message = "mdoc field is required")
    @NotEmpty(message = "mdoc field cannot be empty")
    @Size(max = 10000, message = "mdoc field cannot exceed 10000 characters")
    private String mdoc;
}
