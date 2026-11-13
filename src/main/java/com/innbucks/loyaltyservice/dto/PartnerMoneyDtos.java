package com.innbucks.loyaltyservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The partner voucher money views (V70): a merchant's statement, the platform's
 * list of partner merchants, and the holding reconciliation. Every amount is in
 * the currency named beside it; nothing is ever added across currencies.
 */
public final class PartnerMoneyDtos {

    private PartnerMoneyDtos() {
    }

    @Schema(description = "An amount and how many records made it.")
    public record Amount(BigDecimal amount, long count) {}

    @Schema(description = "An amount from one merchant: the issuer of vouchers used here, or where vouchers were used.")
    public record MerchantAmount(UUID merchantId, String merchantName, BigDecimal amount, long count) {}

    @Schema(description = "Sales by how the customer paid.")
    public record MethodAmount(String method, BigDecimal amount, long count) {}

    @Schema(description = "One currency of a merchant's statement.")
    public record CurrencyStatement(
            String currency,
            @Schema(description = "Partner vouchers used at this merchant in the period (both flows), by issuer.")
            Amount redeemedHere,
            List<MerchantAmount> redeemedHereByIssuer,
            @Schema(description = "Uses at this merchant paid to it from holding in the period.")
            Amount settledToYou,
            @Schema(description = "Codes paid at the redeem (Flow 2): agents' payments straight to this merchant in the period.")
            Amount paidAtRedeemToYou,
            @Schema(description = "Uses at this merchant not paid to it yet, whenever they happened.")
            Amount owedToYouNow,
            @Schema(description = "This merchant's partner sales paid into holding in the period, by payment method.")
            Amount sold,
            List<MethodAmount> soldByMethod,
            @Schema(description = "Where this merchant's held vouchers were used in the period (this merchant included).")
            List<MerchantAmount> yourVouchersRedeemedAt,
            @Schema(description = "Unspent value of this merchant's held vouchers that can still be used.")
            Amount unspentNow,
            @Schema(description = "Unspent value of this merchant's held vouchers past expiry, not released yet.")
            Amount expiredUnreleasedNow) {}

    @Schema(description = "One use owed to the merchant from holding.")
    public record OwedUse(UUID redemptionId, Instant redeemedAt, String reference, UUID issuerMerchantId,
                          String issuerName, BigDecimal amount, String currency, String settlementStatus) {}

    @Schema(description = "A merchant's partner voucher statement.")
    public record MerchantStatement(UUID merchantId, String merchantName, LocalDate from, LocalDate to,
                                    Instant generatedAt, List<CurrencyStatement> currencies,
                                    List<OwedUse> owedUses, boolean owedUsesTruncated) {}

    @Schema(description = "One currency of a merchant in the platform view.")
    public record MerchantCurrencyLine(String currency, Amount sold, Amount redeemedHere, Amount owedNow,
                                       Amount unspentNow) {}

    @Schema(description = "One partner merchant in the platform view.")
    public record MerchantLine(UUID merchantId, String merchantName, UUID tenantId, List<String> merchantCodes,
                               List<MerchantCurrencyLine> currencies) {}

    @Schema(description = "Every partner merchant's figures, for InnBucks.")
    public record PlatformStatement(LocalDate from, LocalDate to, Instant generatedAt,
                                    List<MerchantLine> merchants) {}

    @Schema(description = "One currency of a reconciliation run.")
    public record ReconciliationLine(String currency, String status, BigDecimal paidIn, BigDecimal settledOut,
                                     BigDecimal expectedHolding, BigDecimal outstandingBalance,
                                     BigDecimal expiredBalance, BigDecimal owedToMerchants,
                                     BigDecimal paidNotIssued, BigDecimal ledgerDifference,
                                     BigDecimal fundingInFlight, BigDecimal bankBalance, BigDecimal bankDifference,
                                     int stuckFunding, int stuckSettlements, int stuckPayments,
                                     int paidWithoutVoucher) {}

    @Schema(description = "One reconciliation run: every currency checked at one moment.")
    public record ReconciliationRun(UUID runId, Instant runAt, String trigger, String triggeredBy, String status,
                                    List<ReconciliationLine> currencies) {}

    @Schema(description = "Something that should have moved on by now.")
    public record StuckItem(String kind, UUID id, UUID merchantId, String reference, BigDecimal amount,
                            String currency, Instant since) {}

    @Schema(description = "Recent runs, and what is stuck right now.")
    public record ReconciliationView(List<ReconciliationRun> runs, long stuckAfterSeconds,
                                     List<StuckItem> stuckNow, boolean stuckNowTruncated) {}
}
