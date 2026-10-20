package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Tenant;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.testsupport.ControllerSecurityTestBase;
import com.innbucks.loyaltyservice.testsupport.TestJwtFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A shop-staff account that is also OWNER/ADMIN of a loyalty organization,
 * through the real filter, tenant check, controller and service.
 *
 * <p>Its token carries the employer's {@code merchantId}/{@code shopId} next to
 * the organization's claims. {@code MerchantAuthz} honours the pin first, so the
 * organization's merchant-admin grant used to act on the EMPLOYER: a cashier
 * any loyalty business added as ADMIN could switch the employer off or plant a
 * merchant in its program. Now the organization's hat is worn beside a pin only
 * when that organization owns the pinned merchant.
 */
class TwoHatShopStaffSecurityTest extends ControllerSecurityTestBase {

    private static final UUID EMPLOYER_ORG = UUID.fromString("0e000000-0000-4000-8000-0000000000e1");
    private static final UUID OTHER_ORG = UUID.fromString("0b000000-0000-4000-8000-0000000000b1");

    @Autowired MerchantRepository merchantRepository;

    private UUID employerProgram;
    private UUID employer;
    private final UUID homeShop = UUID.randomUUID();

    @BeforeEach
    void employerWithAProgram() {
        Tenant t = new Tenant();
        t.setCode("EMP-" + UUID.randomUUID().toString().substring(0, 8));
        t.setName("Employer program " + UUID.randomUUID());
        t.setOrganizationId(EMPLOYER_ORG);
        employerProgram = tenantRepository.save(t).getId();

        Merchant m = new Merchant();
        m.setTenantId(employerProgram);
        m.setName("Employer " + UUID.randomUUID());
        m.setOrganizationId(EMPLOYER_ORG);
        employer = merchantRepository.save(m).getId();
    }

    /** Shop staff at the employer who also acts for {@code organizationId}. */
    private String twoHats(String shopRole, UUID organizationId, String orgRole) {
        return TestJwtFactory.builder("twohats-" + UUID.randomUUID() + "@shop.test")
                .role(shopRole)
                .userId(UUID.randomUUID())
                .merchantId(employer)
                .shopId(homeShop)
                .organization(organizationId, orgRole, List.of("loyalty"))
                .sign(jwtSecret);
    }

    private static String merchantBody(String name) {
        return """
                {"name":"%s","waiveFees":true,"waiveFeesReason":"two-hat test"}
                """.formatted(name);
    }

    @Test
    void aCashier_adminOfAnotherBusiness_cannotSwitchTheEmployerOff() throws Exception {
        mockMvc.perform(post("/loyalty/merchants/{id}/deactivate", employer)
                        .header("Authorization", bearer(twoHats("SHOP_USER", OTHER_ORG, "ADMIN")))
                        .header("X-Tenant-Id", employerProgram.toString()))
                .andExpect(status().isForbidden());

        assertThat(merchantRepository.findById(employer).orElseThrow().getStatus())
                .isEqualTo(Merchant.Status.ACTIVE);
    }

    @Test
    void aCashier_adminOfAnotherBusiness_cannotPlantAMerchantInTheEmployersProgram() throws Exception {
        long before = merchantRepository.count();

        mockMvc.perform(post("/loyalty/merchants")
                        .header("Authorization", bearer(twoHats("SHOP_USER", OTHER_ORG, "ADMIN")))
                        .header("X-Tenant-Id", employerProgram.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(merchantBody("Planted " + UUID.randomUUID())))
                .andExpect(status().isForbidden());

        assertThat(merchantRepository.count()).isEqualTo(before);
    }

    @Test
    void aShopAdmin_ownerOfAnotherBusiness_cannotPlantEither() throws Exception {
        // SHOP_ADMIN passes the role gate on merchant create; with the
        // organization withheld it has no business to create one for.
        mockMvc.perform(post("/loyalty/merchants")
                        .header("Authorization", bearer(twoHats("SHOP_ADMIN", OTHER_ORG, "OWNER")))
                        .header("X-Tenant-Id", employerProgram.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(merchantBody("Planted " + UUID.randomUUID())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORGANIZATION_SCOPE_MISSING"));
    }

    @Test
    void theSameCashier_stillWorksTheTill() throws Exception {
        mockMvc.perform(get("/loyalty/shops")
                        .header("Authorization", bearer(twoHats("SHOP_USER", OTHER_ORG, "ADMIN")))
                        .header("X-Tenant-Id", employerProgram.toString()))
                .andExpect(status().isOk());
    }

    @Test
    void shopStaffTheEmployerPromoted_stillOnboardsForTheEmployersOrganization() throws Exception {
        mockMvc.perform(post("/loyalty/merchants")
                        .header("Authorization", bearer(twoHats("SHOP_ADMIN", EMPLOYER_ORG, "ADMIN")))
                        .header("X-Tenant-Id", employerProgram.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(merchantBody("Second branch " + UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.organizationId").value(EMPLOYER_ORG.toString()));
    }
}
