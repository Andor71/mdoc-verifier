package com.mdoc.verifier.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.servlet.filter.OrderedFormContentFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * The filter order as plain Java. The HTTP-level check (RequestLimitFiltersTest.rateLimitIsCheckedBeforeTheBodySize)
 * goes through a cached Spring context, so PIT credits these bean methods only to whichever test happened to start
 * that context and would not run the order check against a mutant here.
 */
class FilterConfigTest {

    private final LimitProperties limits = new LimitProperties(1000, 32,
            new RateLimitProperties(10, 30, Duration.ofMinutes(1), 1000, Duration.ofMinutes(10)));
    private final HandlerExceptionResolver resolver = (request, response, handler, e) -> null;
    private final FilterConfig config = new FilterConfig();

    @Test
    void rateLimitRunsBeforeTheBodySizeCheck() {
        final int rateLimit = config.rateLimitFilter(limits, resolver).getOrder();
        final int bodySize = config.bodySizeFilter(limits, resolver).getOrder();

        assertThat(rateLimit).isLessThan(bodySize); // lower order = earlier in the chain
    }

    @Test
    void bodySizeCheckRunsBeforeAnySpringFilterThatReadsTheBody() {
        // FormContentFilter parses form-encoded PUT/PATCH/DELETE bodies, without a size limit of its own.
        final int bodySize = config.bodySizeFilter(limits, resolver).getOrder();

        assertThat(bodySize).isLessThan(OrderedFormContentFilter.DEFAULT_ORDER);
    }
}
