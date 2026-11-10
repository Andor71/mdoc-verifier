package com.mdoc.verifier.dto.output;

import java.util.Map;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DeviceKeyInfo {

    private CoseKey deviceKey;

    private KeyAuthorizations keyAuthorizations;

    private Map<Integer, Object> keyInfo;
}
