# Loyalty & Vouchers — PUBLIC TEST Endpoints (no auth)

**Audience:** the mobile/web frontend team, building loyalty screens before the
app's auth flow is wired up.

> [!WARNING]
> **These are scaffolding, not the endpoints you will ship against.**
> They are disabled by default and enabled on **staging only**. A phone number is
> guessable, and these endpoints **move value** as well as read it — on an enabled
> cell, anyone who can guess a number can spend that customer's points and
> vouchers. Never point a production build at them.
>
> Every one of them has an authenticated twin, listed in the table below and
> documented in [`Loyalty-Frontend-Integration.md`](./Loyalty-Frontend-Integration.md).
> Build your screens here, then swap the base path and add the two headers.

---

## 1. Before your first call

### Base URL

| Environment | Base URL | Public test endpoints |
|---|---|---|
| Staging | `https://dtx-staging.innbucks.co.zw/foundry` | **enabled** |
| Production | `https://dtx.innbucks.co.zw/foundry` | **disabled — returns 404** |

Every path below is relative to that. The `/foundry` prefix is stripped at the
edge before the request reaches the service, so it appears in your URLs and
nowhere in this document's paths. **Omitting it gets you an nginx `301`, not a
404** — if you see a redirect to an HTML page, that is the missing prefix.

### Send no headers

That is the whole point of this surface:

- **No `Authorization`.** No bearer token, no login, no refresh.
- **No `X-Tenant-Id`.** The tenant is resolved from the phone number or the
  voucher row.
- No role, no device fingerprint, no `loyaltyUserId`.

`Content-Type: application/json` on the POSTs, and nothing else.

### The phone number IS the identity

There is no caller, so whatever phone number is in the URL is the account being
read or spent from. Two consequences:

**1. The phone must be exact E.164, with the leading `+`.**

The path variable is used as-is — it is *not* normalised. `+263782606983` works;
`0782606983` and `263782606983` do **not** and will silently look like a customer
with no activity. Normalise on your side before building the URL.

**2. URL-encode the `+` as `%2B`.**

```
/loyalty/public/customers/%2B263782606983/wallet
```

A literal `+` in a path segment usually survives, but any intermediate that
treats it as a space turns it into a *different* phone number rather than an
error. Encode it and the ambiguity is gone.

### Response envelope

Every response — success and failure — is the same three-key envelope:

```json
{ "code": "200 OK", "message": "Wallet retrieved", "data": { } }
```

**Branch on `code`, never on `message`.** Messages are human-facing and change.
On errors, `code` is a stable machine token (`INSUFFICIENT_FUNDS`,
`AMBIGUOUS_TENANT`, …) and `data` is `null`.

### Pagination — unwrap twice

List endpoints put a page object inside `data`:

```json
{
  "code": "200 OK",
  "message": "Transactions retrieved successfully",
  "data": {
    "content": [ ],
    "page": 0, "size": 20,
    "totalElements": 0, "totalPages": 0,
    "first": true, "last": true
  }
}
```

Your rows are at `data.content`. Query params are `?page=0&size=20&sort=createdAt,desc`.

**Page size is capped at 100** regardless of what you ask for; the default is 20.
Your page index and sort are preserved — only an oversized `size` is clamped.

### When the cell has them switched off

Every endpoint returns **`404`**, deliberately shaped to look like the route does
not exist rather than like a feature waiting to be unlocked. If you get a 404
from all seven at once, the cell has not opted in — that is expected on
production and is not a bug to report.

---

## 2. The seven endpoints

| # | Public (no auth) | Authenticated twin you will ship against |
|---|---|---|
| 1 | `GET /loyalty/public/customers/{phone}/wallet` | `GET /loyalty/users/me/wallet` |
| 2 | `GET /loyalty/public/customers/{phone}/transactions` | `GET /loyalty/users/{id}/transactions` |
| 3 | `GET /loyalty/public/customers/{phone}/vouchers` | `GET /loyalty/vouchers/users/by-phone/{phone}/active` |
| 4 | `POST /loyalty/public/customers/{phone}/points/send` | `POST /loyalty/transfer` |
| 5 | `POST /loyalty/public/customers/{phone}/points/redeem` | `POST /loyalty/redeem` |
| 6 | `POST /loyalty/public/vouchers/{voucherId}/transfer` | `POST /loyalty/vouchers/{id}/transfer` |
| 7 | `POST /loyalty/public/vouchers/redeem` | `POST /loyalty/vouchers/redeem` |

Each one calls **the same service method** as its twin. The business rules —
ownership, insufficient funds, one-hop voucher transfer, expiry, idempotency —
all run unchanged. Only the way the customer is identified differs. So a
behaviour you see here is the behaviour you will get after you switch to the
authenticated endpoints.

---

## 3. Points wallet

### `GET /loyalty/public/customers/{phoneNumber}/wallet`

```
GET /loyalty/public/customers/%2B263782606983/wallet
```

```json
{
  "code": "200 OK",
  "message": "Wallet retrieved",
  "data": {
    "phoneNumber": "+263782606983",
    "totalPoints": 405.0000,
    "totalVouchers": 1
  }
}
```

- Points are **global per customer** — one balance keyed by phone, summed across
  every tenant. There is no tenant to supply.
- `totalVouchers` counts vouchers in `ISSUED` / `DELIVERED` / `VIEWED` /
  `PARTIALLY_USED`.
- **An unknown phone returns `200` with zeros**, not a 404. To a caller holding
  only a phone number, "not a customer" and "no points yet" are deliberately the
  same answer.

---

## 4. Points statement

### `GET /loyalty/public/customers/{phoneNumber}/transactions`

Every earn, redemption, reversal and adjustment, newest first, across every
merchant and every tenant the phone has transacted with.

```json
{
  "id": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "type": "EARN",
  "amount": 50.0000,
  "pointsDelta": 5.0000,
  "balanceAfter": null,
  "ruleId": "3f2504e0-4f89-11d3-9a0c-0305e82c3301",
  "campaignId": null,
  "shopId": null,
  "postedBy": null,
  "channel": "TYPED_PHONE",
  "reference": "ORDER-4471",
  "createdAt": "2026-08-25T14:02:00Z",
  "invoiceId": null
}
```

- **`pointsDelta` is signed** — negative on redemptions. Render from this, not
  from `amount` (which is the underlying transaction value, not points).
- **`balanceAfter` is always `null` here.** The running balance is only recorded
  on write paths, and computing it per row would cost a wallet lookup each. Use
  the wallet endpoint (§3) for the current balance.
- **`invoiceId: null` means "not invoiced"** — a normal state, not missing data.
  Render it as such rather than as an error or a gap.
- **An unknown phone returns an empty page**, not a 404.

---

## 5. Active vouchers

### `GET /loyalty/public/customers/{phoneNumber}/vouchers`

Vouchers in an active state (`ISSUED` / `DELIVERED` / `VIEWED` /
`PARTIALLY_USED`) held by the phone, across every tenant.

```json
{
  "id": "9f8e7d6c-5b4a-3210-fedc-ba9876543210",
  "code": "VCH-AB12CD34",
  "status": "ISSUED",
  "templateId": "1b4e28ba-2fa1-11d2-883f-0016d3cca427",
  "assignedUserId": "c56a4180-65aa-42ec-a945-5fd21dec0538",
  "assigneePhone": "+263782606983",
  "usesRemaining": 1,
  "valueType": "PERCENT",
  "value": 10.0000,
  "currency": null,
  "issuedAt": "2026-08-01T09:00:00Z",
  "expiresAt": "2027-08-01T09:00:00Z"
}
```

**Render by `valueType`:**

| `valueType` | How to render | `value` |
|---|---|---|
| `PERCENT` | "10% off" | the percentage |
| `AMOUNT` | currency-format using `currency` | the amount |
| `FREE_ITEM` | the template's label | **may be null — ignore it** |
| `COMBO` | the template's label | **may be null — ignore it** |

`value` and `currency` are a **snapshot frozen at issuance**. Render what the
voucher carries; do not re-derive from the template, which may have changed since.

**Vouchers expire, points do not.** `expiresAt` is real and worth surfacing.

---

## 6. Send points (P2P)

### `POST /loyalty/public/customers/{phoneNumber}/points/send`

The phone in the URL is the **sender**. There is no `fromUserId` to supply.

```json
{
  "toPhone": "+263772222222",
  "points": 250.0000,
  "reason": "Birthday gift"
}
```

`reason` is optional (max 1000 chars). `points` must be positive.

```json
{
  "code": "200 OK",
  "message": "Points transferred successfully",
  "data": { "newBalance": 155.0000 }
}
```

`newBalance` is the **sender's** balance after the transfer.

Real rules that still apply:

- The sender must be **registered** — a `PENDING` balance cannot be spent.
- An unknown `toPhone` is **auto-enrolled as `PENDING`**. The points land and
  wait for that person to register. This is intended, not an error.
- **Self-transfer is refused** (`SELF_TRANSFER`).
- A short balance returns `INSUFFICIENT_FUNDS`.

---

## 7. Redeem points

### `POST /loyalty/public/customers/{phoneNumber}/points/redeem`

```json
{
  "points": 500.0000,
  "merchantId": null,
  "reason": "Counter redemption",
  "reference": "ORDER-4471"
}
```

```json
{
  "code": "200 OK",
  "message": "Points redeemed successfully",
  "data": {
    "status": "OK",
    "transactionId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
    "newBalance": 25.0000
  }
}
```

- **`merchantId` is optional** when the tenant has exactly one merchant (the
  usual case on a test cell). If you get `AMBIGUOUS_MERCHANT`, supply it.
- **`reference` is an idempotency key.** A repeat with the same
  (merchant, reference) **replays the original** instead of debiting twice.
  Generate it once on user intent — *not* per retry — or your retry becomes a
  second charge.

---

## 8. Transfer a voucher — **one hop only**

### `POST /loyalty/public/vouchers/{voucherId}/transfer`

The current holder is read from the voucher itself, so no sender phone is needed.

```json
{ "toPhone": "+263772222222", "note": "Passing this on" }
```

Returns the voucher (same shape as §5) showing the **new** assignee.

> [!IMPORTANT]
> **A voucher can be transferred exactly once.** A second attempt is refused with
> `VOUCHER_ALREADY_TRANSFERRED`. **Disable your *Send* control on a voucher that
> has already moved** rather than letting the user discover this from an error.

Only an unused, live voucher moves: `ISSUED` / `DELIVERED` / `VIEWED`.
`PARTIALLY_USED` and the terminal states are refused
(`VOUCHER_NOT_TRANSFERABLE`), as is an expired one (`VOUCHER_EXPIRED`).

---

## 9. Redeem a voucher

### `POST /loyalty/public/vouchers/redeem`

```json
{ "code": "VCH-AB12CD34", "merchantId": null }
```

```json
{
  "code": "200 OK",
  "message": "Voucher redeemed successfully",
  "data": {
    "redemptionId": "5c2f1e90-1111-2222-3333-444455556666",
    "voucherId": "9f8e7d6c-5b4a-3210-fedc-ba9876543210",
    "status": "REDEEMED",
    "usesRemaining": 0,
    "value": 10.0000,
    "valueType": "PERCENT",
    "redeemedAt": "2026-08-25T14:02:00Z"
  }
}
```

- `merchantId` may be omitted when the voucher already names one — usually it does.
- **A multi-use voucher comes back `PARTIALLY_USED` with `usesRemaining > 0`,
  not `REDEEMED`.** Drive your UI from `status` + `usesRemaining`, not from the
  fact that the call succeeded.
- Real guards apply: wrong merchant, already redeemed, expired, usage exceeded.

---

## 10. Error codes

`data` is `null` on every error. Branch on `code`.

| HTTP | `code` | Meaning |
|---|---|---|
| 400 | `PHONE_REQUIRED` | Blank phone in the path |
| 400 | `BAD_AMOUNT` | Non-positive or malformed `points` |
| 400 | `SELF_TRANSFER` | Sender and recipient are the same person |
| 400 | `INSUFFICIENT_FUNDS` | Balance is short |
| 400 | `AMBIGUOUS_TENANT` | Phone belongs to more than one tenant — see §11 |
| 400 | `AMBIGUOUS_MERCHANT` | Tenant has 0 or >1 merchants — supply `merchantId` |
| 400 | `VOUCHER_ALREADY_TRANSFERRED` | One hop only |
| 400 | `VOUCHER_NOT_TRANSFERABLE` | Voucher is partially used or in a terminal state |
| 400 | `VOUCHER_EXPIRED` | Past `expiresAt` |
| 400 | `RECIPIENT_REQUIRED` | Missing `toPhone` |
| 404 | — | Endpoints disabled on this cell, **or** no such customer/voucher |

Note the 404 overload: on the **write** endpoints, an unknown phone is a genuine
`404` (unlike the reads, which return empty). If you get a 404 on one endpoint
but the others work, it is a missing customer, not the feature switch.

---

## 11. `AMBIGUOUS_TENANT` — what it means

Points writes must name one tenant. When a phone has a loyalty account under
more than one tenant, the service **refuses rather than guesses** — picking one
arbitrarily would move points in a tenant nobody chose, and the failure would be
silent.

There is no request-body override for this. It is resolved by the backend team
pinning a tenant on the cell (`LOYALTY_PUBLIC_TEST_TENANT_ID`). If you hit it,
send us the phone number and we will pin it.

`AMBIGUOUS_MERCHANT` is the same idea but **you can fix it yourself** by passing
`merchantId` in the request body.

---

## 12. Integration checklist

- [ ] Base URL includes the `/foundry` prefix — a `301` to HTML means it is missing
- [ ] **No** `Authorization` header, **no** `X-Tenant-Id` — send neither
- [ ] Phone is exact E.164 **with `+`**, URL-encoded as `%2B`
- [ ] Branch on `code`, never on `message`
- [ ] Unwrap paginated payloads twice: `data.content`
- [ ] `pointsDelta` is signed; `balanceAfter` is always `null` on the statement
- [ ] `invoiceId: null` renders as "not invoiced", not as an error
- [ ] Render vouchers by `valueType`; `value` may be null for `FREE_ITEM` / `COMBO`
- [ ] Vouchers expire, points do not
- [ ] Disable *Send* on an already-transferred voucher — one hop only
- [ ] Voucher redemption may return `PARTIALLY_USED`, not `REDEEMED`
- [ ] Idempotency `reference` generated on user intent, not per retry
- [ ] An unknown recipient on *send points* is auto-enrolled — that is success
- [ ] **None of this is in the production build** — every screen has an
      authenticated twin to switch to before release
