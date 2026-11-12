package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.VoucherPartnerProperties;
import com.innbucks.loyaltyservice.dto.PartnershipDtos;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Tenant;
import com.innbucks.loyaltyservice.entity.VoucherPartnership;
import com.innbucks.loyaltyservice.entity.VoucherPartnershipMerchant;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.repository.TenantRepository;
import com.innbucks.loyaltyservice.repository.VoucherPartnershipMerchantRepository;
import com.innbucks.loyaltyservice.repository.VoucherPartnershipRepository;
import com.innbucks.loyaltyservice.security.MerchantAuthz;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The parts of {@link VoucherPartnershipService} the end-to-end test cannot
 * reach in one context: the staging switch, which must never act with real
 * money, and the rule that a merchant of the issuer's own program needs no
 * partnership. The flows are in {@code VoucherPartnershipFlowIT}.
 */
class VoucherPartnershipServiceTest {

    private final VoucherPartnershipRepository partnerships = mock(VoucherPartnershipRepository.class);
    private final VoucherPartnershipMerchantRepository members = mock(VoucherPartnershipMerchantRepository.class);
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final MerchantRepository merchants = mock(MerchantRepository.class);

    private final UUID mwosTenant = UUID.randomUUID();
    private final UUID bolaTenant = UUID.randomUUID();
    private Merchant mwos;
    private Merchant bola;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "owner@innbucks.co.zw", null, List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
        mwos = merchant(mwosTenant, "MWOS");
        bola = merchant(bolaTenant, "Bola Bet");
        Tenant t = new Tenant();
        t.setId(bolaTenant);
        t.setCode("bolabet");
        when(tenants.findAllByCodeIgnoreCase("bolabet")).thenReturn(List.of(t));
        when(merchants.findById(mwos.getId())).thenReturn(Optional.of(mwos));
        when(merchants.findByTenantId(bolaTenant)).thenReturn(List.of(bola));
        when(partnerships.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static Merchant merchant(UUID tenantId, String name) {
        Merchant m = new Merchant();
        m.setId(UUID.randomUUID());
        m.setTenantId(tenantId);
        m.setName(name);
        m.setStatus(Merchant.Status.ACTIVE);
        return m;
    }

    private VoucherPartnershipService service(String funding, boolean autoAccept) {
        VoucherPartnerProperties props = new VoucherPartnerProperties(true, "aud", funding, null, null, null, null,
                null, null, null, null, null, null, null, null);
        return new VoucherPartnershipService(partnerships, members, tenants, merchants, mock(MerchantAuthz.class),
                props, autoAccept);
    }

    private VoucherPartnership invited(VoucherPartnershipService service) {
        service.invite(mwosTenant, new PartnershipDtos.InviteRequest("bolabet", List.of(mwos.getId())));
        ArgumentCaptor<VoucherPartnership> saved = ArgumentCaptor.forClass(VoucherPartnership.class);
        verify(partnerships).saveAndFlush(saved.capture());
        return saved.getValue();
    }

    @Test
    void theStagingSwitch_makesAnInvitationActiveAtOnce_withTheOtherSidesMerchants() {
        VoucherPartnership p = invited(service("SIMULATED", true));

        assertThat(p.getStatus()).isEqualTo(VoucherPartnership.Status.ACTIVE);
        assertThat(p.isAutoAccepted()).isTrue();
        assertThat(p.getDecidedBy()).isEqualTo("auto-accept (staging)");
        ArgumentCaptor<VoucherPartnershipMerchant> rows = ArgumentCaptor.forClass(VoucherPartnershipMerchant.class);
        verify(members, times(2)).save(rows.capture());
        assertThat(rows.getAllValues()).extracting(VoucherPartnershipMerchant::getMerchantId)
                .containsExactly(mwos.getId(), bola.getId());
    }

    @Test
    void theStagingSwitch_isIgnored_whenMoneyIsReal() {
        VoucherPartnership p = invited(service("NONE", true));

        assertThat(p.getStatus()).isEqualTo(VoucherPartnership.Status.INVITED);
        assertThat(p.isAutoAccepted()).isFalse();
        verify(members, times(1)).save(any());
    }

    @Test
    void off_anInvitationWaitsForTheOtherSide() {
        VoucherPartnership p = invited(service("SIMULATED", false));

        assertThat(p.getStatus()).isEqualTo(VoucherPartnership.Status.INVITED);
        assertThat(p.isInvitedOnBehalf()).as("a SUPER_ADMIN acts for the tenant").isTrue();
        assertThat(p.getInvitedBy()).isEqualTo("owner@innbucks.co.zw");
    }

    @Test
    void aMerchantOfTheIssuersOwnProgram_needsNoPartnership() {
        Merchant sibling = merchant(mwosTenant, "MWOS Avondale");

        assertThat(service("SIMULATED", false).refusedTargets(mwos, List.of(sibling))).isEmpty();
        verify(partnerships, never()).findBetween(any(), any(), anyCollection());
    }

    @Test
    void anotherProgramsMerchant_needsBothMerchantsInAnActivePartnership() {
        VoucherPartnership active = new VoucherPartnership();
        active.setId(UUID.randomUUID());
        when(partnerships.findBetween(eq(mwosTenant), eq(bolaTenant), anyCollection())).thenReturn(List.of(active));
        VoucherPartnershipMerchant bolaIn = new VoucherPartnershipMerchant();
        bolaIn.setMerchantId(bola.getId());
        when(members.findByPartnershipIdOrderByAddedAtAsc(active.getId())).thenReturn(List.of(bolaIn));
        VoucherPartnershipService service = service("SIMULATED", false);

        // Bola Bet is in, but the issuer is not: refused.
        assertThat(service.refusedTargets(mwos, List.of(bola))).containsExactly(bola);

        VoucherPartnershipMerchant mwosIn = new VoucherPartnershipMerchant();
        mwosIn.setMerchantId(mwos.getId());
        when(members.findByPartnershipIdOrderByAddedAtAsc(active.getId())).thenReturn(List.of(mwosIn, bolaIn));
        assertThat(service.refusedTargets(mwos, List.of(bola))).isEmpty();
    }

    @Test
    void noPartnershipAtAll_refusesEveryMerchantOfThatProgram() {
        when(partnerships.findBetween(eq(mwosTenant), eq(bolaTenant), anyCollection())).thenReturn(List.of());

        assertThat(service("SIMULATED", false).refusedTargets(mwos, List.of(bola))).containsExactly(bola);
    }
}
