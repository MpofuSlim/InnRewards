package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.ApiResult;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.RedemptionRate;
import com.innbucks.loyaltyservice.security.CallerDetails;
import com.innbucks.loyaltyservice.service.RedemptionRateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The redemption formula (business-model point 4): the platform-wide points →
 * currency conversion, set by the InnBucks SUPER_ADMIN and read by every spend
 * path. A merchant sets how points are EARNED (see {@code RuleController}); this
 * is where the platform decides what a point is WORTH when redeemed — a
 * deliberately separate, super-admin-only surface, because the platform carries
 * the liability for every outstanding point.
 *
 * <p>Writes are append-only and effective-dated: setting a rate never edits the
 * prior one, so the full history (who, when, and what each past redemption was
 * valued at) is preserved. Reads are open to any authenticated staff caller so a
 * merchant/cashier UI can display "your points are worth ...", but only a
 * SUPER_ADMIN may change it.
 */
@RestController
@Slf4j
@RequestMapping("/loyalty/redemption-rate")
@Tag(name = "Redemption Rate",
     description = "The platform points→currency redemption formula (business-model point 4). " +
                   "SUPER_ADMIN sets it; every spend path values points through it. Effective-dated " +
                   "and append-only, so history and past valuations are preserved.")
public class RedemptionRateController {

    private final RedemptionRateService rateService;

    public RedemptionRateController(RedemptionRateService rateService) {
        this.rateService = rateService;
    }

    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Set the platform redemption rate (SUPER_ADMIN only)",
            description = "Records a new effective-dated points→currency rate. Append-only: the prior rate " +
                    "stays as history and any redemption already valued against it is untouched. Omit " +
                    "`effectiveFrom` for an immediate change; pass a future instant to schedule one.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201",
                    description = "Rate set",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "201 CREATED",
                                      "message": "Redemption rate set",
                                      "data": {
                                        "id": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
                                        "pointsPerUnit": 100.0000,
                                        "currency": "USD",
                                        "effectiveFrom": "2026-10-01T00:00:00Z",
                                        "createdBy": "11111111-2222-3333-4444-555555555555",
                                        "note": "Launch rate",
                                        "createdAt": "2026-09-01T14:30:00Z"
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "Non-positive rate",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "pointsPerUnit must be greater than zero — a zero or negative rate would make points free or invert their value.",
                                      "data": null
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "Caller is not a SUPER_ADMIN",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "403 FORBIDDEN", "message": "Access is denied", "data": null }
                                    """)))
    })
    public ResponseEntity<ApiResult<Dtos.RedemptionRateResponse>> setRate(
            @Valid @RequestBody Dtos.RedemptionRateRequest req) {
        RedemptionRate saved = rateService.setRate(req.pointsPerUnit(), req.currency(),
                req.effectiveFrom(), CallerDetails.currentUserId(), req.note());
        log.info("Redemption rate set currency={} pointsPerUnit={} effectiveFrom={} by={}",
                saved.getCurrency(), saved.getPointsPerUnit(), saved.getEffectiveFrom(), saved.getCreatedBy());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.of(HttpStatus.CREATED, "Redemption rate set",
                        Dtos.RedemptionRateResponse.of(saved)));
    }

    @GetMapping
    @Operation(summary = "Get the current redemption rate",
            description = "The rate in force now for the currency (default USD). Readable by any authenticated " +
                    "staff caller so a UI can show what points are worth.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "Current rate",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Current redemption rate",
                                      "data": {
                                        "id": "00000000-0000-0000-0000-000000000001",
                                        "pointsPerUnit": 100.0000,
                                        "currency": "USD",
                                        "effectiveFrom": "1970-01-01T00:00:00Z",
                                        "createdBy": null,
                                        "note": "Seeded platform default (was loyalty.points.redeem-rate=100)",
                                        "createdAt": "2026-09-01T00:00:00Z"
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "No rate configured for the currency",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "No redemption rate is configured for EUR. A platform administrator must set one before points can be redeemed in this currency.",
                                      "data": null
                                    }
                                    """)))
    })
    public ResponseEntity<ApiResult<Dtos.RedemptionRateResponse>> current(
            @RequestParam(value = "currency", required = false, defaultValue = "USD") String currency) {
        return ResponseEntity.ok(ApiResult.ok("Current redemption rate",
                Dtos.RedemptionRateResponse.of(rateService.currentRate(currency))));
    }

    @GetMapping("/history")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Redemption rate history (SUPER_ADMIN only)",
            description = "Every rate ever set for the currency, newest-effective first — the audit trail of " +
                    "who changed the platform's point valuation and when.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "History, newest-effective first",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Redemption rate history",
                                      "data": [
                                        {
                                          "id": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
                                          "pointsPerUnit": 120.0000,
                                          "currency": "USD",
                                          "effectiveFrom": "2026-10-01T00:00:00Z",
                                          "createdBy": "11111111-2222-3333-4444-555555555555",
                                          "note": "Q4 devaluation",
                                          "createdAt": "2026-09-15T09:00:00Z"
                                        },
                                        {
                                          "id": "00000000-0000-0000-0000-000000000001",
                                          "pointsPerUnit": 100.0000,
                                          "currency": "USD",
                                          "effectiveFrom": "1970-01-01T00:00:00Z",
                                          "createdBy": null,
                                          "note": "Seeded platform default (was loyalty.points.redeem-rate=100)",
                                          "createdAt": "2026-09-01T00:00:00Z"
                                        }
                                      ]
                                    }
                                    """)))
    })
    public ResponseEntity<ApiResult<List<Dtos.RedemptionRateResponse>>> history(
            @RequestParam(value = "currency", required = false, defaultValue = "USD") String currency) {
        List<Dtos.RedemptionRateResponse> out = rateService.history(currency).stream()
                .map(Dtos.RedemptionRateResponse::of).toList();
        return ResponseEntity.ok(ApiResult.ok("Redemption rate history", out));
    }
}
