package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.client.UserServiceClient;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.MerchantAdminChange;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.LoyaltyRuleRepository;
import com.innbucks.loyaltyservice.repository.MerchantAdminChangeRepository;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.security.CallerDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Who a merchant is bound to ({@code merchants.admin_email}) and every way that
 * binding can move.
 *
 * <p>The binding decides whose sign-in resolves to a merchant, who may manage it
 * here and who receives its invoices, so each case below pins either who may
 * move it, or that the move left a history row. The bug these exist for: a
 * platform admin onboarding a merchant for a seller bound it to THEMSELVES,
 * because the column was always the caller's email and nothing could change it.
 */
class MerchantAdminBindingTest {

    private static final com.innbucks.loyaltyservice.config.LoyaltyProperties PROPS =
            new com.innbucks.loyaltyservice.config.LoyaltyProperties(null, null, null, null, null, null, null);
    private static final com.innbucks.loyaltyservice.config.SupportedCurrencies CURRENCIES =
            new com.innbucks.loyaltyservice.config.SupportedCurrencies("USD", "USD");
    private static final Dtos.FeeModel PRICED =
            new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("0.25"), BigDecimal.ZERO);

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID SUPER_ADMIN_UUID = UUID.randomUUID();
    private static final UUID MERCHANT_ADMIN_UUID = UUID.randomUUID();

    private MerchantRepository repo;
    private MerchantAdminChangeRepository history;
    private MerchantService svc;

    @BeforeEach
    void setUp() {
        repo = mock(MerchantRepository.class);
        history = mock(MerchantAdminChangeRepository.class);
        when(repo.save(any(Merchant.class))).thenAnswer(inv -> {
            Merchant m = inv.getArgument(0);
            if (m.getId() == null) m.setId(UUID.randomUUID());
            return m;
        });
        svc = new MerchantService(repo, mock(UserServiceClient.class), mock(LoyaltyRuleRepository.class),
                PROPS, CURRENCIES, history);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void signIn(String email, UUID userId, String role) {
        var token = new UsernamePasswordAuthenticationToken(
                email, null, AuthorityUtils.createAuthorityList(role));
        token.setDetails(new CallerDetails(null, null, null, userId));
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    private static void asSuperAdmin() {
        signIn("ops@innbucks.co.zw", SUPER_ADMIN_UUID, "ROLE_SUPER_ADMIN");
    }

    private static void asMerchantAdmin() {
        signIn("Tendai@Example.com", MERCHANT_ADMIN_UUID, "ROLE_MERCHANT_ADMIN");
    }

    private static Dtos.MerchantRequest create(String adminEmail) {
        return new Dtos.MerchantRequest("Chikwanha Traders", "Grocery", "USD", Merchant.BillingCycle.MONTHLY,
                PRICED, null, null, null, null, adminEmail);
    }

    private Merchant stored(String adminEmail) {
        Merchant m = new Merchant();
        m.setId(UUID.randomUUID());
        m.setTenantId(TENANT);
        m.setName("Chikwanha Traders");
        m.setCurrency("USD");
        m.setStatus(Merchant.Status.ACTIVE);
        m.setAdminEmail(adminEmail);
        when(repo.findById(m.getId())).thenReturn(Optional.of(m));
        return m;
    }

    private MerchantAdminChange onlyHistoryRow() {
        ArgumentCaptor<MerchantAdminChange> row = ArgumentCaptor.forClass(MerchantAdminChange.class);
        verify(history).save(row.capture());
        return row.getValue();
    }

    private static Merchant savedMerchant(MerchantRepository repo) {
        ArgumentCaptor<Merchant> saved = ArgumentCaptor.forClass(Merchant.class);
        verify(repo).save(saved.capture());
        return saved.getValue();
    }

    // ---- create ---------------------------------------------------------------

    @Test
    void create_withoutAdminEmail_bindsToTheCaller_asBefore() {
        asMerchantAdmin();

        svc.create(TENANT, create(null));

        assertThat(savedMerchant(repo).getAdminEmail()).isEqualTo("Tendai@Example.com");
        MerchantAdminChange row = onlyHistoryRow();
        assertThat(row.getChangeType()).isEqualTo(MerchantAdminChange.ChangeType.CREATED);
        assertThat(row.getPreviousEmail()).isNull();
        assertThat(row.getNewEmail()).isEqualTo("Tendai@Example.com");
        assertThat(row.getChangedBy()).isEqualTo(MERCHANT_ADMIN_UUID.toString());
        assertThat(row.getTenantId()).isEqualTo(TENANT);
    }

    @Test
    void create_withBlankAdminEmail_isTreatedAsOmitted() {
        asMerchantAdmin();

        svc.create(TENANT, create("   "));

        assertThat(savedMerchant(repo).getAdminEmail()).isEqualTo("Tendai@Example.com");
    }

    @Test
    void create_merchantAdminNamingThemselvesInAnyCase_isAccepted() {
        // Naming yourself is self-onboarding however it is spelled. The JWT's
        // spelling is what gets stored, so it matches the account exactly.
        asMerchantAdmin();

        svc.create(TENANT, create("  tendai@example.COM "));

        assertThat(savedMerchant(repo).getAdminEmail()).isEqualTo("Tendai@Example.com");
    }

    @Test
    void create_merchantAdminNamingSomeoneElse_isRefused_andWritesNothing() {
        // Allowing this would let a merchant admin give a stranger a second
        // matching merchant, which strips the stranger's merchantId claim.
        asMerchantAdmin();

        assertThatThrownBy(() -> svc.create(TENANT, create("rudo@chikwanha-traders.co.zw")))
                .isInstanceOfSatisfying(LoyaltyException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(ex.getCode()).isEqualTo("ADMIN_EMAIL_NOT_PERMITTED");
                    assertThat(ex.getMessage()).isEqualTo(
                            "Only a platform admin can onboard a merchant for someone else. "
                                    + "Omit adminEmail to onboard it for yourself.");
                });
        verify(repo, never()).save(any());
        verify(history, never()).save(any());
    }

    @Test
    void create_superAdminNamingASeller_bindsToTheSeller_notToThemselves() {
        // THE bug: this used to bind the merchant to ops@, leaving the seller
        // with no merchant at sign-in and ops@ with one more match.
        asSuperAdmin();

        svc.create(TENANT, create("  rudo@chikwanha-traders.co.zw "));

        assertThat(savedMerchant(repo).getAdminEmail()).isEqualTo("rudo@chikwanha-traders.co.zw");
        MerchantAdminChange row = onlyHistoryRow();
        assertThat(row.getChangeType()).isEqualTo(MerchantAdminChange.ChangeType.CREATED);
        assertThat(row.getNewEmail()).isEqualTo("rudo@chikwanha-traders.co.zw");
        assertThat(row.getChangedBy()).isEqualTo(SUPER_ADMIN_UUID.toString());
    }

    @Test
    void create_superAdminWithoutAdminEmail_stillBindsToThemselves() {
        // Unchanged default, and documented as the trap: a SUPER_ADMIN onboarding
        // for someone else has to NAME them.
        asSuperAdmin();

        svc.create(TENANT, create(null));

        assertThat(savedMerchant(repo).getAdminEmail()).isEqualTo("ops@innbucks.co.zw");
    }

    // ---- reassign -------------------------------------------------------------

    @Test
    void reassign_movesTheBinding_andRecordsFromAndTo() {
        asSuperAdmin();
        Merchant m = stored("ops@innbucks.co.zw");

        Dtos.MerchantResponse resp = svc.reassignAdmin(TENANT, m.getId(), " rudo@chikwanha-traders.co.zw ");

        assertThat(m.getAdminEmail()).isEqualTo("rudo@chikwanha-traders.co.zw");
        assertThat(resp.adminEmail()).isEqualTo("rudo@chikwanha-traders.co.zw");
        MerchantAdminChange row = onlyHistoryRow();
        assertThat(row.getChangeType()).isEqualTo(MerchantAdminChange.ChangeType.REASSIGNED);
        assertThat(row.getPreviousEmail()).isEqualTo("ops@innbucks.co.zw");
        assertThat(row.getNewEmail()).isEqualTo("rudo@chikwanha-traders.co.zw");
        assertThat(row.getMerchantId()).isEqualTo(m.getId());
        assertThat(row.getChangedBy()).isEqualTo(SUPER_ADMIN_UUID.toString());
    }

    @Test
    void reassign_toTheExactCurrentValue_isANoOp_andRecordsNothing() {
        asSuperAdmin();
        Merchant m = stored("rudo@chikwanha-traders.co.zw");

        svc.reassignAdmin(TENANT, m.getId(), "rudo@chikwanha-traders.co.zw");

        assertThat(m.getAdminEmail()).isEqualTo("rudo@chikwanha-traders.co.zw");
        verify(history, never()).save(any());
    }

    @Test
    void reassign_caseOnlyChange_isApplied() {
        // user-service finds a merchant's admin ACCOUNT by exact email, so a
        // wrongly-cased binding signs in fine but never gets its order
        // notifications. Correcting the case must therefore be a real change.
        asSuperAdmin();
        Merchant m = stored("Rudo@Chikwanha-Traders.co.zw");

        svc.reassignAdmin(TENANT, m.getId(), "rudo@chikwanha-traders.co.zw");

        assertThat(m.getAdminEmail()).isEqualTo("rudo@chikwanha-traders.co.zw");
        assertThat(onlyHistoryRow().getChangeType()).isEqualTo(MerchantAdminChange.ChangeType.REASSIGNED);
    }

    @Test
    void reassign_blank_isRefused_neverReadAsClear() {
        asSuperAdmin();
        Merchant m = stored("rudo@chikwanha-traders.co.zw");

        assertThatThrownBy(() -> svc.reassignAdmin(TENANT, m.getId(), "  "))
                .isInstanceOfSatisfying(LoyaltyException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getCode()).isEqualTo("ADMIN_EMAIL_REQUIRED");
                });
        assertThat(m.getAdminEmail()).isEqualTo("rudo@chikwanha-traders.co.zw");
        verify(history, never()).save(any());
    }

    @Test
    void reassign_merchantInAnotherTenant_isRefused() {
        asSuperAdmin();
        Merchant m = stored("rudo@chikwanha-traders.co.zw");

        assertThatThrownBy(() -> svc.reassignAdmin(UUID.randomUUID(), m.getId(), "x@y.co.zw"))
                .isInstanceOf(LoyaltyException.class);
        assertThat(m.getAdminEmail()).isEqualTo("rudo@chikwanha-traders.co.zw");
        verify(history, never()).save(any());
    }

    // ---- unbind ---------------------------------------------------------------

    @Test
    void unbind_clearsTheBinding_andRecordsWhatItWas() {
        asSuperAdmin();
        Merchant m = stored("ops@innbucks.co.zw");

        Dtos.MerchantResponse resp = svc.unbindAdmin(TENANT, m.getId());

        assertThat(m.getAdminEmail()).isNull();
        assertThat(resp.adminEmail()).isNull();
        MerchantAdminChange row = onlyHistoryRow();
        assertThat(row.getChangeType()).isEqualTo(MerchantAdminChange.ChangeType.UNBOUND);
        assertThat(row.getPreviousEmail()).isEqualTo("ops@innbucks.co.zw");
        assertThat(row.getNewEmail()).isNull();
    }

    @Test
    void unbind_alreadyUnbound_isANoOp_andRecordsNothing() {
        asSuperAdmin();
        Merchant m = stored(null);

        svc.unbindAdmin(TENANT, m.getId());

        verify(history, never()).save(any());
    }

    // ---- who sees the binding -------------------------------------------------

    @Test
    void response_carriesAdminEmail_forASuperAdminOnly() {
        // The list is tenant-wide: a SHOP_ADMIN of one merchant reads every
        // other merchant's row, so a person's email must not ride along for them.
        Merchant m = stored("rudo@chikwanha-traders.co.zw");

        asSuperAdmin();
        assertThat(MerchantService.toResponse(m).adminEmail()).isEqualTo("rudo@chikwanha-traders.co.zw");

        asMerchantAdmin();
        assertThat(MerchantService.toResponse(m).adminEmail()).isNull();

        signIn("cashier@example.com", UUID.randomUUID(), "ROLE_SHOP_ADMIN");
        assertThat(MerchantService.toResponse(m).adminEmail()).isNull();
    }

    @Test
    void response_json_omitsTheAdminEmailKey_whenItIsNull() {
        // NON_NULL on the one component: a non-super-admin's payload is
        // byte-for-byte what it was before this field existed.
        JsonMapper json = JsonMapper.builder().build();
        Merchant m = stored("rudo@chikwanha-traders.co.zw");

        asMerchantAdmin();
        String hidden = json.writeValueAsString(MerchantService.toResponse(m));
        assertThat(hidden).doesNotContain("adminEmail");
        assertThat(hidden).contains("\"feeWaivedReason\":null");   // siblings keep ALWAYS

        asSuperAdmin();
        assertThat(json.writeValueAsString(MerchantService.toResponse(m)))
                .contains("\"adminEmail\":\"rudo@chikwanha-traders.co.zw\"");
    }
}
