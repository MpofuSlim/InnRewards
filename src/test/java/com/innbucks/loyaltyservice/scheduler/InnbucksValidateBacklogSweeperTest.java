package com.innbucks.loyaltyservice.scheduler;

import com.innbucks.loyaltyservice.client.InnbucksCustomerValidateClient;
import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.entity.PhoneRegistration;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import com.innbucks.loyaltyservice.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The backlog validate sweep at the unit level — the wire contract is pinned by
 * {@code InnbucksCustomerValidateClientContractTest}, the sampling query runs
 * against real Postgres in the integration suite. What is pinned here is the
 * decision table per phone and, above all, that an unavailable upstream ABORTS
 * the run instead of burning the rest of the batch against a dead gateway.
 */
class InnbucksValidateBacklogSweeperTest {

    private static final String P1 = "+263771000001";
    private static final String P2 = "+263771000002";
    private static final String P3 = "+263771000003";

    private LoyaltyUserRepository users;
    private UserService userService;
    private InnbucksCustomerValidateClient client;
    private LoyaltyMetrics metrics;

    @BeforeEach
    void setUp() {
        users = mock(LoyaltyUserRepository.class);
        userService = mock(UserService.class);
        client = mock(InnbucksCustomerValidateClient.class);
        metrics = mock(LoyaltyMetrics.class);
        when(client.isConfigured()).thenReturn(true);
        when(userService.registerPhone(anyString(), any(), any(), any(), any()))
                .thenReturn(new UserService.RegistrationResult(true, 1, false));
    }

    private InnbucksValidateBacklogSweeper sweeper(boolean enabled, int batch) {
        return new InnbucksValidateBacklogSweeper(users, userService, client, metrics, enabled, batch);
    }

    @Test
    @DisplayName("disabled: touches nothing — no query, no network")
    void disabled_isInert() {
        sweeper(false, 100).sweep();

        verifyNoInteractions(users, client, userService);
    }

    @Test
    @DisplayName("enabled but unconfigured: skips the run before sampling anything")
    void unconfigured_skips() {
        when(client.isConfigured()).thenReturn(false);

        sweeper(true, 100).sweep();

        verifyNoInteractions(users, userService);
        verify(client, never()).checkCustomer(anyString());
    }

    @Test
    @DisplayName("confirmed customers are registered with source INNBUCKS_VALIDATE; non-customers are left alone")
    void registersCustomers_skipsNonCustomers() {
        when(users.sampleUnregisteredBacklogPhones(anyInt())).thenReturn(List.of(P1, P2, P3));
        when(client.checkCustomer(P1)).thenReturn(new InnbucksCustomerValidateClient.Customer("00"));
        when(client.checkCustomer(P2)).thenReturn(new InnbucksCustomerValidateClient.NotACustomer("code_06"));
        when(client.checkCustomer(P3)).thenReturn(new InnbucksCustomerValidateClient.Customer("00"));

        sweeper(true, 100).sweep();

        verify(userService).registerPhone(eq(P1), eq(PhoneRegistration.Source.INNBUCKS_VALIDATE),
                isNull(), isNull(), isNull());
        verify(userService).registerPhone(eq(P3), eq(PhoneRegistration.Source.INNBUCKS_VALIDATE),
                isNull(), isNull(), isNull());
        verify(userService, never()).registerPhone(eq(P2), any(), any(), any(), any());
        verify(metrics, org.mockito.Mockito.times(2)).incBacklogValidateChecked("customer");
        verify(metrics).incBacklogValidateChecked("not_customer");
    }

    @Test
    @DisplayName("an Unavailable answer ABORTS the run — the rest of the batch is not burned")
    void unavailable_abortsTheRun() {
        when(users.sampleUnregisteredBacklogPhones(anyInt())).thenReturn(List.of(P1, P2, P3));
        when(client.checkCustomer(P1)).thenReturn(new InnbucksCustomerValidateClient.Customer("00"));
        when(client.checkCustomer(P2)).thenReturn(new InnbucksCustomerValidateClient.Unavailable("http_503"));

        sweeper(true, 100).sweep();

        // P1's registration stands — work already done is kept.
        verify(userService).registerPhone(eq(P1), any(), any(), any(), any());
        // P3 is never checked: the upstream will answer the same way, and the
        // next scheduled run retries a fresh sample.
        verify(client, never()).checkCustomer(P3);
        verify(userService, never()).registerPhone(eq(P3), any(), any(), any(), any());
        verify(metrics).incBacklogValidateChecked("unavailable");
    }

    @Test
    @DisplayName("the configured batch size bounds the sample")
    void batchSizeIsPassedThrough() {
        when(users.sampleUnregisteredBacklogPhones(25)).thenReturn(List.of());

        sweeper(true, 25).sweep();

        verify(users).sampleUnregisteredBacklogPhones(25);
    }
}
