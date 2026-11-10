package com.mdoc.verifier.dto.output;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CoseKey {

    private String kty;

    private String crv;

    private String x;

    private String y;
}
