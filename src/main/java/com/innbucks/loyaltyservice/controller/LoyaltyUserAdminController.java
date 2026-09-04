package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.ApiResult;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.security.TenantContext;
import com.innbucks.loyaltyservice.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Operator control over a loyalty account's fraud hold.
 *
 * <p>Exists because {@code FraudService}'s velocity rule can set
 * {@code BLOCKED} and, until now, <b>nothing in this service could ever unset
 * it</b> — an account blocked by the auto-block stayed unspendable forever
 * unless someone ran UPDATE against production by hand. That was tolerable only
 * on the assumption that a block was self-inflicted. It was not: any caller
 * could block any account by naming its id in a voucher-redeem body, so the
 * accounts needing a remedy are the ones that never did anything wrong.
 *
 * <p>Tenant-scoped through {@code X-Tenant-Id} like every other admin surface —
 * {@code UserService.require} refuses a user belonging to a different tenant, so
 * an operator can only lift holds inside their own.
 */
@RestController
@Slf4j
@RequestMapping("/loyalty/users")
@Tag(name = "Loyalty User Admin",
     description = "Operator control over a loyalty account's fraud hold. Requires X-Tenant-Id.")
public class LoyaltyUserAdminController {

    private final UserService users;
    private final TenantContext tenantContext;

    public LoyaltyUserAdminController(UserService users, TenantContext tenantContext) {
        this.users = users;
        this.tenantContext = tenantContext;
    }

    @PostMapping("/{userId}/unblock")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','MERCHANT_ADMIN')")
    @Operation(summary = "Lift a fraud hold on a loyalty account",
            description = """
                    Returns a BLOCKED account to ACTIVE so it can spend again. This is the only \
                    way out of BLOCKED — the velocity auto-block in FraudService sets it and \
                    nothing else clears it.

                    Refuses an account that is not blocked. PENDING and INACTIVE are different \
                    states with different remedies, and this must not become a general \
                    make-it-active lever that bypasses them.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Hold lifted; the account is ACTIVE again",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Fraud hold lifted",
                                      "data": {
                                        "id": "8f14e45f-ceea-467a-9ba6-7c3f0e2a1b44",
                                        "tenantId": "0a571c1c-7c75-4000-a000-000000000001",
                                        "phoneNumber": "+263771234567",
                                        "role": "END_USER",
                                        "status": "ACTIVE"
                                      }
                                    }"""))),
            @ApiResponse(responseCode = "403",
                    description = "CROSS_TENANT — the account belongs to a different tenant",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "CROSS_TENANT",
                                      "message": "user belongs to a different tenant",
                                      "data": null
                                    }"""))),
            @ApiResponse(responseCode = "404", description = "No such loyalty account",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "user not found",
                                      "data": null
                                    }"""))),
            @ApiResponse(responseCode = "409",
                    description = "USER_NOT_BLOCKED — the account is not under a fraud hold",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "USER_NOT_BLOCKED",
                                      "message": "This account is not blocked (status PENDING).",
                                      "data": null
                                    }""")))
    })
    public ResponseEntity<ApiResult<Dtos.UserResponse>> unblock(@PathVariable UUID userId) {
        UUID tenantId = tenantContext.requireTenantId();
        var user = users.unblock(tenantId, userId);
        log.info("Fraud hold lifted on loyalty user {} in tenant {}", userId, tenantId);
        return ResponseEntity.ok(ApiResult.ok("Fraud hold lifted", UserService.toResponse(user)));
    }
}
