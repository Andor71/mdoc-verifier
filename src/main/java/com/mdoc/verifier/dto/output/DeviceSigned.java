package com.mdoc.verifier.dto.output;

import java.util.Map;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DeviceSigned {

    // NameSpace -> (DataElementIdentifier -> DataElementValue)
    private Map<String, Map<String, Object>> nameSpaces;

    private DeviceAuth deviceAuth;
}
