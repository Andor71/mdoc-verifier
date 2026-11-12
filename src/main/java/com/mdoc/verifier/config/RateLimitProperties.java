package com.mdoc.verifier.config;

import java.time.Duration;
import java.util.Objects;

/**
 * {@code mdoc.limits.rate-limit.*}, bound as part of {@link LimitProperties}. Token bucket per client: holds at
 * most {@code capacity} tokens (the burst) and gets {@code refillTokens} back every {@code refillPeriod}, added
 * continuously. One request takes one token.
 *
 * @param maxClients  upper bound on the buckets kept in memory
 * @param idleTimeout a bucket unused this long is dropped
 */
public record RateLimitProperties(int capacity, int refillTokens, Duration refillPeriod, int maxClients,
                                  Duration idleTimeout) {

    public RateLimitProperties {
        if (capacity <= 0 || refillTokens <= 0 || maxClients <= 0) {
            throw new IllegalArgumentException(
                    "mdoc.limits.rate-limit: capacity, refill-tokens and max-clients must be positive");
        }
        Objects.requireNonNull(refillPeriod, "mdoc.limits.rate-limit.refill-period is required");
        Objects.requireNonNull(idleTimeout, "mdoc.limits.rate-limit.idle-timeout is required");

        // A dropped bucket comes back full. Dropping it only after it had time to refill completely
        // means eviction never hands a client tokens it would not have had anyway.
        final Duration fullRefill = refillPeriod.multipliedBy(capacity).dividedBy(refillTokens);
        if (idleTimeout.compareTo(fullRefill) < 0) {
            throw new IllegalArgumentException("mdoc.limits.rate-limit.idle-timeout must be at least "
                    + fullRefill + ", the time an empty bucket needs to refill");
        }
    }
}
