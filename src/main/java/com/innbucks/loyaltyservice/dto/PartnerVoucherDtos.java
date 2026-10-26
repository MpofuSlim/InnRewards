package com.innbucks.loyaltyservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Request and response shapes of the partner voucher API (V62). A request object
 * travels as the {@code body} claim of the signed JWS; every response is the
 * plain {@code ApiResult} envelope.
 */
public final class PartnerVoucherDtos {

    private PartnerVoucherDtos() {
    }

    // ------------------------------------------------------------- requests

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "Generate a voucher worth what the customer paid.")
    public record GenerateRequest(
            @Schema(example = "MWOS", description = "The merchant this voucher is for, by the code we gave you.")
            @NotBlank @Size(max = 32) String merchant,
            @Schema(example = "MWOS-POS-HRE001-20261023-000042",
                    description = "Your unique id for this sale; the idempotency key. Unique for the merchant, for ever.")
            @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._:-]+",
                    message = "letters, digits and . _ : - only") String partnerReference,
            @Schema(example = "POS", allowableValues = {"POS", "ONLINE"}) @NotBlank String channel,
            @Schema(example = "HRE-001") @Size(max = 64) String outlet,
            @Schema(example = "T-88231") @Size(max = 64) String terminalId,
            @Schema(example = "cashier-17") @Size(max = 64) String operatorRef,
            @Size(max = 100) String endUserRef,
            @Schema(example = "20.00") @NotNull @Positive BigDecimal amount,
            @Schema(example = "USD") @NotBlank @Size(max = 8) String currency,
            @NotNull @Valid Customer customer,
            @NotNull @Valid Funding funding,
            Boolean printSlip,
            Boolean confirmDuplicate) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Customer(
            @Schema(example = "+263771234567", description = "Where we send the voucher code.")
            @NotBlank @Size(max = 32) String phone,
            @Schema(example = "Tawanda Mpofu") @Size(max = 200) String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Funding(
            @Schema(example = "CASH", allowableValues = {"CASH", "CARD_SWIPE", "MERCHANT_COLLECTED",
                    "INNBUCKS_CODE", "INNBUCKS_ACCOUNT", "ECOCASH"})
            @NotBlank String method,
            @Size(max = 34) String accountNumber,
            @Size(max = 64) String consentRef,
            Instant consentAt,
            @Schema(example = "A1B2C3", description = "CARD_SWIPE: the approval code on the slip, 4 to 12 letters or digits.")
            @Size(max = 32) String approvalCode,
            @Size(max = 4) String last4,
            @Schema(example = "ECOCASH_MERCHANT", allowableValues = {"ECOCASH_MERCHANT", "BANK_TRANSFER",
                    "BETTING_WALLET", "OTHER"})
            @Size(max = 24) String collectedVia,
            @Size(max = 64) String collectionRef,
            @Size(max = 32) String payerPhone) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InquireRequest(
            @Schema(example = "MWOS") @NotBlank @Size(max = 32) String merchant,
            @Schema(example = "PARTNER_REFERENCE", allowableValues = {"PARTNER_REFERENCE", "CODE", "REDEMPTION"})
            @NotBlank String by,
            @Size(max = 64) String partnerReference,
            @Size(max = 64) String code,
            @Size(max = 100) String endUserRef,
            @Size(max = 32) String endUserPhone,
            @Size(max = 64) String terminalId,
            @Size(max = 100) String reference) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RedeemRequest(
            @Schema(example = "MWOS") @NotBlank @Size(max = 32) String merchant,
            @Schema(example = "9087876598764566") @NotBlank @Size(max = 64) String code,
            @Schema(example = "5.00") @Positive BigDecimal amount,
            @Schema(example = "USD") @Size(max = 8) String currency,
            @Schema(example = "MWOS-BET-20261023-77812") @NotBlank @Size(max = 100) String reference,
            @Schema(example = "MWOS-ACC-998877") @NotBlank @Size(max = 100) String endUserRef,
            @Schema(example = "+263771234567") @Size(max = 32) String endUserPhone,
            @Schema(example = "ONLINE", allowableValues = {"POS", "ONLINE"}) String channel,
            @Size(max = 64) String terminalId) {
    }

    // ------------------------------------------------------------ responses

    public record GenerateResult(String merchant, String partnerReference, String orderRef, String status,
                                 String channel, String outlet, String terminalId, BigDecimal amount,
                                 String currency, FundingView funding, PaymentView payment,
                                 VoucherView voucher, boolean replayed) {
    }

    public record FundingView(String method, String status, String failureCode, boolean simulated) {
    }

    public record PaymentView(String type, String paymentCode, String qr, Instant expiresAt) {
    }

    public record VoucherView(UUID voucherId, String status, BigDecimal value, BigDecimal remainingBalance,
                              String currency, Instant issuedAt, Instant expiresAt, String holderPhone,
                              String code) {
    }

    public record VoucherBalanceView(UUID voucherId, String status, boolean redeemable, BigDecimal value,
                                     BigDecimal remainingBalance, String currency, Instant expiresAt) {
    }

    public record RedeemResult(UUID redemptionId, UUID voucherId, String status, BigDecimal value,
                               BigDecimal remainingBalance, String currency, Instant redeemedAt,
                               String reference, boolean replayed, SettlementView settlement) {
    }

    public record SettlementView(String status, String transferReference, Instant settledAt) {
    }
}
