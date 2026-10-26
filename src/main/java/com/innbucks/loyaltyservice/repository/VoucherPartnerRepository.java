package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.VoucherPartner;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface VoucherPartnerRepository extends JpaRepository<VoucherPartner, UUID> {

    Optional<VoucherPartner> findByIssuer(String issuer);
}
