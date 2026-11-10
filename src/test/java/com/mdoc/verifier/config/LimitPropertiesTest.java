package com.mdoc.verifier.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/** A broken limit config must stop the application at startup, never run with a limit silently off. */
class LimitPropertiesTest {

    private static LimitProperties.RateLimit rateLimit(final int capacity, final Duration idleTimeout) {
        return new LimitProperties.RateLimit(capacity, 30, Duration.ofMinutes(1), 1000, idleTimeout);
    }

    @Test
    void acceptsTheShippedValues() {
        assertThatCode(() -> new LimitProperties(131072, rateLimit(10, Duration.ofMinutes(10))))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingOrNonPositiveValues() {
        // A property left out of the yaml binds as 0 or null.
        assertThatThrownBy(() -> new LimitProperties(0, rateLimit(10, Duration.ofMinutes(10))))
                .hasMessageContaining("max-body-bytes");
        assertThatThrownBy(() -> new LimitProperties(131072, null))
                .hasMessageContaining("rate-limit");
        assertThatThrownBy(() -> rateLimit(0, Duration.ofMinutes(10)))
                .hasMessageContaining("must be positive");
        assertThatThrownBy(() -> new LimitProperties.RateLimit(10, 0, Duration.ofMinutes(1), 1000, Duration.ofMinutes(10)))
                .hasMessageContaining("must be positive"); // refill-tokens; not a "/ by zero" further down
        assertThatThrownBy(() -> new LimitProperties.RateLimit(10, 30, Duration.ofMinutes(1), 0, Duration.ofMinutes(10)))
                .hasMessageContaining("must be positive"); // max-clients
        assertThatThrownBy(() -> new LimitProperties.RateLimit(10, 30, null, 1000, Duration.ofMinutes(10)))
                .hasMessageContaining("refill-period");
        assertThatThrownBy(() -> new LimitProperties.RateLimit(10, 30, Duration.ofMinutes(1), 1000, null))
                .hasMessageContaining("idle-timeout");
    }

    @Test
    void idleTimeoutMustCoverAFullRefill() {
        // 10 tokens at 30 per minute: an empty bucket is full again after 20 s.
        assertThatCode(() -> rateLimit(10, Duration.ofSeconds(20))).doesNotThrowAnyException();
        assertThatThrownBy(() -> rateLimit(10, Duration.ofSeconds(19)))
                .hasMessageContaining("idle-timeout must be at least PT20S");
    }
}
