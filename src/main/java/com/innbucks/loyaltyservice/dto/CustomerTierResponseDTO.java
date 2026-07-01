package com.innbucks.loyaltyservice.dto;

// Local mirror of user-service's CustomerTierResponseDTO. Used when calling
// GET /auth/customer/tier to validate that a phone number belongs to a real
// user-service customer before creating a LoyaltyUser projection.
public record CustomerTierResponseDTO(String phoneNumber, int currentTier, Integer nextTier) {}
