package com.innbucks.loyaltyservice.config;

import com.innbucks.loyaltyservice.security.JwtFilter;
import com.innbucks.loyaltyservice.security.MetricsScrapeAuthFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtFilter jwtFilter;
    private final MetricsScrapeAuthFilter metricsScrapeAuthFilter;

    // CORS lives exclusively on the api-gateway (globalcors + RemoveResponseHeader
    // filters per PR #182). Browsers only ever talk to the gateway, so a per-service
    // CorsConfigurationSource here would just emit a second set of headers that
    // collide with the gateway's and trip its DefaultCorsProcessor with "Invalid
    // CORS request". Don't re-introduce a service-level CORS config without also
    // un-doing the gateway-side strip.

    /**
     * The loyalty service can be reached either through the API gateway (which
     * forwards JWTs in the Authorization header) or directly. We verify the
     * JWT via {@link JwtFilter} so downstream {@code @PreAuthorize} checks and
     * {@link com.innbucks.loyaltyservice.security.TenantContext}'s ownership
     * lookup both have a real {@code Authentication} to inspect.
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(
                                // Only health + info are anonymous — every other
                                // actuator endpoint (notably /actuator/prometheus,
                                // which exposes business metrics like points
                                // earned/redeemed and voucher counts) must be
                                // authenticated. Loosen explicitly per endpoint,
                                // not via /actuator/**.
                                "/actuator/health",
                                "/actuator/health/**",
                                "/actuator/info",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/error"
                        ).permitAll()
                        // Service-to-service endpoints under /loyalty/internal/**
                        // are gated by a shared-secret header in their controllers
                        // rather than the user JWT. The JwtFilter also skips this
                        // path so no Authentication is required.
                        .requestMatchers("/loyalty/internal/**").permitAll()
                        // Partner registration (V40). Same posture as the
                        // internal endpoints: the controller authenticates its
                        // own caller — a signed assertion, or a shared key
                        // compared in constant time — so Spring Security must
                        // not 401 the call before that check ever runs.
                        //
                        // Scoped to the exact method + path, not the /loyalty/partner
                        // prefix, so anything else added under that prefix later
                        // falls through to .anyRequest().authenticated() and is
                        // closed by default rather than open by inheritance.
                        //
                        // Unlike /loyalty/internal/**, this one is MEANT to be
                        // reachable from the public edge — the asserting party
                        // runs outside the cluster. It is inert until
                        // loyalty.registration.partner.enabled is true (404), and
                        // refuses every call until key material is provisioned
                        // (503).
                        .requestMatchers(HttpMethod.POST, "/loyalty/partner/registrations").permitAll()
                        // Session renewal (V43). The credential is the refresh
                        // token in the request body, not a bearer — and the
                        // access token these calls exist to replace has usually
                        // expired by the time they are made, so requiring a live
                        // bearer would make renewal possible only while renewal
                        // was unnecessary.
                        //
                        // Scoped to the exact method + path, so the sibling
                        // /loyalty/session/exchange (which DOES require a
                        // phone-scoped bearer, and further checks the scope
                        // marker with @PreAuthorize) falls through to
                        // .anyRequest().authenticated() and stays closed.
                        //
                        // The refresh token is 32 random bytes and every
                        // refusal is one opaque 401, so there is nothing to
                        // enumerate here; the gateway additionally fronts both
                        // paths with an IP-keyed fail-safe rate limiter.
                        .requestMatchers(HttpMethod.POST, "/loyalty/session/refresh").permitAll()
                        .requestMatchers(HttpMethod.POST, "/loyalty/session/logout").permitAll()
                        // TEST-ONLY unauthenticated endpoints (PublicTestController).
                        // Deliberately anonymous so a frontend can be built against
                        // real data before its auth flow exists. The controller
                        // itself is inert unless `loyalty.public-test.enabled` is
                        // true — default false — so permitAll here exposes nothing
                        // on a cell that hasn't opted in.
                        //
                        // This was GET-only while the prefix was read-only. It now
                        // covers writes too (points transfer/redeem, voucher
                        // transfer/redeem), because the frontend needs to exercise
                        // those flows before its auth flow exists. That is a real
                        // widening: on a cell with the switch ON, anyone who can
                        // guess a phone number can SPEND that customer's points and
                        // vouchers, not merely read them. The switch defaulting to
                        // false, and being set per-host in the gitignored
                        // cell.<iso>.local.env rather than the shared ConfigMap, is
                        // what keeps that off production.
                        .requestMatchers("/loyalty/public/**").permitAll()
                        // Loyalty endpoints require authentication. Method-level
                        // @PreAuthorize on the controllers further restricts who
                        // can call what; TenantContext enforces tenant ownership
                        // on the X-Tenant-Id header.
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) -> {
                            res.setContentType("application/json");
                            res.setStatus(401);
                            res.getWriter().write(
                                    "{\"code\":\"401 UNAUTHORIZED\",\"message\":\"Invalid or missing token\",\"data\":null}"
                            );
                        })
                        .accessDeniedHandler((req, res, e) -> {
                            res.setContentType("application/json");
                            res.setStatus(403);
                            res.getWriter().write(
                                    "{\"code\":\"403 FORBIDDEN\",\"message\":\"Forbidden - insufficient role or not the tenant owner\",\"data\":null}"
                            );
                        })
                )
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                // Static-token auth for the Prometheus scraper on /actuator/prometheus
                // (see MetricsScrapeAuthFilter). No-ops for every other request.
                .addFilterBefore(metricsScrapeAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
