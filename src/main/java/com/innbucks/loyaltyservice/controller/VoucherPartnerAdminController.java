package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.ApiResult;
import com.innbucks.loyaltyservice.dto.PartnerAdminDtos;
import com.innbucks.loyaltyservice.service.partner.VoucherPartnerAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * SUPER_ADMIN setup of the partner voucher API (V62): partners (calling
 * systems), their public keys, and the merchants each may act for. No
 * {@code X-Tenant-Id}: a partner spans tenants.
 */
@RestController
@RequestMapping("/loyalty/admin/voucher-partners")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@Tag(name = "Partner vouchers: setup", description = "SUPER_ADMIN only. Register a partner, its keys and its merchants.")
public class VoucherPartnerAdminController {

    static final String PARTNER_DATA = """
            {
                "partnerId": "6a1f9c3e-0d4b-4f7a-9e21-3b5c7d8e9f10",
                "name": "InnBucks middleware",
                "issuer": "innbucks-middleware",
                "allowedIps": ["203.0.113.10"],
                "status": "ACTIVE",
                "keys": [
                  { "kid": "mw-stg-2026-10", "alg": "ES256", "status": "ACTIVE",
                    "createdAt": "2026-10-26T09:00:00Z", "revokedAt": null }
                ],
                "merchants": [
                  { "merchantCode": "MWOS", "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                    "tenantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "status": "ACTIVE",
                    "requireTerminal": false, "bindRedeemToHolder": true,
                    "fundingMethods": ["CASH", "CARD_SWIPE", "MERCHANT_COLLECTED", "INNBUCKS_CODE"],
                    "maxValue": 500.00, "perPhoneDailyCount": 10, "perPhoneDailyValue": 1000.00 }
                ]
            }""";
    static final String LIST = "{ \"code\": \"200 OK\", \"message\": \"Partners\", \"data\": [" + PARTNER_DATA + "] }";
    static final String CREATED = "{ \"code\": \"201 CREATED\", \"message\": \"Partner registered\", \"data\": " + PARTNER_DATA + " }";
    static final String UPDATED = "{ \"code\": \"200 OK\", \"message\": \"Partner updated\", \"data\": " + PARTNER_DATA + " }";
    static final String KEY_ADDED = "{ \"code\": \"200 OK\", \"message\": \"Key added\", \"data\": " + PARTNER_DATA + " }";
    static final String KEY_REVOKED = "{ \"code\": \"200 OK\", \"message\": \"Key revoked\", \"data\": " + PARTNER_DATA + " }";
    static final String LINKED = "{ \"code\": \"200 OK\", \"message\": \"Merchant linked\", \"data\": " + PARTNER_DATA + " }";
    static final String MERCHANT_UPDATED = "{ \"code\": \"200 OK\", \"message\": \"Merchant updated\", \"data\": " + PARTNER_DATA + " }";
    static final String BAD_KEY = """
            {
              "code": "BAD_PUBLIC_KEY",
              "message": "ES256 needs a P-256 (prime256v1) public key.",
              "data": null
            }""";
    static final String FORBIDDEN = """
            {
              "code": "403 FORBIDDEN",
              "message": "Forbidden - insufficient role or not the tenant owner",
              "data": null
            }""";
    static final String NOT_FOUND = """
            {
              "code": "404 NOT_FOUND",
              "message": "partner not found",
              "data": null
            }""";
    static final String CONFLICT = """
            {
              "code": "MERCHANT_ALREADY_LINKED",
              "message": "This merchant, or this merchant code, is already linked to the partner.",
              "data": null
            }""";

    private final VoucherPartnerAdminService service;

    public VoucherPartnerAdminController(VoucherPartnerAdminService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List partners with their keys and merchants")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Partners",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = LIST))),
            @ApiResponse(responseCode = "403", description = "Not a SUPER_ADMIN",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = FORBIDDEN)))
    })
    public ResponseEntity<ApiResult<List<PartnerAdminDtos.PartnerView>>> list() {
        return ResponseEntity.ok(ApiResult.ok("Partners", service.list()));
    }

    @PostMapping
    @Operation(summary = "Register a partner (a calling system, e.g. the InnBucks middleware)")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Registered",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = CREATED))),
            @ApiResponse(responseCode = "400", description = "BAD_IP: an allow-list entry is not an IP address or CIDR range",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = """
                                    { "code": "BAD_IP", "message": "Not an IP address or CIDR range: localhost", "data": null }"""))),
            @ApiResponse(responseCode = "403", description = "Not a SUPER_ADMIN",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = FORBIDDEN))),
            @ApiResponse(responseCode = "409", description = "PARTNER_EXISTS",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = """
                                    { "code": "PARTNER_EXISTS", "message": "A partner with this issuer already exists.", "data": null }""")))
    })
    public ResponseEntity<ApiResult<PartnerAdminDtos.PartnerView>> create(
            @Valid @RequestBody PartnerAdminDtos.CreatePartnerRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.created("Partner registered", service.create(req)));
    }

    @PutMapping("/{partnerId}")
    @Operation(summary = "Change a partner's allowed IPs or status (SUSPENDED refuses every call at once)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = UPDATED))),
            @ApiResponse(responseCode = "403", description = "Not a SUPER_ADMIN",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "No such partner",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = NOT_FOUND)))
    })
    public ResponseEntity<ApiResult<PartnerAdminDtos.PartnerView>> update(
            @PathVariable UUID partnerId, @RequestBody PartnerAdminDtos.UpdatePartnerRequest req) {
        return ResponseEntity.ok(ApiResult.ok("Partner updated", service.update(partnerId, req)));
    }

    @PostMapping("/{partnerId}/keys")
    @Operation(summary = "Register one of the partner's PUBLIC keys",
            description = "Several keys can be active at once, so a rotation causes no downtime: add the new key, "
                    + "let the partner switch, then revoke the old one.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Key added",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = KEY_ADDED))),
            @ApiResponse(responseCode = "400", description = "Not a usable public key for the algorithm",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = BAD_KEY))),
            @ApiResponse(responseCode = "403", description = "Not a SUPER_ADMIN",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "No such partner",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = NOT_FOUND))),
            @ApiResponse(responseCode = "409", description = "KID_EXISTS",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = """
                                    { "code": "KID_EXISTS", "message": "This key id is already registered.", "data": null }""")))
    })
    public ResponseEntity<ApiResult<PartnerAdminDtos.PartnerView>> addKey(
            @PathVariable UUID partnerId, @Valid @RequestBody PartnerAdminDtos.AddKeyRequest req) {
        return ResponseEntity.ok(ApiResult.ok("Key added", service.addKey(partnerId, req)));
    }

    @PostMapping("/{partnerId}/keys/{kid}/revoke")
    @Operation(summary = "Revoke a key: requests signed with it are refused at once")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Revoked",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = KEY_REVOKED))),
            @ApiResponse(responseCode = "403", description = "Not a SUPER_ADMIN",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "No such partner or key",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = NOT_FOUND)))
    })
    public ResponseEntity<ApiResult<PartnerAdminDtos.PartnerView>> revokeKey(@PathVariable UUID partnerId,
                                                                            @PathVariable String kid) {
        return ResponseEntity.ok(ApiResult.ok("Key revoked", service.revokeKey(partnerId, kid)));
    }

    @PostMapping("/{partnerId}/merchants")
    @Operation(summary = "Let the partner act for a merchant",
            description = "The merchant is then in escrow mode: its vouchers exist only through the partner, and "
                    + "free issue, till purchase orders, till/app redemption and transfers are refused for it.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Linked",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = LINKED))),
            @ApiResponse(responseCode = "400", description = "A bad code, funding method or limit",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = """
                                    { "code": "BAD_FUNDING_METHOD", "message": "Unknown funding method CHEQUE.", "data": null }"""))),
            @ApiResponse(responseCode = "403", description = "Not a SUPER_ADMIN",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "No such partner or merchant",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = NOT_FOUND))),
            @ApiResponse(responseCode = "409", description = "Already linked",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = CONFLICT)))
    })
    public ResponseEntity<ApiResult<PartnerAdminDtos.PartnerView>> linkMerchant(
            @PathVariable UUID partnerId, @Valid @RequestBody PartnerAdminDtos.LinkMerchantRequest req) {
        return ResponseEntity.ok(ApiResult.ok("Merchant linked", service.linkMerchant(partnerId, req)));
    }

    @PutMapping("/{partnerId}/merchants/{merchantCode}")
    @Operation(summary = "Change a merchant's settings for the partner, or suspend it")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = MERCHANT_UPDATED))),
            @ApiResponse(responseCode = "403", description = "Not a SUPER_ADMIN",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "No such partner or merchant link",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = NOT_FOUND)))
    })
    public ResponseEntity<ApiResult<PartnerAdminDtos.PartnerView>> updateMerchant(
            @PathVariable UUID partnerId, @PathVariable String merchantCode,
            @RequestBody PartnerAdminDtos.UpdateMerchantRequest req) {
        return ResponseEntity.ok(ApiResult.ok("Merchant updated", service.updateMerchant(partnerId, merchantCode, req)));
    }
}
