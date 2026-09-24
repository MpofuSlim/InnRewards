package com.innbucks.loyaltyservice.integration;

import com.innbucks.loyaltyservice.client.UserServiceClient;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Who receives a merchant's invoice: the OWNERs and ADMINs of the organization
 * that owns it, resolved from user-service at send time. It used to be the
 * merchant's single {@code admin_email}; now a colleague added to the business
 * gets the next invoice and one who left does not.
 */
class InvoiceEmailNotifierTest {

    private static final UUID ORG = UUID.fromString("7b1e2c4d-9f3a-4e5b-8c6d-0a1b2c3d4e5f");

    private final EmailNotificationClient email = mock(EmailNotificationClient.class);
    private final UserServiceClient userService = mock(UserServiceClient.class);
    private final InvoiceEmailNotifier notifier = new InvoiceEmailNotifier(email, userService);

    private static InvoiceGeneratedEvent invoiceFor(UUID organizationId) {
        return new InvoiceGeneratedEvent(UUID.randomUUID(), "Chicken Inn", organizationId, "INV-2026-000007",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), 12, 4, new BigDecimal("3.20"), "USD");
    }

    @Test
    void emailsEveryAdminOfTheOwningOrganization() {
        when(userService.organizationAdminEmails(ORG))
                .thenReturn(List.of("rudo@chikwanha-traders.co.zw", "tendai@chikwanha-traders.co.zw"));

        notifier.onInvoiceGenerated(invoiceFor(ORG));

        verify(email).sendEmail(eq("rudo@chikwanha-traders.co.zw"), anyString(), anyString(), eq("INV-2026-000007"));
        verify(email).sendEmail(eq("tendai@chikwanha-traders.co.zw"), anyString(), anyString(), eq("INV-2026-000007"));
    }

    @Test
    void oneFailedAddress_doesNotCostTheOthersTheirInvoice() {
        when(userService.organizationAdminEmails(ORG))
                .thenReturn(List.of("bounces@chikwanha-traders.co.zw", "tendai@chikwanha-traders.co.zw"));
        doThrow(new RuntimeException("gateway 400"))
                .when(email).sendEmail(eq("bounces@chikwanha-traders.co.zw"), anyString(), anyString(), anyString());

        notifier.onInvoiceGenerated(invoiceFor(ORG));

        verify(email).sendEmail(eq("tendai@chikwanha-traders.co.zw"), anyString(), anyString(), eq("INV-2026-000007"));
    }

    @Test
    void anUnownedMerchant_emailsNobody_andNeverAsksUserService() {
        notifier.onInvoiceGenerated(invoiceFor(null));

        verifyNoInteractions(userService);
        verify(email, never()).sendEmail(any(), any(), any(), any());
    }

    @Test
    void anOrganizationWithNobodyToEmail_sendsNothing() {
        when(userService.organizationAdminEmails(ORG)).thenReturn(List.of());

        notifier.onInvoiceGenerated(invoiceFor(ORG));

        verify(email, never()).sendEmail(any(), any(), any(), any());
    }
}
