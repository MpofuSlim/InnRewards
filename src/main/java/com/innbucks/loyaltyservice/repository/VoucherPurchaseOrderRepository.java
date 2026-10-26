package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.VoucherPurchaseOrder;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
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

    // ---- Partner orders (V62) ----

    /** The partner's idempotency key, unique per merchant (uq_vpo_partner_reference). */
    Optional<VoucherPurchaseOrder> findByMerchantIdAndPartnerReference(UUID merchantId, String partnerReference);

    /** Row lock for the funding writers. Only ever called in a transaction that
     *  has not read the order first (see WalletRepository.lockForUpdate for the
     *  trap a locking query falls into otherwise). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from VoucherPurchaseOrder o where o.id = :id")
    Optional<VoucherPurchaseOrder> lockById(@Param("id") UUID id);

    /** A possible double sale at the same terminal: same phone and amount, recently,
     *  not failed. Two queries rather than a nullable bind on the terminal. */
    @Query("select count(o) from VoucherPurchaseOrder o where o.partnerId = :partnerId "
            + "and o.channel = :channel and o.terminalId = :terminalId and o.assigneePhone = :phone "
            + "and o.amount = :amount and o.createdAt > :since and o.id <> :self "
            + "and (o.fundingStatus is null or o.fundingStatus <> com.innbucks.loyaltyservice.entity.VoucherPurchaseOrder.FundingStatus.FAILED)")
    long countRecentSameSaleAtTerminal(@Param("partnerId") UUID partnerId, @Param("channel") String channel,
                                       @Param("terminalId") String terminalId, @Param("phone") String phone,
                                       @Param("amount") BigDecimal amount,
                                       @Param("since") Instant since, @Param("self") UUID self);

    @Query("select count(o) from VoucherPurchaseOrder o where o.partnerId = :partnerId "
            + "and o.channel = :channel and o.terminalId is null and o.assigneePhone = :phone "
            + "and o.amount = :amount and o.createdAt > :since and o.id <> :self "
            + "and (o.fundingStatus is null or o.fundingStatus <> com.innbucks.loyaltyservice.entity.VoucherPurchaseOrder.FundingStatus.FAILED)")
    long countRecentSameSaleWithoutTerminal(@Param("partnerId") UUID partnerId, @Param("channel") String channel,
                                            @Param("phone") String phone,
                                            @Param("amount") BigDecimal amount,
                                            @Param("since") Instant since, @Param("self") UUID self);

    /** Orders for one phone since a moment, not failed: the per-phone daily limits. */
    @Query("select count(o) from VoucherPurchaseOrder o where o.partnerId = :partnerId "
            + "and o.assigneePhone = :phone and o.createdAt >= :since and o.id <> :self "
            + "and (o.fundingStatus is null or o.fundingStatus <> com.innbucks.loyaltyservice.entity.VoucherPurchaseOrder.FundingStatus.FAILED)")
    long countForPhoneSince(@Param("partnerId") UUID partnerId, @Param("phone") String phone,
                            @Param("since") Instant since, @Param("self") UUID self);

    @Query("select coalesce(sum(o.amount), 0) from VoucherPurchaseOrder o where o.partnerId = :partnerId "
            + "and o.assigneePhone = :phone and o.createdAt >= :since and o.id <> :self "
            + "and (o.fundingStatus is null or o.fundingStatus <> com.innbucks.loyaltyservice.entity.VoucherPurchaseOrder.FundingStatus.FAILED)")
    BigDecimal sumForPhoneSince(@Param("partnerId") UUID partnerId, @Param("phone") String phone,
                                          @Param("since") Instant since, @Param("self") UUID self);

    /** Partner orders whose funding is still open, or funded with no voucher yet:
     *  the reconciler's sweep, oldest first (idx_vpo_partner_open_funding). */
    @Query("select o.id from VoucherPurchaseOrder o where o.partnerId is not null and ("
            + "o.fundingStatus in (com.innbucks.loyaltyservice.entity.VoucherPurchaseOrder.FundingStatus.AWAITING, "
            + "com.innbucks.loyaltyservice.entity.VoucherPurchaseOrder.FundingStatus.PENDING) "
            + "or (o.fundingStatus = com.innbucks.loyaltyservice.entity.VoucherPurchaseOrder.FundingStatus.CONFIRMED "
            + "and o.voucherId is null)) order by o.createdAt asc")
    List<UUID> findOpenPartnerFundingIds(Pageable page);
}
