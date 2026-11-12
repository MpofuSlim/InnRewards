package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.FeeSchedule;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * InnBucks' fee schedules (V67). Save and read only, by shape: a fee decision is
 * never updated or deleted (the table's trigger refuses both too).
 */
public interface FeeScheduleRepository extends Repository<FeeSchedule, UUID> {

    FeeSchedule save(FeeSchedule schedule);

    /**
     * The latest row of every scope (each tenant's default and each merchant's
     * own) in the given tenants, cleared rows included: a tombstone is how a
     * scope says "no schedule", so the resolver must see it to fall through.
     * One query however many merchants the caller prices; never call it with an
     * empty collection.
     */
    @Query(nativeQuery = true, value = """
            SELECT DISTINCT ON (tenant_id, merchant_id) *
              FROM fee_schedules
             WHERE tenant_id IN (:tenantIds)
             ORDER BY tenant_id, merchant_id, created_at DESC, id DESC
            """)
    List<FeeSchedule> findLatestForTenants(@Param("tenantIds") Collection<UUID> tenantIds);

    /** A tenant default's history, newest first. */
    List<FeeSchedule> findByTenantIdAndMerchantIdIsNullOrderByCreatedAtDescIdDesc(UUID tenantId, Pageable pageable);

    /** One merchant's own history, newest first. */
    List<FeeSchedule> findByMerchantIdOrderByCreatedAtDescIdDesc(UUID merchantId, Pageable pageable);
}
