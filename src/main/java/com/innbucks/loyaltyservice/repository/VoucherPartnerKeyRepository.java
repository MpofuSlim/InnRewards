package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.VoucherPartnerKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VoucherPartnerKeyRepository extends JpaRepository<VoucherPartnerKey, UUID> {

    Optional<VoucherPartnerKey> findByKid(String kid);

    List<VoucherPartnerKey> findByPartnerIdOrderByCreatedAtAsc(UUID partnerId);
}
