package com.mdoc.verifier.config;

import com.mdoc.verifier.util.CborParser;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The CBOR parser with the nesting limit from {@code mdoc.limits.max-cbor-depth}. */
@Configuration
@EnableConfigurationProperties(LimitProperties.class)
public class CborConfig {

    @Bean
    public CborParser cborParser(final LimitProperties limits) {
        return new CborParser(limits.maxCborDepth());
    }
}
