package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Wallet;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.LoyaltyTransactionRepository;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The P2P transfer ownership contract (audit: the merchant-admin wallet-drain).
 *
 * <p>The direct endpoint must enforce STRICT ownership — the caller has to BE the
 * sender, with NO admin bypass — so a MERCHANT_ADMIN / SHOP_ADMIN can no longer
 * transfer FROM a customer's wallet. The QR-consume overload skips the check
 * because {@code QrService.issue} already proved the sender's ownership when the
 * signed, single-use token was minted.
 *
 * <p>Pure Mockito (no Docker/Spring in this sandbox, per CLAUDE.md): the point is
 * WHICH ownership check the service calls, and whether it debits, which is
 * exactly what a unit test can pin.
 */
class TransferAuthzTest {

    private final UserService users = mock(UserService.class);
    private final WalletService walletService = mock(WalletService.class);
    private final LoyaltyTransactionRepository transactions = mock(LoyaltyTransactionRepository.class);
    private final MerchantRepository merchants = mock(MerchantRepository.class);
    private final com.innbucks.loyaltyservice.integration.MemberActivityNotifier notifier =
            mock(com.innbucks.loyaltyservice.integration.MemberActivityNotifier.class);

    private final TransferService service =
            new TransferService(users, walletService, transactions, merchants, notifier);

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID SENDER_ID = UUID.randomUUID();
    private static final UUID RECIPIENT_ID = UUID.randomUUID();

    private static LoyaltyUser user(UUID id, String phone) {
        LoyaltyUser u = new LoyaltyUser();
        u.setId(id);
        u.setPhoneNumber(phone);
        u.setMerchantId(UUID.randomUUID());
        return u;
    }

    private static Dtos.TransferRequest req() {
        return new Dtos.TransferRequest(SENDER_ID, RECIPIENT_ID, null, new BigDecimal("10"), "note");
    }

    @Test
    @DisplayName("DRAIN BLOCKED: the direct transfer enforces requireCallerOwns (no admin bypass) and never debits")
    void directTransferEnforcesStrictOwnership() {
        LoyaltyUser sender = user(SENDER_ID, "+263770000001");
        when(users.require(TENANT, SENDER_ID)).thenReturn(sender);
        // A MERCHANT_ADMIN would pass requireCallerOwnsOrIsAdmin but NOT
        // requireCallerOwns — model that: the strict check refuses.
        doThrow(LoyaltyException.forbidden("NOT_WALLET_OWNER", "you can only act on your own loyalty account"))
                .when(users).requireCallerOwns(sender);

        assertThatThrownBy(() -> service.transfer(TENANT, req()))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> org.assertj.core.api.Assertions.assertThat(((LoyaltyException) e).getCode())
                        .isEqualTo("NOT_WALLET_OWNER"));

        // The STRICT check was used, and the admin-bypass one was not.
        verify(users).requireCallerOwns(sender);
        verify(users, never()).requireCallerOwnsOrIsAdmin(any());
        // Nothing moved.
        verify(walletService, never()).apply(any(), any(), any(), any(), any());
        verify(transactions, never()).save(any());
    }

    @Test
    @DisplayName("QR PATH: the enforce=false overload skips the caller-ownership check (token is the sender's consent)")
    void qrOverloadSkipsOwnership() {
        LoyaltyUser sender = user(SENDER_ID, "+263770000001");
        LoyaltyUser recipient = user(RECIPIENT_ID, "+263770000002");
        when(users.require(TENANT, SENDER_ID)).thenReturn(sender);
        when(users.require(TENANT, RECIPIENT_ID)).thenReturn(recipient);
        Wallet from = new Wallet();
        from.setId(UUID.randomUUID());
        from.setPhoneNumber(sender.getPhoneNumber());
        Wallet to = new Wallet();
        to.setId(UUID.randomUUID());
        to.setPhoneNumber(recipient.getPhoneNumber());
        when(walletService.mainWallet(sender.getPhoneNumber())).thenReturn(from);
        when(walletService.mainWallet(recipient.getPhoneNumber())).thenReturn(to);
        when(walletService.apply(any(), any(), any(), any(), any())).thenReturn(new BigDecimal("90"));
        when(walletService.totalBalance(any())).thenReturn(new BigDecimal("90"));

        service.transfer(TENANT, req(), false);

        // The ownership check is NOT consulted on the trusted QR path...
        verify(users, never()).requireCallerOwns(any());
        verify(users, never()).requireCallerOwnsOrIsAdmin(any());
        // ...and the transfer actually moved (both legs applied).
        verify(walletService, org.mockito.Mockito.times(2)).apply(any(), any(), any(), any(), any());
    }
}
