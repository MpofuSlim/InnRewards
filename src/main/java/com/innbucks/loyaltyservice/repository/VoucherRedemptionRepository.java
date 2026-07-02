package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.VoucherRedemption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface VoucherRedemptionRepository extends JpaRepository<VoucherRedemption, UUID> {
    List<VoucherRedemption> findByVoucherIdOrderByRedeemedAtDesc(UUID voucherId);
    List<VoucherRedemption> findTop100ByMerchantIdOrderByRedeemedAtDesc(UUID merchantId);

    /** Redemption count per voucher for a page of vouchers, so the detailed
     *  report shows redemptionCount without an N+1 per row. Row: [voucherId, count]. */
    @Query("SELECT r.voucherId, COUNT(r) FROM VoucherRedemption r "
            + "WHERE r.voucherId IN :ids GROUP BY r.voucherId")
    List<Object[]> countByVoucherIdIn(@Param("ids") List<UUID> ids);
}
