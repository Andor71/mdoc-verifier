package com.mdoc.verifier.dto.output;

import java.util.Map;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Document {

    private String docType;

    private IssuerSigned issuerSigned;

    private DeviceSigned deviceSigned;

    // NameSpace -> (DataElementIdentifier -> ErrorCode)
    private Map<String, Map<String, Integer>> errors;
}
