package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.MerchantAdminChange;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MerchantAdminChangeRepository extends JpaRepository<MerchantAdminChange, UUID> {

    /** A merchant's binding history, oldest first. */
    List<MerchantAdminChange> findByMerchantIdOrderByChangedAtAsc(UUID merchantId);
}
