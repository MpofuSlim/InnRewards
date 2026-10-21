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
            @Schema(example = "wrong-merchant", description = "Rejection reason (REJECTED results only).") String reason,
            // What the use moved (V61) — every voucher is a balance voucher, so one
            // voucher can carry several SUCCESS rows. All null on REJECTED rows.
            @Schema(example = "20.0000", nullable = true,
                    description = "Money this use took off the bill, in the voucher's currency. Null on REJECTED "
                            + "rows. Uses recorded before balance vouchers (2026-10-21) carry the face value.")
            BigDecimal amount,
            @Schema(example = "TILL3-RCPT-88412", nullable = true,
                    description = "The idempotency reference the till sent with this use, if any.")
            String reference,
            @Schema(example = "30.0000", nullable = true,
                    description = "The voucher's remaining balance once this use landed. Null on REJECTED rows "
                            + "and on uses recorded before balance vouchers.")
            BigDecimal balanceAfter
    ) {
    }

    @Schema(name = "VoucherDetail",
            description = "A single voucher with every stored field plus resolved issuer, receiver, "
                    + "merchant, shop and template names.")
    public record VoucherDetail(
            @Schema(example = "d2c8f0a1-0123-4567-1234-567890123456") UUID id,
            @Schema(example = "7183502649174053", description = "Raw code (a STRING). "
                    + "The CSV export writes it grouped in fours with hyphens (7183-5026-4917-4053) so a "
                    + "spreadsheet keeps it as text; JSON always carries it raw.") String code,
            @Schema(example = "REDEEMED",
                    description = "ISSUED, VIEWED, REDEEMED, PARTIALLY_USED, EXPIRED or REVOKED.") String status,

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
            @Schema(example = "SINGLE_USE") String voucherType,
            @Schema(example = "5.00") BigDecimal faceValue,
            @Schema(example = "USD") String currency,
            @Schema(example = "0", description = "1 while the voucher can still be used, 0 once it is spent. "
                    + "Money left is `remainingBalance`.") int usesRemaining,
            @Schema(example = "WHATSAPP", nullable = true) String deliveryChannel,
            @Schema(example = "spring-2026", nullable = true) String campaignSource,

            @Schema(example = "2026-06-01T08:00:00Z") Instant issuedAt,
            @Schema(example = "2026-06-01T08:00:05Z", nullable = true) Instant deliveredAt,
            @Schema(example = "2026-06-02T18:20:00Z", nullable = true) Instant viewedAt,
            @Schema(example = "2026-06-14T09:31:00Z", nullable = true) Instant redeemedAt,
            @Schema(example = "2026-12-31T23:59:59Z", nullable = true) Instant expiresAt,
            @Schema(example = "false", description = "True when past expiry and not yet redeemed/revoked.") boolean expired,

            // Sender — who the voucher is a gift FROM (V46 presentation fact, from the
            // issue request body). Distinct from the issuer above: a cashier keying in a
            // gift between two customers is the issuer, never the sender.
            @Schema(example = "Tawanda Mpofu", nullable = true,
                    description = "Who the voucher is from, as shown to the recipient. NOT the issuing staff member.") String senderName,
            @Schema(example = "+263782608767", nullable = true) String senderPhone,

            // Single-hop transfer provenance (V34) — null until the voucher changes hands.
            @Schema(example = "2026-06-10T12:00:00Z", nullable = true) Instant transferredAt,
            @Schema(example = "44444444-4444-4444-4444-444444444444", nullable = true,
                    description = "The assignee the voucher moved AWAY from.") UUID transferredFromUserId,
            @Schema(example = "+263771234567", nullable = true) String transferredFromPhone,

            @Schema(example = "1", description = "Number of successful USES of this voucher — a balance "
                    + "voucher spent in several partial uses counts each one. Refused attempts are not counted.")
            long redemptionCount,
            @Schema(nullable = true, description = "Full redemption log — populated only on the single-voucher "
                    + "detail endpoint; null in list/report rows.") List<RedemptionDetail> redemptions,

            // How the voucher was paid for (V56). FREE = issued without a payment.
            @Schema(example = "ECOCASH", allowableValues = {"FREE", "ONLINE", "INNBUCKS", "ECOCASH", "ONLINE_CARD",
                    "CASH", "CARD_POS"},
                    description = "How the voucher was paid for. FREE = issued without a payment (direct or bulk "
                            + "issue); ONLINE = electronic, rail not recorded (confirmed before 2026-09-30); "
                            + "INNBUCKS / ECOCASH / ONLINE_CARD = that electronic rail; CASH / CARD_POS = "
                            + "confirmed at the till.") String paymentMethod,
            @Schema(example = "VCH-4F9A1C22B7D3", nullable = true,
                    description = "The purchase order it was paid through; null when FREE.") String orderRef,
            // V61 — every voucher holds a balance. APPENDED, like the two above, so the
            // CSV (which appends the same column at its end) keeps old columns in place.
            @Schema(example = "0.0000", nullable = true,
                    description = "What is left to spend, in `currency`. Equals faceValue until the first use; "
                            + "0 once fully redeemed. Null only on a legacy non-money voucher.")
            BigDecimal remainingBalance
    ) {
    }

    @Schema(name = "VoucherSummary", description = "Aggregate totals for a report scope.")
    public record VoucherSummary(
            @Schema(example = "1240", description = "All vouchers issued in scope over the period.") long totalIssued,
            @Schema(description = "Voucher count per status.") Map<String, Long> countByStatus,
            @Schema(description = "Summed face value per status, in USD (the platform base currency). Vouchers with no money face value (PERCENT/FREE_ITEM/COMBO) contribute 0 here but are still counted in countByStatus.") Map<String, BigDecimal> faceValueByStatus,
            @Schema(example = "8420.00", description = "Sum of every voucher's face value, in USD (the platform base currency), so a mixed-currency scope totals in one unit.") BigDecimal totalFaceValue,
            @Schema(example = "388", description = "Vouchers FULLY redeemed (status REDEEMED). A part-used "
                    + "balance voucher is PARTIALLY_USED and is counted under outstandingCount.") long redeemedCount,
            @Schema(example = "2110.00", description = "Summed face value, in USD, of the FULLY redeemed vouchers.")
            BigDecimal redeemedFaceValue,
            @Schema(example = "760", description = "Live vouchers (ISSUED + VIEWED + PARTIALLY_USED).") long outstandingCount,
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
