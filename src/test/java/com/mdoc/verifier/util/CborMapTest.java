package com.mdoc.verifier.util;

import static com.mdoc.verifier.support.Cbor.bytes;
import static com.mdoc.verifier.support.Cbor.integer;
import static com.mdoc.verifier.support.Cbor.text;
import static com.mdoc.verifier.support.Cbor.textMap;
import static com.mdoc.verifier.support.MdocFixtures.assertDecodeError;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;

import com.mdoc.verifier.enums.ErrorCode;
import org.junit.jupiter.api.Test;

class CborMapTest {

    private final CborMap map = CborUtil.textKeyMap(
            textMap("name", text("Alma"), "data", bytes(0x01), "count", integer(3), "org.example", text("x")), "$.m");

    @Test
    void typedGettersReturnValues() {
        assertThat(map.has("name")).isTrue();
        assertThat(map.has("missing")).isFalse();
        assertThat(map.text("name")).isEqualTo("Alma");
        assertThat(map.bytes("data")).containsExactly(0x01);
        assertThat(map.uint("count")).isEqualTo(BigInteger.valueOf(3));
    }

    @Test
    void missingFieldIsReportedAtTheMap() {
        assertDecodeError(() -> map.required("missing"), ErrorCode.MISSING_FIELD, "$.m")
                .hasMessage("Missing required field 'missing'");
    }

    @Test
    void wrongTypeIsReportedAtTheField() {
        assertDecodeError(() -> map.text("count"), ErrorCode.UNEXPECTED_TYPE, "$.m.count");
        assertDecodeError(() -> map.bytes("name"), ErrorCode.UNEXPECTED_TYPE, "$.m.name");
        assertDecodeError(() -> map.uint("name"), ErrorCode.UNEXPECTED_TYPE, "$.m.name");
    }

    @Test
    void pathOfQuotesKeysWithDots() {
        assertThat(map.pathOf("name")).isEqualTo("$.m.name");
        assertThat(map.pathOf("org.example")).isEqualTo("$.m[\"org.example\"]");
    }
}
