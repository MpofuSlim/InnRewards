package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.client.UserServiceClient;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MerchantService#list(UUID, Pageable, boolean)}.
 *
 * <p>Pins how the unassigned filter routes between the repository's two
 * finders depending on what user-service returns, and that user-service
 * failures propagate (no silent fallback — see the service comment).
 */
class MerchantServiceTest {

    private static Merchant merchant(UUID id, UUID tenantId, String name) {
        Merchant m = new Merchant();
        m.setId(id);
        m.setTenantId(tenantId);
        m.setName(name);
        m.setCategory("Coffee");
        m.setCurrency("USD");
        m.setStatus(Merchant.Status.ACTIVE);
        return m;
    }

    @Test
    void list_defaultUnassignedFalse_skipsUserServiceAndReturnsAll() {
        MerchantRepository repo = mock(MerchantRepository.class);
        UserServiceClient userClient = mock(UserServiceClient.class);
        UUID tenantId = UUID.randomUUID();
        Pageable page = PageRequest.of(0, 20);
        when(repo.findByTenantId(tenantId, page))
                .thenReturn(new PageImpl<>(List.of(merchant(UUID.randomUUID(), tenantId, "A"))));

        Page<Dtos.MerchantResponse> result =
                new MerchantService(repo, userClient).list(tenantId, page);

        assertThat(result.getContent()).hasSize(1);
        verify(repo).findByTenantId(tenantId, page);
        verifyNoInteractions(userClient);
    }

    @Test
    void list_unassignedTrue_emptyExclusionSet_fallsThroughToFindByTenantId() {
        // Hibernate refuses to emit `IN ()`; when nobody has an admin yet,
        // the unassigned page IS the unfiltered page.
        MerchantRepository repo = mock(MerchantRepository.class);
        UserServiceClient userClient = mock(UserServiceClient.class);
        UUID tenantId = UUID.randomUUID();
        Pageable page = PageRequest.of(0, 20);
        when(userClient.assignedMerchantIds()).thenReturn(Set.of());
        when(repo.findByTenantId(tenantId, page))
                .thenReturn(new PageImpl<>(List.of(merchant(UUID.randomUUID(), tenantId, "Solo"))));

        Page<Dtos.MerchantResponse> result =
                new MerchantService(repo, userClient).list(tenantId, page, true);

        assertThat(result.getContent()).hasSize(1);
        verify(repo).findByTenantId(tenantId, page);
        verify(repo, never()).findByTenantIdAndIdNotIn(any(), any(), any());
    }

    @Test
    void list_unassignedTrue_nonEmptyExclusion_callsNotInFinderWithThatSet() {
        MerchantRepository repo = mock(MerchantRepository.class);
        UserServiceClient userClient = mock(UserServiceClient.class);
        UUID tenantId = UUID.randomUUID();
        UUID claimed = UUID.randomUUID();
        UUID free = UUID.randomUUID();
        Pageable page = PageRequest.of(0, 20);
        when(userClient.assignedMerchantIds()).thenReturn(Set.of(claimed));
        when(repo.findByTenantIdAndIdNotIn(eq(tenantId), eq(Set.of(claimed)), eq(page)))
                .thenReturn(new PageImpl<>(List.of(merchant(free, tenantId, "Up for grabs"))));

        Page<Dtos.MerchantResponse> result =
                new MerchantService(repo, userClient).list(tenantId, page, true);

        assertThat(result.getContent()).extracting(Dtos.MerchantResponse::id).containsExactly(free);
        verify(repo).findByTenantIdAndIdNotIn(tenantId, Set.of(claimed), page);
        verify(repo, never()).findByTenantId(any(UUID.class), any(Pageable.class));
    }

    @Test
    void list_unassignedTrue_userServiceDown_propagatesIllegalStateException() {
        // Silent fallback to "all merchants" would show the picker
        // already-claimed merchants and defeat the whole feature, so the
        // service lets the exception bubble for the controller to map to 503.
        MerchantRepository repo = mock(MerchantRepository.class);
        UserServiceClient userClient = mock(UserServiceClient.class);
        UUID tenantId = UUID.randomUUID();
        Pageable page = PageRequest.of(0, 20);
        when(userClient.assignedMerchantIds())
                .thenThrow(new IllegalStateException("user-service unavailable"));

        assertThatThrownBy(() -> new MerchantService(repo, userClient).list(tenantId, page, true))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(repo);
    }

    private static MerchantService newService(MerchantRepository repo) {
        return new MerchantService(repo, mock(UserServiceClient.class));
    }

    private static Dtos.MerchantRequest req(Dtos.FeeModel issued, Dtos.FeeModel redeemed) {
        return new Dtos.MerchantRequest("Cafe A", "F&B", "USD",
                Merchant.BillingCycle.MONTHLY, issued, redeemed);
    }

    @Test
    void create_acceptsFixedPlusPercentage() {
        MerchantRepository repo = mock(MerchantRepository.class);
        when(repo.save(any(Merchant.class))).thenAnswer(inv -> inv.getArgument(0));
        MerchantService svc = newService(repo);

        Dtos.FeeModel mix = new Dtos.FeeModel(Merchant.FeeType.FIXED_PLUS_PERCENTAGE,
                new java.math.BigDecimal("0.30"), new java.math.BigDecimal("2.5"));

        Dtos.MerchantResponse resp = svc.create(UUID.randomUUID(), req(mix, mix));

        assertThat(resp.feeIssued().type()).isEqualTo(Merchant.FeeType.FIXED_PLUS_PERCENTAGE);
        assertThat(resp.feeIssued().fixed()).isEqualByComparingTo("0.30");
        assertThat(resp.feeIssued().percentage()).isEqualByComparingTo("2.5");
        assertThat(resp.feeRedeemed().type()).isEqualTo(Merchant.FeeType.FIXED_PLUS_PERCENTAGE);
    }

    @Test
    void create_rejectsFixedWithNonZeroPercentage() {
        MerchantService svc = newService(mock(MerchantRepository.class));
        Dtos.FeeModel bad = new Dtos.FeeModel(Merchant.FeeType.FIXED,
                new java.math.BigDecimal("0.30"), new java.math.BigDecimal("2.5"));

        assertThatThrownBy(() -> svc.create(UUID.randomUUID(), req(bad, null)))
                .hasMessageContaining("FIXED")
                .hasMessageContaining("percentage");
    }

    @Test
    void create_rejectsPercentageWithNonZeroFixed() {
        MerchantService svc = newService(mock(MerchantRepository.class));
        Dtos.FeeModel bad = new Dtos.FeeModel(Merchant.FeeType.PERCENTAGE,
                new java.math.BigDecimal("0.30"), new java.math.BigDecimal("2.5"));

        assertThatThrownBy(() -> svc.create(UUID.randomUUID(), req(bad, null)))
                .hasMessageContaining("PERCENTAGE")
                .hasMessageContaining("fixed");
    }

    @Test
    void create_rejectsPercentageWithZeroPercentage() {
        MerchantService svc = newService(mock(MerchantRepository.class));
        Dtos.FeeModel bad = new Dtos.FeeModel(Merchant.FeeType.PERCENTAGE,
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO);

        assertThatThrownBy(() -> svc.create(UUID.randomUUID(), req(bad, null)))
                .hasMessageContaining("percentage > 0");
    }

    @Test
    void create_rejectsFixedPlusPercentageMissingALeg() {
        MerchantService svc = newService(mock(MerchantRepository.class));
        Dtos.FeeModel bad = new Dtos.FeeModel(Merchant.FeeType.FIXED_PLUS_PERCENTAGE,
                new java.math.BigDecimal("0.30"), java.math.BigDecimal.ZERO);

        assertThatThrownBy(() -> svc.create(UUID.randomUUID(), req(bad, null)))
                .hasMessageContaining("FIXED_PLUS_PERCENTAGE");
    }

    @Test
    void create_rejectsNegativeValues() {
        MerchantService svc = newService(mock(MerchantRepository.class));
        Dtos.FeeModel bad = new Dtos.FeeModel(Merchant.FeeType.FIXED,
                new java.math.BigDecimal("-0.10"), java.math.BigDecimal.ZERO);

        assertThatThrownBy(() -> svc.create(UUID.randomUUID(), req(bad, null)))
                .hasMessageContaining(">= 0");
    }

    @Test
    void create_nullFeeModels_defaultEntityToFixedZero() {
        MerchantRepository repo = mock(MerchantRepository.class);
        when(repo.save(any(Merchant.class))).thenAnswer(inv -> inv.getArgument(0));
        MerchantService svc = newService(repo);

        Dtos.MerchantResponse resp = svc.create(UUID.randomUUID(), req(null, null));

        // Entity defaults: type=FIXED, fixed=0, percentage=0 (no billing impact).
        assertThat(resp.feeIssued().type()).isEqualTo(Merchant.FeeType.FIXED);
        assertThat(resp.feeIssued().fixed()).isEqualByComparingTo("0");
        assertThat(resp.feeIssued().percentage()).isEqualByComparingTo("0");
    }

    @Test
    void create_nullCurrency_defaultsToCellCurrency_notHardcodedUsd() {
        MerchantRepository repo = mock(MerchantRepository.class);
        when(repo.save(any(Merchant.class))).thenAnswer(inv -> inv.getArgument(0));
        MerchantService svc = newService(repo);
        // KE cell: a merchant created without an explicit currency must inherit KES.
        ReflectionTestUtils.setField(svc, "cellCurrency", "KES");

        Dtos.MerchantRequest noCurrency = new Dtos.MerchantRequest(
                "Nairobi Cafe", "F&B", null, Merchant.BillingCycle.MONTHLY, null, null);
        Dtos.MerchantResponse resp = svc.create(UUID.randomUUID(), noCurrency);

        assertThat(resp.currency()).isEqualTo("KES");
    }

    @Test
    void create_explicitCurrency_winsOverCellDefault() {
        MerchantRepository repo = mock(MerchantRepository.class);
        when(repo.save(any(Merchant.class))).thenAnswer(inv -> inv.getArgument(0));
        MerchantService svc = newService(repo);
        ReflectionTestUtils.setField(svc, "cellCurrency", "KES");

        Dtos.MerchantRequest usd = new Dtos.MerchantRequest(
                "USD Merchant", "F&B", "USD", Merchant.BillingCycle.MONTHLY, null, null);
        Dtos.MerchantResponse resp = svc.create(UUID.randomUUID(), usd);

        assertThat(resp.currency()).isEqualTo("USD");
    }

    // --- Duplicate-name guard (per tenant, case-insensitive) ------------------

    @Test
    void create_firstMerchantWithName_succeeds() {
        MerchantRepository repo = mock(MerchantRepository.class);
        UUID tenantId = UUID.randomUUID();
        when(repo.existsByTenantIdAndNameIgnoreCase(tenantId, "Cafe A")).thenReturn(false);
        when(repo.save(any(Merchant.class))).thenAnswer(inv -> inv.getArgument(0));
        MerchantService svc = newService(repo);

        Dtos.MerchantResponse resp = svc.create(tenantId, req(null, null));

        assertThat(resp.name()).isEqualTo("Cafe A");
        verify(repo).save(any(Merchant.class));
    }

    @Test
    void create_duplicateNameDifferentCase_throwsConflict() {
        MerchantRepository repo = mock(MerchantRepository.class);
        UUID tenantId = UUID.randomUUID();
        // The IgnoreCase finder is what the DB would answer for a name that
        // already exists in any casing; simulate the hit.
        when(repo.existsByTenantIdAndNameIgnoreCase(tenantId, "cafe a")).thenReturn(true);
        MerchantService svc = newService(repo);

        Dtos.MerchantRequest sameNameLowerCase = new Dtos.MerchantRequest(
                "cafe a", "F&B", "USD", Merchant.BillingCycle.MONTHLY, null, null);

        assertThatThrownBy(() -> svc.create(tenantId, sameNameLowerCase))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> {
                    LoyaltyException le = (LoyaltyException) e;
                    assertThat(le.getStatus()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
                    assertThat(le.getCode()).isEqualTo("MERCHANT_NAME_TAKEN");
                });
        verify(repo, never()).save(any(Merchant.class));
    }

    @Test
    void create_trimsNameBeforeDuplicateCheckAndPersist() {
        MerchantRepository repo = mock(MerchantRepository.class);
        UUID tenantId = UUID.randomUUID();
        when(repo.existsByTenantIdAndNameIgnoreCase(tenantId, "Cafe A")).thenReturn(false);
        when(repo.save(any(Merchant.class))).thenAnswer(inv -> inv.getArgument(0));
        MerchantService svc = newService(repo);

        Dtos.MerchantRequest padded = new Dtos.MerchantRequest(
                "  Cafe A  ", "F&B", "USD", Merchant.BillingCycle.MONTHLY, null, null);
        Dtos.MerchantResponse resp = svc.create(tenantId, padded);

        // Existence is checked against the trimmed name, and the trimmed name is persisted.
        verify(repo).existsByTenantIdAndNameIgnoreCase(tenantId, "Cafe A");
        assertThat(resp.name()).isEqualTo("Cafe A");
    }
}
