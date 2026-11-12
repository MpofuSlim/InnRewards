package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.FeeSchedule;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Tenant;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.FeeScheduleRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyRuleRepository;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.repository.TenantRepository;
import com.innbucks.loyaltyservice.security.CallerDetails;
import com.innbucks.loyaltyservice.security.MerchantAuthz;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

/**
 * {@link PlatformFeeSchedules}: only InnBucks writes a fee schedule, every
 * change appends a row stamped with who made it, and a merchant reads its own
 * fees without learning which staff member set them.
 */
class PlatformFeeSchedulesTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID MERCHANT = UUID.randomUUID();
    private static final UUID STAFF_ID = UUID.randomUUID();

    private final FeeScheduleRepository schedules = mock(FeeScheduleRepository.class);
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final MerchantRepository merchants = mock(MerchantRepository.class);
    private final LoyaltyRuleRepository rules = mock(LoyaltyRuleRepository.class);
    private final MerchantAuthz merchantAuthz = mock(MerchantAuthz.class);
    private final PlatformFeeSchedules service =
            new PlatformFeeSchedules(schedules, tenants, merchants, rules, merchantAuthz);

    @BeforeEach
    void knownTenantAndMerchant() {
        Tenant t = new Tenant();
        t.setId(TENANT);
        t.setName("Simbisa");
        when(tenants.findById(TENANT)).thenReturn(Optional.of(t));
        Merchant m = new Merchant();
        m.setId(MERCHANT);
        m.setTenantId(TENANT);
        m.setName("Pizza Inn");
        when(merchants.findById(MERCHANT)).thenReturn(Optional.of(m));
        when(schedules.save(any(FeeSchedule.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticate(String name, UUID userId, String... authorities) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(name, null,
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
        auth.setDetails(new CallerDetails(null, null, null, userId));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static void asFinance() {
        authenticate("finance@innbucks.co.zw", STAFF_ID, "ROLE_PRODUCT_OFFICER", "loyalty-fees:manage");
    }

    private static void asMerchantAdmin() {
        authenticate("owner@simbisa.co.zw", UUID.randomUUID(), "ROLE_MERCHANT_ADMIN");
    }

    private static Dtos.FeeModel fixed(String amount) {
        return new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal(amount), BigDecimal.ZERO);
    }

    private static Dtos.FeeModel percent(String pct) {
        return new Dtos.FeeModel(Merchant.FeeType.PERCENTAGE, BigDecimal.ZERO, new BigDecimal(pct));
    }

    private static Dtos.FeeScheduleRequest request(Dtos.FeeModel issued, Dtos.FeeModel redeemed, String note) {
        return new Dtos.FeeScheduleRequest(issued, redeemed, note);
    }

    private static FeeSchedule liveRow(UUID merchantId, String issuedFixed) {
        FeeSchedule s = new FeeSchedule();
        s.setId(UUID.randomUUID());
        s.setTenantId(TENANT);
        s.setMerchantId(merchantId);
        s.setFeeIssuedType(Merchant.FeeType.FIXED);
        s.setFeeIssuedFixed(new BigDecimal(issuedFixed));
        s.setFeeIssuedPercentage(BigDecimal.ZERO);
        s.setFeeRedeemedType(Merchant.FeeType.PERCENTAGE);
        s.setFeeRedeemedFixed(BigDecimal.ZERO);
        s.setFeeRedeemedPercentage(new BigDecimal("1.5"));
        s.setSetBy("finance@innbucks.co.zw");
        s.setCreatedAt(Instant.parse("2026-11-12T08:00:00Z"));
        return s;
    }

    private static void assertCode(Executable call, HttpStatus status, String code) {
        assertThatThrownBy(call::execute)
                .isInstanceOfSatisfying(LoyaltyException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(status);
                    assertThat(e.getCode()).isEqualTo(code);
                });
    }

    // ── who may write ────────────────────────────────────────────────────────

    @Test
    void aMerchantAdmin_canWriteNoFeeSchedule_andIsRefusedBeforeAnythingIsRead() {
        asMerchantAdmin();
        Dtos.FeeScheduleRequest req = request(fixed("0"), fixed("0"), null);

        assertCode(() -> service.setTenantDefault(TENANT, req), HttpStatus.FORBIDDEN, "FEES_PLATFORM_ONLY");
        assertCode(() -> service.clearTenantDefault(TENANT), HttpStatus.FORBIDDEN, "FEES_PLATFORM_ONLY");
        assertCode(() -> service.setMerchant(MERCHANT, req), HttpStatus.FORBIDDEN, "FEES_PLATFORM_ONLY");
        assertCode(() -> service.clearMerchant(MERCHANT), HttpStatus.FORBIDDEN, "FEES_PLATFORM_ONLY");

        verify(schedules, never()).save(any());
        // Refused on authority first: the answer says nothing about which ids exist.
        verify(tenants, never()).findById(any());
        verify(merchants, never()).findById(any());
    }

    @Test
    void superAdmin_mayWriteToo() {
        authenticate("owner@innbucks.co.zw", STAFF_ID, "ROLE_SUPER_ADMIN");

        service.setTenantDefault(TENANT, request(fixed("0.30"), percent("1"), null));

        verify(schedules).save(any(FeeSchedule.class));
    }

    // ── what a write records ─────────────────────────────────────────────────

    @Test
    void settingATenantDefault_appendsOneStampedRow() {
        asFinance();
        Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);

        service.setTenantDefault(TENANT, request(fixed("0.30"), percent("1.5"),
                "  <b>2026 rate card</b>, agreed with Simbisa  "));

        ArgumentCaptor<FeeSchedule> saved = ArgumentCaptor.forClass(FeeSchedule.class);
        verify(schedules).save(saved.capture());
        FeeSchedule row = saved.getValue();
        assertThat(row.getTenantId()).isEqualTo(TENANT);
        assertThat(row.getMerchantId()).isNull();
        assertThat(row.isCleared()).isFalse();
        assertThat(row.getFeeIssuedType()).isEqualTo(Merchant.FeeType.FIXED);
        assertThat(row.getFeeIssuedFixed()).isEqualByComparingTo("0.30");
        assertThat(row.getFeeIssuedPercentage()).isEqualByComparingTo("0");
        assertThat(row.getFeeRedeemedType()).isEqualTo(Merchant.FeeType.PERCENTAGE);
        assertThat(row.getFeeRedeemedPercentage()).isEqualByComparingTo("1.5");
        assertThat(row.getNote()).isEqualTo("2026 rate card, agreed with Simbisa");
        assertThat(row.getSetBy()).isEqualTo("finance@innbucks.co.zw");
        assertThat(row.getSetByUserId()).isEqualTo(STAFF_ID);
        // Stamped as the timestamptz column stores it (microseconds).
        assertThat(row.getCreatedAt()).isEqualTo(row.getCreatedAt().truncatedTo(ChronoUnit.MICROS));
        assertThat(row.getCreatedAt()).isAfterOrEqualTo(before);
    }

    @Test
    void settingAMerchantsSchedule_namesItsTenantAndMerchant() {
        asFinance();

        service.setMerchant(MERCHANT, request(fixed("0.25"), fixed("0.10"), "   "));

        ArgumentCaptor<FeeSchedule> saved = ArgumentCaptor.forClass(FeeSchedule.class);
        verify(schedules).save(saved.capture());
        assertThat(saved.getValue().getTenantId()).isEqualTo(TENANT);
        assertThat(saved.getValue().getMerchantId()).isEqualTo(MERCHANT);
        assertThat(saved.getValue().getNote()).as("a blank note is no note").isNull();
    }

    @Test
    void theAuthorFallsBackToTheUserId_thenToUnknown() {
        authenticate("", STAFF_ID, "ROLE_SUPER_ADMIN");
        service.setTenantDefault(TENANT, request(fixed("0.30"), fixed("0"), null));
        authenticate("", null, "ROLE_SUPER_ADMIN");
        service.setTenantDefault(TENANT, request(fixed("0.30"), fixed("0"), null));

        ArgumentCaptor<FeeSchedule> saved = ArgumentCaptor.forClass(FeeSchedule.class);
        verify(schedules, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(FeeSchedule::getSetBy)
                .containsExactly(STAFF_ID.toString(), "unknown");
    }

    // ── validation ───────────────────────────────────────────────────────────

    @Test
    void bothSidesNeedAType() {
        asFinance();

        assertCode(() -> service.setTenantDefault(TENANT,
                        request(new Dtos.FeeModel(null, new BigDecimal("0.30"), null), fixed("0"), null)),
                HttpStatus.BAD_REQUEST, "FEE_TYPE_REQUIRED");
        assertCode(() -> service.setMerchant(MERCHANT, request(fixed("0.30"), null, null)),
                HttpStatus.BAD_REQUEST, "FEE_TYPE_REQUIRED");
        verify(schedules, never()).save(any());
    }

    @Test
    void theUsualFeeRulesApply_andAPercentageAbove100IsRefused() {
        asFinance();

        assertCode(() -> service.setTenantDefault(TENANT, request(percent("100.01"), fixed("0"), null)),
                HttpStatus.BAD_REQUEST, "FEE_PERCENT_TOO_HIGH");
        assertCode(() -> service.setTenantDefault(TENANT, request(
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("0.30"), BigDecimal.ONE),
                        fixed("0"), null)),
                HttpStatus.BAD_REQUEST, "FEE_FIXED_HAS_PERCENT");
        assertCode(() -> service.setTenantDefault(TENANT, request(fixed("-0.01"), fixed("0"), null)),
                HttpStatus.BAD_REQUEST, "FEE_NEGATIVE");
        verify(schedules, never()).save(any());

        // 100% exactly, and zero on both sides, are InnBucks' to choose.
        service.setTenantDefault(TENANT, request(percent("100"), fixed("0"), null));
        service.setTenantDefault(TENANT, request(fixed("0"), percent("0"), null));
        verify(schedules, times(2)).save(any());
    }

    @Test
    void anUnknownTenantOrMerchantIs404() {
        asFinance();

        assertCode(() -> service.setTenantDefault(UUID.randomUUID(), request(fixed("0.30"), fixed("0"), null)),
                HttpStatus.NOT_FOUND, "404 NOT_FOUND");
        assertCode(() -> service.setMerchant(UUID.randomUUID(), request(fixed("0.30"), fixed("0"), null)),
                HttpStatus.NOT_FOUND, "404 NOT_FOUND");
    }

    // ── withdrawing ──────────────────────────────────────────────────────────

    @Test
    void withdrawingWhatWasNeverSet_is409_andAppendsNothing() {
        asFinance();
        when(schedules.findLatestForTenants(anyCollection())).thenReturn(List.of());

        assertCode(() -> service.clearTenantDefault(TENANT), HttpStatus.CONFLICT, "NO_FEE_SCHEDULE");
        assertCode(() -> service.clearMerchant(MERCHANT), HttpStatus.CONFLICT, "NO_FEE_SCHEDULE");
        verify(schedules, never()).save(any());
    }

    @Test
    void withdrawingALiveSchedule_appendsATombstone() {
        asFinance();
        when(schedules.findLatestForTenants(anyCollection())).thenReturn(List.of(liveRow(MERCHANT, "0.30")));

        service.clearMerchant(MERCHANT);

        ArgumentCaptor<FeeSchedule> saved = ArgumentCaptor.forClass(FeeSchedule.class);
        verify(schedules).save(saved.capture());
        FeeSchedule tombstone = saved.getValue();
        assertThat(tombstone.isCleared()).isTrue();
        assertThat(tombstone.getMerchantId()).isEqualTo(MERCHANT);
        assertThat(tombstone.getFeeIssuedType()).isNull();
        assertThat(tombstone.getFeeRedeemedType()).isNull();
        assertThat(tombstone.getSetBy()).isEqualTo("finance@innbucks.co.zw");
    }

    @Test
    void aMerchantsScheduleIsNotTheTenantDefault_soClearingTheDefaultNeedsADefault() {
        asFinance();
        when(schedules.findLatestForTenants(anyCollection())).thenReturn(List.of(liveRow(MERCHANT, "0.30")));

        assertCode(() -> service.clearTenantDefault(TENANT), HttpStatus.CONFLICT, "NO_FEE_SCHEDULE");
    }

    // ── reading ──────────────────────────────────────────────────────────────

    @Test
    void aMerchantAdminReadsItsFees_throughMerchantAuthz_andNeverSeesWhoSetThem() {
        asMerchantAdmin();
        FeeSchedule own = liveRow(MERCHANT, "0.30");
        when(schedules.findLatestForTenants(anyCollection())).thenReturn(List.of(own));
        when(schedules.findByMerchantIdOrderByCreatedAtDescIdDesc(eq(MERCHANT), any())).thenReturn(List.of(own));

        Dtos.MerchantFees fees = service.merchantFees(MERCHANT);

        verify(merchantAuthz).requireCallerMayReadMerchant(TENANT, MERCHANT);
        assertThat(fees.feeIssuedSource()).isEqualTo("MERCHANT_SCHEDULE");
        assertThat(fees.feeIssued().fixed()).isEqualByComparingTo("0.30");
        assertThat(fees.merchantSchedule().setBy()).isNull();
        assertThat(fees.history()).extracting(Dtos.FeeScheduleEntry::setBy).containsOnlyNulls();
    }

    @Test
    void aMerchantAdminOfAnotherBusiness_isRefusedByMerchantAuthz() {
        asMerchantAdmin();
        doThrow(LoyaltyException.forbidden("NOT_MERCHANT_OWNER", "You can only act on merchants you administer."))
                .when(merchantAuthz).requireCallerMayReadMerchant(TENANT, MERCHANT);

        assertCode(() -> service.merchantFees(MERCHANT), HttpStatus.FORBIDDEN, "NOT_MERCHANT_OWNER");
    }

    @Test
    void innBucksReadsAnyMerchant_andSeesWhoSetEachFee() {
        asFinance();
        FeeSchedule tenantDefault = liveRow(null, "0.40");
        when(schedules.findLatestForTenants(anyCollection())).thenReturn(List.of(tenantDefault));

        Dtos.MerchantFees fees = service.merchantFees(MERCHANT);

        verifyNoInteractions(merchantAuthz);
        assertThat(fees.feeIssuedSource()).isEqualTo("TENANT_SCHEDULE");
        assertThat(fees.feeRedeemedSource()).isEqualTo("TENANT_SCHEDULE");
        assertThat(fees.merchantSchedule()).isNull();
        assertThat(fees.tenantSchedule().setBy()).isEqualTo("finance@innbucks.co.zw");
    }

    @Test
    void withNoScheduleAnywhere_theLegacySourceIsReported() {
        asFinance();
        when(schedules.findLatestForTenants(anyCollection())).thenReturn(List.of());

        Dtos.MerchantFees fees = service.merchantFees(MERCHANT);

        assertThat(fees.feeIssuedSource()).isEqualTo("NONE");
        assertThat(fees.feeIssued().fixed()).isEqualByComparingTo("0");
    }

    @Test
    void anEmptyTenantSet_readsNothing() {
        assertThat(service.forTenants(List.of())).isSameAs(PlatformFeeBook.EMPTY);
        assertThat(service.forTenants(null)).isSameAs(PlatformFeeBook.EMPTY);
        verify(schedules, never()).findLatestForTenants(anyCollection());
    }
}
