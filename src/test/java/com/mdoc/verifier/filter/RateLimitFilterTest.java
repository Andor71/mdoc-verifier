package com.mdoc.verifier.filter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Which requests share a bucket. The HTTP behaviour is in {@link RequestLimitFiltersTest}. */
class RateLimitFilterTest {

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
}
