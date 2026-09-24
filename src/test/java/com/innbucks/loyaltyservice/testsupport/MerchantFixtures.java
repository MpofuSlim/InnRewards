package com.innbucks.loyaltyservice.testsupport;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.service.MerchantService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

/**
 * Creates a merchant FIXTURE for a test that is about something else — points,
 * vouchers, invoicing — and only needs a merchant to exist.
 *
 * <p>{@link MerchantService#create} decides who owns the new merchant from the
 * caller, and refuses a caller with no loyalty organization rather than create
 * a merchant nobody can manage. Those tests have no caller at all, so the
 * fixture is created as a SUPER_ADMIN (an unowned merchant, exactly what they
 * got before ownership moved to organizations), and whatever authentication the
 * test had is put back afterwards so its own assertions run as it intended.
 */
public final class MerchantFixtures {

    private MerchantFixtures() {}

    public static Dtos.MerchantResponse createAsPlatform(MerchantService merchants, UUID tenantId,
                                                         Dtos.MerchantRequest request) {
        Authentication previous = SecurityContextHolder.getContext().getAuthentication();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "fixtures@platform.test", null, List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
        try {
            return merchants.create(tenantId, request);
        } finally {
            SecurityContextHolder.getContext().setAuthentication(previous);
        }
    }
}
