package com.innbucks.loyaltyservice.integration;

import com.innbucks.loyaltyservice.config.LoyaltyProperties;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Mobile-money façade. Disabled by default per spec ("out of scope for now")
 * but exposed so adding M-Pesa later doesn't change calling code.
 */
@Component
public class MpesaIntegration {

    private final LoyaltyProperties props;

    public MpesaIntegration(LoyaltyProperties props) {
        this.props = props;
    }

    public Map<String, Object> convertToAirtime(UUID userId, BigDecimal points) {
        if (!props.integration().mpesaEnabled()) {
            throw LoyaltyException.badRequest("INTEGRATION_DISABLED",
                    "M-Pesa / airtime conversion is not enabled");
        }
        return Map.of(
                "userId", userId.toString(),
                "points", points.toPlainString(),
                "status", "QUEUED"
        );
    }
}
