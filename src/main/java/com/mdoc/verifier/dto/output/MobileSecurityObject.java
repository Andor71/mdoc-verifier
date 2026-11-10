package com.mdoc.verifier.dto.output;

import java.util.Map;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MobileSecurityObject {

    private String version;

    private String digestAlgorithm;

    // NameSpace -> (DigestID -> Digest)
    private Map<String, Map<Integer, String>> valueDigests;

    private DeviceKeyInfo deviceKeyInfo;

    private String docType;

    private ValidityInfo validityInfo;
}
