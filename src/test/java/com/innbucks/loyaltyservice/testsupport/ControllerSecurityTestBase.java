package com.innbucks.loyaltyservice.testsupport;

import com.innbucks.loyaltyservice.client.UserServiceClient;
import com.innbucks.loyaltyservice.dto.CustomerTierResponseDTO;
import com.innbucks.loyaltyservice.entity.Tenant;
import com.innbucks.loyaltyservice.entity.TenantMember;
import com.innbucks.loyaltyservice.repository.TenantMemberRepository;
import com.innbucks.loyaltyservice.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Shared base for controller security + role tests. Boots the full Spring
 * context once (shared via Spring test context caching) so the real
 * {@code SecurityFilterChain}, {@code JwtFilter}, {@code TenantContext}, and
 * {@code GlobalExceptionHandler} all run — what subclasses assert against
 * matches what runs in prod.
 *
 * <p>Runs against the H2 test profile (the same one
 * {@code LoyaltyServiceIntegrationTest} uses). Subclasses {@code @MockitoBean}
 * the specific service their controller depends on, so this base only stubs
 * the cross-service collaborators.
 *
 * <p>Convenience helpers create tenants + memberships so subclasses can write
 * the four canonical security cases (no token / wrong role / missing tenant
 * header / cross-tenant) without boilerplate.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class ControllerSecurityTestBase {

    @Autowired protected MockMvc mockMvc;
    @Autowired protected TenantRepository tenantRepository;
    @Autowired protected TenantMemberRepository tenantMemberRepository;

    @Value("${jwt.secret}") protected String jwtSecret;

    /** Stubbed so we don't need a running user-service. Subclasses can override behaviour as needed. */
    @MockitoBean protected UserServiceClient userServiceClient;

    @BeforeEach
    void seedDefaults() {
        // Default stub — most tests don't enrol users, but the bean has to be wired.
        when(userServiceClient.getCustomerTier(anyString())).thenReturn(Optional.of(
                new CustomerTierResponseDTO("+263770000000", 1, 2)));
    }

    /** Creates a tenant with a unique code and returns its id. */
    protected UUID newTenant(String code) {
        Tenant t = new Tenant();
        t.setCode(code + "-" + UUID.randomUUID().toString().substring(0, 8));
        t.setName("Test Tenant " + code);
        return tenantRepository.save(t).getId();
    }

    /** Grants the given email membership of the tenant so {@code TenantContext} accepts them.
     *  Email-keyed (userId left null) — this is the legacy-compatibility path the dual-mode
     *  membership check must keep admitting. */
    protected void joinTenant(UUID tenantId, String email) {
        TenantMember m = new TenantMember();
        m.setTenantId(tenantId);
        m.setEmail(email);
        tenantMemberRepository.save(m);
    }

    /** Grants the given user UUID membership of the tenant (userId-keyed, email left null)
     *  so a token carrying that {@code userId} claim passes {@code TenantContext}. */
    protected void joinTenantByUserId(UUID tenantId, UUID userId) {
        TenantMember m = new TenantMember();
        m.setTenantId(tenantId);
        m.setUserId(userId);
        tenantMemberRepository.save(m);
    }

    /** Mints a JWT signed with the test jwt.secret. */
    protected String jwt(String email, String role) {
        return TestJwtFactory.builder(email).role(role).sign(jwtSecret);
    }

    protected String bearer(String token) {
        return "Bearer " + token;
    }
}
