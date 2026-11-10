package com.mdoc.verifier.dto.output;

import java.time.Instant;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ValidityInfo {

    private Instant signed;

    private Instant validFrom;

    private Instant validUntil;

    private Instant expectedUpdate;
}
