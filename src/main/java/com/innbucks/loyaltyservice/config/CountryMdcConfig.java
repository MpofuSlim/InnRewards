package com.innbucks.loyaltyservice.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/**
 * Multi-cell country pin. Mirror of user-service's {@code CountryMdcConfig};
 * see that file for the full design rationale. Every running JVM is anchored
 * to one InnBucks market via {@code INNBUCKS_COUNTRY} (default {@code ZW});
 * startup refuses to boot with an unknown value so a misconfigured cell
 * fails loudly instead of silently mis-tagging every log line.
 *
 * <p>Pairs with the {@code homeCountry} MDC key set by {@link
 * com.innbucks.loyaltyservice.security.JwtFilter}: this filter sets the
 * deployment country ({@code country}); JwtFilter sets the customer's
 * country from their JWT claim ({@code homeCountry}). A mismatch
 * (post-step-7 edge routing) is a wrong-cell signal worth alerting on.
 */
@Configuration
@Slf4j
public class CountryMdcConfig {

    public static final String MDC_KEY = "country";

    private static final Set<String> KNOWN_COUNTRIES = Set.of(
            "ZW", "KE", "ZM", "MW", "ZA", "BW", "MZ", "LS", "SZ", "NG"
    );

    private final String country;

    public CountryMdcConfig(@Value("${innbucks.country:ZW}") String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(
                    "innbucks.country (env INNBUCKS_COUNTRY) must be set to one of " + KNOWN_COUNTRIES
                            + " — refusing to start without a country pin");
        }
        String normalised = configured.trim().toUpperCase(Locale.ROOT);
        if (!KNOWN_COUNTRIES.contains(normalised)) {
            throw new IllegalStateException(
                    "innbucks.country='" + configured + "' is not a known InnBucks market. Allowed: "
                            + KNOWN_COUNTRIES + " — refusing to start with an unknown country pin");
        }
        this.country = normalised;
        log.info("[startup] loyalty-service pinned to country={}", normalised);
    }

    String getCountry() {
        return country;
    }

    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> countryMdcFilterRegistration() {
        FilterRegistrationBean<OncePerRequestFilter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request,
                                            HttpServletResponse response,
                                            FilterChain chain)
                    throws ServletException, IOException {
                MDC.put(MDC_KEY, country);
                try {
                    chain.doFilter(request, response);
                } finally {
                    MDC.remove(MDC_KEY);
                }
            }
        });
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        reg.addUrlPatterns("/*");
        return reg;
    }
}
