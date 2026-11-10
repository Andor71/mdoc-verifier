package com.mdoc.verifier.dto.output;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CoseSign1 {

    private CoseHeader protectedHeader;

    private CoseHeader unprotectedHeader;

    private String signature;
}
