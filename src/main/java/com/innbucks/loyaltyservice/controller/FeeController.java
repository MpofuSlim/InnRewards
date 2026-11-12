package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.ApiResult;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.security.FeesPermissions;
import com.innbucks.loyaltyservice.security.ReportsPermissions;
import com.innbucks.loyaltyservice.service.PlatformFeeSchedules;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * What merchants pay InnBucks for vouchers (V67, owner decision 2026-11-12).
 * InnBucks sets fees, never the merchant: every write here needs
 * {@code loyalty-fees:manage} or SUPER_ADMIN ({@link FeesPermissions#HAS_MANAGE}).
 *
 * <p>A tenant has a DEFAULT every merchant under it pays; a merchant can have its
 * OWN, which beats the default. Until InnBucks sets either, a merchant keeps
 * paying what it paid before (the legacy rule and merchant-record fees).
 *
 * <p>No {@code X-Tenant-Id}: the tenant or merchant is in the path, and these
 * are platform calls.
 */
@RestController
@Slf4j
@RequestMapping("/loyalty/fees")
@Tag(name = "Fees",
        description = "Voucher fees merchants pay InnBucks. Set by InnBucks only (loyalty-fees:manage or "
                + "SUPER_ADMIN): a tenant's default and any merchant's own. Merchants read their own.")
public class FeeController {

    private final PlatformFeeSchedules fees;

    public FeeController(PlatformFeeSchedules fees) {
        this.fees = fees;
    }

    // ------------------------------------------------------------------ tenant

    @GetMapping("/tenants/{tenantId}")
    @PreAuthorize(FeesPermissions.HAS_MANAGE + " or " + ReportsPermissions.HAS_READ)
    @Operation(summary = "A tenant's default voucher fees, and their history",
            description = "The default every merchant under the tenant pays, unless InnBucks set the "
                    + "merchant its own. `current` is null when InnBucks has not set a default (or withdrew "
                    + "it): merchants then pay what they paid before InnBucks managed fees."
                    + ReportsPermissions.DOC)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The tenant's default and its history",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = TENANT_200))),
            @ApiResponse(responseCode = "403", description = "Not InnBucks staff",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = FORBIDDEN_403))),
            @ApiResponse(responseCode = "404", description = "No such tenant",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = TENANT_404)))
    })
    public ResponseEntity<ApiResult<Dtos.TenantFees>> tenant(@PathVariable UUID tenantId) {
        return ResponseEntity.ok(ApiResult.ok("Tenant fees", fees.tenantFees(tenantId)));
    }

    @PutMapping("/tenants/{tenantId}")
    @PreAuthorize(FeesPermissions.HAS_MANAGE)
    @Operation(summary = "Set a tenant's default voucher fees (InnBucks only)",
            description = "Both sides, always together. Applies from now to every merchant under the "
                    + "tenant that InnBucks has not set its own fees, and replaces the fees merchants set "
                    + "for themselves before InnBucks managed them. A zero fee is allowed. Recorded with who "
                    + "set it; earlier values stay in the history.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Default set",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = TENANT_200))),
            @ApiResponse(responseCode = "400", description = "A fee that does not add up",
                    content = @Content(mediaType = "application/json",
                            examples = {
                                    @ExampleObject(name = "Missing type", value = TYPE_REQUIRED_400),
                                    @ExampleObject(name = "Percentage over 100", value = PERCENT_400),
                                    @ExampleObject(name = "Field errors", value = VALIDATION_400)})),
            @ApiResponse(responseCode = "403", description = "Not allowed to set fees",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = FORBIDDEN_403))),
            @ApiResponse(responseCode = "404", description = "No such tenant",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = TENANT_404)))
    })
    public ResponseEntity<ApiResult<Dtos.TenantFees>> setTenant(@PathVariable UUID tenantId,
                                                                @Valid @RequestBody Dtos.FeeScheduleRequest req) {
        Dtos.TenantFees out = fees.setTenantDefault(tenantId, req);
        log.info("Tenant default fees set tenantId={}", tenantId);
        return ResponseEntity.ok(ApiResult.ok("Tenant fees set", out));
    }

    @DeleteMapping("/tenants/{tenantId}")
    @PreAuthorize(FeesPermissions.HAS_MANAGE)
    @Operation(summary = "Withdraw a tenant's default voucher fees (InnBucks only)",
            description = "Merchants without their own fees from InnBucks go back to what they paid before "
                    + "InnBucks managed fees. Recorded in the history.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Default withdrawn",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = TENANT_CLEARED_200))),
            @ApiResponse(responseCode = "403", description = "Not allowed to set fees",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = FORBIDDEN_403))),
            @ApiResponse(responseCode = "404", description = "No such tenant",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = TENANT_404))),
            @ApiResponse(responseCode = "409", description = "Nothing to withdraw",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = NO_TENANT_SCHEDULE_409)))
    })
    public ResponseEntity<ApiResult<Dtos.TenantFees>> clearTenant(@PathVariable UUID tenantId) {
        Dtos.TenantFees out = fees.clearTenantDefault(tenantId);
        log.info("Tenant default fees withdrawn tenantId={}", tenantId);
        return ResponseEntity.ok(ApiResult.ok("Tenant fees withdrawn", out));
    }

    // ---------------------------------------------------------------- merchant

    @GetMapping("/merchants/{merchantId}")
    @PreAuthorize(FeesPermissions.HAS_MANAGE + " or " + ReportsPermissions.HAS_READ
            + " or hasAnyRole('MERCHANT_ADMIN','SHOP_ADMIN')")
    @Operation(summary = "What a merchant pays InnBucks for vouchers",
            description = "The fees that apply now, where each comes from (`MERCHANT_SCHEDULE` and "
                    + "`TENANT_SCHEDULE` are set by InnBucks; `MERCHANT_RULE`, `MERCHANT_RECORD` and "
                    + "`TENANT_RULE` are fees from before InnBucks managed them; `NONE` is no fee), and every "
                    + "change to the merchant's own fees. A merchant or shop admin reads its own merchant; "
                    + "`setBy` is shown to InnBucks staff only." + ReportsPermissions.DOC)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The merchant's fees",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = MERCHANT_200))),
            @ApiResponse(responseCode = "403", description = "Not this merchant's admin",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = NOT_OWNER_403))),
            @ApiResponse(responseCode = "404", description = "No such merchant",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = MERCHANT_404)))
    })
    public ResponseEntity<ApiResult<Dtos.MerchantFees>> merchant(@PathVariable UUID merchantId) {
        return ResponseEntity.ok(ApiResult.ok("Merchant fees", fees.merchantFees(merchantId)));
    }

    @PutMapping("/merchants/{merchantId}")
    @PreAuthorize(FeesPermissions.HAS_MANAGE)
    @Operation(summary = "Set a merchant's own voucher fees (InnBucks only)",
            description = "Both sides, always together. Beats the tenant's default for this merchant from "
                    + "now on. A zero fee is allowed. Recorded with who set it; earlier values stay in the "
                    + "history.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Fees set",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = MERCHANT_200))),
            @ApiResponse(responseCode = "400", description = "A fee that does not add up",
                    content = @Content(mediaType = "application/json",
                            examples = {
                                    @ExampleObject(name = "Missing type", value = TYPE_REQUIRED_400),
                                    @ExampleObject(name = "Percentage over 100", value = PERCENT_400),
                                    @ExampleObject(name = "Fixed fee on a percentage type",
                                            value = FIXED_ON_PERCENT_400),
                                    @ExampleObject(name = "Field errors", value = VALIDATION_400)})),
            @ApiResponse(responseCode = "403", description = "Not allowed to set fees",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = FORBIDDEN_403))),
            @ApiResponse(responseCode = "404", description = "No such merchant",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = MERCHANT_404)))
    })
    public ResponseEntity<ApiResult<Dtos.MerchantFees>> setMerchant(@PathVariable UUID merchantId,
                                                                    @Valid @RequestBody Dtos.FeeScheduleRequest req) {
        Dtos.MerchantFees out = fees.setMerchant(merchantId, req);
        log.info("Merchant fees set merchantId={}", merchantId);
        return ResponseEntity.ok(ApiResult.ok("Merchant fees set", out));
    }

    @DeleteMapping("/merchants/{merchantId}")
    @PreAuthorize(FeesPermissions.HAS_MANAGE)
    @Operation(summary = "Withdraw a merchant's own voucher fees (InnBucks only)",
            description = "The merchant goes back to its tenant's default (or, without one, to what it paid "
                    + "before InnBucks managed fees). Recorded in the history.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Own fees withdrawn",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = MERCHANT_200))),
            @ApiResponse(responseCode = "403", description = "Not allowed to set fees",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = FORBIDDEN_403))),
            @ApiResponse(responseCode = "404", description = "No such merchant",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = MERCHANT_404))),
            @ApiResponse(responseCode = "409", description = "Nothing to withdraw",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = NO_MERCHANT_SCHEDULE_409)))
    })
    public ResponseEntity<ApiResult<Dtos.MerchantFees>> clearMerchant(@PathVariable UUID merchantId) {
        Dtos.MerchantFees out = fees.clearMerchant(merchantId);
        log.info("Merchant fees withdrawn merchantId={}", merchantId);
        return ResponseEntity.ok(ApiResult.ok("Merchant fees withdrawn", out));
    }

    // ---------------------------------------------------------------- examples

    static final String TENANT_200 = """
            {
              "code": "200 OK",
              "message": "Tenant fees",
              "data": {
                "tenantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                "tenantName": "Simbisa Brands",
                "current": {
                  "id": "0b9e7a1c-1d2e-4f3a-8b4c-5d6e7f8a9b0c",
                  "scope": "TENANT",
                  "tenantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                  "merchantId": null,
                  "cleared": false,
                  "feeIssued": { "type": "PERCENTAGE", "fixed": 0, "percentage": 2.5 },
                  "feeRedeemed": { "type": "FIXED", "fixed": 0.10, "percentage": 0 },
                  "note": "Signed rate card, Nov 2026",
                  "setBy": "finance@innbucks.co.zw",
                  "setAt": "2026-11-12T09:00:00Z"
                },
                "history": [
                  {
                    "id": "0b9e7a1c-1d2e-4f3a-8b4c-5d6e7f8a9b0c",
                    "scope": "TENANT",
                    "tenantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                    "merchantId": null,
                    "cleared": false,
                    "feeIssued": { "type": "PERCENTAGE", "fixed": 0, "percentage": 2.5 },
                    "feeRedeemed": { "type": "FIXED", "fixed": 0.10, "percentage": 0 },
                    "note": "Signed rate card, Nov 2026",
                    "setBy": "finance@innbucks.co.zw",
                    "setAt": "2026-11-12T09:00:00Z"
                  }
                ]
              }
            }""";

    static final String TENANT_CLEARED_200 = """
            {
              "code": "200 OK",
              "message": "Tenant fees withdrawn",
              "data": {
                "tenantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                "tenantName": "Simbisa Brands",
                "current": null,
                "history": [
                  {
                    "id": "5c4d3e2f-1a0b-4c9d-8e7f-6a5b4c3d2e1f",
                    "scope": "TENANT",
                    "tenantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                    "merchantId": null,
                    "cleared": true,
                    "feeIssued": null,
                    "feeRedeemed": null,
                    "note": null,
                    "setBy": "finance@innbucks.co.zw",
                    "setAt": "2026-11-13T10:00:00Z"
                  },
                  {
                    "id": "0b9e7a1c-1d2e-4f3a-8b4c-5d6e7f8a9b0c",
                    "scope": "TENANT",
                    "tenantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                    "merchantId": null,
                    "cleared": false,
                    "feeIssued": { "type": "PERCENTAGE", "fixed": 0, "percentage": 2.5 },
                    "feeRedeemed": { "type": "FIXED", "fixed": 0.10, "percentage": 0 },
                    "note": "Signed rate card, Nov 2026",
                    "setBy": "finance@innbucks.co.zw",
                    "setAt": "2026-11-12T09:00:00Z"
                  }
                ]
              }
            }""";

    static final String MERCHANT_200 = """
            {
              "code": "200 OK",
              "message": "Merchant fees",
              "data": {
                "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                "tenantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                "merchantName": "Pizza Inn",
                "feeIssued": { "type": "PERCENTAGE", "fixed": 0, "percentage": 2.5 },
                "feeIssuedSource": "TENANT_SCHEDULE",
                "feeRedeemed": { "type": "FIXED", "fixed": 0.10, "percentage": 0 },
                "feeRedeemedSource": "TENANT_SCHEDULE",
                "merchantSchedule": null,
                "tenantSchedule": {
                  "id": "0b9e7a1c-1d2e-4f3a-8b4c-5d6e7f8a9b0c",
                  "scope": "TENANT",
                  "tenantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                  "merchantId": null,
                  "cleared": false,
                  "feeIssued": { "type": "PERCENTAGE", "fixed": 0, "percentage": 2.5 },
                  "feeRedeemed": { "type": "FIXED", "fixed": 0.10, "percentage": 0 },
                  "note": "Signed rate card, Nov 2026",
                  "setBy": "finance@innbucks.co.zw",
                  "setAt": "2026-11-12T09:00:00Z"
                },
                "history": []
              }
            }""";

    static final String TYPE_REQUIRED_400 = """
            {
              "code": "FEE_TYPE_REQUIRED",
              "message": "feeIssued: type is required",
              "data": null
            }""";

    static final String PERCENT_400 = """
            {
              "code": "FEE_PERCENT_TOO_HIGH",
              "message": "feeIssued: percentage must be 100 or less",
              "data": null
            }""";

    static final String FIXED_ON_PERCENT_400 = """
            {
              "code": "FEE_PERCENT_HAS_FIXED",
              "message": "feeRedeemed: type=PERCENTAGE requires fixed to be null or 0",
              "data": null
            }""";

    static final String VALIDATION_400 = """
            {
              "code": "400 BAD_REQUEST",
              "message": "Validation failed",
              "data": { "feeRedeemed": "must not be null" }
            }""";

    static final String FORBIDDEN_403 = """
            {
              "code": "403 FORBIDDEN",
              "message": "You don't have permission to do that.",
              "data": null
            }""";

    static final String NOT_OWNER_403 = """
            {
              "code": "NOT_MERCHANT_OWNER",
              "message": "You can only act on merchants you administer.",
              "data": null
            }""";

    static final String TENANT_404 = """
            {
              "code": "404 NOT_FOUND",
              "message": "tenant not found",
              "data": null
            }""";

    static final String MERCHANT_404 = """
            {
              "code": "404 NOT_FOUND",
              "message": "merchant not found",
              "data": null
            }""";

    static final String NO_TENANT_SCHEDULE_409 = """
            {
              "code": "NO_FEE_SCHEDULE",
              "message": "InnBucks has not set fees for this tenant, so there is nothing to withdraw.",
              "data": null
            }""";

    static final String NO_MERCHANT_SCHEDULE_409 = """
            {
              "code": "NO_FEE_SCHEDULE",
              "message": "InnBucks has not set fees for this merchant, so there is nothing to withdraw.",
              "data": null
            }""";
}
