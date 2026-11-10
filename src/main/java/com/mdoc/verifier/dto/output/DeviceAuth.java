package com.mdoc.verifier.dto.output;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DeviceAuth {

    private CoseSign1 deviceSignature;

    private CoseMac0 deviceMac;
}
