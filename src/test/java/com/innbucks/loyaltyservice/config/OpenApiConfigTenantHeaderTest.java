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
    void operationAlreadyDeclaringTheHeader_isNotDuplicated() throws Exception {
        Operation op = new Operation().addParametersItem(
                new HeaderParameter().name("X-Tenant-Id"));

        op = customizer.customize(op, handler(ShopLikeController.class, "tenantScoped"));

        assertThat(op.getParameters()).hasSize(1);
    }
}
