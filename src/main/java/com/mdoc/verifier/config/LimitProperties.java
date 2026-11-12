package com.mdoc.verifier.config;

import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code mdoc.limits.*} from application.yaml. Checked in the constructors, so a missing or nonsensical value
 * stops the application at startup instead of silently disabling a limit.
 */
@ConfigurationProperties("mdoc.limits")
public record LimitProperties(int maxBodyBytes, int maxCborDepth, RateLimitProperties rateLimit) {

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
}
