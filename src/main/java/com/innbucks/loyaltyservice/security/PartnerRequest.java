package com.innbucks.loyaltyservice.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.innbucks.loyaltyservice.entity.VoucherPartner;

/**
 * A partner API request whose signature, key, issuer, audience, operation,
 * times, source address and {@code jti} have all been checked (V62). The filter
 * puts it on the request; the controller reads it from there and never from the
 * body, which the filter has already consumed.
 *
 * @param body the call's request object, numbers read as exact decimals; null
 *             when the signed payload carried none (a 400, not a 401)
 */
public record PartnerRequest(VoucherPartner partner, String kid, String op, String jti, JsonNode body) {

    public static final String ATTRIBUTE = PartnerRequest.class.getName();
}
