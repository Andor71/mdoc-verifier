package com.mdoc.verifier.filter;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HexFormat;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.mdoc.verifier.config.LimitProperties;
import com.mdoc.verifier.exception.RateLimitExceededException;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Token bucket per client IP (Bucket4j, in memory, so per running instance). Runs before anything reads the
 * body, so a rejected request costs a map lookup. The client IP is {@code getRemoteAddr()}: behind the ALB,
 * Tomcat fills it from X-Forwarded-For ({@code server.forward-headers-strategy: native}).
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String HEALTH_PATH = "/health";
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final int IPV6_PREFIX_BYTES = 8; // /64: what one IPv6 customer gets

    private final LimitProperties.RateLimit config;
    private final HandlerExceptionResolver exceptionResolver;
    private final Cache<String, Bucket> buckets;

    public RateLimitFilter(final LimitProperties.RateLimit config, final HandlerExceptionResolver exceptionResolver) {
        this.config = config;
        this.exceptionResolver = exceptionResolver;
        // Bounded and self-cleaning: a plain Map would grow with every new IP until the heap is gone.
        this.buckets = Caffeine.newBuilder()
                .maximumSize(config.maxClients())
                .expireAfterAccess(config.idleTimeout())
                .build();
    }

    /** The ALB health check must never get 429, or the ALB takes the task out of service. */
    @Override
    protected boolean shouldNotFilter(final HttpServletRequest request) {
        return HEALTH_PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response,
                                    final FilterChain chain) throws ServletException, IOException {
        final Bucket bucket = buckets.get(clientKey(request.getRemoteAddr()), key -> newBucket());
        final ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            chain.doFilter(request, response);
            return;
        }

        // Rounded up: "retry after 0 seconds" would invite an immediate retry that fails again.
        final long retryAfter = Math.max(1, Math.ceilDiv(probe.getNanosToWaitForRefill(), NANOS_PER_SECOND));
        // Filters run outside the DispatcherServlet, so @RestControllerAdvice never sees an exception thrown
        // here. Handing it to the resolver lets GlobalExceptionHandler write the response.
        if (exceptionResolver.resolveException(request, response, null, new RateLimitExceededException(retryAfter)) == null) {
            response.sendError(HttpStatus.TOO_MANY_REQUESTS.value()); // no handler: still reject, never fall through
        }
    }

    private Bucket newBucket() {
        return Bucket.builder()
                .addLimit(limit -> limit.capacity(config.capacity())
                        .refillGreedy(config.refillTokens(), config.refillPeriod()))
                .build();
    }

    /**
     * IPv4: the address itself. IPv6: the /64 prefix, because one customer gets a whole /64 and could
     * otherwise use a fresh address, and a fresh bucket, for every request.
     */
    static String clientKey(final String remoteAddr) {
        if (remoteAddr.indexOf(':') < 0) {
            return remoteAddr;
        }
        try {
            // With a ':' in it, getByName parses an IPv6 literal or throws; it never starts a DNS lookup.
            final InetAddress address = InetAddress.getByName(remoteAddr);
            if (address instanceof Inet4Address) {
                return address.getHostAddress(); // IPv4-mapped form, ::ffff:203.0.113.7
            }
            return HexFormat.of().formatHex(address.getAddress(), 0, IPV6_PREFIX_BYTES) + "::/64";
        } catch (final UnknownHostException e) {
            return remoteAddr;
        }
    }
}
