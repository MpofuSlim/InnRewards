package com.innbucks.loyaltyservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The optional filters every voucher report and export accepts, as query
 * parameters (bound with {@code @ParameterObject}). All are optional and
 * combine with AND; {@code status}, {@code from} and {@code to} (issue date)
 * stay separate parameters as before. The summary block honours every filter
 * except {@code status}, so the status tabs keep counting the other statuses.
 */
public record VoucherReportFilters(
        @Schema(description = "One merchant. Ownership-checked (403 NOT_MERCHANT_OWNER for one you do not "
                + "administer). Ignored on /vouchers/merchant/{id} and /vouchers/shop/{id}, whose path wins.",
                example = "b4c0d2e3-2345-6789-abcd-ef0123456789", nullable = true)
        UUID merchantId,
        @Schema(description = "One outlet. Ownership-checked (403 NOT_SHOP_MEMBER / NOT_MERCHANT_OWNER).",
                example = "c5d1e3f4-3456-7890-abcd-ef0123456789", nullable = true)
        UUID shopId,
        @Schema(description = "Face-value currency (ISO 4217), e.g. USD, ZWG, ZAR. Case-insensitive.",
                example = "USD", nullable = true)
        String currency,
        @Schema(description = "How the voucher was paid for. FREE = issued without a payment (direct or "
                + "bulk issue). ONLINE = any electronic payment; INNBUCKS / ECOCASH / ONLINE_CARD = that rail "
                + "only (recorded from 2026-09-30 — older electronic payments match ONLINE only). CASH and "
                + "CARD_POS = confirmed at the till.", example = "ECOCASH", nullable = true)
        PaymentMethod paymentMethod,
        @Schema(description = "The staff member who issued it: part of their email or phone.",
                example = "nyanyiwast", nullable = true)
        String issuedBy,
        @Schema(description = "A customer's phone, matched against the recipient AND the sender, in any "
                + "spelling (0777…, +263777…).", example = "0777224008", nullable = true)
        String phone,
        @Schema(description = "Free-text search: part of the voucher code (raw or grouped), a recipient or "
                + "sender name, a phone, or the issuer's email.", example = "Nyanyiwa", nullable = true)
        String q,
        @Schema(description = "true = bulk-issued stock only (no recipient yet); false = individually "
                + "issued only.", example = "false", nullable = true)
        Boolean bulk,
        @Schema(description = "One bulk batch.", example = "9f9f9f9f-0000-1111-2222-333333333333", nullable = true)
        UUID batchId,
        @Schema(description = "Campaign tag given at issue (exact).", example = "spring-2026", nullable = true)
        String campaign,
        @Schema(description = "Expires on or after this day (UTC).", example = "2026-10-01", nullable = true)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiresFrom,
        @Schema(description = "Expires on or before this day (UTC).", example = "2026-10-31", nullable = true)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiresTo,
        @Schema(description = "Redeemed on or after this day (UTC).", example = "2026-09-01", nullable = true)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate redeemedFrom,
        @Schema(description = "Redeemed on or before this day (UTC).", example = "2026-09-30", nullable = true)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate redeemedTo,
        @Schema(description = "Face value at least this (in the voucher's own currency).", example = "5.00",
                nullable = true)
        BigDecimal minValue,
        @Schema(description = "Face value at most this (in the voucher's own currency).", example = "50.00",
                nullable = true)
        BigDecimal maxValue
) {

    /** How a voucher was paid for — also the report row's {@code paymentMethod}. */
    public enum PaymentMethod { FREE, ONLINE, INNBUCKS, ECOCASH, ONLINE_CARD, CASH, CARD_POS }

    public static VoucherReportFilters none() {
        return new VoucherReportFilters(null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    /** True when no filter is set — the report then runs its original queries. */
    public boolean isEmpty() {
        return merchantId == null && shopId == null && blank(currency) && paymentMethod == null
                && blank(issuedBy) && blank(phone) && blank(q) && bulk == null && batchId == null
                && blank(campaign) && expiresFrom == null && expiresTo == null && redeemedFrom == null
                && redeemedTo == null && minValue == null && maxValue == null;
    }

    /** Builds filters in code (service callers and tests); the HTTP layer binds
     *  the record from query parameters directly. */
    public static final class Builder {
        private UUID merchantId, shopId, batchId;
        private String currency, issuedBy, phone, q, campaign;
        private PaymentMethod paymentMethod;
        private Boolean bulk;
        private LocalDate expiresFrom, expiresTo, redeemedFrom, redeemedTo;
        private BigDecimal minValue, maxValue;

        public Builder merchantId(UUID v) { merchantId = v; return this; }
        public Builder shopId(UUID v) { shopId = v; return this; }
        public Builder currency(String v) { currency = v; return this; }
        public Builder paymentMethod(PaymentMethod v) { paymentMethod = v; return this; }
        public Builder issuedBy(String v) { issuedBy = v; return this; }
        public Builder phone(String v) { phone = v; return this; }
        public Builder q(String v) { q = v; return this; }
        public Builder bulk(Boolean v) { bulk = v; return this; }
        public Builder batchId(UUID v) { batchId = v; return this; }
        public Builder campaign(String v) { campaign = v; return this; }
        public Builder expiresFrom(LocalDate v) { expiresFrom = v; return this; }
        public Builder expiresTo(LocalDate v) { expiresTo = v; return this; }
        public Builder redeemedFrom(LocalDate v) { redeemedFrom = v; return this; }
        public Builder redeemedTo(LocalDate v) { redeemedTo = v; return this; }
        public Builder minValue(BigDecimal v) { minValue = v; return this; }
        public Builder maxValue(BigDecimal v) { maxValue = v; return this; }

        public VoucherReportFilters build() {
            return new VoucherReportFilters(merchantId, shopId, currency, paymentMethod, issuedBy, phone, q,
                    bulk, batchId, campaign, expiresFrom, expiresTo, redeemedFrom, redeemedTo, minValue, maxValue);
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
