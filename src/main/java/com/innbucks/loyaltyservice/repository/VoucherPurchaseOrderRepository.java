package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.VoucherPurchaseOrder;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface VoucherPurchaseOrderRepository extends JpaRepository<VoucherPurchaseOrder, UUID> {

    Optional<VoucherPurchaseOrder> findByOrderRef(String orderRef);

    /**
     * Pessimistic lock for the two confirmation writers (S2S confirm-payment
     * and staff confirm-cash) — without it a cash confirm racing a gateway
     * confirm could both read PENDING_PAYMENT and issue two vouchers for one
     * payment.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from VoucherPurchaseOrder o where o.orderRef = :orderRef")
    Optional<VoucherPurchaseOrder> lockByOrderRef(@Param("orderRef") String orderRef);
}
