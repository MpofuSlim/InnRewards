package com.innbucks.loyaltyservice.integration;

import com.innbucks.loyaltyservice.util.MsisdnMasking;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Sends SMS through the {@code innbucks-core-gateway} adapter (which routes to
 * InnBucks {@code messenger-interface}). Used by {@link GuestCheckoutNotifier}
 * as the PRIMARY channel for the walk-in congratulations message — SMS is
 * universal and reaches a guest who has no InnBucks/WhatsApp account yet.
 * Failures surface as {@link NotificationDeliveryException} so the caller can
 * log and fall back to WhatsApp (delivery is best-effort).
 */
@Slf4j
@Component
public class SmsNotificationClient {

    private static final String SMS_PATH = "/notifications/sms";

    private final RestClient restClient;

    public SmsNotificationClient(@Qualifier("innbucksGatewayRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public void sendSms(String destination, String message, String reference) {
        if (destination == null || destination.isBlank()) {
            throw new NotificationDeliveryException("SMS recipient is blank");
        }
        if (message == null || message.isBlank()) {
            throw new NotificationDeliveryException("SMS message is blank");
        }
        String ref = (reference != null && !reference.isBlank())
                ? reference
                : "TKT-SMS-" + UUID.randomUUID();
        Map<String, String> body = new HashMap<>();
        body.put("destination", destination);
        body.put("message", message);
        body.put("reference", ref);
        body.put("senderId", "INNBUCKS");
        try {
            restClient.post()
                    .uri(SMS_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("SMS notification accepted by gateway destination={} ref={}", MsisdnMasking.mask(destination), ref);
        } catch (RestClientResponseException ex) {
            log.warn("InnBucks gateway rejected SMS destination={} ref={} status={} body={}",
                    MsisdnMasking.mask(destination), ref, ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new NotificationDeliveryException(
                    "InnBucks gateway rejected SMS: HTTP " + ex.getStatusCode().value(), ex);
        } catch (RuntimeException ex) {
            log.warn("InnBucks gateway unreachable destination={} ref={} error={}",
                    MsisdnMasking.mask(destination), ref, ex.getMessage());
            throw new NotificationDeliveryException(
                    "InnBucks gateway unreachable: " + ex.getMessage(), ex);
        }
    }
}
