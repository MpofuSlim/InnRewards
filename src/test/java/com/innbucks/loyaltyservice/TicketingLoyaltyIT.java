package com.innbucks.loyaltyservice;

import com.innbucks.loyaltyservice.client.UserServiceClient;
import com.innbucks.loyaltyservice.dto.CustomerTierResponseDTO;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.service.TicketingLoyaltyService;
import com.innbucks.loyaltyservice.service.UserService;
import com.innbucks.loyaltyservice.service.WalletService;
import com.innbucks.loyaltyservice.testsupport.PostgresIntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Ticketing↔loyalty bridge (#262): organizer maps to one auto-provisioned
 * merchant; ticket earn/redeem run against it, idempotent on the booking
 * reference. Postgres via Testcontainers; unique organizer + phone per method
 * since merchants/wallets are global and these ITs don't roll back.
 */
class TicketingLoyaltyIT extends PostgresIntegrationTestBase {

    @Autowired TicketingLoyaltyService ticketing;
    @Autowired MerchantRepository merchants;
    @Autowired WalletService walletService;
    @Autowired UserService userService;

    @MockitoBean UserServiceClient userServiceClient;

    private UUID organizerUuid;
    private String phone;

    @BeforeEach
    void setUp() {
        when(userServiceClient.getCustomerTier(anyString()))
                .thenAnswer(inv -> Optional.of(new CustomerTierResponseDTO(inv.getArgument(0), 1, 2)));
        organizerUuid = UUID.randomUUID();
        phone = "+26378" + (System.nanoTime() % 1_000_000_000L);
    }

    @Test
    void earn_provisions_the_organizer_merchant_and_awards_points() {
        assertThat(merchants.findByOrganizerUuid(organizerUuid)).as("no merchant before first earn").isEmpty();

        TicketingLoyaltyService.EarnResult r = ticketing.earn(organizerUuid, phone, new BigDecimal("25"), "bk-earn-1");

        // Default earn rate 1/unit -> 25 points.
        assertThat(r.replayed()).isFalse();
        assertThat(r.pointsEarned()).isEqualByComparingTo("25");
        assertThat(r.balanceAfter()).isEqualByComparingTo("25");

        Optional<Merchant> m = merchants.findByOrganizerUuid(organizerUuid);
        assertThat(m).as("merchant auto-provisioned").isPresent();
        assertThat(m.get().getTenantId()).isEqualTo(TicketingLoyaltyService.TICKETING_TENANT_ID);
        assertThat(r.merchantId()).isEqualTo(m.get().getId());
        assertThat(walletService.totalBalance(phone)).isEqualByComparingTo("25");
    }

    @Test
    void earn_is_idempotent_on_reference() {
        ticketing.earn(organizerUuid, phone, new BigDecimal("25"), "bk-earn-dup");
        TicketingLoyaltyService.EarnResult replay =
                ticketing.earn(organizerUuid, phone, new BigDecimal("25"), "bk-earn-dup");

        assertThat(replay.replayed()).as("same reference replays, no second credit").isTrue();
        assertThat(walletService.totalBalance(phone)).isEqualByComparingTo("25");
    }

    @Test
    void redeem_burns_points_and_is_idempotent() {
        // Earn while PENDING, then promote to ACTIVE (register-then-spend flow).
        ticketing.earn(organizerUuid, phone, new BigDecimal("100"), "bk-redeem-earn");
        userService.promoteByPhone(phone);

        TicketingLoyaltyService.RedeemResult r =
                ticketing.redeem(organizerUuid, phone, new BigDecimal("40"), "bk-redeem-1");
        assertThat(r.balanceAfter()).isEqualByComparingTo("60");
        assertThat(walletService.totalBalance(phone)).isEqualByComparingTo("60");

        // Replay with the same reference must not debit again.
        TicketingLoyaltyService.RedeemResult replay =
                ticketing.redeem(organizerUuid, phone, new BigDecimal("40"), "bk-redeem-1");
        assertThat(replay.balanceAfter()).isEqualByComparingTo("60");
        assertThat(walletService.totalBalance(phone)).isEqualByComparingTo("60");
    }

    @Test
    void earn_and_redeem_with_the_same_booking_reference_do_not_collide() {
        // Production split payment: booking sends the same bookingId to BOTH legs
        // against one organizer merchant. uq_txn_merchant_reference spans PURCHASE
        // and REDEMPTION, so the bridge must keep their references distinct.
        ticketing.earn(organizerUuid, phone, new BigDecimal("100"), "bk-split");
        userService.promoteByPhone(phone);
        TicketingLoyaltyService.RedeemResult r =
                ticketing.redeem(organizerUuid, phone, new BigDecimal("30"), "bk-split");
        assertThat(r.balanceAfter()).isEqualByComparingTo("70");
        assertThat(walletService.totalBalance(phone)).isEqualByComparingTo("70");
    }

    @Test
    void redeem_for_an_unregistered_phone_fails_clearly() {
        // A phone that earned but never registered is PENDING -> not spendable.
        ticketing.earn(organizerUuid, phone, new BigDecimal("50"), "bk-pending-earn");

        assertThatThrownBy(() -> ticketing.redeem(organizerUuid, phone, new BigDecimal("10"), "bk-pending-redeem"))
                .isInstanceOf(LoyaltyException.class);
        assertThat(walletService.totalBalance(phone)).as("nothing burned").isEqualByComparingTo("50");
    }

    @Test
    void rule_returns_earn_and_redeem_rates_and_reuses_the_merchant() {
        TicketingLoyaltyService.TicketingRule rule = ticketing.rule(organizerUuid);
        assertThat(rule.earnRate()).isEqualByComparingTo("1");
        assertThat(rule.redeemRate()).isEqualByComparingTo("100");
        assertThat(rule.tenantId()).isEqualTo(TicketingLoyaltyService.TICKETING_TENANT_ID);

        // A subsequent earn for the same organizer reuses the one merchant.
        TicketingLoyaltyService.EarnResult r = ticketing.earn(organizerUuid, phone, new BigDecimal("10"), "bk-reuse");
        assertThat(r.merchantId()).isEqualTo(rule.merchantId());
        assertThat(merchants.findByOrganizerUuid(organizerUuid)).isPresent();
    }

    @Test
    void earn_without_a_phone_is_rejected() {
        assertThatThrownBy(() -> ticketing.earn(organizerUuid, "  ", new BigDecimal("10"), "bk-nophone"))
                .isInstanceOf(LoyaltyException.class)
                .hasMessageContaining("phoneNumber");
    }
}
