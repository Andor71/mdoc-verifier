package com.mdoc.verifier.config;

import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code mdoc.limits.*} from application.yaml. Checked in the constructors, so a missing or nonsensical value
 * stops the application at startup instead of silently disabling a limit.
 */
@ConfigurationProperties("mdoc.limits")
public record LimitProperties(int maxBodyBytes, int maxCborDepth, RateLimit rateLimit) {

    /**
     * Upper bound for {@code max-cbor-depth}. The header walk itself is iterative, but the authlete decoder and
     * CborJsonConverter recurse once per level and overflow the call stack at a few thousand levels; this keeps
     * the setting far below that.
     */
    public static final int MAX_CBOR_DEPTH_CEILING = 256;

    public LimitProperties {
        if (maxBodyBytes <= 0) {
            throw new IllegalArgumentException("mdoc.limits.max-body-bytes must be positive");
        }
        if (maxCborDepth <= 0 || maxCborDepth > MAX_CBOR_DEPTH_CEILING) {
            throw new IllegalArgumentException(
                    "mdoc.limits.max-cbor-depth must be between 1 and " + MAX_CBOR_DEPTH_CEILING);
        }
        Objects.requireNonNull(rateLimit, "mdoc.limits.rate-limit is required");
    }

    /**
     * Token bucket per client: holds at most {@code capacity} tokens (the burst) and gets {@code refillTokens}
     * back every {@code refillPeriod}, added continuously. One request takes one token.
     *
     * @param maxClients  upper bound on the buckets kept in memory
     * @param idleTimeout a bucket unused this long is dropped
     */
    public record RateLimit(int capacity, int refillTokens, Duration refillPeriod, int maxClients,
                            Duration idleTimeout) {

        public RateLimit {
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
}
