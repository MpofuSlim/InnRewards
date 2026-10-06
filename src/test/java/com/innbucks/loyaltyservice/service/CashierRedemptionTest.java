package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.client.UserServiceClient;
import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.LoyaltyTransaction;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Wallet;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.LoyaltyTransactionRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.repository.PhoneRegistrationRepository;
import com.innbucks.loyaltyservice.repository.ShopRepository;
import com.innbucks.loyaltyservice.repository.WalletRepository;
import com.innbucks.loyaltyservice.security.CallerDetails;
import com.innbucks.loyaltyservice.security.MerchantAuthz;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Cashiers burn points at the till (owner decision 2026-10). The REAL ownership
 * rule ({@link UserService#requireCallerMayRedeemFor}) and the REAL merchant
 * rule ({@link MerchantAuthz}) are wired into the redemption path here — only
 * the repositories and the wallet are mocks — because the old controller test
 * mocked the whole service and so never noticed that every cashier burn was a
 * 403 {@code NOT_WALLET_OWNER}.
 */
class CashierRedemptionTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID MERCHANT = UUID.randomUUID();
    private static final UUID OTHER_MERCHANT = UUID.randomUUID();
    private static final UUID SHOP = UUID.randomUUID();
    private static final UUID ORG = UUID.randomUUID();
    private static final UUID CUSTOMER_ID = UUID.randomUUID();
    private static final String CUSTOMER_PHONE = "+263771230001";
    private static final String CASHIER_PHONE = "+263771230002";

    private final LoyaltyUserRepository loyaltyUsers = mock(LoyaltyUserRepository.class);
    private final PhoneRegistrationRepository registrations = mock(PhoneRegistrationRepository.class);
    private final OnDemandEligibilityCheck onDemand = mock(OnDemandEligibilityCheck.class);
    private final UserService users = new UserService(loyaltyUsers, mock(WalletRepository.class),
            mock(UserServiceClient.class), mock(LoyaltyMetrics.class), registrations, onDemand);

    private final MerchantRepository merchantRepo = mock(MerchantRepository.class);
    private final MerchantAuthz merchantAuthz = new MerchantAuthz(merchantRepo, mock(ShopRepository.class));
    private final MerchantService merchants = mock(MerchantService.class);
    private final WalletService walletService = mock(WalletService.class);
    private final LoyaltyTransactionRepository transactions = mock(LoyaltyTransactionRepository.class);
    private final RedemptionRateService rateService = mock(RedemptionRateService.class);

    @SuppressWarnings("unchecked")
    private final RedemptionService service = new RedemptionService(users, merchants, walletService,
            transactions, mock(LoyaltyMetrics.class), rateService,
            mock(com.innbucks.loyaltyservice.integration.MemberActivityNotifier.class),
            (ObjectProvider<RedemptionService>) mock(ObjectProvider.class),
            new com.innbucks.loyaltyservice.config.SupportedCurrencies("USD", "USD"),
            new ExchangeRateService(mock(com.innbucks.loyaltyservice.repository.ExchangeRateRepository.class),
                    new com.innbucks.loyaltyservice.config.SupportedCurrencies("USD", "USD"),
                    new BigDecimal("25")),
            merchantAuthz);

    private LoyaltyUser customer;

    @BeforeEach
    void seed() {
        customer = new LoyaltyUser();
        customer.setId(CUSTOMER_ID);
        customer.setTenantId(TENANT);
        customer.setPhoneNumber(CUSTOMER_PHONE);
        customer.setStatus(LoyaltyUser.Status.ACTIVE);
        when(loyaltyUsers.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer));

        for (UUID id : List.of(MERCHANT, OTHER_MERCHANT)) {
            Merchant m = new Merchant();
            m.setId(id);
            m.setTenantId(TENANT);
            m.setCurrency("USD");
            m.setOrganizationId(id.equals(MERCHANT) ? ORG : UUID.randomUUID());
            when(merchants.requireMerchant(TENANT, id)).thenReturn(m);
            when(merchantRepo.findById(id)).thenReturn(Optional.of(m));
        }

        Wallet w = new Wallet();
        w.setId(UUID.randomUUID());
        w.setPhoneNumber(CUSTOMER_PHONE);
        when(walletService.mainWallet(CUSTOMER_PHONE)).thenReturn(w);
        when(walletService.apply(any(), any(), any(), any(), any())).thenReturn(new BigDecimal("400"));
        when(rateService.valueOf(new BigDecimal("100"), "USD")).thenReturn(new BigDecimal("1.0000"));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    // ---- the redemption path ----

    @Test
    void aCashier_burnsACustomersPoints_atItsOwnMerchant_andTheRowCarriesItsShop() {
        authenticate("ROLE_SHOP_USER", MERCHANT, SHOP, CASHIER_PHONE, null);

        RedemptionService.RedemptionResult r = service.redeemPoints(TENANT, MERCHANT, burn(MERCHANT), true);

        assertThat(r.balance()).isEqualByComparingTo("400");
        verify(walletService).apply(any(), eq(new BigDecimal("-100")), any(), any(), eq(TENANT));
        LoyaltyTransaction row = savedRow();
        assertThat(row.getMerchantId()).isEqualTo(MERCHANT);
        assertThat(row.getShopId()).as("a cashier's burn shows up in /transactions/my-shop").isEqualTo(SHOP);
        assertThat(row.getUserId()).isEqualTo(CUSTOMER_ID);
    }

    @Test
    void aCashier_cannotBurnAtAnotherMerchant_andTheSpendGateIsNeverReached() {
        // PENDING, so reaching the spend gate WOULD consult the registration fact.
        customer.setStatus(LoyaltyUser.Status.PENDING);
        authenticate("ROLE_SHOP_USER", MERCHANT, SHOP, CASHIER_PHONE, null);

        assertThatThrownBy(() -> service.redeemPoints(TENANT, OTHER_MERCHANT, burn(OTHER_MERCHANT), true))
                .isInstanceOfSatisfying(LoyaltyException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("NOT_MERCHANT_OWNER");
                    assertThat(e.getMessage()).isEqualTo("You can only act on merchants you administer.");
                });

        verify(walletService, never()).apply(any(), any(), any(), any(), any());
        verifyNoInteractions(registrations, onDemand);
    }

    @Test
    void aShopAdmin_isPinnedTheSameWay() {
        authenticate("ROLE_SHOP_ADMIN", MERCHANT, SHOP, CASHIER_PHONE, null);

        assertThatThrownBy(() -> service.redeemPoints(TENANT, OTHER_MERCHANT, burn(OTHER_MERCHANT), true))
                .extracting(e -> ((LoyaltyException) e).getCode()).isEqualTo("NOT_MERCHANT_OWNER");
        assertThatCode(() -> service.redeemPoints(TENANT, MERCHANT, burn(MERCHANT), true))
                .doesNotThrowAnyException();
    }

    @Test
    void aMerchantAdmin_burnsOnlyAtItsOrganizationsMerchants() {
        authenticate("ROLE_MERCHANT_ADMIN", null, null, null, ORG);

        assertThatThrownBy(() -> service.redeemPoints(TENANT, OTHER_MERCHANT, burn(OTHER_MERCHANT), true))
                .extracting(e -> ((LoyaltyException) e).getCode()).isEqualTo("NOT_MERCHANT_OWNER");

        service.redeemPoints(TENANT, MERCHANT, burn(MERCHANT), true);
        assertThat(savedRow().getShopId()).as("no shop claim, no shop").isNull();
    }

    @Test
    void superAdmin_burnsAnywhere() {
        authenticate("ROLE_SUPER_ADMIN", null, null, null, null);

        assertThatCode(() -> service.redeemPoints(TENANT, OTHER_MERCHANT, burn(OTHER_MERCHANT), true))
                .doesNotThrowAnyException();
    }

    @Test
    void aCustomer_burnsTheirOwnWallet_withNoMerchantAdministration() {
        authenticate("ROLE_CUSTOMER", null, null, CUSTOMER_PHONE, null);

        service.redeemPoints(TENANT, MERCHANT, burn(MERCHANT), true);

        verify(walletService).apply(any(), eq(new BigDecimal("-100")), any(), any(), eq(TENANT));
        assertThat(savedRow().getShopId()).isNull();
    }

    @Test
    void aCustomer_stillCannotBurnSomeoneElsesWallet() {
        authenticate("ROLE_CUSTOMER", null, null, "+263771239999", null);

        assertThatThrownBy(() -> service.redeemPoints(TENANT, MERCHANT, burn(MERCHANT), true))
                .isInstanceOfSatisfying(LoyaltyException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("NOT_WALLET_OWNER");
                    assertThat(e.getMessage()).isEqualTo("you can only act on your own loyalty account");
                });
        verify(walletService, never()).apply(any(), any(), any(), any(), any());
        verify(merchants, never()).requireMerchant(any(), any());
    }

    @Test
    void aCustomerWhoAlsoHoldsAStaffRole_isTreatedAsStaff() {
        // Not a PLAIN customer, so the merchant pin applies even to their own wallet.
        authenticate(List.of("ROLE_CUSTOMER", "ROLE_SHOP_USER"), MERCHANT, SHOP, CUSTOMER_PHONE, null);

        assertThatThrownBy(() -> service.redeemPoints(TENANT, OTHER_MERCHANT, burn(OTHER_MERCHANT), true))
                .extracting(e -> ((LoyaltyException) e).getCode()).isEqualTo("NOT_MERCHANT_OWNER");
    }

    @Test
    void theS2sPath_stampsNoShop_andRunsNoCallerChecks() {
        // A cashier token on the context (guest checkout runs under one) must not
        // leak its shop onto an S2S burn, which resolves its own attribution.
        authenticate("ROLE_SHOP_USER", OTHER_MERCHANT, SHOP, CASHIER_PHONE, null);

        service.redeemPoints(TENANT, MERCHANT, burn(MERCHANT));

        assertThat(savedRow().getShopId()).isNull();
    }

    // ---- the two ownership rules side by side ----

    @Test
    void requireCallerMayRedeemFor_admitsEveryTillRole_andTheOwner() {
        for (String role : List.of("ROLE_SUPER_ADMIN", "ROLE_MERCHANT_ADMIN", "ROLE_SHOP_ADMIN", "ROLE_SHOP_USER")) {
            authenticate(role, null, null, CASHIER_PHONE, null);
            assertThatCode(() -> users.requireCallerMayRedeemFor(customer)).as(role).doesNotThrowAnyException();
        }
        authenticate("ROLE_CUSTOMER", null, null, CUSTOMER_PHONE, null);
        assertThatCode(() -> users.requireCallerMayRedeemFor(customer)).doesNotThrowAnyException();
    }

    @Test
    void requireCallerMayRedeemFor_refusesAnyOtherRole() {
        authenticate("ROLE_TENANT_ADMIN", null, null, CASHIER_PHONE, null);
        assertThatThrownBy(() -> users.requireCallerMayRedeemFor(customer))
                .extracting(e -> ((LoyaltyException) e).getCode()).isEqualTo("NOT_WALLET_OWNER");
    }

    @Test
    void requireCallerOwnsOrIsAdmin_isUnchanged_aCashierGainsNothingElse() {
        // GET /users/{id}/transactions rides this check: SHOP_USER stays out.
        authenticate("ROLE_SHOP_USER", MERCHANT, SHOP, CASHIER_PHONE, null);
        assertThatThrownBy(() -> users.requireCallerOwnsOrIsAdmin(customer))
                .extracting(e -> ((LoyaltyException) e).getCode()).isEqualTo("NOT_WALLET_OWNER");

        for (String role : List.of("ROLE_SUPER_ADMIN", "ROLE_MERCHANT_ADMIN", "ROLE_SHOP_ADMIN")) {
            authenticate(role, null, null, CASHIER_PHONE, null);
            assertThatCode(() -> users.requireCallerOwnsOrIsAdmin(customer)).as(role).doesNotThrowAnyException();
        }
        authenticate("ROLE_CUSTOMER", null, null, CUSTOMER_PHONE, null);
        assertThatCode(() -> users.requireCallerOwnsOrIsAdmin(customer)).doesNotThrowAnyException();
    }

    // ---- helpers ----

    private static Dtos.RedemptionRequest burn(UUID merchantId) {
        return new Dtos.RedemptionRequest(merchantId, CUSTOMER_ID, new BigDecimal("100"), "till burn", null);
    }

    private LoyaltyTransaction savedRow() {
        ArgumentCaptor<LoyaltyTransaction> cap = ArgumentCaptor.forClass(LoyaltyTransaction.class);
        verify(transactions, org.mockito.Mockito.atLeastOnce()).save(cap.capture());
        return cap.getValue();
    }

    private static void authenticate(String role, UUID merchantId, UUID shopId, String phone, UUID org) {
        authenticate(List.of(role), merchantId, shopId, phone, org);
    }

    private static void authenticate(List<String> roles, UUID merchantId, UUID shopId, String phone, UUID org) {
        var auth = new UsernamePasswordAuthenticationToken("caller@test.local", null,
                roles.stream().map(SimpleGrantedAuthority::new).toList());
        auth.setDetails(new CallerDetails(merchantId, shopId, phone, UUID.randomUUID(), org));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
