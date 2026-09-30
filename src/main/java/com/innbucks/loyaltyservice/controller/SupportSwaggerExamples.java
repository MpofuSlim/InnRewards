package com.innbucks.loyaltyservice.controller;

/**
 * Swagger example bodies shared by the support controllers. Annotation values
 * must be compile-time constants, and the same refusal (401, 403, an expired
 * lookup) appears on every support endpoint — one copy here means a changed
 * message is changed once, not on twenty handlers.
 *
 * <p>Every body is the exact {@code code} and {@code message} the code throws.
 * All data is placeholder: this repository is public.
 */
final class SupportSwaggerExamples {

    private SupportSwaggerExamples() {}

    static final String UNAUTHORIZED = """
            {
              "code": "401 UNAUTHORIZED",
              "message": "Invalid or missing token",
              "data": null
            }""";

    /** Method security refusing a token without the permission — including a SUPER_ADMIN ROLE with no perms claim. */
    static final String FORBIDDEN = """
            {
              "code": "403 FORBIDDEN",
              "message": "You don't have permission to do that.",
              "data": null
            }""";

    static final String LOOKUP_NOT_FOUND = """
            {
              "code": "lookup_not_found",
              "message": "This lookup has expired or does not exist. Look the customer up again.",
              "data": null
            }""";

    static final String BAD_PAGE_PARAM = """
            {
              "code": "400 BAD_REQUEST",
              "message": "Invalid value for 'page'.",
              "data": null
            }""";

    static final String INVALID_RANGE = """
            {
              "code": "invalid_range",
              "message": "'from' must be before 'to'.",
              "data": null
            }""";

    /** The 360, as the lookup and the lookup re-read both return it. */
    static final String CUSTOMER_360 = """
                "lookupId": "0c9b8a7d-6e5f-4a3b-9c2d-1e0f9a8b7c6d",
                "expiresAt": "2026-09-30T20:15:00Z",
                "customer": {
                  "phone": "****4567",
                  "registration": {
                    "registered": true,
                    "source": "TICKETING_OTP",
                    "registeredAt": "2026-08-01T09:12:44Z",
                    "revokedAt": null,
                    "revokedReason": null
                  },
                  "memberships": [
                    {
                      "tenantId": "0a571c1c-7c75-4000-a000-000000000001",
                      "tenantName": "Example Retail Group",
                      "userId": "8f14e45f-ceea-467a-9ba6-7c3f0e2a1b44",
                      "status": "ACTIVE",
                      "statusReason": null,
                      "joinedAt": "2026-07-14T11:02:09Z"
                    }
                  ],
                  "wallet": {
                    "totalBalance": 1250.0000,
                    "wallets": [
                      {
                        "id": "3d1f0c2b-8a9e-4b7c-9d6e-5f4a3b2c1d0e",
                        "label": "Main",
                        "type": "MAIN",
                        "pocket": null,
                        "balance": 1250.0000,
                        "lockedUntil": null
                      }
                    ]
                  },
                  "recentTransactions": {
                    "content": [
                      {
                        "id": "22222222-3333-4444-5555-666666666666",
                        "tenantId": "0a571c1c-7c75-4000-a000-000000000001",
                        "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                        "merchantName": "Example Pizza",
                        "userId": "8f14e45f-ceea-467a-9ba6-7c3f0e2a1b44",
                        "type": "PURCHASE",
                        "status": "POSTED",
                        "amount": 25.0000,
                        "currency": "USD",
                        "pointsDelta": 250.0000,
                        "reference": "POS-20260929-0042",
                        "reversesId": null,
                        "shopId": "c7d8e9f0-1234-5678-90ab-cdef12345678",
                        "postedBy": "f4a8c2d6-1e39-4b77-8c05-2a9d6e4b8f12",
                        "channel": "TYPED_PHONE",
                        "createdAt": "2026-09-29T14:30:00Z"
                      }
                    ],
                    "page": 0,
                    "size": 10,
                    "totalElements": 1,
                    "totalPages": 1,
                    "first": true,
                    "last": true
                  },
                  "vouchers": {
                    "heldLive": 1,
                    "heldTotal": 3,
                    "sent": 1,
                    "transferredAway": 0,
                    "recentHeld": [
                      {
                        "id": "6a5b4c3d-2e1f-4a0b-9c8d-7e6f5a4b3c2d",
                        "tenantId": "0a571c1c-7c75-4000-a000-000000000001",
                        "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                        "merchantName": "Example Pizza",
                        "status": "ISSUED",
                        "voucherType": "SINGLE_USE",
                        "value": 5.0000,
                        "currency": "USD",
                        "holder": "****4567",
                        "holderName": "Rudo",
                        "sender": "****8899",
                        "senderName": "Tatenda",
                        "transferredFrom": null,
                        "usesRemaining": 1,
                        "issuedAt": "2026-09-20T10:00:00Z",
                        "deliveredAt": "2026-09-20T10:00:01Z",
                        "viewedAt": null,
                        "redeemedAt": null,
                        "expiresAt": "2027-09-20T10:00:00Z",
                        "transferredAt": null,
                        "campaignSource": null
                      }
                    ]
                  },
                  "voucherOrders": {
                    "total": 1,
                    "recent": [
                      {
                        "id": "9e8d7c6b-5a4f-4e3d-8c2b-1a0f9e8d7c6b",
                        "orderRef": "VCH-1A2B3C4D5E6F",
                        "roles": ["PAYER", "SENDER"],
                        "tenantId": "0a571c1c-7c75-4000-a000-000000000001",
                        "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                        "merchantName": "Example Pizza",
                        "status": "PAID",
                        "amount": 5.0000,
                        "currency": "USD",
                        "payer": "****4567",
                        "recipient": "****7788",
                        "sender": "****4567",
                        "paidVia": "GATEWAY",
                        "paidAt": "2026-09-21T08:15:00Z",
                        "expiresAt": "2026-09-21T08:40:00Z",
                        "createdAt": "2026-09-21T08:10:00Z",
                        "voucherId": "1f2e3d4c-5b6a-4978-8a9b-0c1d2e3f4a5b"
                      }
                    ]
                  },
                  "sessions": { "activeChains": 1 },
                  "tier": { "currentTier": 2, "nextTier": 3 },
                  "notes": 2,
                  "messages": 1
                }
            """;

    static final String RATE_LIMITED = """
            {
              "code": "support_message_rate_limited",
              "message": "This customer has already been sent 5 support messages in the last 24 hours. Try again later.",
              "data": { "scope": "RECIPIENT", "limit": 5, "windowMinutes": 1440 }
            }""";

    static final String RATE_LIMITED_AGENT = """
            {
              "code": "support_message_rate_limited",
              "message": "You have sent 60 customer messages in the last hour. Try again later.",
              "data": { "scope": "AGENT", "limit": 60, "windowMinutes": 60 }
            }""";

    static final String CHANNEL_UNAVAILABLE = """
            {
              "code": "channel_unavailable",
              "message": "WHATSAPP is not configured on this cell. Choose another channel.",
              "data": null
            }""";

    static final String NOT_DELIVERED = """
            {
              "code": "message_not_delivered",
              "message": "The message could not be delivered on any channel. The attempt is recorded.",
              "data": {
                "id": "2b1a0f9e-8d7c-4b6a-9f5e-4d3c2b1a0f9e",
                "kind": "CUSTOM",
                "channelRequested": "SMS_THEN_WHATSAPP",
                "deliveredVia": null,
                "outcome": "FAILED",
                "recipientRole": "CUSTOMER",
                "recipient": "****4567",
                "text": "Hi, your points adjustment has been applied. Check your balance in the app.\\n- InnBucks Loyalty Support",
                "sentBy": { "uuid": "5b0e7a1c-3f2d-4c9e-8a7b-6d5e4f3a2b1c", "login": "agent.moyo@example.com" },
                "createdAt": "2026-09-30T08:30:00Z",
                "completedAt": "2026-09-30T08:30:04Z",
                "failureCode": "sms_and_whatsapp_failed"
              }
            }""";

    static final String MESSAGE_SENT = """
            {
              "code": "201 CREATED",
              "message": "Message sent",
              "data": {
                "id": "1a0f9e8d-7c6b-4a5f-8e4d-3c2b1a0f9e8d",
                "kind": "CUSTOM",
                "channelRequested": "SMS_THEN_WHATSAPP",
                "deliveredVia": "SMS",
                "outcome": "SENT",
                "recipientRole": "CUSTOMER",
                "recipient": "****4567",
                "text": "Hi, your points adjustment has been applied. Check your balance in the app.\\n- InnBucks Loyalty Support",
                "sentBy": { "uuid": "5b0e7a1c-3f2d-4c9e-8a7b-6d5e4f3a2b1c", "login": "agent.moyo@example.com" },
                "createdAt": "2026-09-30T08:30:00Z",
                "completedAt": "2026-09-30T08:30:02Z",
                "failureCode": null
              }
            }""";

    static final String VOUCHER_RESENT = """
            {
              "code": "201 CREATED",
              "message": "Voucher resent to its holder",
              "data": {
                "id": "0f9e8d7c-6b5a-4f4e-9d3c-2b1a0f9e8d7c",
                "kind": "VOUCHER_RESEND",
                "channelRequested": "WHATSAPP",
                "deliveredVia": "WHATSAPP",
                "outcome": "SENT",
                "recipientRole": "VOUCHER_HOLDER",
                "recipient": "****4567",
                "text": null,
                "sentBy": { "uuid": "5b0e7a1c-3f2d-4c9e-8a7b-6d5e4f3a2b1c", "login": "agent.moyo@example.com" },
                "createdAt": "2026-09-30T08:40:00Z",
                "completedAt": "2026-09-30T08:40:01Z",
                "failureCode": null
              }
            }""";

    static final String AGENT_IDENTITY_REQUIRED = """
            {
              "code": "agent_identity_required",
              "message": "This action needs an agent token carrying a userUuid claim. Sign in again.",
              "data": null
            }""";

    static final String INVALID_REASON = """
            {
              "code": "invalid_reason",
              "message": "Give a reason. It is empty once formatting is removed.",
              "data": null
            }""";

    static final String MEMBERSHIP_NOT_FOUND = """
            {
              "code": "membership_not_found",
              "message": "This customer has no loyalty membership with that id.",
              "data": null
            }""";

    static final String REASON_BLANK = """
            {
              "code": "400 BAD_REQUEST",
              "message": "Validation failed",
              "data": { "reason": "must not be blank" }
            }""";
}
