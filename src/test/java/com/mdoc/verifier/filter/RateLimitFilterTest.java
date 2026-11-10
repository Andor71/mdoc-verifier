package com.mdoc.verifier.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import com.mdoc.verifier.config.LimitProperties;
import com.mdoc.verifier.exception.RateLimitExceededException;
import io.github.bucket4j.TimeMeter;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.ModelAndView;

/** The filter on its own, with a fake clock. Through Spring MVC: {@link RequestLimitFiltersTest}. */
class RateLimitFilterTest {

    /** Burst of 2, then 30 per minute: one token every 2 seconds. */
    private static final LimitProperties.RateLimit CONFIG =
            new LimitProperties.RateLimit(2, 30, Duration.ofMinutes(1), 1000, Duration.ofMinutes(10));

    /** Time only moves when the test says so. */
    private static final class FakeClock implements TimeMeter {
        private long nanos;

        void advance(final Duration duration) {
            nanos += duration.toNanos();
        }

        @Override
        public long currentTimeNanos() {
            return nanos;
        }

        @Override
        public boolean isWallClockBased() {
            return false;
        }
    }

    @Nested
    class Refill {

        private final FakeClock clock = new FakeClock();
        private final AtomicReference<RateLimitExceededException> rejected = new AtomicReference<>();
        private final RateLimitFilter filter = new RateLimitFilter(CONFIG, (request, response, handler, e) -> {
            rejected.set((RateLimitExceededException) e);
            return new ModelAndView();
        }, clock);

        /** True if the request reached the rest of the chain. */
        private boolean send() throws Exception {
            rejected.set(null);
            final MockFilterChain chain = new MockFilterChain(); // one chain per request: it can only run once
            final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/decode");
            request.setRemoteAddr("203.0.113.20");
            filter.doFilter(request, new MockHttpServletResponse(), chain);
            return chain.getRequest() != null;
        }

        @Test
        void burstPassesThenRetryAfterCountsDownAndTheTokenComesBack() throws Exception {
            assertThat(send()).isTrue();
            assertThat(send()).isTrue();

            assertThat(send()).isFalse();                                // t = 0: next token in 2 s
            assertThat(rejected.get().getRetryAfterSeconds()).isEqualTo(2);

            clock.advance(Duration.ofMillis(700));                       // t = 0.7 s: 1.3 s left, rounded up
            assertThat(send()).isFalse();
            assertThat(rejected.get().getRetryAfterSeconds()).isEqualTo(2);

            clock.advance(Duration.ofMillis(800));                       // t = 1.5 s: 0.5 s left, rounded up
            assertThat(send()).isFalse();
            assertThat(rejected.get().getRetryAfterSeconds()).isEqualTo(1);

            clock.advance(Duration.ofMillis(500));                       // t = 2 s: one token back
            assertThat(send()).isTrue();
            assertThat(send()).isFalse();                                // and only one
        }

        @Test
        void neverRefillsAboveTheCapacity() throws Exception {
            clock.advance(Duration.ofHours(1));                          // would be 1800 tokens without the cap

            assertThat(send()).isTrue();
            assertThat(send()).isTrue();
            assertThat(send()).isFalse();
        }
    }

    @Test
    void rejectsEvenWhenNoExceptionHandlerWritesTheResponse() throws Exception {
        final RateLimitFilter filter = new RateLimitFilter(CONFIG, (request, response, handler, e) -> null);
        final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/decode");

        for (int i = 0; i < CONFIG.capacity(); i++) {
            filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        }
        final MockHttpServletResponse response = new MockHttpServletResponse();
        final MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(chain.getRequest()).isNull();
    }

    @Nested
    class ClientKey {

        @Test
        void ipv4AddressIsItsOwnKey() {
            assertThat(RateLimitFilter.clientKey("203.0.113.7")).isEqualTo("203.0.113.7");
            assertThat(RateLimitFilter.clientKey("203.0.113.8")).isNotEqualTo(RateLimitFilter.clientKey("203.0.113.7"));
        }

        @Test
        void ipv6AddressesInOneSlash64ShareAKey() {
            final String key = RateLimitFilter.clientKey("2001:db8:1:2::1");

            assertThat(RateLimitFilter.clientKey("2001:db8:1:2:ffff:ffff:ffff:ffff")).isEqualTo(key);
            assertThat(RateLimitFilter.clientKey("2001:db8:1:2:0:0:0:1")).isEqualTo(key); // Tomcat's uncompressed form
            assertThat(RateLimitFilter.clientKey("2001:db8:1:3::1")).isNotEqualTo(key);   // next /64: another customer
        }

        @Test
        void ipv4MappedIpv6IsTheIpv4Address() {
            assertThat(RateLimitFilter.clientKey("::ffff:203.0.113.7")).isEqualTo("203.0.113.7");
        }

        @Test
        void textThatIsNoAddressIsUsedAsItIs() {
            assertThat(RateLimitFilter.clientKey("not:an:ip")).isEqualTo("not:an:ip"); // would be a DNS name to getByName
            assertThat(RateLimitFilter.clientKey("1:2:3")).isEqualTo("1:2:3");         // looks like IPv6, parse fails
        }
    }
}
