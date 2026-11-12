package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.VoucherRedeemPayment;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Payments of Flow 2 codes at the issuer's agent (V69). */
public interface VoucherRedeemPaymentRepository extends JpaRepository<VoucherRedeemPayment, UUID> {

    /** The redeem's reference is unique per merchant (uq_vrp_reference). */
    Optional<VoucherRedeemPayment> findByMerchantIdAndReference(UUID merchantId, String reference);

    Optional<VoucherRedeemPayment> findByRedemptionId(UUID redemptionId);

    /** A payment open, or confirmed and being recorded, for this code. */
    boolean existsByOrderIdAndStatusIn(UUID orderId, Collection<VoucherRedeemPayment.Status> statuses);

    /** Payment evidence already behind a Flow 2 payment that was not refused (uq_vrp_evidence). */
    boolean existsByMerchantIdAndEvidenceKeyAndStatusNot(UUID merchantId, String evidenceKey,
                                                         VoucherRedeemPayment.Status status);

    /** The newest attempt for a code, whatever its state. */
    Optional<VoucherRedeemPayment> findFirstByOrderIdOrderByStartedAtDesc(UUID orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from VoucherRedeemPayment p where p.id = :id")
    Optional<VoucherRedeemPayment> lockById(@Param("id") UUID id);

    /** Payments still open, or confirmed with the voucher not yet recorded: the reconciler's sweep (idx_vrp_open). */
    @Query("select p.id from VoucherRedeemPayment p where "
            + "p.status = com.innbucks.loyaltyservice.entity.VoucherRedeemPayment.Status.PENDING "
            + "or (p.status = com.innbucks.loyaltyservice.entity.VoucherRedeemPayment.Status.CONFIRMED "
            + "and p.redemptionId is null) order by p.startedAt asc")
    List<UUID> findOpenIds(Pageable page);
}
