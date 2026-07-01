package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.TenantMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TenantMemberRepository extends JpaRepository<TenantMember, UUID> {

    boolean existsByTenantIdAndEmail(UUID tenantId, String email);

    boolean existsByTenantIdAndUserId(UUID tenantId, UUID userId);

    List<TenantMember> findByTenantId(UUID tenantId);

    List<TenantMember> findByEmail(String email);

    List<TenantMember> findByUserId(UUID userId);

    Optional<TenantMember> findByTenantIdAndEmail(UUID tenantId, String email);

    Optional<TenantMember> findByTenantIdAndUserId(UUID tenantId, UUID userId);

    void deleteByTenantIdAndEmail(UUID tenantId, String email);

    void deleteByTenantIdAndUserId(UUID tenantId, UUID userId);
}
