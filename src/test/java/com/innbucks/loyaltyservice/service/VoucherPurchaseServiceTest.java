package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyProperties;
import com.innbucks.loyaltyservice.config.SupportedCurrencies;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.ExchangeRate;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.entity.VoucherPurchaseOrder;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.ExchangeRateRepository;
import com.innbucks.loyaltyservice.repository.VoucherPurchaseOrderRepository;
import com.innbucks.loyaltyservice.repository.VoucherRepository;
import com.innbucks.loyaltyservice.security.CallerDetails;
import com.innbucks.loyaltyservice.security.MerchantAuthz;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.*;

/**
 * Pins the pay-before-issue contract (V47):
 *
 * <ul>
 *   <li>an order snapshots a request a direct issue would ACCEPT, and refuses
 *       everything issue would refuse — at creation, on the staff caller,
 *       never after the customer paid;</li>
 *   <li>nothing is issued at creation; confirmation (gateway or cash) is the
 *       only issuer, exactly once, idempotent on replay;</li>
 *   <li>the cents cross-check refuses a mismatched confirm (100x guard);</li>
 *   <li>a LATE gateway confirmation is honoured (money already moved), while
 *       cash on an expired order is refused (money is being taken NOW).</li>
 * </ul>
 */
class VoucherPurchaseServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID MERCHANT = UUID.randomUUID();

    private static final SupportedCurrencies CURRENCIES = new SupportedCurrencies("USD,ZWG", "USD");

    private final VoucherPurchaseOrderRepository orders = mock(VoucherPurchaseOrderRepository.class);
    private final VoucherRepository vouchers = mock(VoucherRepository.class);
    private final VoucherService voucherService = mock(VoucherService.class);
    private final MerchantAuthz merchantAuthz = mock(MerchantAuthz.class);
    private final ExchangeRateRepository fxRates = mock(ExchangeRateRepository.class);

    private VoucherPurchaseService service;

    @BeforeEach
    void setUp() {
        LoyaltyProperties props = mock(LoyaltyProperties.class, RETURNS_DEEP_STUBS);
        when(props.voucher().purchaseOrderTtl()).thenReturn(Duration.ofMinutes(30));
        service = new VoucherPurchaseService(orders, vouchers, voucherService, merchantAuthz,
                CURRENCIES, new ExchangeRateService(fxRates, CURRENCIES, new BigDecimal("25")), props);

        Merchant m = new Merchant();
        m.setId(MERCHANT);
        m.setTenantId(TENANT);
        m.setName("Pizza Inn");
        m.setCurrency("USD");
        when(merchantAuthz.requireCallerAdministersMerchant(TENANT, MERCHANT)).thenReturn(m);
        when(orders.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Dtos.VoucherResponse issued = new Dtos.VoucherResponse(UUID.randomUUID(), "VCH-CODE",
                "DELIVERED", "SINGLE_USE", null, "+263786546765",
                "Tawanda Mpofu", "+263782608767", 1,
                new BigDecimal("5.00"), "USD", Instant.now(), null, new BigDecimal("5.00"));
        when(voucherService.issueFromOrder(any())).thenReturn(issued);
        when(vouchers.findById(any())).thenReturn(Optional.empty());
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static Dtos.PurchaseVoucherRequest request(BigDecimal value, String currency,
                                                       String senderPhone, String assigneePhone,
                                                       String payerPhone) {
        return new Dtos.PurchaseVoucherRequest(MERCHANT, null, value, currency, null,
                assigneePhone, "Sedrick Nyanyiwa", null,
                "Tawanda Mpofu", senderPhone, payerPhone,
                Voucher.DeliveryChannel.WHATSAPP, null);
    }

    private VoucherPurchaseOrder savedOrder() {
        ArgumentCaptor<VoucherPurchaseOrder> cap = ArgumentCaptor.forClass(VoucherPurchaseOrder.class);
        verify(orders).save(cap.capture());
        return cap.getValue();
    }

    private static VoucherPurchaseOrder pendingOrder() {
        VoucherPurchaseOrder o = new VoucherPurchaseOrder();
        o.setOrderRef("VCH-4F9A1C22B7D3");
        o.setTenantId(TENANT);
        o.setMerchantId(MERCHANT);
        o.setAmount(new BigDecimal("5.0000"));
        o.setCurrency("USD");
        o.setPayerPhone("+263782608767");
        o.setVoucherType(Voucher.VoucherType.SINGLE_USE);
        o.setUsageLimit(1);
        o.setExpiresAt(Instant.now().plus(Duration.ofMinutes(20)));
        return o;
    }

    // ------------------------------------------------------------------
    // Creation: snapshot + validation, nothing issued
    // ------------------------------------------------------------------

    @Test
    void create_snapshotsTheRequest_andIssuesNothing() {
        Dtos.VoucherPurchaseOrderResponse resp = service.create(TENANT,
                request(new BigDecimal("5.00"), "USD", "+263782608767", "+263786546765", null));

        VoucherPurchaseOrder o = savedOrder();
        assertThat(o.getOrderRef()).matches("VCH-[0-9A-F]{12}");
        assertThat(o.getStatus()).isEqualTo(VoucherPurchaseOrder.Status.PENDING_PAYMENT);
        assertThat(o.getAmount()).isEqualByComparingTo("5.00");
        assertThat(o.getCurrency()).isEqualTo("USD");
        // Payer defaults to the SENDER — the person gifting is the person paying.
        assertThat(o.getPayerPhone()).isEqualTo("+263782608767");
        assertThat(o.getSenderName()).isEqualTo("Tawanda Mpofu");
        assertThat(o.getAssigneeName()).isEqualTo("Sedrick Nyanyiwa");
        assertThat(o.getExpiresAt()).isAfter(Instant.now().plus(Duration.ofMinutes(25)));

        assertThat(resp.status()).isEqualTo("PENDING_PAYMENT");
        assertThat(resp.voucher()).isNull();
        verify(voucherService, never()).issueFromOrder(any());
    }

    @Test
    void create_payerFallsBackToAssignee_whenNoSenderAnywhere() {
        // No senderPhone in the request and no JWT phone on the context.
        service.create(TENANT, new Dtos.PurchaseVoucherRequest(MERCHANT, null,
                new BigDecimal("5.00"), "USD", null,
                "+263786546765", null, null, null, null, null, null, null));

        assertThat(savedOrder().getPayerPhone()).isEqualTo("+263786546765");
    }

    @Test
    void create_issuerIdentityAndSenderDefault_comeFromTheJwt() {
        var auth = new UsernamePasswordAuthenticationToken("admin@example.com", "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_MERCHANT_ADMIN")));
        auth.setDetails(new CallerDetails(null, null, "+263782608767", null));
        SecurityContextHolder.getContext().setAuthentication(auth);

        service.create(TENANT, new Dtos.PurchaseVoucherRequest(MERCHANT, null,
                new BigDecimal("5.00"), "USD", null,
                "+263786546765", null, null, "Tawanda Mpofu", null, null, null, null));

        VoucherPurchaseOrder o = savedOrder();
        assertThat(o.getSenderPhone()).isEqualTo("+263782608767");
        assertThat(o.getPayerPhone()).isEqualTo("+263782608767");
        assertThat(o.getIssuerEmail()).isEqualTo("admin@example.com");
        assertThat(o.getIssuerPhone()).isEqualTo("+263782608767");
    }

    @Test
    void create_refusesEverythingIssueWouldRefuse() {
        // No value / non-positive
        assertRefused(request(null, "USD", "+263782608767", null, null), "MISSING_VALUE");
        assertRefused(request(BigDecimal.ZERO, "USD", "+263782608767", null, null), "MISSING_VALUE");
        // Sub-cent value could never be charged exactly
        assertRefused(request(new BigDecimal("5.005"), "USD", "+263782608767", null, null), "AMOUNT_PRECISION");
        // Currency allowlist
        assertRefused(request(new BigDecimal("5.00"), "GBP", "+263782608767", null, null), "UNSUPPORTED_CURRENCY");
        // Supported but rateless — fails at CREATE, not after the customer paid
        when(fxRates.currentRate(nullable(UUID.class), anyString(), any(Instant.class)))
                .thenReturn(Optional.empty());
        assertRefused(request(new BigDecimal("267.00"), "ZWG", "+263782608767", null, null), "NO_FX_RATE");
        // MULTI_USE needs a limit
        assertThatThrownBy(() -> service.create(TENANT, new Dtos.PurchaseVoucherRequest(
                MERCHANT, Voucher.VoucherType.MULTI_USE, new BigDecimal("5.00"), "USD", null,
                null, null, null, null, "+263782608767", null, null, null)))
                .isInstanceOfSatisfying(LoyaltyException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("USAGE_LIMIT_REQUIRED"));
        // No phone anywhere: the prompt has to reach someone
        assertRefused(request(new BigDecimal("5.00"), "USD", null, null, null), "PAYER_PHONE_REQUIRED");

        verify(orders, never()).save(any());
    }

    private void assertRefused(Dtos.PurchaseVoucherRequest req, String code) {
        assertThatThrownBy(() -> service.create(TENANT, req))
                .isInstanceOfSatisfying(LoyaltyException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(code));
    }

    @Test
    void create_ratedNonUsdCurrency_isAccepted() {
        ExchangeRate r = new ExchangeRate();
        r.setId(UUID.randomUUID());
        r.setCurrency("ZWG");
        r.setRatePerUsd(new BigDecimal("26.700000"));
        r.setEffectiveFrom(Instant.now().minusSeconds(3600));
        r.setSource(ExchangeRate.Source.ADMIN);
        when(fxRates.currentRate(nullable(UUID.class), eq("ZWG"), any(Instant.class)))
                .thenReturn(Optional.of(r));

        service.create(TENANT, request(new BigDecimal("267.00"), "ZWG", "+263782608767", null, null));

        assertThat(savedOrder().getCurrency()).isEqualTo("ZWG");
    }

    // ------------------------------------------------------------------
    // Gateway confirmation (S2S)
    // ------------------------------------------------------------------

    @Test
    void confirmPayment_marksPaidAndIssues_exactlyOnce() {
        VoucherPurchaseOrder o = pendingOrder();
        when(orders.lockByOrderRef(o.getOrderRef())).thenReturn(Optional.of(o));

        var view = service.internalConfirmPayment(o.getOrderRef(), "TKZ-VCH-ABC123", 500);

        assertThat(o.getStatus()).isEqualTo(VoucherPurchaseOrder.Status.PAID);
        assertThat(o.getPaidVia()).isEqualTo(VoucherPurchaseOrder.PaidVia.GATEWAY);
        assertThat(o.getPaymentRef()).isEqualTo("TKZ-VCH-ABC123");
        assertThat(o.getVoucherId()).isNotNull();
        assertThat(view.status()).isEqualTo("PAID");
        verify(voucherService).issueFromOrder(o);
    }

    @Test
    void confirmPayment_sameRefReplay_isIdempotent_noSecondVoucher() {
        VoucherPurchaseOrder o = pendingOrder();
        when(orders.lockByOrderRef(o.getOrderRef())).thenReturn(Optional.of(o));
        service.internalConfirmPayment(o.getOrderRef(), "TKZ-VCH-ABC123", 500);

        var replay = service.internalConfirmPayment(o.getOrderRef(), "TKZ-VCH-ABC123", 500);

        assertThat(replay.status()).isEqualTo("PAID");
        verify(voucherService, times(1)).issueFromOrder(any());
    }

    @Test
    void confirmPayment_differentRefOnAPaidOrder_isRefused() {
        VoucherPurchaseOrder o = pendingOrder();
        when(orders.lockByOrderRef(o.getOrderRef())).thenReturn(Optional.of(o));
        service.internalConfirmPayment(o.getOrderRef(), "TKZ-VCH-ABC123", 500);

        assertThatThrownBy(() -> service.internalConfirmPayment(o.getOrderRef(), "TKZ-VCH-OTHER", 500))
                .isInstanceOfSatisfying(LoyaltyException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("ORDER_ALREADY_PAID"));
    }

    @Test
    void confirmPayment_amountMismatch_isRefused_nothingIssued() {
        VoucherPurchaseOrder o = pendingOrder();
        when(orders.lockByOrderRef(o.getOrderRef())).thenReturn(Optional.of(o));

        // The 100x guard's confirm leg: 500c order, 50000c reported.
        assertThatThrownBy(() -> service.internalConfirmPayment(o.getOrderRef(), "TKZ-VCH-ABC123", 50000))
                .isInstanceOfSatisfying(LoyaltyException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("AMOUNT_MISMATCH");
                    assertThat(ex.getStatus().value()).isEqualTo(422);
                });
        assertThat(o.getStatus()).isEqualTo(VoucherPurchaseOrder.Status.PENDING_PAYMENT);
        verify(voucherService, never()).issueFromOrder(any());
    }

    @Test
    void confirmPayment_lateButUncancelled_isStillHonoured() {
        // The money has already moved by the time confirm arrives — refusing
        // would strand a paid customer on an operator queue.
        VoucherPurchaseOrder o = pendingOrder();
        o.setExpiresAt(Instant.now().minus(Duration.ofMinutes(5)));
        when(orders.lockByOrderRef(o.getOrderRef())).thenReturn(Optional.of(o));

        var view = service.internalConfirmPayment(o.getOrderRef(), "TKZ-VCH-ABC123", 500);

        assertThat(view.status()).isEqualTo("PAID");
        verify(voucherService).issueFromOrder(o);
    }

    @Test
    void confirmPayment_cancelledOrder_isRefused() {
        VoucherPurchaseOrder o = pendingOrder();
        o.setStatus(VoucherPurchaseOrder.Status.CANCELLED);
        when(orders.lockByOrderRef(o.getOrderRef())).thenReturn(Optional.of(o));

        assertThatThrownBy(() -> service.internalConfirmPayment(o.getOrderRef(), "TKZ-VCH-ABC123", 500))
                .isInstanceOfSatisfying(LoyaltyException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("ORDER_NOT_CONFIRMABLE"));
        verify(voucherService, never()).issueFromOrder(any());
    }

    // ------------------------------------------------------------------
    // Cash confirmation (staff)
    // ------------------------------------------------------------------

    @Test
    void confirmCash_marksPaidViaCash_recordsWho_andIssues() {
        var auth = new UsernamePasswordAuthenticationToken("cashier@example.com", "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_SHOP_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);
        VoucherPurchaseOrder o = pendingOrder();
        when(orders.lockByOrderRef(o.getOrderRef())).thenReturn(Optional.of(o));

        Dtos.VoucherPurchaseOrderResponse resp = service.confirmCash(TENANT, o.getOrderRef());

        assertThat(o.getStatus()).isEqualTo(VoucherPurchaseOrder.Status.PAID);
        assertThat(o.getPaidVia()).isEqualTo(VoucherPurchaseOrder.PaidVia.CASH);
        assertThat(o.getPaymentRef()).startsWith("CASH-");
        assertThat(o.getCashConfirmedBy()).isEqualTo("cashier@example.com");
        assertThat(resp.paidVia()).isEqualTo("CASH");
        verify(voucherService).issueFromOrder(o);
    }

    @Test
    void confirmCash_doubleClick_isIdempotent() {
        VoucherPurchaseOrder o = pendingOrder();
        when(orders.lockByOrderRef(o.getOrderRef())).thenReturn(Optional.of(o));
        service.confirmCash(TENANT, o.getOrderRef());

        Dtos.VoucherPurchaseOrderResponse replay = service.confirmCash(TENANT, o.getOrderRef());

        assertThat(replay.status()).isEqualTo("PAID");
        verify(voucherService, times(1)).issueFromOrder(any());
    }

    @Test
    void confirmCash_afterAnElectronicPayment_isRefused() {
        VoucherPurchaseOrder o = pendingOrder();
        when(orders.lockByOrderRef(o.getOrderRef())).thenReturn(Optional.of(o));
        service.internalConfirmPayment(o.getOrderRef(), "TKZ-VCH-ABC123", 500);

        // The customer already paid electronically — taking cash on top would
        // charge them twice.
        assertThatThrownBy(() -> service.confirmCash(TENANT, o.getOrderRef()))
                .isInstanceOfSatisfying(LoyaltyException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("ORDER_ALREADY_PAID"));
    }

    @Test
    void confirmCash_onAnExpiredOrder_isRefused() {
        // Unlike a late gateway confirm, cash is being taken NOW — a fresh
        // order costs nothing and keeps the amount/FX snapshot current.
        VoucherPurchaseOrder o = pendingOrder();
        o.setExpiresAt(Instant.now().minus(Duration.ofMinutes(5)));
        when(orders.lockByOrderRef(o.getOrderRef())).thenReturn(Optional.of(o));

        assertThatThrownBy(() -> service.confirmCash(TENANT, o.getOrderRef()))
                .isInstanceOfSatisfying(LoyaltyException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("ORDER_EXPIRED"));
        verify(voucherService, never()).issueFromOrder(any());
    }

    @Test
    void confirmCash_wrongTenant_isANotFound() {
        VoucherPurchaseOrder o = pendingOrder();
        when(orders.lockByOrderRef(o.getOrderRef())).thenReturn(Optional.of(o));

        assertThatThrownBy(() -> service.confirmCash(UUID.randomUUID(), o.getOrderRef()))
                .isInstanceOfSatisfying(LoyaltyException.class,
                        ex -> assertThat(ex.getStatus().value()).isEqualTo(404));
    }

    // ------------------------------------------------------------------
    // Internal view + extend
    // ------------------------------------------------------------------

    @Test
    void internalView_expiredUnpaidOrder_readsExpiredAndUnpayable() {
        VoucherPurchaseOrder o = pendingOrder();
        o.setExpiresAt(Instant.now().minus(Duration.ofMinutes(1)));
        when(orders.findByOrderRef(o.getOrderRef())).thenReturn(Optional.of(o));

        var view = service.internalView(o.getOrderRef());

        assertThat(view.status()).isEqualTo("EXPIRED");
        assertThat(view.payable()).isFalse();
        assertThat(view.payerMsisdn()).isEqualTo("+263782608767");
    }

    @Test
    void extendExpiry_neverShortens_andRefusesADeadOrder() {
        VoucherPurchaseOrder o = pendingOrder();
        Instant original = o.getExpiresAt();
        when(orders.lockByOrderRef(o.getOrderRef())).thenReturn(Optional.of(o));

        // 5 minutes from now is EARLIER than the current +20m deadline — keep it.
        service.internalExtendExpiry(o.getOrderRef(), 5);
        assertThat(o.getExpiresAt()).isEqualTo(original);

        // 45 minutes extends past it.
        service.internalExtendExpiry(o.getOrderRef(), 45);
        assertThat(o.getExpiresAt()).isAfter(original);

        o.setExpiresAt(Instant.now().minus(Duration.ofMinutes(1)));
        assertThatThrownBy(() -> service.internalExtendExpiry(o.getOrderRef(), 30))
                .isInstanceOfSatisfying(LoyaltyException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("ORDER_NOT_EXTENDABLE"));

        assertThatThrownBy(() -> service.internalExtendExpiry(o.getOrderRef(), 0))
                .isInstanceOfSatisfying(LoyaltyException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("INVALID_EXTENSION"));
    }
}
