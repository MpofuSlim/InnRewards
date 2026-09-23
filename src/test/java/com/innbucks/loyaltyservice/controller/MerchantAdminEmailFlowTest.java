package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.MerchantAdminChange;
import com.innbucks.loyaltyservice.repository.MerchantAdminChangeRepository;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.testsupport.ControllerSecurityTestBase;
import com.innbucks.loyaltyservice.testsupport.TestJwtFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The admin binding end to end, with the REAL service and a real database: the
 * merchant row and its V50 history row are written in one transaction, so a
 * test that mocked the service could not show either landing.
 */
class MerchantAdminEmailFlowTest extends ControllerSecurityTestBase {

    private static final String SELLER = "rudo@chikwanha-traders.co.zw";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired MerchantRepository merchants;
    @Autowired MerchantAdminChangeRepository changes;

    private final UUID opsUuid = UUID.randomUUID();

    private String superAdmin() {
        return bearer(TestJwtFactory.builder("ops@test.local").role("SUPER_ADMIN").userId(opsUuid).sign(jwtSecret));
    }

    private Merchant seedMerchant(UUID tenant, String adminEmail) {
        Merchant m = new Merchant();
        m.setTenantId(tenant);
        m.setName("Seeded " + UUID.randomUUID());
        m.setCurrency("USD");
        m.setAdminEmail(adminEmail);
        return merchants.save(m);
    }

    @Test
    void super_admin_onboarding_for_a_seller_binds_it_to_the_seller() throws Exception {
        UUID tenant = newTenant("onbehalf");

        MvcResult created = mockMvc.perform(post("/loyalty/merchants")
                        .header("Authorization", superAdmin())
                        .header("X-Tenant-Id", tenant.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Chikwanha Traders","adminEmail":"%s",
                                 "waiveFees":true,"waiveFeesReason":"flow test"}
                                """.formatted(SELLER)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.adminEmail").value(SELLER))
                .andReturn();

        UUID id = UUID.fromString(JSON.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asString());
        assertThat(merchants.findById(id)).get()
                .extracting(Merchant::getAdminEmail).isEqualTo(SELLER);

        List<MerchantAdminChange> history = changes.findByMerchantIdOrderByChangedAtAsc(id);
        assertThat(history).singleElement().satisfies(row -> {
            assertThat(row.getChangeType()).isEqualTo(MerchantAdminChange.ChangeType.CREATED);
            assertThat(row.getPreviousEmail()).isNull();
            assertThat(row.getNewEmail()).isEqualTo(SELLER);
            assertThat(row.getChangedBy()).isEqualTo(opsUuid.toString());
            assertThat(row.getTenantId()).isEqualTo(tenant);
        });
    }

    @Test
    void merchant_admin_naming_someone_else_is_refused_and_creates_nothing() throws Exception {
        UUID tenant = newTenant("onbehalf-refused");
        joinTenant(tenant, "tendai@test.local");

        mockMvc.perform(post("/loyalty/merchants")
                        .header("Authorization", bearer(jwt("tendai@test.local", "MERCHANT_ADMIN")))
                        .header("X-Tenant-Id", tenant.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Not Mine","adminEmail":"%s",
                                 "waiveFees":true,"waiveFeesReason":"flow test"}
                                """.formatted(SELLER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_EMAIL_NOT_PERMITTED"));

        assertThat(merchants.findByTenantId(tenant)).isEmpty();
    }

    @Test
    void rebind_then_unbind_leaves_a_complete_history() throws Exception {
        UUID tenant = newTenant("rebind-flow");
        Merchant m = seedMerchant(tenant, "ops@test.local");

        mockMvc.perform(put("/loyalty/merchants/{id}/admin-email", m.getId())
                        .header("Authorization", superAdmin())
                        .header("X-Tenant-Id", tenant.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"adminEmail":"%s"}
                                """.formatted(SELLER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Merchant admin updated successfully"))
                .andExpect(jsonPath("$.data.adminEmail").value(SELLER));

        mockMvc.perform(delete("/loyalty/merchants/{id}/admin-email", m.getId())
                        .header("Authorization", superAdmin())
                        .header("X-Tenant-Id", tenant.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Merchant admin cleared successfully"))
                .andExpect(jsonPath("$.data.adminEmail").doesNotExist());

        assertThat(merchants.findById(m.getId())).get()
                .extracting(Merchant::getAdminEmail).isNull();
        List<MerchantAdminChange> history = changes.findByMerchantIdOrderByChangedAtAsc(m.getId());
        assertThat(history).extracting(MerchantAdminChange::getChangeType).containsExactly(
                MerchantAdminChange.ChangeType.REASSIGNED, MerchantAdminChange.ChangeType.UNBOUND);
        assertThat(history.get(0).getPreviousEmail()).isEqualTo("ops@test.local");
        assertThat(history.get(0).getNewEmail()).isEqualTo(SELLER);
        assertThat(history.get(1).getPreviousEmail()).isEqualTo(SELLER);
        assertThat(history.get(1).getNewEmail()).isNull();
    }

    @Test
    void rebinding_a_merchant_in_another_tenant_is_404_and_changes_nothing() throws Exception {
        UUID home = newTenant("rebind-home");
        UUID elsewhere = newTenant("rebind-elsewhere");
        Merchant m = seedMerchant(elsewhere, "ops@test.local");

        mockMvc.perform(put("/loyalty/merchants/{id}/admin-email", m.getId())
                        .header("Authorization", superAdmin())
                        .header("X-Tenant-Id", home.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"adminEmail":"%s"}
                                """.formatted(SELLER)))
                .andExpect(status().isNotFound());

        assertThat(merchants.findById(m.getId())).get()
                .extracting(Merchant::getAdminEmail).isEqualTo("ops@test.local");
        assertThat(changes.findByMerchantIdOrderByChangedAtAsc(m.getId())).isEmpty();
    }

    @Test
    void the_merchant_list_shows_the_binding_to_a_super_admin_only() throws Exception {
        UUID tenant = newTenant("list-visibility");
        joinTenant(tenant, "tendai@test.local");
        seedMerchant(tenant, SELLER);

        mockMvc.perform(get("/loyalty/merchants")
                        .header("Authorization", superAdmin())
                        .header("X-Tenant-Id", tenant.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].adminEmail").value(SELLER));

        MvcResult asMerchantAdmin = mockMvc.perform(get("/loyalty/merchants")
                        .header("Authorization", bearer(jwt("tendai@test.local", "MERCHANT_ADMIN")))
                        .header("X-Tenant-Id", tenant.toString()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode row = JSON.readTree(asMerchantAdmin.getResponse().getContentAsString())
                .path("data").path("content").path(0);
        assertThat(row.has("id")).isTrue();
        assertThat(row.has("adminEmail")).as("key absent, not null").isFalse();
    }
}
