package com.innbucks.loyaltyservice.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

@Configuration
public class OpenApiConfig {

    /**
     * Path prefix the public edge mounts the fleet under (e.g. {@code /foundry}
     * on dtx.innbucks.co.zw). Swagger UI resolves this server URL in the BROWSER
     * against the public origin, so it must carry the prefix even though nginx
     * strips it before the request reaches any service. Blank (the default)
     * falls back to "/" — the domain-root behavior for local dev.
     */
    @Value("${PUBLIC_API_PREFIX:}")
    private String publicApiPrefix;

    @Bean
    public OpenAPI openAPI() {
        final String securitySchemeName = "bearerAuth";

        Server server = new Server();
        server.setUrl(publicApiPrefix.isBlank() ? "/" : publicApiPrefix);
        server.setDescription("Gateway relative server");

        return new OpenAPI()
                .servers(List.of(server))
                .info(new Info()
                        .title("Loyalty & Voucher Management Platform")
                        .version("1.0.0")
                        .description("""
                                Multi-tenant Loyalty & Voucher Management Platform (LVMP).

                                **Core domains** (each grouped under its own tag below):
                                - Tenants — top-level platform tenants
                                - Merchants — branded brands/outlets that issue points & vouchers
                                - Rules & Campaigns — earn-rate rules and time-bound multipliers
                                - Transactions — earn / redeem / adjust / reverse / transfer points
                                - Vouchers — templates, issuance, redemption, anti-fraud
                                - QR — signed merchant earn / P2P transfer tokens
                                - Invoicing — periodic merchant billing
                                - Reporting — operator/tenant/merchant/user dashboards
                                - Mini-apps — SuperApp shell manifest

                                **Tenant header (required on every tenant-scoped endpoint):**
                                send either `X-Tenant-Id: <uuid>` OR `X-Tenant-Code: <slug>`. The only
                                endpoints that do NOT require a tenant header are
                                `POST /loyalty/tenants` and `GET /loyalty/tenants` (operator-level).

                                **Identity boundary:** loyalty-service does NOT own user identity.
                                Customers register and are stored in user-service. This service only
                                holds a per-tenant projection (LoyaltyUser) keyed by phone number.
                                Wallets, transactions, and vouchers all reference that projection's
                                internal UUID, NOT the user-service userId directly.
                                """))
                .addSecurityItem(new SecurityRequirement().addList(securitySchemeName))
                .components(new Components()
                        .addSecuritySchemes(securitySchemeName,
                                new SecurityScheme()
                                        .name(securitySchemeName)
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT"))
                        .addParameters("X-Tenant-Id",
                                new HeaderParameter()
                                        .name("X-Tenant-Id")
                                        .description("Tenant UUID — required on every tenant-scoped endpoint (alternative to X-Tenant-Code).")
                                        .required(false)
                                        .schema(new StringSchema().format("uuid")))
                        .addParameters("X-Tenant-Code",
                                new HeaderParameter()
                                        .name("X-Tenant-Code")
                                        .description("Tenant short code — required on every tenant-scoped endpoint (alternative to X-Tenant-Id).")
                                        .required(false)
                                        .schema(new StringSchema())));
    }

    /**
     * Attaches the {@code X-Tenant-Id} / {@code X-Tenant-Code} header
     * parameters (defined as reusable components above) to every tenant-scoped
     * operation, so Swagger UI renders actual input fields for them. Before
     * this, the components existed but were never referenced — the description
     * SAID "requires X-Tenant-Id" while the Try-it-out form gave you nowhere to
     * type it, making tenant-scoped endpoints untestable from the UI.
     *
     * <p>Skipped (no tenant header field rendered):
     * <ul>
     *   <li>{@code /loyalty/internal/**} — S2S endpoints gated by
     *       X-Internal-Token, not a tenant header;</li>
     *   <li>operations marked public with an empty method-level
     *       {@code @SecurityRequirements} (e.g. guest-checkout) — the tenant
     *       comes from the resource itself;</li>
     *   <li>{@code TenantController} — tenant discovery/creation is what you
     *       call BEFORE you have a tenant to scope by;</li>
     *   <li>operations that already declare an X-Tenant-Id parameter
     *       explicitly (no duplicates).</li>
     * </ul>
     */
    @Bean
    public OperationCustomizer tenantHeaderOperationCustomizer() {
        return (operation, handlerMethod) -> {
            Class<?> controller = handlerMethod.getBeanType();
            if ("TenantController".equals(controller.getSimpleName())) {
                return operation;
            }
            RequestMapping classMapping = controller.getAnnotation(RequestMapping.class);
            if (classMapping != null && classMapping.value().length > 0
                    && classMapping.value()[0].startsWith("/loyalty/internal")) {
                return operation;
            }
            io.swagger.v3.oas.annotations.security.SecurityRequirements publicMarker =
                    handlerMethod.getMethodAnnotation(
                            io.swagger.v3.oas.annotations.security.SecurityRequirements.class);
            if (publicMarker != null && publicMarker.value().length == 0) {
                return operation;
            }
            boolean alreadyDeclared = operation.getParameters() != null
                    && operation.getParameters().stream()
                            .anyMatch(p -> "X-Tenant-Id".equals(p.getName()));
            if (!alreadyDeclared) {
                operation.addParametersItem(new Parameter().$ref("#/components/parameters/X-Tenant-Id"));
                operation.addParametersItem(new Parameter().$ref("#/components/parameters/X-Tenant-Code"));
            }
            return operation;
        };
    }
}
