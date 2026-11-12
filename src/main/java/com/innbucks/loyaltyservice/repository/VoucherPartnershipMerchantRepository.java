package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.VoucherPartnershipMerchant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface VoucherPartnershipMerchantRepository
        extends JpaRepository<VoucherPartnershipMerchant, VoucherPartnershipMerchant.Key> {

    List<VoucherPartnershipMerchant> findByPartnershipIdOrderByAddedAtAsc(UUID partnershipId);

    List<VoucherPartnershipMerchant> findByPartnershipIdIn(Collection<UUID> partnershipIds);

    @Modifying
    @Query("delete from VoucherPartnershipMerchant m where m.partnershipId = :partnershipId and m.tenantId = :tenantId")
    int deleteSide(@Param("partnershipId") UUID partnershipId, @Param("tenantId") UUID tenantId);
}
