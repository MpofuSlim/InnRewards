package com.innbucks.loyaltyservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request and response shapes of tenant-to-tenant voucher partnerships (V68). */
public final class PartnershipDtos {

    private PartnershipDtos() {
    }

    @Schema(description = "Invite another tenant to accept your vouchers, and yours theirs.")
    public record InviteRequest(
            @Schema(example = "bolabet", description = "Required. The other tenant's code.")
            @NotBlank @Size(max = 50) String partnerTenantCode,
            @Schema(description = "Required. Your merchants that take part: their vouchers can name the other "
                    + "side's merchants, and they accept the other side's vouchers. 1 to 50.")
            @NotEmpty @Size(max = 50) List<@NotNull UUID> merchantIds) {
    }

    @Schema(description = "The merchants of YOUR side of the partnership.")
    public record MerchantsRequest(
            @Schema(description = "Required. 1 to 50 of your own merchants.")
            @NotEmpty @Size(max = 50) List<@NotNull UUID> merchantIds) {
    }

    public record SuspendRequest(
            @Schema(example = "Settlement dispute under review", description = "Required. Why InnBucks is "
                    + "suspending it.")
            @NotBlank @Size(max = 500) String reason) {
    }

    public record MerchantView(UUID merchantId, String name) {
    }

    public record Side(UUID tenantId, String tenantCode, String tenantName, List<MerchantView> merchants) {
    }

    public record PartnershipView(
            UUID id,
            @Schema(example = "ACTIVE", allowableValues = {"INVITED", "ACTIVE", "SUSPENDED", "DECLINED", "ENDED"})
            String status,
            @Schema(description = "True when the staging switch accepted it, not the other side.")
            boolean autoAccepted,
            Side inviter,
            Side invitee,
            Instant invitedAt,
            Instant decidedAt,
            Instant endedAt,
            Instant suspendedAt,
            String suspensionReason,
            @Schema(description = "Who invited, answered, ended or suspended it. Shown to InnBucks only.")
            String invitedBy,
            String decidedBy,
            String endedBy,
            String suspendedBy) {
    }
}
