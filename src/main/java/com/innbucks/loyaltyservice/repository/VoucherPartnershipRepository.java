package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.VoucherPartnership;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VoucherPartnershipRepository extends JpaRepository<VoucherPartnership, UUID> {

    /** Every partnership the tenant is on either side of, newest first. */
    @Query("""
        select p from VoucherPartnership p
        where p.inviterTenantId = :tenantId or p.inviteeTenantId = :tenantId
        order by p.invitedAt desc, p.id desc
        """)
    List<VoucherPartnership> findInvolving(@Param("tenantId") UUID tenantId);

    List<VoucherPartnership> findAllByOrderByInvitedAtDescIdDesc();

    /** The live partnership between two tenants, whichever side invited; at most one (uq_voucher_partnership_live_pair). */
    @Query("""
        select p from VoucherPartnership p
        where ((p.inviterTenantId = :a and p.inviteeTenantId = :b)
            or (p.inviterTenantId = :b and p.inviteeTenantId = :a))
          and p.status in :statuses
        """)
    List<VoucherPartnership> findBetween(@Param("a") UUID a, @Param("b") UUID b,
                                         @Param("statuses") Collection<VoucherPartnership.Status> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from VoucherPartnership p where p.id = :id")
    Optional<VoucherPartnership> lockById(@Param("id") UUID id);
}
