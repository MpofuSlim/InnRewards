# Loyalty & Vouchers — Frontend Integration Guide

Everything a customer-facing frontend needs to integrate with loyalty-service:
points wallet, points statement, points transfer and redemption, vouchers by
phone, voucher transfer and voucher redemption.

Pairs with the Swagger UI, which is the authority on every field. This doc
covers the cross-cutting rules that don't live on any single endpoint — the
tenant header, the identity model, and which endpoints are safe to ship
against.

---

## 1. Before your first call

### Base URL

| Cell | Base |
|---|---|
| Staging | `https://dtx-staging.innbucks.co.zw/foundry` |
| Production | `https://dtx.innbucks.co.zw/foundry` |

Every path below is relative to that. The `/foundry` prefix is stripped at the
edge, so the backend never sees it — but the browser must send it.

### Two headers on essentially every call

```
Authorization: Bearer <jwt>
X-Tenant-Id: <tenant-uuid>
```

**The tenant header is not optional.** Every endpoint in this doc is
tenant-scoped, and a request without a valid `X-Tenant-Id` (or `X-Tenant-Code:
<slug>` as an alternative) is rejected before the controller runs. This is the
single most common reason a correct-looking call fails during first
integration.

The JWT comes from user-service login. loyalty-service only verifies it.

### The identity model — read this once, it will save you a day

**loyalty-service does not own user identity.** Customers register in
user-service. This service holds a per-tenant *projection* called a
`LoyaltyUser`, keyed by phone number, with its **own UUID**.

That means:

- The `userId` in a JWT is **not** the id loyalty endpoints want.
- A `loyaltyUserId` is per-tenant: the same customer has a different one in
  each tenant they've transacted with.
- Wallets, transactions and vouchers all reference the *projection's* UUID.

Practically: prefer the phone-keyed and `/me` endpoints, which resolve identity
for you. Only use the `{id}` endpoints with an id you were handed by a previous
loyalty response.

### Response envelope

Every response — success or failure — is the same shape:

```json
{ "code": "200 OK", "message": "Human-readable", "data": { } }
```

On failure `code` is either an HTTP status (`"400 BAD_REQUEST"`) or a
machine-readable slug (`"SELF_TRANSFER"`, `"VOUCHER_ALREADY_TRANSFERRED"`).
**Branch on `code`, never on `message`** — messages get reworded.

Paginated endpoints wrap their payload again inside `data`:

```json
{
  "code": "200 OK",
  "message": "…",
  "data": {
    "content": [ ],
    "page": 0, "size": 20, "totalElements": 42, "totalPages": 3,
    "first": true, "last": false
  }
}
```

Pass `?page=0&size=20&sort=createdAt,desc` on any of them.

---

## 2. Points wallet

### `GET /loyalty/users/me/wallet`

The "I just opened the app, what do I have?" call. Resolves the caller from the
JWT's `phoneNumber` claim — no id needed.

It aggregates **across every tenant** the customer exists in. From the
customer's point of view there is one wallet, not a per-tenant breakdown.

```json
{
  "code": "200 OK",
  "message": "Wallet retrieved",
  "data": {
    "phoneNumber": "+263771234567",
    "totalPoints": 225.00,
    "totalVouchers": 3
  }
}
```

`totalVouchers` counts vouchers in `ISSUED` / `DELIVERED` / `VIEWED` /
`PARTIALLY_USED`.

**Two things worth using:**

- It returns an **ETag** and `Cache-Control: private, max-age=30`. Send
  `If-None-Match` on a poll and you get a `304` with no body. This is the
  intended way to poll for "did anything change?" while the app is in
  foreground.
- `400 NO_PHONE_CLAIM` means the JWT has no `phoneNumber` claim — a staff
  token, not a customer one.

---

## 3. Points statement

### `GET /loyalty/users/{loyaltyUserId}/transactions`

Paginated ledger for one loyalty user, newest first.

```json
{
  "id": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "type": "PURCHASE",
  "amount": 100.00,
  "pointsDelta": 10.00,
  "balanceAfter": null,
  "ruleId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "campaignId": null,
  "shopId": "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11",
  "postedBy": null,
  "channel": "CHECKOUT_S2S",
  "reference": "ORDER-4471",
  "createdAt": "2026-08-24T09:15:00Z",
  "invoiceId": null
}
```

Field notes that will otherwise confuse you:

- **`pointsDelta` is signed.** Positive = earned, negative = spent. `type` tells
  you what kind of movement: `PURCHASE`, `REDEMPTION`, `TRANSFER`, `ADJUSTMENT`,
  `REVERSAL`.
- **`balanceAfter` is `null` on this endpoint, always.** The running balance is
  only recorded on write paths; computing it per row would cost a wallet lookup
  each. Use the wallet endpoint for the current balance.
- **`invoiceId` is usually `null`, and that is a real answer** — not missing
  data. Invoices are priced off *voucher* fees, not points, and a zero-total
  invoice is never raised. A period with points but no billable voucher
  activity produces no invoice at all. Render it as "not invoiced", never as a
  gap.
- **A `pointsDelta` of `0` on a `PURCHASE` is not a bug.** A transaction below
  the merchant's earning floor completes normally and earns nothing.

A `CUSTOMER` token can only read its own ledger; admin roles can read any user
in their tenant.

---

## 4. Send points (P2P)

### `POST /loyalty/transfer`

```json
{
  "fromUserId": "11111111-2222-3333-4444-555555555555",
  "toPhone": "+263771234567",
  "points": 250.0000,
  "reason": "Birthday gift"
}
```

- `fromUserId` is a **loyalty user id**, and the caller must own it (or be an
  admin).
- Recipient is **exactly one** of `toUserId` or `toPhone` — sending both or
  neither is `RECIPIENT_REQUIRED`.
- An unknown `toPhone` is auto-enrolled as a `PENDING` loyalty user. The points
  land and become spendable once they register. This is deliberate: you can
  gift to someone who isn't a customer yet.
- The **sender** must be registered — you cannot spend from a `PENDING`
  balance.

Returns the sender's new balance.

| Code | Meaning |
|---|---|
| `BAD_AMOUNT` | points ≤ 0 |
| `RECIPIENT_REQUIRED` | neither or both recipient fields |
| `SELF_TRANSFER` | recipient resolves to the sender's own wallet |
| `INSUFFICIENT_FUNDS` | not enough points |

> **Note on `SELF_TRANSFER`:** wallets are global per phone, so two loyalty user
> ids belonging to the same phone resolve to one wallet. Transferring between
> them is blocked even though the ids differ.

---

## 5. Redeem points

### `POST /loyalty/redeem`

```json
{
  "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
  "userId": "11111111-2222-3333-4444-555555555555",
  "points": 500.0000,
  "reason": "Counter redemption",
  "reference": "ORDER-4471"
}
```

`merchantId` is ignored when the JWT already carries one (shop staff tokens do);
it is required for `MERCHANT_ADMIN`.

**`reference` is an idempotency key.** A repeat redeem with the same
`(merchant, reference)` replays the original response instead of debiting the
wallet again. Use the order/booking id. Generate it when the user taps
*Redeem*, not when the request starts — a key regenerated per retry defeats the
whole mechanism.

---

## 6. Vouchers by phone

### `GET /loyalty/vouchers/users/by-phone/{phoneNumber}/active`

Paginated. Returns vouchers in an active state — `ISSUED`, `DELIVERED`,
`VIEWED`, `PARTIALLY_USED` — for that phone **within the tenant on the header**.

```json
{
  "id": "9f8e7d6c-5b4a-3210-fedc-ba9876543210",
  "code": "VCH-AB12CD34",
  "status": "VIEWED",
  "templateId": "4d3c2b1a-9876-5432-10fe-dcba98765432",
  "assignedUserId": "66666666-7777-8888-9999-000000000000",
  "assigneePhone": "+263771234567",
  "usesRemaining": 1,
  "valueType": "PERCENT",
  "value": 10.0000,
  "currency": "USD",
  "issuedAt": "2026-08-20T09:00:00Z",
  "expiresAt": "2027-08-20T09:00:00Z"
}
```

**`valueType` drives rendering** and has four values:

| `valueType` | Render `value` as |
|---|---|
| `AMOUNT` | currency-formatted, using `currency` |
| `PERCENT` | "10% off" |
| `FREE_ITEM` | ignore `value` — it may be null |
| `COMBO` | ignore `value` — it may be null |

`value` and `currency` are a **snapshot frozen at issuance**. A merchant editing
the template later does not change already-issued vouchers, so always render
what the voucher carries, never re-derive from the template.

**Vouchers do still expire** (365 days by default). Points no longer expire at
all — don't reuse one expiry UI for both.

### `POST /loyalty/vouchers/codes/{code}/viewed`

Marks a voucher `VIEWED`. Call it when the customer actually opens the voucher
detail. Only the assignee (or staff) may call it.

---

## 7. Send a voucher (P2P) — **one hop only**

### `POST /loyalty/vouchers/{voucherId}/transfer`

```json
{
  "toPhone": "+263771234567",
  "note": "Passing this on to my sister"
}
```

> ### A voucher can only be transferred **once**
>
> The lifecycle is **issued → transferred → redeemed**. A voucher that has
> already changed hands is refused with `VOUCHER_ALREADY_TRANSFERRED`.
>
> Build the UI for this: hide or disable the *Send* action on a voucher that has
> already been transferred, rather than letting the user hit the error. The
> transfer is one-way and cannot be undone from the app.

Rules:

- Same recipient rule as points — exactly one of `toUserId` / `toPhone`. An
  unknown phone is auto-enrolled `PENDING`, so you can pass a voucher to
  someone who hasn't signed up.
- **Only an unused, live voucher moves**: `ISSUED`, `DELIVERED`, `VIEWED`.
  A `PARTIALLY_USED` voucher is refused along with the terminal states.
- The caller must be the **current holder**.
- Returns the updated voucher — the response shows the **new** assignee.

| Code | HTTP | Meaning |
|---|---|---|
| `VOUCHER_ALREADY_TRANSFERRED` | 400 | already had its one hop |
| `VOUCHER_NOT_TRANSFERABLE` | 400 | wrong status (message names it) |
| `VOUCHER_EXPIRED` | 400 | past `expiresAt` |
| `SELF_TRANSFER` | 400 | recipient is the caller |
| `RECIPIENT_REQUIRED` | 400 | neither or both recipient fields |
| `NOT_VOUCHER_OWNER` | 403 | caller isn't the holder |

**The recipient is not notified.** There is no push/SMS on transfer today, so if
your UX depends on the recipient finding out, the sender has to tell them.

---

## 8. Redeem a voucher

### `POST /loyalty/vouchers/redeem`

```json
{
  "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
  "code": "VCH-AB12CD34",
  "userId": "11111111-2222-3333-4444-555555555555",
  "outletCode": "WESTGATE",
  "deviceFingerprint": "abc123def456",
  "ipAddress": "192.168.1.100"
}
```

```json
{
  "code": "200 OK",
  "message": "Voucher redeemed successfully",
  "data": {
    "redemptionId": "…", "voucherId": "…", "status": "REDEEMED",
    "usesRemaining": 0, "value": 10.0000, "valueType": "PERCENT",
    "redeemedAt": "2026-08-25T14:02:00Z"
  }
}
```

**Send `deviceFingerprint` if you can.** Failed redemption attempts are recorded
as fraud attempts, and repeated failures from one device inside the fraud window
auto-block the account. Without a fingerprint that protection can't run.

Check `usesRemaining` in the response: a multi-use voucher returns
`PARTIALLY_USED` with a remaining count rather than `REDEEMED`.

---

## 9. QR

### `POST /loyalty/qr/issue` → `POST /loyalty/qr/consume`

`issue` mints a signed, short-lived token (default TTL **300s**) for either a
merchant (`sourceType: MERCHANT` — customer scans to earn) or a user
(`sourceType: USER` — merchant scans to receive a P2P transfer).

`consume` takes the `token` + `signature` straight from the scanned payload plus
the scanning `userId`. Pass both through verbatim — never re-sign or reconstruct
them client-side.

Tokens are single-use and expire fast. Regenerate on display, don't cache.

---

## 10. TEST-ONLY endpoints

### `GET /loyalty/public/customers/{phoneNumber}/transactions`

**No JWT. No tenant header. No role.** Exists so you can build screens against
real data before your auth flow is wired up.

Returns the same paginated statement as §3, collapsed across every tenant the
phone belongs to.

- **Disabled by default.** A cell that hasn't opted in returns **404**. It is
  enabled on staging only.
- **Never point a production build at this.** A phone number is guessable, so an
  enabled cell leaks any customer's history to anyone who asks. It is not
  hardened and it is not a fallback.
- An unknown phone returns an **empty page**, not a 404.

Ship against `GET /loyalty/users/{id}/transactions` (§3). This one is
scaffolding.

---

## 11. Not implemented — don't build against it

**`POST /loyalty/convert-to-airtime`** returns `200` with a feature-flag payload
saying *"M-Pesa / airtime conversion is not enabled in this build."* It is a
stub. There is no airtime conversion.

---

## 12. Integration checklist

- [ ] `X-Tenant-Id` on every call — this is the #1 first-day failure
- [ ] `Authorization: Bearer <jwt>` from user-service login
- [ ] Base URL includes the `/foundry` prefix
- [ ] Branch on `code`, never on `message`
- [ ] `loyaltyUserId` ≠ JWT `userId` — never send the JWT one
- [ ] Unwrap paginated payloads twice: `data.content`
- [ ] `pointsDelta` is signed; `balanceAfter` is always null on the statement
- [ ] `invoiceId: null` renders as "not invoiced", not as an error
- [ ] Render vouchers by `valueType`; `value` may be null for `FREE_ITEM`/`COMBO`
- [ ] Vouchers expire, points do not
- [ ] Disable *Send* on an already-transferred voucher — one hop only
- [ ] Idempotency `reference` generated on user intent, not per retry
- [ ] Send `deviceFingerprint` on voucher redemption
- [ ] Nothing in §10 or §11 is in the production build
