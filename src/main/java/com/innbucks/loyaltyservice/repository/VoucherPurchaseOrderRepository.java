package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.VoucherPurchaseOrder;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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

    // ---- Phone finders for the customer-support 360 (V55 indexes) ----
    // `phones` is every stored spelling of one number: these three columns are
    // written as the staff member typed them, never canonicalised.

    String ANY_PHONE = "(o.payerPhone IN :phones OR o.assigneePhone IN :phones OR o.senderPhone IN :phones)";

    /** Every order the phone appears on as payer, recipient or sender, newest first. */
    @Query(value = "SELECT o FROM VoucherPurchaseOrder o WHERE " + ANY_PHONE + " ORDER BY o.createdAt DESC, o.id",
            countQuery = "SELECT COUNT(o) FROM VoucherPurchaseOrder o WHERE " + ANY_PHONE)
    Page<VoucherPurchaseOrder> findByAnyPhone(@Param("phones") Collection<String> phones, Pageable pageable);

    boolean existsByPayerPhoneInOrAssigneePhoneInOrSenderPhoneIn(Collection<String> payer,
                                                                 Collection<String> assignee,
                                                                 Collection<String> sender);
}
