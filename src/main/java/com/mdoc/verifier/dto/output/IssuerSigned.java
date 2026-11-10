package com.mdoc.verifier.dto.output;

import java.util.List;
import java.util.Map;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class  IssuerSigned {

    // NameSpace -> IssuerSignedItems
    private Map<String, List<IssuerSignedItem>> nameSpaces;

    private IssuerAuth issuerAuth;
}
