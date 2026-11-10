package com.mdoc.verifier.dto.output;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class IssuerSignedItem {

    private Integer digestID;

    private String random;

    private String elementIdentifier;

    private Object elementValue;
}
