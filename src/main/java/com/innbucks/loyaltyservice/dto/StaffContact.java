package com.innbucks.loyaltyservice.dto;

import java.util.UUID;

/**
 * Loyalty-side projection of ONE row from user-service's
 * {@code GET /users/internal/shop-staff/by-merchant/{merchantId}/contacts}
 * (the S2S surface added for the earn-integrity STAFF_RECIPIENT guard).
 *
 * <p>{@code phoneNumber} may be null — user-service deliberately includes
 * phoneless staff accounts so the {@code userUuid} stays available for the
 * phase-2 pair-detection report; {@link com.innbucks.loyaltyservice.service.StaffRegistry}
 * filters the nulls when building its match set. Unknown JSON fields fall
 * through ignored, per the trim-aggressively client convention.
 */
public record StaffContact(UUID userUuid, String phoneNumber) {}
