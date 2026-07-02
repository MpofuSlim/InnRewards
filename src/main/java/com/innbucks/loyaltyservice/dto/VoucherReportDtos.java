package com.innbucks.loyaltyservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DTOs for the detailed voucher reports
 * ({@code GET /loyalty/reports/vouchers/**}). Kept in their own file rather than
 * bloating {@code Dtos.java} because the voucher report is a self-contained
 * feature with several nested shapes.
 *
 * <p>Every level (operator / tenant / merchant / shop) returns the same
 * {@link VoucherReport}: a scope header, summary aggregates, and a page of
 * {@link VoucherDetail} rows carrying every field of each voucher — issuer AND
 * receiver numbers, merchant + shop names, the value snapshot, the full
 * lifecycle timeline, and (for the single-voucher endpoint) the redemption log.
 */
public final class VoucherReportDtos {

    private VoucherReportDtos() {
    }

    @Schema(name = "VoucherRedemptionDetail",
            description = "One redemption attempt against a voucher (success or rejected).")
    public record RedemptionDetail(
            @Schema(example = "fa1b2c3d-4e5f-6071-8293-a4b5c6d7e8f9") UUID id,
            @Schema(example = "2026-06-14T09:31:00Z") Instant redeemedAt,
            @Schema(example = "SUCCESS", description = "SUCCESS or REJECTED.") String result,
            @Schema(example = "b4c0d2e3-2345-6789-abcd-ef0123456789",
                    description = "Merchant the voucher was presented at.") UUID merchantId,
            @Schema(example = "WESTGATE-TILL-3", description = "Free-text outlet/till code, if supplied.") String outletCode,
            @Schema(example = "33333333-3333-3333-3333-333333333333",
                    description = "LoyaltyUser who redeemed it, if known.") UUID redeemerUserId,
            @Schema(example = "41.220.10.5") String ipAddress,
            @Schema(example = "fp-deadbeef-0001") String deviceFingerprint,
            @Schema(example = "wrong-merchant", description = "Rejection reason (REJECTED results only).") String reason
    ) {
    }

    @Schema(name = "VoucherDetail",
            description = "A single voucher with every stored field plus resolved issuer, receiver, "
                    + "merchant, shop and template names.")
    public record VoucherDetail(
            @Schema(example = "d2c8f0a1-0123-4567-1234-567890123456") UUID id,
            @Schema(example = "VCH-AB12CD34") String code,
            @Schema(example = "REDEEMED",
                    description = "ISSUED, DELIVERED, VIEWED, REDEEMED, PARTIALLY_USED, EXPIRED or REVOKED.") String status,

            @Schema(example = "11111111-1111-1111-1111-111111111111") UUID tenantId,
            @Schema(example = "b4c0d2e3-2345-6789-abcd-ef0123456789") UUID merchantId,
            @Schema(example = "Innbucks Westgate") String merchantName,
            @Schema(example = "c5d1e3f4-3456-7890-abcd-ef0123456789", nullable = true,
                    description = "Outlet the voucher was issued from; null for merchant-level issuance.") UUID shopId,
            @Schema(example = "Westgate Branch", nullable = true) String shopName,
            @Schema(example = "a1a1a1a1-1111-2222-3333-444444444444") UUID templateId,
            @Schema(example = "Coffee Combo") String templateName,
            @Schema(example = "9f9f9f9f-0000-1111-2222-333333333333", nullable = true,
                    description = "Bulk-issue batch this voucher belongs to, if any.") UUID batchId,

            // Issuer — who created the voucher (captured from their JWT at issue time).
            @Schema(example = "77777777-7777-7777-7777-777777777777", nullable = true) UUID issuerUserId,
            @Schema(example = "+263772000111", nullable = true,
                    description = "Issuer's phone (E.164). Null for pre-migration / system-issued vouchers.") String issuerPhone,
            @Schema(example = "shopadmin@westgate.co.zw", nullable = true) String issuerEmail,

            // Receiver — who the voucher is for.
            @Schema(example = "33333333-3333-3333-3333-333333333333", nullable = true) UUID receiverUserId,
            @Schema(example = "+263771234567", nullable = true,
                    description = "Receiver's phone (E.164).") String receiverPhone,
            @Schema(example = "Jane Moyo", nullable = true) String receiverName,

            // Value snapshot frozen at issuance.
            @Schema(example = "AMOUNT") String valueType,
            @Schema(example = "5.00") BigDecimal faceValue,
            @Schema(example = "USD") String currency,
            @Schema(example = "0", description = "Remaining redemptions (multi-use vouchers).") int usesRemaining,
            @Schema(example = "WHATSAPP", nullable = true) String deliveryChannel,
            @Schema(example = "spring-2026", nullable = true) String campaignSource,

            @Schema(example = "2026-06-01T08:00:00Z") Instant issuedAt,
            @Schema(example = "2026-06-01T08:00:05Z", nullable = true) Instant deliveredAt,
            @Schema(example = "2026-06-02T18:20:00Z", nullable = true) Instant viewedAt,
            @Schema(example = "2026-06-14T09:31:00Z", nullable = true) Instant redeemedAt,
            @Schema(example = "2026-12-31T23:59:59Z", nullable = true) Instant expiresAt,
            @Schema(example = "false", description = "True when past expiry and not yet redeemed/revoked.") boolean expired,

            @Schema(example = "1", description = "Number of redemption attempts logged against this voucher.") long redemptionCount,
            @Schema(nullable = true, description = "Full redemption log — populated only on the single-voucher "
                    + "detail endpoint; null in list/report rows.") List<RedemptionDetail> redemptions
    ) {
    }

    @Schema(name = "VoucherSummary", description = "Aggregate totals for a report scope.")
    public record VoucherSummary(
            @Schema(example = "1240", description = "All vouchers issued in scope over the period.") long totalIssued,
            @Schema(description = "Voucher count per status.") Map<String, Long> countByStatus,
            @Schema(description = "Summed face value per status.") Map<String, BigDecimal> faceValueByStatus,
            @Schema(example = "8420.00", description = "Sum of every voucher's face value.") BigDecimal totalFaceValue,
            @Schema(example = "388") long redeemedCount,
            @Schema(example = "2110.00") BigDecimal redeemedFaceValue,
            @Schema(example = "760", description = "Live vouchers (ISSUED + DELIVERED + VIEWED + PARTIALLY_USED).") long outstandingCount,
            @Schema(example = "88") long expiredCount,
            @Schema(example = "4") long revokedCount,
            @Schema(example = "31.3", description = "redeemedCount / totalIssued as a percentage.") double redemptionRatePct
    ) {
    }

    @Schema(name = "VoucherReport",
            description = "Detailed voucher report: scope header + summary aggregates + a page of voucher detail rows.")
    public record VoucherReport(
            @Schema(example = "MERCHANT", description = "OPERATOR, TENANT, MERCHANT or SHOP.") String level,
            @Schema(example = "b4c0d2e3-2345-6789-abcd-ef0123456789", nullable = true,
                    description = "The merchant/shop/tenant id this report is scoped to; null for OPERATOR.") UUID scopeId,
            @Schema(example = "Innbucks Westgate", nullable = true) String scopeName,
            @Schema(example = "2026-06-01T00:00:00Z") Instant from,
            @Schema(example = "2026-07-01T00:00:00Z") Instant to,
            VoucherSummary summary,
            PageResponse<VoucherDetail> vouchers
    ) {
    }
}
