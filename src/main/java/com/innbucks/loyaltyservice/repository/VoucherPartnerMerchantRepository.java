package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.VoucherPartnerMerchant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VoucherPartnerMerchantRepository extends JpaRepository<VoucherPartnerMerchant, UUID> {

    Optional<VoucherPartnerMerchant> findByPartnerIdAndMerchantCode(UUID partnerId, String merchantCode);

    Optional<VoucherPartnerMerchant> findByPartnerIdAndMerchantId(UUID partnerId, UUID merchantId);

    List<VoucherPartnerMerchant> findByPartnerIdOrderByMerchantCodeAsc(UUID partnerId);
}
