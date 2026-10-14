package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.client.UserServiceClient;
import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.LoyaltyTransaction;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.TransactionType;
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
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The phone-keyed burn ({@code POST /loyalty/redeem} with {@code assigneePhone}),
 * with the REAL {@link UserService} (phone normalisation, the tenant-scoped
 * lookup, the ownership rule) and the REAL {@link MerchantAuthz}. Only the
 * repositories and the wallet are mocks. The service is a spy so the test can
 * prove the burn never reaches {@code findOrCreatePending}: a till that mistypes
 * a number must get a 404, not a freshly minted account.
 */
class PhoneKeyedRedemptionTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID MERCHANT = UUID.randomUUID();
    private static final UUID OTHER_MERCHANT = UUID.randomUUID();
    private static final UUID SHOP = UUID.randomUUID();
    private static final UUID CUSTOMER_ID = UUID.randomUUID();
    private static final String CUSTOMER_PHONE = "+263771230001";
    private static final String CASHIER_PHONE = "+263771230002";
    private static final String REF = "POS-20261014-0042";

    private final LoyaltyUserRepository loyaltyUsers = mock(LoyaltyUserRepository.class);
    private final PhoneRegistrationRepository registrations = mock(PhoneRegistrationRepository.class);
    private final OnDemandEligibilityCheck onDemand = mock(OnDemandEligibilityCheck.class);
    private final UserService users = spy(new UserService(loyaltyUsers, mock(WalletRepository.class),
            mock(UserServiceClient.class), mock(LoyaltyMetrics.class), registrations, onDemand));

    private final MerchantRepository merchantRepo = mock(MerchantRepository.class);
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
            new MerchantAuthz(merchantRepo, mock(ShopRepository.class)));

    private LoyaltyUser customer;
    private Wallet wallet;

    @BeforeEach
    void seed() {
        customer = new LoyaltyUser();
        customer.setId(CUSTOMER_ID);
        customer.setTenantId(TENANT);
        customer.setPhoneNumber(CUSTOMER_PHONE);
        customer.setStatus(LoyaltyUser.Status.ACTIVE);
        when(loyaltyUsers.findByTenantIdAndPhoneNumber(TENANT, CUSTOMER_PHONE)).thenReturn(Optional.of(customer));
        when(loyaltyUsers.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer));

        for (UUID id : List.of(MERCHANT, OTHER_MERCHANT)) {
            Merchant m = new Merchant();
            m.setId(id);
            m.setTenantId(TENANT);
            m.setCurrency("USD");
            m.setOrganizationId(UUID.randomUUID());
            when(merchants.requireMerchant(TENANT, id)).thenReturn(m);
            when(merchantRepo.findById(id)).thenReturn(Optional.of(m));
        }

        wallet = new Wallet();
        wallet.setId(UUID.randomUUID());
        wallet.setPhoneNumber(CUSTOMER_PHONE);
        wallet.setBalance(new BigDecimal("500"));
        when(walletService.mainWallet(CUSTOMER_PHONE)).thenReturn(wallet);
        when(walletService.apply(any(), any(), any(), any(), any())).thenReturn(new BigDecimal("400"));
        when(rateService.valueOf(new BigDecimal("100"), "USD")).thenReturn(new BigDecimal("1.0000"));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    // ---- a till burns by phone ----

    @Test
    void aCashier_burnsByPhone_inAnyCommonSpelling_andTheRowCarriesItsShop() {
        for (String spelling : List.of("0771230001", "771230001", "+263771230001", "263771230001")) {
            authenticate("ROLE_SHOP_USER", MERCHANT, SHOP, CASHIER_PHONE);

            RedemptionService.RedemptionResult r =
                    service.redeemPoints(TENANT, MERCHANT, byPhone(spelling, null), true);

            assertThat(r.balance()).as(spelling).isEqualByComparingTo("400");
        }
        verify(walletService, org.mockito.Mockito.times(4))
                .apply(eq(wallet.getId()), eq(new BigDecimal("-100")), any(), any(), eq(TENANT));
        LoyaltyTransaction row = savedRow();
        assertThat(row.getUserId()).isEqualTo(CUSTOMER_ID);
        assertThat(row.getMerchantId()).isEqualTo(MERCHANT);
        assertThat(row.getShopId()).as("the burn shows up in the till's /my-shop feed").isEqualTo(SHOP);
        assertThat(row.getType()).isEqualTo(TransactionType.REDEMPTION);
        // Resolved through the tenant-scoped lookup — never a create.
        verify(users, atLeastOnce()).requireByPhone(TENANT, CUSTOMER_PHONE);
        verify(users, never()).findOrCreatePending(any(), any(), any());
        verify(users, never()).findOrEnrol(any(), any(), any());
        verify(loyaltyUsers, never()).save(any());
    }

    @Test
    void anUnknownPhone_is404_andNoAccountIsCreated() {
        authenticate("ROLE_SHOP_USER", MERCHANT, SHOP, CASHIER_PHONE);

        assertThatThrownBy(() -> service.redeemPoints(TENANT, MERCHANT, byPhone("0771239999", null), true))
                .isInstanceOfSatisfying(LoyaltyException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getCode()).isEqualTo("404 NOT_FOUND");
                    assertThat(e.getMessage()).isEqualTo("user not found");
                });

        verify(loyaltyUsers).findByTenantIdAndPhoneNumber(TENANT, "+263771239999");
        verify(loyaltyUsers, never()).save(any());
        verify(users, never()).findOrCreatePending(any(), any(), any());
        verify(walletService, never()).apply(any(), any(), any(), any(), any());
        verify(transactions, never()).save(any());
        verifyNoInteractions(merchants, registrations, onDemand);
    }

    @Test
    void anUnparseablePhone_is400BadPhone_likeTheEarn_andNothingIsLookedUp() {
        authenticate("ROLE_SHOP_USER", MERCHANT, SHOP, CASHIER_PHONE);

        assertThatThrownBy(() -> service.redeemPoints(TENANT, MERCHANT, byPhone("12ab", null), true))
                .isInstanceOfSatisfying(LoyaltyException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getCode()).isEqualTo("BAD_PHONE");
                    assertThat(e.getMessage()).isEqualTo("Invalid phone number: 12ab");
                });
        verifyNoInteractions(loyaltyUsers, merchants, walletService, transactions);
    }

    @Test
    void aCashierBurningByPhone_atAnotherMerchant_isRefusedBeforeTheSpendGate() {
        customer.setStatus(LoyaltyUser.Status.PENDING); // reaching the gate WOULD consult the fact
        authenticate("ROLE_SHOP_USER", MERCHANT, SHOP, CASHIER_PHONE);

        assertThatThrownBy(() -> service.redeemPoints(TENANT, OTHER_MERCHANT, byPhone(CUSTOMER_PHONE, null), true))
                .isInstanceOfSatisfying(LoyaltyException.class,
                        e -> assertThat(e.getCode()).isEqualTo("NOT_MERCHANT_OWNER"));
        verify(walletService, never()).apply(any(), any(), any(), any(), any());
        verifyNoInteractions(registrations, onDemand);
    }

    @Test
    void aPendingHolder_byPhone_isRefusedByTheSameSpendGate() {
        customer.setStatus(LoyaltyUser.Status.PENDING);
        authenticate("ROLE_SHOP_USER", MERCHANT, SHOP, CASHIER_PHONE);

        assertThatThrownBy(() -> service.redeemPoints(TENANT, MERCHANT, byPhone(CUSTOMER_PHONE, null), true))
                .isInstanceOfSatisfying(LoyaltyException.class,
                        e -> assertThat(e.getCode()).isEqualTo("USER_PENDING"));
        verify(users).requireSpendable(customer);
        verify(walletService, never()).apply(any(), any(), any(), any(), any());
    }

    // ---- a customer is bound to their own phone ----

    @Test
    void aCustomer_burnsByTheirOwnPhone() {
        authenticate("ROLE_CUSTOMER", null, null, CUSTOMER_PHONE);

        service.redeemPoints(TENANT, MERCHANT, byPhone("0771230001", null), true);

        verify(walletService).apply(any(), eq(new BigDecimal("-100")), any(), any(), eq(TENANT));
        verify(users).requireCallerMayRedeemFor(customer);
        assertThat(savedRow().getShopId()).isNull();
    }

    /**
     * Same refusal for an enrolled and an unenrolled number, and the lookup is
     * never made — otherwise 404-vs-403 would tell any customer which phones
     * hold an account in which program.
     */
    @Test
    void aCustomerNamingAnotherPhone_isNotWalletOwner_whetherOrNotThatPhoneIsEnrolled() {
        authenticate("ROLE_CUSTOMER", null, null, "+263771239999");

        for (String someoneElse : List.of(CUSTOMER_PHONE, "+263771238888")) {
            assertThatThrownBy(() -> service.redeemPoints(TENANT, MERCHANT, byPhone(someoneElse, null), true))
                    .as(someoneElse)
                    .isInstanceOfSatisfying(LoyaltyException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                        assertThat(e.getCode()).isEqualTo("NOT_WALLET_OWNER");
                        assertThat(e.getMessage()).isEqualTo("you can only act on your own loyalty account");
                    });
        }
        verify(loyaltyUsers, never()).findByTenantIdAndPhoneNumber(any(), anyString());
        verifyNoInteractions(merchants, walletService, transactions);
    }

    // ---- the replay is bound to its customer ----

    @Test
    void aPhoneKeyedReplay_forTheSameCustomer_replays() {
        authenticate("ROLE_SHOP_USER", MERCHANT, SHOP, CASHIER_PHONE);
        LoyaltyTransaction prior = priorRedemption(CUSTOMER_ID);

        RedemptionService.RedemptionResult r =
                service.redeemPoints(TENANT, MERCHANT, byPhone("0771230001", REF), true);

        assertThat(r.transactionId()).isEqualTo(prior.getId());
        assertThat(r.balance()).isEqualByComparingTo("500");
        verify(walletService, never()).apply(any(), any(), any(), any(), any());
    }

    /**
     * Without the binding this was a phone → balance oracle for any till: name a
     * phone and a REDEMPTION reference the till already used, get that phone's
     * balance back with no debit.
     */
    @Test
    void aPhoneKeyedReplay_ofAnotherCustomersReference_is409_andRevealsNothing() {
        authenticate("ROLE_SHOP_USER", MERCHANT, SHOP, CASHIER_PHONE);
        priorRedemption(UUID.randomUUID());

        assertThatThrownBy(() -> service.redeemPoints(TENANT, MERCHANT, byPhone("0771230001", REF), true))
                .isInstanceOfSatisfying(LoyaltyException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getCode()).isEqualTo("DUPLICATE_REFERENCE");
                    assertThat(e.getMessage()).isEqualTo("transaction with this merchant reference already exists");
                });
        verify(walletService, never()).mainWallet(any());
        verify(walletService, never()).apply(any(), any(), any(), any(), any());
    }

    // ---- helpers ----

    private LoyaltyTransaction priorRedemption(UUID owner) {
        LoyaltyTransaction prior = new LoyaltyTransaction();
        prior.setId(UUID.randomUUID());
        prior.setType(TransactionType.REDEMPTION);
        prior.setMerchantId(MERCHANT);
        prior.setUserId(owner);
        prior.setReference(REF);
        when(transactions.findFirstByMerchantIdAndReference(MERCHANT, REF)).thenReturn(Optional.of(prior));
        return prior;
    }

    private static Dtos.RedemptionRequest byPhone(String phone, String reference) {
        return new Dtos.RedemptionRequest(MERCHANT, null, new BigDecimal("100"), "till burn", reference,
                null, null, phone);
    }

    private LoyaltyTransaction savedRow() {
        ArgumentCaptor<LoyaltyTransaction> cap = ArgumentCaptor.forClass(LoyaltyTransaction.class);
        verify(transactions, atLeastOnce()).save(cap.capture());
        return cap.getValue();
    }

    private static void authenticate(String role, UUID merchantId, UUID shopId, String phone) {
        var auth = new UsernamePasswordAuthenticationToken("caller@test.local", null,
                List.of(new SimpleGrantedAuthority(role)));
        auth.setDetails(new CallerDetails(merchantId, shopId, phone, UUID.randomUUID(), null));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
