package com.innbucks.loyaltyservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Setup of the partner voucher API (V62), by a SUPER_ADMIN. */
public final class PartnerAdminDtos {

    private PartnerAdminDtos() {
    }

    public record CreatePartnerRequest(
            @Schema(example = "InnBucks middleware") @NotBlank @Size(max = 100) String name,
            @Schema(example = "innbucks-middleware", description = "The JWS `iss` its requests carry.")
            @NotBlank @Size(max = 100) String issuer,
            @Schema(example = "[\"203.0.113.10\", \"198.51.100.0/28\"]",
                    description = "Addresses it calls from (nginx's X-Real-IP). Empty refuses every call.")
            List<String> allowedIps) {
    }

    public record UpdatePartnerRequest(List<String> allowedIps,
                                       @Schema(allowableValues = {"ACTIVE", "SUSPENDED"}) String status) {
    }

    public record AddKeyRequest(
            @Schema(example = "mw-stg-2026-10") @NotBlank @Size(max = 64) String kid,
            @Schema(example = "ES256", allowableValues = {"ES256", "RS256"}) @NotBlank String alg,
            @Schema(example = "-----BEGIN PUBLIC KEY-----\\nMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE...\\n-----END PUBLIC KEY-----")
            @NotBlank @Size(max = 4000) String publicKeyPem) {
    }

    public record LinkMerchantRequest(
            @NotNull UUID merchantId,
            @Schema(example = "MWOS", description = "What the partner sends as `merchant`.")
            @NotBlank @Size(max = 32) String merchantCode,
            Boolean requireTerminal,
            Boolean bindRedeemToHolder,
            @Schema(example = "[\"CASH\", \"CARD_SWIPE\", \"MERCHANT_COLLECTED\", \"INNBUCKS_CODE\"]")
            List<String> fundingMethods,
            @Schema(example = "500.00") BigDecimal maxValue,
            @Schema(example = "10") Integer perPhoneDailyCount,
            @Schema(example = "1000.00") BigDecimal perPhoneDailyValue) {
    }

    public record UpdateMerchantRequest(Boolean requireTerminal, Boolean bindRedeemToHolder,
                                        List<String> fundingMethods, BigDecimal maxValue,
                                        Integer perPhoneDailyCount, BigDecimal perPhoneDailyValue,
                                        @Schema(allowableValues = {"ACTIVE", "SUSPENDED"}) String status) {
    }

    public record PartnerView(UUID partnerId, String name, String issuer, List<String> allowedIps, String status,
                              List<KeyView> keys, List<MerchantLinkView> merchants) {
    }

    public record KeyView(String kid, String alg, String status, Instant createdAt, Instant revokedAt) {
    }

    public record MerchantLinkView(String merchantCode, UUID merchantId, UUID tenantId, String status,
                                   boolean requireTerminal, boolean bindRedeemToHolder, List<String> fundingMethods,
                                   BigDecimal maxValue, Integer perPhoneDailyCount, BigDecimal perPhoneDailyValue) {
    }
}
