package com.innbucks.loyaltyservice.config;

import com.innbucks.loyaltyservice.security.JwtFilter;
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
                        // TODO(demo): TEMPORARY public access — revert to merchant-authenticated (@PreAuthorize + X-Tenant-Id) before production.
                        // Guest checkout is open for the demo so an unregistered
                        // walk-in flow can be shown end-to-end without a merchant
                        // JWT or X-Tenant-Id. The controller still enforces the
                        // SHOP_NOT_OWNED ownership guard when a merchant JWT IS
                        // presented.
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/loyalty/shops/*/guest-checkout").permitAll()
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
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
