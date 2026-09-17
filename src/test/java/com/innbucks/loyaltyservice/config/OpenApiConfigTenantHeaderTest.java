package com.innbucks.loyaltyservice.config;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.method.HandlerMethod;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the tenant-header Swagger contract: tenant-scoped operations get the
 * X-Tenant-Id / X-Tenant-Code parameter refs (so the UI renders input fields),
 * while internal controllers, public (empty @SecurityRequirements) operations,
 * TenantController, and operations that already declare the header are left
 * untouched.
 */
class OpenApiConfigTenantHeaderTest {

    private final OperationCustomizer customizer = new OpenApiConfig().tenantHeaderOperationCustomizer();

    // --- fixture controllers -------------------------------------------------

    @RequestMapping("/loyalty/shops")
    static class ShopLikeController {
        @PostMapping("/x")
        public void tenantScoped() { }

        @io.swagger.v3.oas.annotations.security.SecurityRequirements({})
        @PostMapping("/y")
        public void publicOp() { }
    }

    @RequestMapping("/loyalty/internal/wallets")
    static class InternalLikeController {
        @PostMapping("/x")
        public void internalOp() { }
    }

    @RequestMapping("/loyalty/tenants")
    static class TenantController {
        @PostMapping
        public void createTenant() { }
    }

    /**
     * Shaped like the real PublicTestController: the public marker sits on the
     * CLASS, not the method. That is exactly the shape the method-level check
     * did not see, so every public-test operation rendered tenant-header fields
     * the endpoint ignores.
     */
    @io.swagger.v3.oas.annotations.security.SecurityRequirements
    @RequestMapping("/loyalty/public")
    static class PublicTestLikeController {
        @PostMapping("/customers/{phoneNumber}/points/send")
        public void send() { }
    }

    private static HandlerMethod handler(Class<?> controller, String method) throws Exception {
        return new HandlerMethod(controller.getDeclaredConstructor().newInstance(),
                controller.getDeclaredMethod(method));
    }

    // --- cases ---------------------------------------------------------------

    @Test
    void tenantScopedOperation_getsBothHeaderParameterRefs() throws Exception {
        Operation op = customizer.customize(new Operation(), handler(ShopLikeController.class, "tenantScoped"));

        assertThat(op.getParameters()).extracting("$ref").containsExactly(
                "#/components/parameters/X-Tenant-Id",
                "#/components/parameters/X-Tenant-Code");
    }

    @Test
    void publicOperation_emptySecurityRequirements_isSkipped() throws Exception {
        Operation op = customizer.customize(new Operation(), handler(ShopLikeController.class, "publicOp"));

        assertThat(op.getParameters()).isNull();
    }

    @Test
    void internalController_isSkipped() throws Exception {
        Operation op = customizer.customize(new Operation(), handler(InternalLikeController.class, "internalOp"));

        assertThat(op.getParameters()).isNull();
    }

    @Test
    void tenantController_isSkipped() throws Exception {
        Operation op = customizer.customize(new Operation(), handler(TenantController.class, "createTenant"));

        assertThat(op.getParameters()).isNull();
    }

    @Test
    void publicTestSurface_classLevelMarker_getsNoTenantHeaders() throws Exception {
        // The regression from the FE's own screenshot: X-Tenant-Id / X-Tenant-Code
        // rendered on /loyalty/public/** operations, which take no tenant header.
        Operation op = customizer.customize(new Operation(), handler(PublicTestLikeController.class, "send"));

        assertThat(op.getParameters()).isNull();
    }

    @Test
    void publicTestSurface_getsTheApiKeyHeader_andNothingElseDoes() throws Exception {
        OperationCustomizer apiKey = new OpenApiConfig().publicTestApiKeyOperationCustomizer();

        Operation pub = apiKey.customize(new Operation(), handler(PublicTestLikeController.class, "send"));
        assertThat(pub.getParameters()).extracting("$ref")
                .containsExactly("#/components/parameters/x-api-key");

        Operation shop = apiKey.customize(new Operation(), handler(ShopLikeController.class, "tenantScoped"));
        assertThat(shop.getParameters()).isNull();

        // Idempotent: a second pass does not stack a duplicate field.
        pub = apiKey.customize(pub, handler(PublicTestLikeController.class, "send"));
        assertThat(pub.getParameters()).hasSize(1);
    }

    @Test
    void operationAlreadyDeclaringTheHeader_isNotDuplicated() throws Exception {
        Operation op = new Operation().addParametersItem(
                new HeaderParameter().name("X-Tenant-Id"));

        op = customizer.customize(op, handler(ShopLikeController.class, "tenantScoped"));

        assertThat(op.getParameters()).hasSize(1);
    }
}
