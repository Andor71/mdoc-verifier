package com.mdoc.verifier.dto.output;

import java.util.List;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CoseHeader {

    private String algorithm;

    private String keyId;

    private List<String> x5chain;
}
