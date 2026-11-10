package com.mdoc.verifier.dto.output;

import java.util.List;
import java.util.Map;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class KeyAuthorizations {

    private List<String> nameSpaces;

    // NameSpace -> DataElementIdentifiers
    private Map<String, List<String>> dataElements;
}
