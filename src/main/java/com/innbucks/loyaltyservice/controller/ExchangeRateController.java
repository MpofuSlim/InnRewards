package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.ApiResult;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.ExchangeRate;
import com.innbucks.loyaltyservice.security.CallerDetails;
import com.innbucks.loyaltyservice.security.TenantContext;
import com.innbucks.loyaltyservice.service.ExchangeRateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * The FX table (multi-currency design: USD base + ZAR + ZWG), the sibling of
 * {@link RedemptionRateController} — with a two-scope, bank-default/tenant-
 * override model mirroring the loyalty_rules inheritance:
 *
 * <ul>
 *   <li><b>Platform rate</b> ({@code POST /}) — SUPER_ADMIN sets the default
 *       every tenant inherits (the "bank rate"; a scheduled feed job writes
 *       FEED rows at this scope in a later phase).</li>
 *   <li><b>Tenant override</b> ({@code POST /override}) — a tenant admin sets
 *       their own rate, which beats the bank rate for that tenant only.</li>
 *   <li><b>Effective rate</b> ({@code GET /}) — resolves what actually applies:
 *       tenant override when the X-Tenant header is present and one is set,
 *       else the platform rate. Readable by any authenticated staff caller so
 *       a till UI can display "1 USD = ZWG …".</li>
 * </ul>
 *
 * <p>Writes are append-only and effective-dated — setting a rate never edits a
 * prior one, so history (who set what, when, and what every past transaction
 * was valued at) is preserved.
 */
@RestController
@Slf4j
@RequestMapping("/loyalty/exchange-rates")
@Tag(name = "Exchange Rates",
     description = "The USD-base FX table for multi-currency (USD + ZAR + ZWG). Two scopes: " +
                   "SUPER_ADMIN sets the platform default every tenant inherits (the \"bank rate\", " +
                   "e.g. the daily RBZ figure for ZWG); a tenant admin can set an override that beats " +
                   "the bank rate for their tenant only. Rates are effective-dated and append-only. " +
                   "USD is the base — its rate is 1 by definition and is never stored.")
public class ExchangeRateController {

    private final ExchangeRateService fx;
    private final TenantContext tenantContext;
    private final HttpServletRequest request;

    public ExchangeRateController(ExchangeRateService fx, TenantContext tenantContext,
                                  HttpServletRequest request) {
        this.fx = fx;
        this.tenantContext = tenantContext;
        this.request = request;
    }

    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Set the platform (bank-default) USD→currency rate (SUPER_ADMIN only)",
            description = "Records a new effective-dated PLATFORM rate for a supported non-base " +
                    "currency — the default every tenant inherits unless it has its own override. " +
                    "Append-only: the prior rate stays as history and every money row already valued " +
                    "against it is untouched. Omit `effectiveFrom` for an immediate change; a future " +
                    "instant schedules one. A rate deviating beyond the sanity band from the current " +
                    "in-force rate is refused (FX_RATE_OUT_OF_BAND) unless `force=true` is sent WITH a " +
                    "`note` explaining why.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201",
                    description = "Rate set",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "201 CREATED",
                                      "message": "Exchange rate set",
                                      "data": {
                                        "id": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
                                        "tenantId": null,
                                        "currency": "ZWG",
                                        "ratePerUsd": 26.700000,
                                        "effectiveFrom": "2026-09-02T08:30:00Z",
                                        "source": "ADMIN",
                                        "createdBy": "11111111-2222-3333-4444-555555555555",
                                        "note": "RBZ interbank 2026-09-02",
                                        "createdAt": "2026-09-02T08:30:00Z"
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "Unsupported currency, base currency, bad rate, or out-of-band change",
                    content = @Content(mediaType = "application/json",
                            examples = {
                                    @ExampleObject(name = "Out of band", value = """
                                            {
                                              "code": "FX_RATE_OUT_OF_BAND",
                                              "message": "New ZWG rate 267.000000 deviates 900.00% from the current 26.700000 (band ±25%). If this is deliberate, resubmit with force=true and a note explaining why.",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Base is immutable", value = """
                                            {
                                              "code": "FX_BASE_IMMUTABLE",
                                              "message": "USD is the base currency — its rate is 1 by definition and is never stored or set.",
                                              "data": null
                                            }
                                            """)
                            })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "Caller is not a SUPER_ADMIN",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "403 FORBIDDEN", "message": "You don't have permission to do that.", "data": null }
                                    """)))
    })
    public ResponseEntity<ApiResult<Dtos.ExchangeRateResponse>> setPlatformRate(
            @Valid @RequestBody Dtos.ExchangeRateRequest req) {
        ExchangeRate saved = fx.setRate(null, req.currency(), req.ratePerUsd(), req.effectiveFrom(),
                Boolean.TRUE.equals(req.force()), CallerDetails.currentUserId(), req.note());
        log.info("Platform exchange rate set currency={} ratePerUsd={} effectiveFrom={} force={} by={}",
                saved.getCurrency(), saved.getRatePerUsd(), saved.getEffectiveFrom(),
                Boolean.TRUE.equals(req.force()), saved.getCreatedBy());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.of(HttpStatus.CREATED, "Exchange rate set",
                        Dtos.ExchangeRateResponse.of(saved)));
    }

    @PostMapping("/override")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','PLATFORM_ADMIN','SUPER_ADMIN')")
    @Operation(summary = "Set THIS tenant's USD→currency override (tenant admins)",
            description = "Records an effective-dated rate scoped to the caller's tenant (X-Tenant-Id " +
                    "header). From its effective instant it OVERRIDES the platform bank rate for this " +
                    "tenant only — the bank rate applies only while no override is in force. Same " +
                    "append-only semantics and sanity band as the platform endpoint; a tenant's FIRST " +
                    "override is banded against the bank rate it overrides. Merchant/shop roles are " +
                    "deliberately excluded: FX prices the whole tenant's money, so only tenant-level " +
                    "admins may set it.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201",
                    description = "Override set",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "201 CREATED",
                                      "message": "Tenant exchange-rate override set",
                                      "data": {
                                        "id": "8d0f7780-8536-51ef-a55c-018fd2001bf8",
                                        "tenantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "currency": "ZWG",
                                        "ratePerUsd": 27.500000,
                                        "effectiveFrom": "2026-09-02T09:00:00Z",
                                        "source": "ADMIN",
                                        "createdBy": "22222222-3333-4444-5555-666666666666",
                                        "note": "Our settlement bank's ZWG rate",
                                        "createdAt": "2026-09-02T09:00:00Z"
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "Missing tenant header, unsupported currency, base currency, bad rate, " +
                            "or out-of-band change",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Missing tenant", value = """
                                    {
                                      "code": "MISSING_TENANT",
                                      "message": "X-Tenant-Id or X-Tenant-Code header is required",
                                      "data": null
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "Caller lacks a tenant-admin role, or is not a member of the tenant",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "403 FORBIDDEN", "message": "You are not a member of this tenant.", "data": null }
                                    """)))
    })
    public ResponseEntity<ApiResult<Dtos.ExchangeRateResponse>> setTenantOverride(
            @Valid @RequestBody Dtos.ExchangeRateRequest req) {
        UUID tenantId = tenantContext.requireTenantId();
        ExchangeRate saved = fx.setRate(tenantId, req.currency(), req.ratePerUsd(), req.effectiveFrom(),
                Boolean.TRUE.equals(req.force()), CallerDetails.currentUserId(), req.note());
        log.info("Tenant exchange-rate override set tenantId={} currency={} ratePerUsd={} effectiveFrom={} force={} by={}",
                tenantId, saved.getCurrency(), saved.getRatePerUsd(), saved.getEffectiveFrom(),
                Boolean.TRUE.equals(req.force()), saved.getCreatedBy());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.of(HttpStatus.CREATED, "Tenant exchange-rate override set",
                        Dtos.ExchangeRateResponse.of(saved)));
    }

    @GetMapping
    @Operation(summary = "Get the EFFECTIVE USD→currency rate",
            description = "The rate that actually applies right now: with an X-Tenant-Id/X-Tenant-Code " +
                    "header, the tenant's own override when one is in force, else the platform bank " +
                    "rate (the response's `tenantId` tells you which — null = bank rate). Without a " +
                    "tenant header, the platform rate alone. Readable by any authenticated staff " +
                    "caller so a till UI can display the conversion. USD itself is refused " +
                    "(FX_BASE_IMMUTABLE — the base is 1 by definition); a supported currency with no " +
                    "rate anywhere returns NO_FX_RATE.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "Effective rate (tenantId null = the platform bank rate; set = this tenant's override)",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Current exchange rate",
                                      "data": {
                                        "id": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
                                        "tenantId": null,
                                        "currency": "ZWG",
                                        "ratePerUsd": 26.700000,
                                        "effectiveFrom": "2026-09-02T08:30:00Z",
                                        "source": "ADMIN",
                                        "createdBy": "11111111-2222-3333-4444-555555555555",
                                        "note": "RBZ interbank 2026-09-02",
                                        "createdAt": "2026-09-02T08:30:00Z"
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "No rate configured yet, unsupported currency, or the base currency",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "No rate yet", value = """
                                    {
                                      "code": "NO_FX_RATE",
                                      "message": "No exchange rate is configured for ZWG. A platform administrator must set USD→ZWG before this currency can be priced.",
                                      "data": null
                                    }
                                    """)))
    })
    public ResponseEntity<ApiResult<Dtos.ExchangeRateResponse>> current(
            @RequestParam("currency") String currency) {
        return ResponseEntity.ok(ApiResult.ok("Current exchange rate",
                Dtos.ExchangeRateResponse.of(fx.currentRate(optionalTenantId(), currency))));
    }

    @GetMapping("/history")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Exchange-rate history for a currency (SUPER_ADMIN only)",
            description = "Every rate ever set for the currency across ALL scopes, newest-effective " +
                    "first — the audit trail of who changed FX and when. `tenantId` null marks platform " +
                    "(bank-default) rows; set marks a tenant's override. `source` distinguishes ADMIN " +
                    "(operator) rows from FEED (automated) rows.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "History, newest-effective first",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Exchange rate history",
                                      "data": [
                                        {
                                          "id": "8d0f7780-8536-51ef-a55c-018fd2001bf8",
                                          "tenantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                          "currency": "ZWG",
                                          "ratePerUsd": 27.500000,
                                          "effectiveFrom": "2026-09-02T09:00:00Z",
                                          "source": "ADMIN",
                                          "createdBy": "22222222-3333-4444-5555-666666666666",
                                          "note": "Our settlement bank's ZWG rate",
                                          "createdAt": "2026-09-02T09:00:00Z"
                                        },
                                        {
                                          "id": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
                                          "tenantId": null,
                                          "currency": "ZWG",
                                          "ratePerUsd": 26.700000,
                                          "effectiveFrom": "2026-09-02T08:30:00Z",
                                          "source": "ADMIN",
                                          "createdBy": "11111111-2222-3333-4444-555555555555",
                                          "note": "RBZ interbank 2026-09-02",
                                          "createdAt": "2026-09-02T08:30:00Z"
                                        }
                                      ]
                                    }
                                    """)))
    })
    public ResponseEntity<ApiResult<List<Dtos.ExchangeRateResponse>>> history(
            @RequestParam("currency") String currency) {
        List<Dtos.ExchangeRateResponse> out = fx.history(currency).stream()
                .map(Dtos.ExchangeRateResponse::of).toList();
        return ResponseEntity.ok(ApiResult.ok("Exchange rate history", out));
    }

    /**
     * Tenant scope for the effective-rate read: honoured (with the full
     * membership check) when the caller sent a tenant header, platform scope
     * otherwise. Sniffing the headers first keeps the endpoint usable by
     * platform staff who carry no tenant — {@link TenantContext#requireTenant}
     * would 400 them — while a caller WITH a header still goes through
     * membership verification rather than being trusted on the raw value.
     */
    private UUID optionalTenantId() {
        String id = request.getHeader("X-Tenant-Id");
        String code = request.getHeader("X-Tenant-Code");
        boolean hasTenantHeader = (id != null && !id.isBlank()) || (code != null && !code.isBlank());
        return hasTenantHeader ? tenantContext.requireTenantId() : null;
    }
}
