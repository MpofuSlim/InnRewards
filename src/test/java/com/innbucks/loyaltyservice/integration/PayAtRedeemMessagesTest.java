package com.innbucks.loyaltyservice.integration;

import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.util.SmsTextSanitizer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The two messages of a code paid at the redeem (Flow 2, V69). */
class PayAtRedeemMessagesTest {

    private static final String PHONE = "+263771234567";
    private static final UUID ORDER = UUID.fromString("0f9e8d7c-6b5a-4c3d-2e1f-0a9b8c7d6e5f");
    private static final UUID MERCHANT = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private final NotificationGateway.PayAtRedeemCode code = new NotificationGateway.PayAtRedeemCode(ORDER, MERCHANT,
            PHONE, "2784148844684308", new BigDecimal("10.0000"), "USD", Instant.parse("2026-12-12T13:40:02Z"));
    private final NotificationGateway.PaidAtRedeem paid = new NotificationGateway.PaidAtRedeem(ORDER, MERCHANT,
            PHONE, "2784148844684308", new BigDecimal("10.0000"), "USD");

    @Test
    void theCodeMessage_saysWhatToPay_where_withWhichCode_andByWhen() {
        assertThat(NotificationGateway.payAtRedeemCodeMessage(code, "MWOS"))
                .isEqualTo("To pay MWOS USD 10.00, give code 2784-1488-4468-4308 at any MWOS agent before 12 Dec 2026.");
        assertThat(NotificationGateway.payAtRedeemCodeMessage(code, null))
                .isEqualTo("To pay USD 10.00, give code 2784-1488-4468-4308 at any agent before 12 Dec 2026.");
    }

    @Test
    void theReceipt_namesTheCodeByItsLastFourDigitsOnly() {
        String receipt = NotificationGateway.paidAtRedeemMessage(paid, "MWOS");
        assertThat(receipt).isEqualTo("We received your payment of USD 10.00 to MWOS for code ending 4308.");
        assertThat(receipt).doesNotContain("2784148844684308").doesNotContain("2784-1488");
    }

    @Test
    void bothMessages_surviveTheSmsSanitiserUnchanged() {
        for (String m : new String[] {
                NotificationGateway.payAtRedeemCodeMessage(code, "MWOS"),
                NotificationGateway.paidAtRedeemMessage(paid, "MWOS")}) {
            assertThat(SmsTextSanitizer.toGsmSafe(m)).isEqualTo(m);
            assertThat(m).doesNotContain("\n");
        }
    }

    @Test
    void sendsByWhatsApp_fallsBackToSms_andIgnoresAMissingPhone() {
        SmsNotificationClient sms = mock(SmsNotificationClient.class);
        WhatsAppNotificationClient whatsApp = mock(WhatsAppNotificationClient.class);
        MerchantRepository merchants = mock(MerchantRepository.class);
        Merchant mwos = new Merchant();
        mwos.setName("MWOS");
        when(merchants.findById(MERCHANT)).thenReturn(Optional.of(mwos));
        NotificationGateway gateway = new NotificationGateway(sms, whatsApp, merchants);

        doThrow(new RuntimeException("down")).when(whatsApp).sendCustomNotification(eq(PHONE), anyString());
        gateway.notifyPayAtRedeemCode(code);
        verify(sms).sendSms(PHONE,
                "To pay MWOS USD 10.00, give code 2784-1488-4468-4308 at any MWOS agent before 12 Dec 2026.",
                "VOUCHER-PAYCODE-" + ORDER);

        SmsNotificationClient quietSms = mock(SmsNotificationClient.class);
        WhatsAppNotificationClient quietWhatsApp = mock(WhatsAppNotificationClient.class);
        NotificationGateway quiet = new NotificationGateway(quietSms, quietWhatsApp, merchants);
        quiet.notifyPaidAtRedeem(new NotificationGateway.PaidAtRedeem(ORDER, MERCHANT, " ", "1", BigDecimal.ONE, "USD"));
        verifyNoInteractions(quietSms, quietWhatsApp);
    }
}
