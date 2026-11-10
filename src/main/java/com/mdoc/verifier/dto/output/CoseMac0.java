package com.mdoc.verifier.dto.output;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CoseMac0 {

    private CoseHeader protectedHeader;

    private CoseHeader unprotectedHeader;

    private String tag;
}
