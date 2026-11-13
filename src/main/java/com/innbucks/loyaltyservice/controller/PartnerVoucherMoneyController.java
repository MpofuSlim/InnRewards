package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.ApiResult;
import com.innbucks.loyaltyservice.dto.PartnerMoneyDtos;
import com.innbucks.loyaltyservice.security.CallerDetails;
import com.innbucks.loyaltyservice.security.ReportsPermissions;
import com.innbucks.loyaltyservice.service.partner.PartnerHoldingReconciliation;
import com.innbucks.loyaltyservice.service.partner.PartnerLedger;
import com.innbucks.loyaltyservice.service.partner.PartnerVoucherStatements;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Partner voucher money (V70): who is owed what, and whether the holding
 * account adds up. No {@code X-Tenant-Id}: partner vouchers cross tenants (a
 * MWOS voucher used at Bola Bet), so a merchant is named in the path and checked
 * against the caller.
 */
@RestController
@RequestMapping("/loyalty/partner-vouchers")
@Tag(name = "Partner vouchers: money",
        description = "Statements (what was used, paid and owed) and the daily holding reconciliation.")
public class PartnerVoucherMoneyController {

    private static final Logger log = LoggerFactory.getLogger(PartnerVoucherMoneyController.class);

    static final String STATEMENT_200 = """
            {
              "code": "200 OK",
              "message": "Partner voucher statement",
              "data": {
                "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                "merchantName": "MWOS",
                "from": "2026-11-01",
                "to": "2026-11-13",
                "generatedAt": "2026-11-13T10:02:11Z",
                "currencies": [
                  {
                    "currency": "USD",
                    "redeemedHere": { "amount": 190.00, "count": 10 },
                    "redeemedHereByIssuer": [
                      { "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789", "merchantName": "MWOS", "amount": 160.00, "count": 9 },
                      { "merchantId": "c5d1e3f4-3456-789a-bcde-f0123456789a", "merchantName": "Bola Bet", "amount": 30.00, "count": 1 }
                    ],
                    "settledToYou": { "amount": 130.00, "count": 5 },
                    "paidAtRedeemToYou": { "amount": 40.00, "count": 4 },
                    "owedToYouNow": { "amount": 20.00, "count": 1 },
                    "sold": { "amount": 500.00, "count": 20 },
                    "soldByMethod": [
                      { "method": "CARD_SWIPE", "amount": 100.00, "count": 4 },
                      { "method": "CASH", "amount": 300.00, "count": 12 },
                      { "method": "MERCHANT_COLLECTED", "amount": 100.00, "count": 4 }
                    ],
                    "yourVouchersRedeemedAt": [
                      { "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789", "merchantName": "MWOS", "amount": 120.00, "count": 5 },
                      { "merchantId": "c5d1e3f4-3456-789a-bcde-f0123456789a", "merchantName": "Bola Bet", "amount": 45.00, "count": 2 }
                    ],
                    "unspentNow": { "amount": 320.00, "count": 14 },
                    "expiredUnreleasedNow": { "amount": 15.00, "count": 1 }
                  }
                ],
                "owedUses": [
                  { "redemptionId": "7e2a4c61-5b3d-4f8e-9a10-2c4d6e8f0a12", "redeemedAt": "2026-11-13T09:41:07Z",
                    "reference": "MW-RDM-000771", "issuerMerchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789", "issuerName": "MWOS",
                    "amount": 20.00, "currency": "USD", "settlementStatus": "PENDING" }
                ],
                "owedUsesTruncated": false
              }
            }""";

    static final String PLATFORM_200 = """
            {
              "code": "200 OK",
              "message": "Partner merchants",
              "data": {
                "from": "2026-11-01",
                "to": "2026-11-13",
                "generatedAt": "2026-11-13T10:02:11Z",
                "merchants": [
                  { "merchantId": "c5d1e3f4-3456-789a-bcde-f0123456789a", "merchantName": "Bola Bet",
                    "tenantId": "9b8e7d6c-5a4f-4e3d-8c2b-1a0f9e8d7c6b", "merchantCodes": ["BOLA"],
                    "currencies": [
                      { "currency": "USD", "sold": { "amount": 200.00, "count": 8 },
                        "redeemedHere": { "amount": 45.00, "count": 2 }, "owedNow": { "amount": 45.00, "count": 2 },
                        "unspentNow": { "amount": 185.00, "count": 7 } }
                    ] },
                  { "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789", "merchantName": "MWOS",
                    "tenantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "merchantCodes": ["MWOS"],
                    "currencies": [
                      { "currency": "USD", "sold": { "amount": 500.00, "count": 20 },
                        "redeemedHere": { "amount": 190.00, "count": 10 }, "owedNow": { "amount": 20.00, "count": 1 },
                        "unspentNow": { "amount": 335.00, "count": 15 } }
                    ] }
                ]
              }
            }""";

    static final String VIEW_200 = """
            {
              "code": "200 OK",
              "message": "Partner holding reconciliation",
              "data": {
                "runs": [
                  {
                      "runId": "0f3c2b1a-9e8d-4c7b-a6f5-e4d3c2b1a0f9",
                      "runAt": "2026-11-13T04:17:00.412318Z",
                      "trigger": "SCHEDULED",
                      "triggeredBy": null,
                      "status": "BALANCED",
                      "currencies": [
                        { "currency": "USD", "status": "BALANCED",
                          "paidIn": 1250.0000, "settledOut": 610.0000, "expectedHolding": 640.0000,
                          "outstandingBalance": 520.0000, "expiredBalance": 15.0000, "owedToMerchants": 65.0000,
                          "paidNotIssued": 40.0000, "ledgerDifference": 0.0000, "fundingInFlight": 25.0000,
                          "bankBalance": null, "bankDifference": null,
                          "stuckFunding": 0, "stuckSettlements": 1, "stuckPayments": 0, "paidWithoutVoucher": 0 }
                      ]
                  }
                ],
                "stuckAfterSeconds": 3600,
                "stuckNow": [
                  { "kind": "SETTLEMENT", "id": "7e2a4c61-5b3d-4f8e-9a10-2c4d6e8f0a12",
                    "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                    "reference": "MW-RDM-000771", "amount": 20.0000, "currency": "USD",
                    "since": "2026-11-13T02:41:07Z" }
                ],
                "stuckNowTruncated": false
              }
            }""";

    static final String RUN_200 = """
            {
              "code": "200 OK",
              "message": "Partner holding reconciliation run",
              "data": {
                  "runId": "5d6e7f80-1a2b-4c3d-9e4f-a5b6c7d8e9f0",
                  "runAt": "2026-11-13T10:05:42.118203Z",
                  "trigger": "MANUAL",
                  "triggeredBy": "ops@innbucks.co.zw",
                  "status": "BALANCED",
                  "currencies": [
                    { "currency": "USD", "status": "BALANCED",
                      "paidIn": 1250.0000, "settledOut": 610.0000, "expectedHolding": 640.0000,
                      "outstandingBalance": 520.0000, "expiredBalance": 15.0000, "owedToMerchants": 65.0000,
                      "paidNotIssued": 40.0000, "ledgerDifference": 0.0000, "fundingInFlight": 25.0000,
                      "bankBalance": null, "bankDifference": null,
                      "stuckFunding": 0, "stuckSettlements": 1, "stuckPayments": 0, "paidWithoutVoucher": 0 }
                  ]
              }
            }""";

    static final String RANGE_400 = """
            { "code": "RANGE_INVERTED", "message": "from must not be after to", "data": null }""";
    static final String NOT_OWNER_403 = """
            { "code": "NOT_MERCHANT_OWNER", "message": "You can only act on merchants you administer.", "data": null }""";
    static final String FORBIDDEN_403 = """
            { "code": "403 FORBIDDEN", "message": "You don't have permission to do that.", "data": null }""";
    static final String MERCHANT_404 = """
            { "code": "404 NOT_FOUND", "message": "merchant not found", "data": null }""";

    private final PartnerVoucherStatements statements;
    private final PartnerHoldingReconciliation reconciliation;

    public PartnerVoucherMoneyController(PartnerVoucherStatements statements,
                                         PartnerHoldingReconciliation reconciliation) {
        this.statements = statements;
        this.reconciliation = reconciliation;
    }

    @GetMapping("/merchants/{merchantId}/statement")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','MERCHANT_ADMIN') or " + ReportsPermissions.HAS_READ)
    @Operation(summary = "A merchant's partner voucher statement",
            description = "For the period (UTC days, `from` and `to` inclusive; default this month to date): "
                    + "partner vouchers used at this merchant, by issuer (both flows); what InnBucks paid it from "
                    + "holding; agents' payments straight to it for codes paid at the redeem; what it sold into "
                    + "holding, by payment method; and where its own vouchers were used. As they stand now, "
                    + "whatever the period: what is still owed to it (with each use, oldest first, at most 200), "
                    + "and the unspent value of its vouchers, live and expired. A merchant admin reads its own "
                    + "merchant." + ReportsPermissions.DOC)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The statement",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = STATEMENT_200))),
            @ApiResponse(responseCode = "400", description = "RANGE_INVERTED",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = RANGE_400))),
            @ApiResponse(responseCode = "403", description = "Not this merchant's admin",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = NOT_OWNER_403))),
            @ApiResponse(responseCode = "404", description = "No such merchant",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = MERCHANT_404)))
    })
    public ResponseEntity<ApiResult<PartnerMoneyDtos.MerchantStatement>> merchantStatement(
            @PathVariable UUID merchantId,
            @Parameter(description = "First day (UTC), inclusive. Default: the first of the month of `to`.")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Last day (UTC), inclusive. Default: today.")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return noStore(ApiResult.ok("Partner voucher statement", statements.merchantStatement(merchantId, from, to)));
    }

    @GetMapping("/statement")
    @PreAuthorize("hasRole('SUPER_ADMIN') or " + ReportsPermissions.HAS_READ)
    @Operation(summary = "Every partner merchant's figures (InnBucks)",
            description = "One row per merchant linked to a partner, by name: what it sold into holding and what "
                    + "was used at it in the period, and, as they stand now, what is owed to it and the unspent "
                    + "value of its vouchers (expired included)." + ReportsPermissions.DOC)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Partner merchants",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = PLATFORM_200))),
            @ApiResponse(responseCode = "400", description = "RANGE_INVERTED",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = RANGE_400))),
            @ApiResponse(responseCode = "403", description = "Not InnBucks staff",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = FORBIDDEN_403)))
    })
    public ResponseEntity<ApiResult<PartnerMoneyDtos.PlatformStatement>> platformStatement(
            @Parameter(description = "First day (UTC), inclusive. Default: the first of the month of `to`.")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Last day (UTC), inclusive. Default: today.")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return noStore(ApiResult.ok("Partner merchants", statements.platformStatement(from, to)));
    }

    @GetMapping("/reconciliation")
    @PreAuthorize("hasRole('SUPER_ADMIN') or " + ReportsPermissions.HAS_READ)
    @Operation(summary = "The holding reconciliation: recent runs, and what is stuck now",
            description = "Each run checks, per currency: paid in minus settled out (what should be in holding) "
                    + "against the unspent balance of the vouchers, the uses not settled yet and the sales paid "
                    + "whose voucher is not issued yet. `ledgerDifference` must be 0; anything else is "
                    + "`LEDGER_MISMATCH`. `bankBalance` and `bankDifference` stay null until the funding provider "
                    + "reports the holding balance. `stuckNow` is read live: items older than "
                    + "`stuckAfterSeconds` that should have moved on (oldest first, at most 50)."
                    + ReportsPermissions.DOC)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Recent runs",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = VIEW_200))),
            @ApiResponse(responseCode = "403", description = "Not InnBucks staff",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = FORBIDDEN_403)))
    })
    public ResponseEntity<ApiResult<PartnerMoneyDtos.ReconciliationView>> reconciliation(
            @Parameter(description = "How many runs, newest first (1 to 100, default 7).")
            @RequestParam(defaultValue = "7") int limit) {
        List<PartnerLedger.StuckItem> stuck = reconciliation.stuckNow();
        boolean truncated = stuck.size() >= PartnerHoldingReconciliation.STUCK_ITEMS_LIMIT;
        PartnerMoneyDtos.ReconciliationView view = new PartnerMoneyDtos.ReconciliationView(
                reconciliation.recent(limit).stream().map(PartnerVoucherMoneyController::runView).toList(),
                reconciliation.stuckAfter().toSeconds(),
                stuck.stream().map(s -> new PartnerMoneyDtos.StuckItem(s.kind().name(), s.id(), s.merchantId(),
                        s.reference(), s.amount(), s.currency(), s.since())).toList(),
                truncated);
        return noStore(ApiResult.ok("Partner holding reconciliation", view));
    }

    @PostMapping("/reconciliation/run")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Run the holding reconciliation now (SUPER_ADMIN)",
            description = "Checks every currency now, stores the run (trigger `MANUAL`, with who ran it) and "
                    + "returns it. Moves no money; the daily run is unaffected.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The run",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = RUN_200))),
            @ApiResponse(responseCode = "403", description = "Not a SUPER_ADMIN",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = FORBIDDEN_403)))
    })
    public ResponseEntity<ApiResult<PartnerMoneyDtos.ReconciliationRun>> runNow() {
        String who = CallerDetails.currentEmail();
        if (who == null) {
            UUID id = CallerDetails.currentUserId();
            who = id == null ? null : id.toString();
        }
        PartnerHoldingReconciliation.Run run =
                reconciliation.run(PartnerHoldingReconciliation.Trigger.MANUAL, who);
        log.info("Partner holding reconciliation run by hand runId={} status={}", run.runId(), run.status());
        return noStore(ApiResult.ok("Partner holding reconciliation run", runView(run)));
    }

    static PartnerMoneyDtos.ReconciliationRun runView(PartnerHoldingReconciliation.Run run) {
        return new PartnerMoneyDtos.ReconciliationRun(run.runId(), run.runAt(), run.trigger().name(),
                run.triggeredBy(), run.status().name(),
                run.currencies().stream().map(l -> new PartnerMoneyDtos.ReconciliationLine(l.currency(),
                        l.status().name(), l.paidIn(), l.settledOut(), l.expectedHolding(), l.outstandingBalance(),
                        l.expiredBalance(), l.owedToMerchants(), l.paidNotIssued(), l.ledgerDifference(),
                        l.fundingInFlight(), l.bankBalance(), l.bankDifference(), l.stuckFunding(),
                        l.stuckSettlements(), l.stuckPayments(), l.paidWithoutVoucher())).toList());
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
