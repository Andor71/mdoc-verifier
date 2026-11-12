package com.mdoc.verifier.config;

import com.mdoc.verifier.filter.BodySizeFilter;
import com.mdoc.verifier.filter.RateLimitFilter;
import io.github.bucket4j.TimeMeter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Registers the request filters with an explicit order: rate limit first (only looks at the IP, cheapest),
 * then body size. Both run before the DispatcherServlet and so before Jackson reads the body.
 */
@Configuration
@EnableConfigurationProperties(LimitProperties.class)
public class FilterConfig {

    static final int RATE_LIMIT_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;
    static final int BODY_SIZE_ORDER = Ordered.HIGHEST_PRECEDENCE + 20;

    /** Spring MVC's own resolver chain, which includes the @RestControllerAdvice handlers. */
    private static final String MVC_EXCEPTION_RESOLVER = "handlerExceptionResolver";

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilter(
            final LimitProperties limits,
            @Qualifier(MVC_EXCEPTION_RESOLVER) final HandlerExceptionResolver exceptionResolver) {
        // SYSTEM_MILLISECONDS: Bucket4j's own default for local buckets
        final FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(
                new RateLimitFilter(limits.rateLimit(), exceptionResolver, TimeMeter.SYSTEM_MILLISECONDS));
        registration.setOrder(RATE_LIMIT_ORDER);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<BodySizeFilter> bodySizeFilter(
            final LimitProperties limits,
            @Qualifier(MVC_EXCEPTION_RESOLVER) final HandlerExceptionResolver exceptionResolver) {
        final FilterRegistrationBean<BodySizeFilter> registration =
                new FilterRegistrationBean<>(new BodySizeFilter(limits.maxBodyBytes(), exceptionResolver));
        registration.setOrder(BODY_SIZE_ORDER);
        return registration;
    }
}
