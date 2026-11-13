package com.innbucks.loyaltyservice.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
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
import java.util.List;
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
            @Schema(example = "POS", allowableValues = {"POS", "ONLINE", "APP", "API"}, description = """
                    Where the sale happened. Required when `funding` is sent; for a voucher paid at the \
                    redeem (no `funding`) it may be left out and is then ONLINE. API (a third-party \
                    integrator) follows the ONLINE rules: no cash or card, no outlet or terminal, and the \
                    code is never in the response. Cash and card need POS.""")
            String channel,
            @Schema(example = "HRE-001") @Size(max = 64) String outlet,
            @Schema(example = "T-88231") @Size(max = 64) String terminalId,
            @Schema(example = "cashier-17") @Size(max = 64) String operatorRef,
            @Size(max = 100) String endUserRef,
            @Schema(example = "20.00") @NotNull @Positive BigDecimal amount,
            @Schema(example = "USD") @NotBlank @Size(max = 8) String currency,
            @NotNull @Valid Customer customer,
            @Schema(description = """
                    How the customer paid. Leave it out for a voucher paid at the redeem (Flow 2): the \
                    customer gets the code now and pays it in full at one of the issuer's agents, whose \
                    redeem carries the payment. Such a voucher is SINGLE and works at the issuer only.""")
            @Valid Funding funding,
            Boolean confirmDuplicate,
            @Schema(example = "90", description = """
                    Optional. How many days the voucher stays valid, counted from when it is issued. \
                    1 to 365 by default. Leave it out for the merchant's standard validity.""")
            Integer validityDays,
            @Schema(example = "SINGLE", allowableValues = {"SINGLE", "MULTIPLE"}, description = """
                    Optional. SINGLE (the default): the voucher must be redeemed for its full value in one \
                    redemption; a smaller amount is refused with 400 PARTIAL_USE_NOT_ALLOWED. MULTIPLE: it \
                    can be spent over several redemptions until its balance is used up.""")
            String usage,
            @Schema(example = "[\"ANY\"]", description = """
                    Optional. Where else the voucher can be redeemed. Left out: only at `merchant`. "ANY": \
                    at every merchant in the issuer's live partnerships that you serve (and the issuer's \
                    sister merchants that you serve). One or more merchant codes (a single string is \
                    accepted too): at `merchant` and those, each of which must be such a partner, else \
                    400 MERCHANT_NOT_A_PARTNER naming the codes. "ANY" cannot be combined with codes. At \
                    most 20. Worked out and fixed when the order is made; `voucher.redeemableAt` returns it.""")
            @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
            @Size(max = 20) List<String> redeemableAt) {
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
            @Schema(example = "ONLINE", allowableValues = {"POS", "ONLINE", "APP", "API"}, description = """
                    Where the redemption happens; left out, ONLINE. API follows the ONLINE rules.""")
            String channel,
            @Size(max = 64) String terminalId,
            @Schema(description = """
                    Required for a voucher paid at the redeem (`payAtRedeem: true`): how the agent took the \
                    customer's money, CASH, CARD_SWIPE or MERCHANT_COLLECTED (cash and card at a POS only), \
                    with `accountNumber` the agent's account to take it from. Refused on any other voucher, \
                    which was paid for when it was sold.""")
            @Valid Funding funding) {
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

    /** A merchant where a voucher can be used: the code we gave the partner, and its name. */
    public record MerchantRef(@Schema(example = "BOLABET") String merchant,
                              @Schema(example = "Bola Bet") String name) {
    }

    public record VoucherView(UUID voucherId, String status, BigDecimal value, BigDecimal remainingBalance,
                              String currency, Instant issuedAt, Instant expiresAt, String holderPhone,
                              @Schema(example = "9087876598764566", description = """
                                      The voucher code, for a POS sale only, so the till prints it on the \
                                      customer's slip; null for an ONLINE sale. 16 digits, raw: print it in \
                                      groups of four (9087-8765-9876-4566). Keep it a string. It spends the \
                                      voucher, so never log it.""")
                              String code,
                              @Schema(example = "MULTIPLE", allowableValues = {"SINGLE", "MULTIPLE"}) String usage,
                              @Schema(description = "Every merchant where it can be used, the issuer first.")
                              List<MerchantRef> redeemableAt,
                              @Schema(description = """
                                      True for a voucher paid at the redeem (Flow 2) that is not paid yet: \
                                      its redeem must carry `funding`. `voucherId` is null until it is paid.""")
                              boolean payAtRedeem) {
    }

    public record VoucherBalanceView(UUID voucherId, String status, boolean redeemable, BigDecimal value,
                                     BigDecimal remainingBalance, String currency, Instant expiresAt,
                                     @Schema(example = "MULTIPLE", allowableValues = {"SINGLE", "MULTIPLE"})
                                     String usage,
                                     @Schema(description = "Every merchant where it can be used, the issuer first.")
                                     List<MerchantRef> redeemableAt,
                                     @Schema(description = """
                                             True for a voucher paid at the redeem (Flow 2) that is not paid \
                                             yet: the agent takes the money, and its redeem must carry `funding`.""")
                                     boolean payAtRedeem) {
    }

    public record RedeemResult(UUID redemptionId, UUID voucherId, String status, BigDecimal value,
                               BigDecimal remainingBalance, String currency, Instant redeemedAt,
                               String reference, boolean replayed, SettlementView settlement,
                               @Schema(description = """
                                       A voucher paid at the redeem (Flow 2) only: the agent's payment. Null \
                                       for every other voucher, which was paid for when it was sold.""")
                               FundingView funding) {
    }

    public record SettlementView(String status, String transferReference, Instant settledAt) {
    }
}
