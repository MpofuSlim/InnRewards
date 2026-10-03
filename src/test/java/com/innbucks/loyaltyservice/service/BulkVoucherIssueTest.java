package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.config.LoyaltyProperties;
import com.innbucks.loyaltyservice.config.SupportedCurrencies;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.integration.NotificationGateway;
import com.innbucks.loyaltyservice.repository.ExchangeRateRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyRuleRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import com.innbucks.loyaltyservice.repository.VoucherBatchRepository;
import com.innbucks.loyaltyservice.repository.VoucherRedemptionRepository;
import com.innbucks.loyaltyservice.repository.VoucherRepository;
import com.innbucks.loyaltyservice.security.MerchantAuthz;
import com.innbucks.loyaltyservice.util.VoucherCodes;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Bulk issue is capped per request and does a fixed amount of lookup work
 * however many vouchers it creates. It used to run a {@code findByCode} per
 * voucher (plus a rule and an FX lookup), each auto-flushing every voucher
 * created so far, so a batch got quadratically slower.
 */
class BulkVoucherIssueTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID MERCHANT = UUID.randomUUID();
    private static final SupportedCurrencies CURRENCIES = new SupportedCurrencies("USD", "USD");

    private final VoucherRepository vouchers = mock(VoucherRepository.class);
    private final MerchantAuthz merchantAuthz = mock(MerchantAuthz.class);
    private final LoyaltyRuleRepository rules = mock(LoyaltyRuleRepository.class);
    private final VoucherBatchRepository batches = mock(VoucherBatchRepository.class);
    private final NotificationGateway notifications = mock(NotificationGateway.class);

    private VoucherService service;

    @BeforeEach
    void setUp() {
        LoyaltyProperties props = mock(LoyaltyProperties.class, RETURNS_DEEP_STUBS);
        when(props.voucher().secret()).thenReturn("test-voucher-secret-value");
        when(props.voucher().defaultValidityDays()).thenReturn(365);
        // Unstubbed (0): the service falls back to the default cap of 1000,
        // the same value LoyaltyProperties.Voucher normalises a non-positive to.
        service = new VoucherService(vouchers, batches,
                mock(VoucherRedemptionRepository.class),
                mock(MerchantService.class), merchantAuthz, CURRENCIES, rules,
                mock(LoyaltyUserRepository.class),
                mock(UserService.class), notifications,
                mock(FraudService.class), new LoyaltyMetrics(new SimpleMeterRegistry()),
                mock(com.innbucks.loyaltyservice.integration.MemberActivityNotifier.class),
                props, new ExchangeRateService(mock(ExchangeRateRepository.class),
                        CURRENCIES, new BigDecimal("25")),
                mock(org.springframework.context.ApplicationEventPublisher.class));
        when(rules.findApplicable(eq(TENANT), eq(MERCHANT), eq(TransactionType.PURCHASE)))
                .thenReturn(List.of());
        Merchant m = new Merchant();
        m.setId(MERCHANT);
        m.setTenantId(TENANT);
        m.setName("Pizza Inn");
        m.setCurrency("USD");
        when(merchantAuthz.requireCallerAdministersMerchant(TENANT, MERCHANT)).thenReturn(m);
    }

    private static Dtos.BulkIssueRequest bulk(int quantity) {
        return new Dtos.BulkIssueRequest(MERCHANT, null, new BigDecimal("5.00"), "USD", null,
                quantity, "spring-test", null);
    }

    private List<Voucher> savedVouchers(int times) {
        ArgumentCaptor<Voucher> cap = ArgumentCaptor.forClass(Voucher.class);
        verify(vouchers, times(times)).save(cap.capture());
        return cap.getAllValues();
    }

    @Test
    void overTheCap_is400_withTheDomainCode_andCreatesNothing() {
        assertThatThrownBy(() -> service.issueBulk(TENANT, bulk(1001)))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> {
                    LoyaltyException le = (LoyaltyException) e;
                    assertThat(le.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(le.getCode()).isEqualTo("BULK_QUANTITY_TOO_LARGE");
                    assertThat(le.getMessage()).isEqualTo(
                            "A bulk issue can create at most 1000 vouchers. "
                                    + "Split the batch into requests of 1000 or fewer.");
                });
        verifyNoInteractions(batches);
        verify(vouchers, never()).save(any());
        verify(vouchers, never()).findExistingCodes(anyCollection());
    }

    @Test
    void theCapIsConfigurable() {
        LoyaltyProperties props = mock(LoyaltyProperties.class, RETURNS_DEEP_STUBS);
        when(props.voucher().secret()).thenReturn("test-voucher-secret-value");
        when(props.voucher().bulkMaxQuantity()).thenReturn(3);
        VoucherService capped = new VoucherService(vouchers, batches,
                mock(VoucherRedemptionRepository.class),
                mock(MerchantService.class), merchantAuthz, CURRENCIES, rules,
                mock(LoyaltyUserRepository.class),
                mock(UserService.class), notifications,
                mock(FraudService.class), new LoyaltyMetrics(new SimpleMeterRegistry()),
                mock(com.innbucks.loyaltyservice.integration.MemberActivityNotifier.class),
                props, new ExchangeRateService(mock(ExchangeRateRepository.class),
                        CURRENCIES, new BigDecimal("25")),
                mock(org.springframework.context.ApplicationEventPublisher.class));

        assertThatThrownBy(() -> capped.issueBulk(TENANT, bulk(4)))
                .hasMessage("A bulk issue can create at most 3 vouchers. "
                        + "Split the batch into requests of 3 or fewer.");
        assertThat(capped.issueBulk(TENANT, bulk(3))).hasSize(3);
    }

    @Test
    void theDefaultCapIs1000_andNonPositiveMeansTheDefault() {
        assertThat(new LoyaltyProperties.Voucher("s", 365, 5, 60).bulkMaxQuantity()).isEqualTo(1000);
        assertThat(new LoyaltyProperties.Voucher("s", 365, 5, 60, null, null, -1).bulkMaxQuantity())
                .isEqualTo(1000);
        assertThat(new LoyaltyProperties.Voucher("s", 365, 5, 60, null, null, 250).bulkMaxQuantity())
                .isEqualTo(250);
    }

    @Test
    void atTheCap_issuesEveryVoucher_withDistinctWellFormedCodes() {
        List<Dtos.VoucherResponse> out = service.issueBulk(TENANT, bulk(1000));

        assertThat(out).hasSize(1000);
        List<Voucher> saved = savedVouchers(1000);
        Set<String> codes = new HashSet<>();
        for (Voucher v : saved) {
            codes.add(v.getCode());
            assertThat(VoucherCodes.isWellFormedNumeric(v.getCode())).isTrue();
            // Unchanged bulk semantics: no holder, no sender, signed, one use,
            // a frozen USD worth and an expiry from the rules/default.
            assertThat(v.getAssignedUserId()).isNull();
            assertThat(v.getAssigneePhone()).isNull();
            assertThat(v.getSenderPhone()).isNull();
            assertThat(v.getSignature()).isNotBlank();
            assertThat(v.getUsesRemaining()).isEqualTo(1);
            assertThat(v.getBaseValue()).isEqualByComparingTo("5.00");
            assertThat(v.getExpiresAt()).isNotNull();
            assertThat(v.getCampaignSource()).isEqualTo("spring-test");
        }
        assertThat(codes).hasSize(1000);
        // Bulk stock is never delivered.
        verifyNoInteractions(notifications);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 10, 1000})
    void theLookupWorkDoesNotGrowWithTheQuantity(int quantity) {
        service.issueBulk(TENANT, bulk(quantity));

        // ONE collision query for the whole batch, never a per-code lookup.
        verify(vouchers, times(1)).findExistingCodes(anyCollection());
        verify(vouchers, never()).findByCode(anyString());
        // Rule (expiry) resolved once for the batch, not per voucher.
        verify(rules, times(1)).findApplicable(TENANT, MERCHANT, TransactionType.PURCHASE);
        verify(vouchers, times(quantity)).save(any(Voucher.class));
    }

    @Test
    void collidingCodes_areRegenerated_andOnlyThoseAreRechecked() {
        AtomicInteger calls = new AtomicInteger();
        List<Collection<String>> asked = new ArrayList<>();
        List<String> taken = new ArrayList<>();
        when(vouchers.findExistingCodes(anyCollection())).thenAnswer(inv -> {
            Collection<String> codes = new ArrayList<>(inv.getArgument(0));
            asked.add(codes);
            if (calls.getAndIncrement() == 0) {
                // Two codes of the first round already exist in the table.
                List<String> clash = codes.stream().limit(2).toList();
                taken.addAll(clash);
                return clash;
            }
            return List.of();
        });

        service.issueBulk(TENANT, bulk(50));

        assertThat(asked).hasSize(2);
        assertThat(asked.get(0)).hasSize(50);
        assertThat(asked.get(1)).hasSize(2);   // only the replacements
        List<Voucher> saved = savedVouchers(50);
        Set<String> codes = new HashSet<>();
        saved.forEach(v -> codes.add(v.getCode()));
        assertThat(codes).hasSize(50).doesNotContainAnyElementsOf(taken);
    }

    @Test
    void codesThatNeverStopColliding_failRatherThanLoop() {
        when(vouchers.findExistingCodes(anyCollection()))
                .thenAnswer(inv -> new ArrayList<>(inv.<Collection<String>>getArgument(0)));

        assertThatThrownBy(() -> service.issueBulk(TENANT, bulk(5)))
                .isInstanceOf(IllegalStateException.class);
        verify(vouchers, times(8)).findExistingCodes(anyCollection());
        verify(vouchers, never()).save(any());
    }
}
