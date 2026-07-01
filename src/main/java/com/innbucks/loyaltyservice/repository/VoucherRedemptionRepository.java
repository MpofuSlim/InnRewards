package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.VoucherRedemption;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VoucherRedemptionRepository extends JpaRepository<VoucherRedemption, UUID> {
    List<VoucherRedemption> findByVoucherIdOrderByRedeemedAtDesc(UUID voucherId);
    List<VoucherRedemption> findTop100ByMerchantIdOrderByRedeemedAtDesc(UUID merchantId);
}
