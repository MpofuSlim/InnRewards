package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Shop;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.ShopRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ShopService}'s duplicate-name guard — both the
 * single-create path (409 {@code SHOP_NAME_TAKEN}) and the CSV bulk-upload
 * path (duplicate rows reported as failures, never inserted). Shop names are
 * unique per merchant, case-insensitive.
 */
class ShopServiceTest {

    private static Merchant merchant(UUID tenantId, UUID merchantId) {
        Merchant m = new Merchant();
        m.setId(merchantId);
        m.setTenantId(tenantId);
        m.setName("Pizza Inn");
        m.setCurrency("USD");
        m.setStatus(Merchant.Status.ACTIVE);
        return m;
    }

    private static ShopService newService(ShopRepository shops, MerchantService merchants) {
        return new ShopService(shops, merchants, mock(PlatformTransactionManager.class));
    }

    // --- Single create --------------------------------------------------------

    @Test
    void create_firstShopWithName_succeeds() {
        ShopRepository shops = mock(ShopRepository.class);
        MerchantService merchants = mock(MerchantService.class);
        UUID tenantId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        when(merchants.requireMerchant(tenantId, merchantId)).thenReturn(merchant(tenantId, merchantId));
        when(shops.existsByMerchantIdAndNameIgnoreCase(merchantId, "Pizza Inn Avondale")).thenReturn(false);
        when(shops.save(any(Shop.class))).thenAnswer(inv -> inv.getArgument(0));

        Dtos.ShopResponse resp = newService(shops, merchants).create(tenantId,
                new Dtos.ShopRequest(merchantId, "Pizza Inn Avondale", "123 King Rd"));

        assertThat(resp.name()).isEqualTo("Pizza Inn Avondale");
        verify(shops).save(any(Shop.class));
    }

    @Test
    void create_duplicateNameDifferentCase_throwsConflict() {
        ShopRepository shops = mock(ShopRepository.class);
        MerchantService merchants = mock(MerchantService.class);
        UUID tenantId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        when(merchants.requireMerchant(tenantId, merchantId)).thenReturn(merchant(tenantId, merchantId));
        when(shops.existsByMerchantIdAndNameIgnoreCase(merchantId, "pizza inn avondale")).thenReturn(true);

        assertThatThrownBy(() -> newService(shops, merchants).create(tenantId,
                new Dtos.ShopRequest(merchantId, "pizza inn avondale", "123 King Rd")))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> {
                    LoyaltyException le = (LoyaltyException) e;
                    assertThat(le.getStatus()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
                    assertThat(le.getCode()).isEqualTo("SHOP_NAME_TAKEN");
                });
        verify(shops, never()).save(any(Shop.class));
    }

    // --- Bulk upload ----------------------------------------------------------

    @Test
    void bulkUpload_skipsRowDuplicatingAnExistingShop() {
        ShopRepository shops = mock(ShopRepository.class);
        MerchantService merchants = mock(MerchantService.class);
        UUID tenantId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        when(merchants.requireMerchant(tenantId, merchantId)).thenReturn(merchant(tenantId, merchantId));
        // "Existing Shop" is already in the DB (any casing); the fresh one is not.
        when(shops.existsByMerchantIdAndNameIgnoreCase(eq(merchantId), any())).thenReturn(false);
        when(shops.existsByMerchantIdAndNameIgnoreCase(merchantId, "Existing Shop")).thenReturn(true);
        when(shops.save(any(Shop.class))).thenAnswer(inv -> inv.getArgument(0));

        String csv = "name,address\nExisting Shop,A1\nNew Shop,B1\n";
        Dtos.BulkShopUploadResult result = newService(shops, merchants)
                .bulkUploadFromCsv(tenantId, merchantId, stream(csv));

        assertThat(result.processed()).isEqualTo(2);
        assertThat(result.created()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.failures()).singleElement().satisfies(f -> {
            assertThat(f.name()).isEqualTo("Existing Shop");
            assertThat(f.error()).isEqualTo("duplicate shop name");
        });
        // Only the non-duplicate row is persisted.
        verify(shops, times(1)).save(any(Shop.class));
    }

    @Test
    void bulkUpload_skipsInFileDuplicate_caseInsensitive_firstWins() {
        ShopRepository shops = mock(ShopRepository.class);
        MerchantService merchants = mock(MerchantService.class);
        UUID tenantId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        when(merchants.requireMerchant(tenantId, merchantId)).thenReturn(merchant(tenantId, merchantId));
        // Nothing pre-exists in the DB — the collision is purely within the file.
        when(shops.existsByMerchantIdAndNameIgnoreCase(eq(merchantId), any())).thenReturn(false);
        when(shops.save(any(Shop.class))).thenAnswer(inv -> inv.getArgument(0));

        String csv = "name,address\nAvondale,A1\navondale,A2\n";
        Dtos.BulkShopUploadResult result = newService(shops, merchants)
                .bulkUploadFromCsv(tenantId, merchantId, stream(csv));

        assertThat(result.processed()).isEqualTo(2);
        assertThat(result.created()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.failures()).singleElement().satisfies(f -> {
            assertThat(f.row()).isEqualTo(3); // second data row (header is row 1)
            assertThat(f.error()).isEqualTo("duplicate shop name");
        });
        verify(shops, times(1)).save(any(Shop.class));
    }

    private static ByteArrayInputStream stream(String csv) {
        return new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
    }
}
