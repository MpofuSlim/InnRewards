package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.integration.TenantMemberNotifier;
import com.innbucks.loyaltyservice.repository.TenantMemberRepository;
import com.innbucks.loyaltyservice.repository.TenantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantServiceTest {

    private TenantService newService(TenantRepository tenants) {
        return new TenantService(tenants, mock(TenantMemberRepository.class),
                mock(CacheManager.class), mock(TenantMemberNotifier.class));
    }

    @Test
    void list_paged_excludesSystemTicketingTenant() {
        // The operator "Loyalty Tenants" list must not surface the seeded
        // platform-internal ticketing container tenant. Enforced by querying
        // findByIdNot(TICKETING_TENANT_ID, ...) instead of findAll(...).
        TenantRepository tenants = mock(TenantRepository.class);
        Pageable pageable = PageRequest.of(0, 20);
        when(tenants.findByIdNot(TicketingLoyaltyService.TICKETING_TENANT_ID, pageable))
                .thenReturn(Page.empty(pageable));

        newService(tenants).list(pageable);

        verify(tenants).findByIdNot(TicketingLoyaltyService.TICKETING_TENANT_ID, pageable);
        verify(tenants, never()).findAll(any(Pageable.class));
    }

    @Test
    void list_unpaged_excludesSystemTicketingTenant() {
        TenantRepository tenants = mock(TenantRepository.class);
        when(tenants.findByIdNot(TicketingLoyaltyService.TICKETING_TENANT_ID))
                .thenReturn(List.of());

        List<?> result = newService(tenants).list();

        assertEquals(0, result.size());
        verify(tenants).findByIdNot(TicketingLoyaltyService.TICKETING_TENANT_ID);
        verify(tenants, never()).findAll();
    }
}
