package com.mdoc.verifier.config;

import static com.mdoc.verifier.support.MdocFixtures.assertDecodeError;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import com.authlete.cbor.CBORItemList;
import com.mdoc.verifier.enums.ErrorCode;
import com.mdoc.verifier.support.Cbor;
import com.mdoc.verifier.util.CborParser;
import org.junit.jupiter.api.Test;

/** Plain Java for the same reason as FilterConfigTest: PIT does not credit bean methods run in a cached context. */
class CborConfigTest {

    @Test
    void parserUsesTheConfiguredDepth() {
        final LimitProperties limits = new LimitProperties(1000, 2,
                new RateLimitProperties(10, 30, Duration.ofMinutes(1), 1000, Duration.ofMinutes(10)));

        final CborParser parser = new CborConfig().cborParser(limits);

        assertThat(parser.decode(Cbor.raw(0x81, 0x81, 0x00), "$")).isInstanceOf(CBORItemList.class); // [[0]]
        assertDecodeError(() -> parser.decode(Cbor.raw(0x81, 0x81, 0x81, 0x00), "$"), ErrorCode.CBOR_TOO_DEEP, "$");
    }
}
