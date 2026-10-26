package com.innbucks.loyaltyservice.controller;

/**
 * Swagger example bodies for the partner voucher API (V62). Kept apart so the
 * controller stays readable; the codes and messages are the ones the service
 * throws.
 */
final class PartnerVoucherSwaggerExamples {

    private PartnerVoucherSwaggerExamples() {
    }

    static final String SIGNED_REQUEST = "eyJhbGciOiJFUzI1NiIsImtpZCI6Im13LXN0Zy0yMDI2LTEwIiwidHlwIjoidm91Y2hlci1yZXF1ZXN0K2p3dCJ9"
            + ".eyJpc3MiOiJpbm5idWNrcy1taWRkbGV3YXJlIiwiYXVkIjoiaW5uYnVja3MtbG95YWx0eS12b3VjaGVyczp6dy1zdGFnaW5nIn0"
            + ".c2lnbmF0dXJl";

    static final String REQUEST_DESCRIPTION = """
            One compact JWS, sent as `Content-Type: application/jose`. Header: \
            `{"alg":"ES256","kid":"<your key id>","typ":"voucher-request+jwt"}`. Claims: `iss`, `aud` (this \
            cell's audience), `op` (`generate`, `inquire` or `redeem`, matching the path), `jti` (a new UUID \
            for every request, retries included), `iat`, `exp` (at most 120 s after `iat`) and `body`, the \
            call's request object. Do not send `Authorization` or `X-Tenant-Id`.""";

    static final String GENERATE_201 = """
            {
              "code": "201 CREATED",
              "message": "Voucher issued",
              "data": {
                "merchant": "MWOS",
                "partnerReference": "MWOS-POS-HRE001-20261023-000042",
                "orderRef": "VCH-3F9A1C2B7D10",
                "status": "ISSUED",
                "channel": "POS",
                "outlet": "HRE-001",
                "terminalId": "T-88231",
                "amount": 20.00,
                "currency": "USD",
                "funding": { "method": "CASH", "status": "CONFIRMED", "failureCode": null, "simulated": true },
                "payment": null,
                "voucher": {
                  "voucherId": "8d3c6f0e-2b1a-4c55-9e7d-0a1b2c3d4e5f",
                  "status": "ISSUED",
                  "value": 20.00,
                  "remainingBalance": 20.00,
                  "currency": "USD",
                  "issuedAt": "2026-10-23T09:14:05Z",
                  "expiresAt": "2027-10-23T09:14:05Z",
                  "holderPhone": "****4567",
                  "code": null
                },
                "replayed": false
              }
            }""";

    static final String GENERATE_202_AWAITING = """
            {
              "code": "202 ACCEPTED",
              "message": "Waiting for the customer to pay",
              "data": {
                "merchant": "MWOS",
                "partnerReference": "MWOS-WEB-20261023-558812",
                "orderRef": "VCH-7C21D0E4A9B3",
                "status": "AWAITING_PAYMENT",
                "channel": "ONLINE",
                "outlet": null,
                "terminalId": null,
                "amount": 10.00,
                "currency": "USD",
                "funding": { "method": "INNBUCKS_CODE", "status": "PENDING", "failureCode": null, "simulated": true },
                "payment": { "type": "INNBUCKS_CODE", "paymentCode": "123456789", "qr": null,
                             "expiresAt": "2026-10-23T09:44:05Z" },
                "voucher": null,
                "replayed": false
              }
            }""";

    static final String GENERATE_202_PROCESSING = """
            {
              "code": "202 ACCEPTED",
              "message": "Payment is being processed",
              "data": {
                "merchant": "MWOS",
                "partnerReference": "SIMSLOW-MWOS-POS-0007",
                "orderRef": "VCH-0B8E2F41C6D9",
                "status": "PROCESSING",
                "channel": "POS",
                "outlet": null,
                "terminalId": null,
                "amount": 20.00,
                "currency": "USD",
                "funding": { "method": "CASH", "status": "PENDING", "failureCode": null, "simulated": true },
                "payment": null,
                "voucher": null,
                "replayed": false
              }
            }""";

    static final String VALIDATION_400 = """
            {
              "code": "400 BAD_REQUEST",
              "message": "Validation failed",
              "data": { "customer.phone": "must not be blank" }
            }""";

    static final String FUNDING_METHOD_400 = """
            {
              "code": "FUNDING_METHOD_NOT_ALLOWED",
              "message": "This payment method is not available here.",
              "data": null
            }""";

    static final String BAD_PHONE_400 = """
            {
              "code": "BAD_PHONE",
              "message": "Give the customer's phone number, for example +263771234567.",
              "data": null
            }""";

    static final String AUTH_401 = """
            {
              "code": "PARTNER_AUTH_REJECTED",
              "message": "Request rejected.",
              "data": null
            }""";

    static final String MERCHANT_403 = """
            {
              "code": "MERCHANT_NOT_PERMITTED",
              "message": "This merchant is not set up for you.",
              "data": null
            }""";

    static final String NOT_FOUND_404 = """
            {
              "code": "404 NOT_FOUND",
              "message": "The requested resource was not found.",
              "data": null
            }""";

    static final String DUPLICATE_409 = """
            {
              "code": "POSSIBLE_DUPLICATE",
              "message": "The same sale was made a moment ago. If this is a second sale, send it again with confirmDuplicate: true.",
              "data": null
            }""";

    static final String EVIDENCE_409 = """
            {
              "code": "APPROVAL_CODE_ALREADY_USED",
              "message": "This card approval code already paid for another voucher.",
              "data": null
            }""";

    static final String FLOAT_422 = """
            {
              "code": "FLOAT_INSUFFICIENT",
              "message": "The merchant's float can't cover this sale. Nothing was charged.",
              "data": {
                "merchant": "MWOS",
                "partnerReference": "SIMFAIL-MWOS-POS-0003",
                "orderRef": "VCH-5D1A9E7B3C20",
                "status": "FAILED",
                "channel": "POS",
                "outlet": null,
                "terminalId": null,
                "amount": 20.00,
                "currency": "USD",
                "funding": { "method": "CASH", "status": "FAILED", "failureCode": "FLOAT_INSUFFICIENT", "simulated": true },
                "payment": null,
                "voucher": null,
                "replayed": false
              }
            }""";

    static final String MISMATCH_422 = """
            {
              "code": "IDEMPOTENCY_MISMATCH",
              "message": "This partnerReference was already used for a different request.",
              "data": null
            }""";

    static final String LIMIT_422 = """
            {
              "code": "LIMIT_EXCEEDED",
              "message": "This phone has received the most vouchers allowed in a day.",
              "data": { "limit": "PER_PHONE_DAILY_COUNT" }
            }""";

    static final String FUNDING_503 = """
            {
              "code": "FUNDING_UNAVAILABLE",
              "message": "Voucher payments aren't available right now. Nothing was recorded; send the same request again later.",
              "data": null
            }""";

    static final String PROVIDER_503 = """
            {
              "code": "PROVIDER_UNAVAILABLE",
              "message": "The payment provider could not be reached. Nothing moved; use a new partnerReference to try again.",
              "data": null
            }""";

    static final String REPLAY_503 = """
            {
              "code": "PARTNER_REPLAY_CHECK_UNAVAILABLE",
              "message": "Please retry: sign the request again with a new jti.",
              "data": null
            }""";

    static final String INQUIRE_CODE_200 = """
            {
              "code": "200 OK",
              "message": "Voucher found",
              "data": {
                "voucherId": "8d3c6f0e-2b1a-4c55-9e7d-0a1b2c3d4e5f",
                "status": "PARTIALLY_USED",
                "redeemable": true,
                "value": 20.00,
                "remainingBalance": 15.00,
                "currency": "USD",
                "expiresAt": "2027-10-23T09:14:05Z"
              }
            }""";

    static final String INQUIRE_REDEMPTION_200 = """
            {
              "code": "200 OK",
              "message": "Redemption found",
              "data": {
                "redemptionId": "b7e1f3a2-9c4d-4e6f-8a0b-1c2d3e4f5a6b",
                "voucherId": "8d3c6f0e-2b1a-4c55-9e7d-0a1b2c3d4e5f",
                "status": "PARTIALLY_USED",
                "value": 5.00,
                "remainingBalance": 15.00,
                "currency": "USD",
                "redeemedAt": "2026-10-23T10:02:11Z",
                "reference": "MWOS-BET-20261023-77812",
                "replayed": true,
                "settlement": { "status": "SETTLED", "transferReference": "SIM-SETTLE-b7e1f3a2-9c4d-4e6f-8a0b-1c2d3e4f5a6b",
                                "settledAt": "2026-10-23T10:02:11Z" }
              }
            }""";

    static final String INQUIRE_ORDER_404 = """
            {
              "code": "404 NOT_FOUND",
              "message": "order not found",
              "data": null
            }""";

    static final String VOUCHER_404 = """
            {
              "code": "404 NOT_FOUND",
              "message": "voucher not found",
              "data": null
            }""";

    static final String MISTYPED_400 = """
            {
              "code": "VOUCHER_CODE_MISTYPED",
              "message": "That voucher code doesn't look right. Please check it and try again.",
              "data": null
            }""";

    static final String NOT_OWNER_403 = """
            {
              "code": "NOT_VOUCHER_OWNER",
              "message": "This voucher isn't assigned to you.",
              "data": null
            }""";

    static final String LOCKED_429 = """
            {
              "code": "VOUCHER_ATTEMPTS_LOCKED",
              "message": "Too many incorrect voucher codes were tried. Please wait and try again later.",
              "data": { "retryAfterSeconds": 1800 }
            }""";

    static final String REDEEM_200 = """
            {
              "code": "200 OK",
              "message": "Voucher redeemed",
              "data": {
                "redemptionId": "b7e1f3a2-9c4d-4e6f-8a0b-1c2d3e4f5a6b",
                "voucherId": "8d3c6f0e-2b1a-4c55-9e7d-0a1b2c3d4e5f",
                "status": "PARTIALLY_USED",
                "value": 5.00,
                "remainingBalance": 15.00,
                "currency": "USD",
                "redeemedAt": "2026-10-23T10:02:11Z",
                "reference": "MWOS-BET-20261023-77812",
                "replayed": false,
                "settlement": { "status": "SETTLED", "transferReference": "SIM-SETTLE-b7e1f3a2-9c4d-4e6f-8a0b-1c2d3e4f5a6b",
                                "settledAt": "2026-10-23T10:02:11Z" }
              }
            }""";

    static final String EXPIRED_400 = """
            {
              "code": "EXPIRED",
              "message": "This voucher has expired.",
              "data": null
            }""";

    static final String ALREADY_REDEEMED_409 = """
            {
              "code": "ALREADY_REDEEMED",
              "message": "This voucher has already been fully redeemed.",
              "data": null
            }""";

    static final String REDEEM_MISMATCH_422 = """
            {
              "code": "IDEMPOTENCY_MISMATCH",
              "message": "This reference was already used for a different redemption.",
              "data": null
            }""";
}
