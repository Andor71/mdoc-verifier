package com.mdoc.verifier.dto.output;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class IssuerAuth {

    private CoseHeader protectedHeader;

    private CoseHeader unprotectedHeader;

    private MobileSecurityObject payload;

    private String signature;
}
