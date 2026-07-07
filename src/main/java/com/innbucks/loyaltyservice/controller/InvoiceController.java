package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.ApiResult;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.dto.PageResponse;
import com.innbucks.loyaltyservice.security.TenantContext;
import com.innbucks.loyaltyservice.service.InvoicingService;
import com.innbucks.loyaltyservice.service.MerchantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@RestController
@Slf4j
@RequestMapping("/loyalty/invoices")
@Tag(name = "Invoicing",
     description = "Per-merchant periodic billing. The daily InvoiceScheduler calls `/generate` for each " +
                   "active merchant; this controller exposes manual generation, listing, and mark-as-paid " +
                   "for support flows. The invoice total is the sum of per-voucher fees computed from " +
                   "the merchant's `feeIssued` / `feeRedeemed` schedules (FIXED / PERCENTAGE / " +
                   "FIXED_PLUS_PERCENTAGE — see `POST /loyalty/merchants` for details). Loyalty points " +
                   "carry no per-point fee. Requires X-Tenant-Id.")
public class InvoiceController {

    private final InvoicingService invoicing;
    private final MerchantService merchants;
    private final TenantContext tenantContext;
    private final com.innbucks.loyaltyservice.security.MerchantAuthz merchantAuthz;

    public InvoiceController(InvoicingService invoicing, MerchantService merchants,
                             TenantContext tenantContext,
                             com.innbucks.loyaltyservice.security.MerchantAuthz merchantAuthz) {
        this.invoicing = invoicing;
        this.merchants = merchants;
        this.tenantContext = tenantContext;
        this.merchantAuthz = merchantAuthz;
    }

    @GetMapping("/merchant/{merchantId}")
    @Operation(summary = "List invoices for a merchant",
            description = "Returns every invoice ever generated for the merchant, most recent first. " +
                          "Includes paid, pending, and overdue. Used by the merchant billing dashboard.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Invoices returned",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Paginated invoices", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Invoices retrieved successfully",
                                      "data": {
                                        "content": [
                                          {
                                            "id": "1a2b3c4d-5e6f-7081-9293-a4b5c6d7e8f9",
                                            "invoiceNumber": "INV-202604-0001",
                                            "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                            "periodStart": "2026-04-01",
                                            "periodEnd": "2026-04-30",
                                            "pointsIssued": 12500.0000,
                                            "pointsRedeemed": 4300.0000,
                                            "vouchersIssued": 220,
                                            "vouchersRedeemed": 138,
                                            "totalAmount": 245.50,
                                            "currency": "USD",
                                            "status": "PAID",
                                            "paidAt": "2026-05-02T09:15:00Z"
                                          },
                                          {
                                            "id": "2b3c4d5e-6f70-8192-a3b4-c5d6e7f80910",
                                            "invoiceNumber": "INV-202605-0001",
                                            "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                            "periodStart": "2026-05-01",
                                            "periodEnd": "2026-05-31",
                                            "pointsIssued": 4100.0000,
                                            "pointsRedeemed": 1200.0000,
                                            "vouchersIssued": 70,
                                            "vouchersRedeemed": 38,
                                            "totalAmount": 82.40,
                                            "currency": "USD",
                                            "status": "PENDING",
                                            "paidAt": null
                                          }
                                        ],
                                        "page": 0,
                                        "size": 20,
                                        "totalElements": 2,
                                        "totalPages": 1,
                                        "first": true,
                                        "last": true
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Merchant not found in this tenant",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Not found", value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "Merchant not found",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SHOP_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<PageResponse<Dtos.InvoiceResponse>>> listForMerchant(@PathVariable UUID merchantId,
                                                                                          @ParameterObject Pageable pageable) {
        UUID tenantId = tenantContext.requireTenantId();
        merchantAuthz.requireCallerAdministersMerchant(tenantId, merchantId);
        PageResponse<Dtos.InvoiceResponse> data = PageResponse.from(
                invoicing.listForMerchant(tenantId, merchantId, pageable));
        return ResponseEntity.ok(ApiResult.ok("Invoices retrieved successfully", data));
    }

    @PostMapping("/generate")
    @Operation(summary = "Manually generate an invoice",
            description = "Operator escape hatch — usually unnecessary because InvoiceScheduler runs nightly. " +
                          "Body: `{ merchantId, periodStart, periodEnd }` (ISO dates). Aggregates points " +
                          "issued + vouchers issued/redeemed within the period and applies the merchant's " +
                          "fee schedule. Idempotent on (merchant, period).\n\n" +
                          "**No-op on zero billing**: if the merchant had no billable activity in the period " +
                          "(no vouchers issued, no vouchers redeemed), no invoice is created and the response " +
                          "is `200 OK` with `data: null` and an explanatory message. Keeps the merchant's " +
                          "billing page free of `$0.00` placeholder invoices.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "Invoice generated",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Generated", value = """
                                    {
                                      "code": "201 CREATED",
                                      "message": "Invoice generated successfully",
                                      "data": {
                                        "id": "2b3c4d5e-6f70-8192-a3b4-c5d6e7f80910",
                                        "invoiceNumber": "INV-202605-0001",
                                        "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                        "periodStart": "2026-05-01",
                                        "periodEnd": "2026-05-31",
                                        "pointsIssued": 4100.0000,
                                        "pointsRedeemed": 1200.0000,
                                        "vouchersIssued": 70,
                                        "vouchersRedeemed": 38,
                                        "totalAmount": 82.40,
                                        "currency": "USD",
                                        "status": "PENDING",
                                        "paidAt": null
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Nothing to bill — no invoice created",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "No billable activity", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "No billable activity in this period; no invoice created",
                                      "data": null
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Malformed body (bad UUID / unparseable date)",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Bad request", value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "Text 'foo' could not be parsed as a Date",
                                      "data": null
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Merchant not found in this tenant",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Not found", value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "Merchant not found",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SHOP_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.InvoiceResponse>> generate(@Valid @RequestBody Dtos.InvoiceGenerateRequestDTO body) {
        UUID tenantId = tenantContext.requireTenantId();
        merchantAuthz.requireCallerAdministersMerchant(tenantId, body.merchantId());
        var m = merchants.requireMerchant(tenantId, body.merchantId());
        return invoicing.generate(m, body.periodStart(), body.periodEnd())
                .map(inv -> ResponseEntity.status(HttpStatus.CREATED)
                        .body(ApiResult.created("Invoice generated successfully", InvoicingService.toResponse(inv))))
                .orElseGet(() -> ResponseEntity.ok(ApiResult.<Dtos.InvoiceResponse>ok(
                        "No billable activity in this period; no invoice created", null)));
    }

    @PostMapping("/{id}/pay")
    @Operation(summary = "Mark an invoice as paid",
            description = "Records that the invoice has been settled (timestamps `paidAt`). Loyalty-service " +
                          "does not collect payments itself — payment-service or an external accounting " +
                          "system calls this once funds clear.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Invoice marked paid",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Paid", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Invoice marked as paid",
                                      "data": {
                                        "id": "2b3c4d5e-6f70-8192-a3b4-c5d6e7f80910",
                                        "invoiceNumber": "INV-202605-0001",
                                        "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                        "periodStart": "2026-05-01",
                                        "periodEnd": "2026-05-31",
                                        "pointsIssued": 4100.0000,
                                        "pointsRedeemed": 1200.0000,
                                        "vouchersIssued": 70,
                                        "vouchersRedeemed": 38,
                                        "totalAmount": 82.40,
                                        "currency": "USD",
                                        "status": "PAID",
                                        "paidAt": "2026-05-04T16:45:00Z"
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Invoice not found in this tenant",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Not found", value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "Invoice not found",
                                      "data": null
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "422",
                    description = "Invoice already paid or cancelled",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Already paid", value = """
                                    {
                                      "code": "422 UNPROCESSABLE_ENTITY",
                                      "message": "INVOICE_ALREADY_PAID",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SHOP_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.InvoiceResponse>> pay(@PathVariable UUID id) {
        Dtos.InvoiceResponse data = InvoicingService.toResponse(
                invoicing.markPaid(tenantContext.requireTenantId(), id));
        return ResponseEntity.ok(ApiResult.ok("Invoice marked as paid", data));
    }
}
