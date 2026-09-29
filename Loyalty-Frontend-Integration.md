# Loyalty & Vouchers — Frontend Integration Guide

Everything a customer-facing frontend needs to integrate with loyalty-service:
points wallet, points statement, points transfer and redemption, vouchers by
phone, voucher transfer and voucher redemption — plus the rules the till app
(`SHOP_USER`) has to respect (§10).

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

**The tenant header is not optional.** Nearly every endpoint in this doc is
tenant-scoped, and a request without a valid `X-Tenant-Id` (or `X-Tenant-Code:
<slug>` as an alternative) is rejected before the controller runs. The only
exceptions are the caller-scoped ones — `GET /loyalty/users/me`,
`GET /loyalty/users/me/wallet`, `/loyalty/session/**` — and the staging-only
`/loyalty/public/**`, which need no tenant header. This is the
single most common reason a correct-looking call fails during first
integration.

The JWT comes from user-service login (staff) or ticketing's OTP verify
(customers, the `loyaltyToken`). loyalty-service verifies both.

### Keeping a customer signed in — `/loyalty/session`

The OTP-minted `loyaltyToken` lives 12 hours. Don't send a second SMS when it
runs out; trade it once for a renewable chain:

| Call | Credential | Body |
|---|---|---|
| `POST /loyalty/session/exchange` | `Authorization: Bearer <loyaltyToken>` | none — call ONCE, right after OTP verify |
| `POST /loyalty/session/refresh` | the refresh token, **in the body** (no bearer needed) | `{ "refreshToken": "LRT-…" }` |
| `POST /loyalty/session/logout` | same | `{ "refreshToken": "LRT-…" }` |

`exchange` (`"Session established"`) and `refresh` (`"Session refreshed"`) both
return:

```json
{
  "code": "200 OK",
  "message": "Session refreshed",
  "data": {
    "phoneNumber": "+263771234567",
    "loyaltyToken": "eyJhbGciOiJIUzI1NiJ9…",
    "expiresInSeconds": 43200,
    "refreshToken": "LRT-9tR2xQ1sK4mZ7pC0aB6vN3jH8dL5fG2yW1eU4oI0sA",
    "refreshExpiresInSeconds": 7776000
  }
}
```

- **Every refresh returns a NEW refresh token — store it and throw the old one
  away.** Presenting a used one is treated as theft: the whole chain is revoked,
  this device included, and the customer must verify by OTP again.
- Every refusal is one `401 SESSION_REFRESH_REJECTED`. The fix is always the
  same: send the customer back through OTP.
- `logout` is always `200`, even for an unknown token.
- The refresh window slides (90 days): an app in regular use never has to
  re-verify.

### Finding your loyalty ids — `GET /loyalty/users/me`

No path, no body; the phone comes from the token. One row per tenant the
customer has transacted with:

```json
{
  "code": "200 OK",
  "message": "Accounts retrieved",
  "data": {
    "phoneNumber": "+263771234567",
    "accounts": [
      {
        "userId": "11111111-2222-3333-4444-555555555555",
        "tenantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
        "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
        "status": "ACTIVE"
      }
    ]
  }
}
```

`userId` is the **loyalty user id** the spend endpoints want (§4, §5) and
`tenantId` is what goes in `X-Tenant-Id`. `PENDING` rows are returned on
purpose — that status is why a spend would be refused, so show it rather than
hide the account. An empty `accounts` list is a normal `200` (never
transacted); the wallet (§2) may still hold points. A staff token gets
`400 NO_PHONE_CLAIM`.

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
loyalty response — `GET /loyalty/users/me` (above) is where a customer app gets
them.

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

`totalVouchers` counts vouchers in `ISSUED` / `VIEWED` / `PARTIALLY_USED`.

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
  "invoiceId": null,
  "currency": "USD",
  "baseAmount": 100.00
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
- **`amount` is in `currency` — always render the two together.** Points are
  earned on `baseAmount`, the USD value frozen when the row was written. A
  `null` `baseAmount` means "not known in USD" (an old non-USD row), never zero.
- **`channel`** says how an earn arrived: `TYPED_PHONE` (staff keyed the phone,
  including the till's guest checkout), `QR_PRESENCE` (the customer scanned a
  merchant QR) or `CHECKOUT_S2S` (a server-side flow such as a shop payment).
  `null` on non-earn rows.
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
| `USER_PENDING` / `USER_INACTIVE` / `USER_BLOCKED` | 403 — the sender's account can't spend yet (or at all); show the `message` |

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

You can send `amount` (+ optional `currency`, default the merchant's) instead of
`points`: the server works out the points from the platform redemption rate.
Prefer it — it lets the platform, not the app, decide what a point is worth.
Sending both is allowed only if they agree at the current rate
(`RATE_MISMATCH` otherwise).

A caller can only redeem from their own account (admins may act on behalf).
The same `USER_PENDING` / `USER_INACTIVE` / `USER_BLOCKED` refusals as §4 apply.

**`reference` is an idempotency key.** A repeat redeem with the same
`(merchant, reference)` replays the original response instead of debiting the
wallet again. Use the order/booking id. Generate it when the user taps
*Redeem*, not when the request starts — a key regenerated per retry defeats the
whole mechanism.

---

## 6. Vouchers by phone

### `GET /loyalty/vouchers/users/by-phone/{phoneNumber}/active`

Paginated. Returns vouchers in an active state — `ISSUED`, `VIEWED`,
`PARTIALLY_USED` — for that phone **within the tenant on the header**.

```json
{
  "id": "9f8e7d6c-5b4a-3210-fedc-ba9876543210",
  "code": "7183502649174053",
  "status": "VIEWED",
  "voucherType": "SINGLE_USE",
  "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
  "shopId": null,
  "batchId": null,
  "campaignSource": null,
  "assignedUserId": "66666666-7777-8888-9999-000000000000",
  "assigneePhone": "+263771234567",
  "assigneeName": "Sedrick Nyanyiwa",
  "senderName": "Tawanda Mpofu",
  "senderPhone": "+263782608767",
  "issuerUserId": "77777777-7777-7777-7777-777777777777",
  "issuerPhone": "+263772000111",
  "issuerEmail": "shopadmin@westgate.co.zw",
  "usesRemaining": 1,
  "value": 10.0000,
  "currency": "USD",
  "baseValue": 10.0000,
  "issuedAt": "2026-08-20T09:00:00Z",
  "deliveredAt": "2026-08-20T09:00:05Z",
  "viewedAt": "2026-08-21T09:00:00Z",
  "redeemedAt": null,
  "transferredAt": null,
  "expiresAt": "2027-08-20T09:00:00Z",
  "transferredFromUserId": null,
  "transferredFromPhone": null
}
```

**A voucher is a money amount.** Render `value` currency-formatted in
`currency`. There are no voucher templates and no `valueType` any more (both
fields are gone from the response), and `voucherType` is always `SINGLE_USE`:
a voucher is worth its face value **once**.

- `baseValue` is the USD worth frozen when the voucher was issued. It is `null`
  on a few very old vouchers — never read that as zero.
- **Three different people can appear on one voucher — don't mix them up.**
  The **holder** (`assignee*`) owns it; the **sender** (`sender*`) gifted it and
  is who the "from" line should name; the **issuer** (`issuer*`) is the staff
  member who keyed it in. At a till all three are different people.
- `status` is one of `ISSUED`, `VIEWED`, `PARTIALLY_USED`, `REDEEMED`,
  `EXPIRED`, `REVOKED`. There is no `DELIVERED`: a voucher is `ISSUED` until it
  is used. `deliveredAt` only means a WhatsApp/SMS send was *attempted*.

**Who sees `code` on this endpoint:** the phone's owner and admin roles.
A till (`SHOP_USER`) looking up someone else's phone gets every `code` as
`null` — see §10.

**The voucher code is a 16-digit STRING — never parse it as a number.**
`7183502649174053` is above JavaScript's `Number.MAX_SAFE_INTEGER` (2^53), so
`Number(code)`, `parseInt(code)` or a numeric JSON decoder silently changes the
last digits and the code never redeems. Keep it a string end to end.

- **Show it in groups of four**: `7183 5026 4917 4053`. The API always sends
  it raw (no spaces); the grouping is display only. Older vouchers issued before
  the switch carry a 12-character alphanumeric code (`K7M2PQ9XR4TB`) — group
  those the same way (`K7M2 PQ9X R4TB`).
- **Send it back however the user typed it.** The backend ignores spaces,
  dashes (any kind, including the ones a phone keyboard substitutes) and
  invisible characters, and upper-cases letters, so `7183 5026 4917 4053`,
  `7183-5026-4917-4053` and the raw form all redeem the same voucher. Don't
  build your own stripping — just trim and send.
- **The last digit is a check digit.** A code with one digit mistyped, or two
  neighbouring digits swapped, can never belong to a real voucher, so an input
  field may validate locally before sending (optional — the backend is the
  authority): strip spaces/dashes, require `^[1-9][0-9]{15}$`, then check
  `(luhnCheckDigit(first15) + 5) % 10 === lastDigit`. A code failing that is a
  typo; say "check the code" rather than "no such voucher". Legacy
  alphanumeric codes skip this check.
- **Use a numeric keypad that still allows spaces** — `inputmode="numeric"` on
  a text input, NOT `type="number"`, which refuses the spaces and dashes people
  type and hands frameworks a rounded number. Don't block pasting.
- **Spreadsheet exports carry hyphens** (`7183-5026-4917-4053`) so Excel keeps
  the cell as text; a code copied out of one redeems as-is.

**Vouchers do still expire** (365 days by default). Points no longer expire at
all — don't reuse one expiry UI for both.

### `POST /loyalty/vouchers/codes/{code}/viewed`

Marks a voucher `VIEWED`. Call it when the customer actually opens the voucher
detail. Only the assignee (or staff) may call it. Put the raw `code` in the path
(as returned, no spaces). It shares the voucher guessing lockout (§8), so only
call it for a voucher from the customer's own list.

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
- **Only an unused, live voucher moves**: `ISSUED`, `VIEWED`.
  A `PARTIALLY_USED` voucher is refused along with the terminal states.
- The caller must be the **current holder**. An admin of the voucher's own
  merchant may transfer for a customer (support), but never to their own phone.
  A till (`SHOP_USER`) cannot transfer at all.
- Returns the updated voucher, showing the **new** assignee — with `code: null`.
  The transfer **rotates the code**: the sender's old code stops working the
  moment it succeeds, and the caller never sees the new one.

| Code | HTTP | Meaning |
|---|---|---|
| `VOUCHER_ALREADY_TRANSFERRED` | 400 | already had its one hop |
| `VOUCHER_NOT_TRANSFERABLE` | 400 | wrong status (message names it) |
| `VOUCHER_EXPIRED` | 400 | past `expiresAt` |
| `SELF_TRANSFER` | 400 | recipient is the caller |
| `RECIPIENT_REQUIRED` | 400 | neither or both recipient fields |
| `NOT_VOUCHER_OWNER` | 403 | caller isn't the holder |
| `NOT_MERCHANT_OWNER` | 403 | an admin of a different merchant |
| `STAFF_RECIPIENT` | 403 | an admin tried to send it to their own phone |
| `403 FORBIDDEN` | 403 | the token's role can't transfer (a till token) |

**Both sides are notified** (WhatsApp first, SMS fallback): the recipient gets a
"you received a voucher" message **and the new code**, the sender gets a
confirmation (without a code). The recipient also sees it in their own voucher
list. Tell the sender their copy of the code no longer works.

---

## 8. Redeem a voucher

### `POST /loyalty/vouchers/redeem`

```json
{
  "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
  "code": "7183 5026 4917 4053",
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
    "usesRemaining": 0, "value": 10.0000,
    "redeemedAt": "2026-08-25T14:02:00Z"
  }
}
```

`value` is the money the voucher is worth, in the voucher's `currency` (from
your voucher list). A voucher is used **once**: a successful redeem is
`REDEEMED` with `usesRemaining: 0`. Multi-use vouchers are retired.

**Send `deviceFingerprint` if you can.** Failed attempts are recorded as fraud
evidence keyed by device; repeated failures from one device can block a
customer's own account. At a till nobody is blocked (the device is the shop's)
— the guessing lockout below is what protects a till.

`outletCode` ≤ 80, `deviceFingerprint` ≤ 128 and `ipAddress` ≤ 64 characters;
longer is a `400` naming the field.

Who can redeem: the voucher's **holder** (customer app), or **staff of the
voucher's merchant** at a till, with the code the customer presents.

### Mistyped codes and the guessing lockout

| `code` | Status | Meaning | What to show |
|---|---|---|---|
| `VOUCHER_CODE_MISTYPED` | 400 | A 16-digit code whose check digit shows a wrong digit, two neighbours swapped, or a digit dropped or doubled. Worked out from the code alone; **never counts** toward the lockout. | "That code doesn't look right — check it and try again." |
| `404 NOT_FOUND` | 404 | No such voucher. **Counts.** | "We couldn't find that voucher." |
| `NOT_VOUCHER_OWNER` | 403 | A voucher that isn't this customer's (whatever its state). **Counts.** | the `message` |
| `VOUCHER_ATTEMPTS_LOCKED` | 429 | 5 counted misses within a minute: this caller is locked out of voucher redemption for **30 minutes**. Also returned, for at most a minute, when this caller already has as many redeems in flight as misses left. | "Too many incorrect codes. Try again in N minutes." |

```json
{
  "code": "VOUCHER_ATTEMPTS_LOCKED",
  "message": "Too many incorrect voucher codes were tried. Please wait and try again later.",
  "data": { "retryAfterSeconds": 1800 }
}
```

- **Read the wait from `data.retryAfterSeconds`** (also sent as the
  `Retry-After` header, which a browser may not expose to your code). Show a
  countdown; don't auto-retry — every call during the lock is refused, **even
  with the correct code**, and the lock is never shortened or extended by more
  attempts.
- **Who is locked:** a customer by their phone, a cashier by their own staff
  account. One cashier being locked does not affect the other tills at the
  shop — another staff login can carry on.
- **What never counts:** a mistyped 16-digit code (the 400 above), and — for
  the voucher's holder or staff — an expired, revoked or already-used voucher, a
  voucher for another shop, or a holder-account problem. Only unknown codes and
  other people's codes do.
- **Not every typo is caught.** The check digit catches one wrong digit, two
  swapped neighbours (except 0↔9), and a dropped or doubled digit; about one in
  ten bigger slips gets through and comes back as a counted 404. Older
  12-character codes have no check digit at all, so every typo on one is a
  counted 404. The local check-digit validation above catches the same cases
  before a call is made.
- **Don't fire redeems in parallel for one user.** Attempts in flight use the
  same budget, so a burst is refused with a short 429.
- **A successful redemption does not reset the count.**
- `POST /loyalty/vouchers/codes/{code}/viewed` shares the same lock: for a
  customer, its `NOT_VOUCHER_OWNER` 403 **and an unknown code's silent 200**
  both count, and it answers 429 while the caller is locked. Only call it for a
  voucher the customer actually holds (one from their own wallet list).
- The staging-only `/loyalty/public/vouchers/redeem` is **not** locked.

---

## 9. QR

### `POST /loyalty/qr/issue` → `POST /loyalty/qr/consume`

`issue` mints a signed, short-lived, single-use token (default TTL **300s**).
`consume` **always credits the caller**: the scanning customer's own
`userId` goes in the body.

| `sourceType` | Who issues | Who scans (consumes) | Effect |
|---|---|---|---|
| `MERCHANT` | merchant admin / shop admin, for their own merchant | the **customer** | the customer earns points on `amount` |
| `USER` | a customer, from their own account | the **recipient** customer | points move from the issuer to the scanner (P2P) |

`consume` takes the `token` + `signature` straight from the scanned payload plus
the scanning `userId`. Pass both through verbatim — never re-sign or reconstruct
them client-side.

Tokens are single-use and expire fast. Regenerate on display, don't cache.

A merchant QR can't be scanned by that merchant's own staff:

| `code` | HTTP | Meaning |
|---|---|---|
| `SELF_EARN` | 403 | the scanner's token is staff of the QR's merchant |
| `STAFF_RECIPIENT` | 403 | the scanning phone belongs to staff of that merchant (any login) |
| `QR_REUSED` | 409 | already consumed |
| `QR_EXPIRED` | 400 | past its TTL |
| `BAD_SIGNATURE` | 403 | token/signature don't match |

A refused staff scan does **not** use the QR up — the customer can still scan
it. A till token (`SHOP_USER`) cannot call `consume` at all.

---

## 10. The till app (`SHOP_USER`)

A cashier serves customers; they can never take from one. Build the till
around these rules:

- **Vouchers by phone come back without codes.** For a phone that isn't the
  cashier's own, every `code` is `null`. Show that the customer has a voucher
  (value, expiry, sender) and **ask the customer for the code** from their
  WhatsApp/SMS/app, then redeem it (§8). Don't build "tap a voucher to redeem".
- **No voucher transfer, no QR consume.** Both answer `403 FORBIDDEN`
  (`"You don't have permission to do that."`). Hide the actions.
- **Guest checkout** (`POST /loyalty/shops/{shopId}/guest-checkout`,
  body `{ "phoneNumber": "+263771234567", "cashAmount": 10.00 }`) refuses:

  | `code` | Meaning |
  |---|---|
  | `SELF_EARN` | the phone is the cashier's own |
  | `STAFF_RECIPIENT` | the phone belongs to any staff member of this merchant |
  | `NOT_SHOP_MEMBER` | `{shopId}` isn't the shop on the cashier's token |
  | `SHOP_NOT_OWNED` | the shop belongs to another merchant |

  Always send the cashier's own `shopId`; no shop picker. Show the `message`
  and let them fix the number — retrying the same phone never works.
- **Issuing a gift at the till** is an admin action (`SHOP_ADMIN` and up). The
  customer sending it and the friend receiving it **both** get the code.
- **Guessing lockout is per cashier account** (§8): one locked cashier doesn't
  block the other tills.

---

## 11. TEST-ONLY endpoints

### `GET /loyalty/public/customers/{phoneNumber}/transactions`

**No JWT. No tenant header. No role.** Exists so you can build screens against
real data before your auth flow is wired up.

Where a cell configures a key, every `/loyalty/public/**` call must send
`x-api-key: <key>` (the app reads it from Firebase Remote Config); a missing or
wrong key is an opaque `401`. On a cell with no key configured the header is
ignored. The key identifies the app, not the customer — it does not make
these endpoints safe.

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

## 12. Not implemented — don't build against it

**`POST /loyalty/convert-to-airtime`** returns `200` with a feature-flag payload
saying *"M-Pesa / airtime conversion is not enabled in this build."* It is a
stub. There is no airtime conversion.

---

## 13. Integration checklist

- [ ] `X-Tenant-Id` on every call — this is the #1 first-day failure
- [ ] `Authorization: Bearer <jwt>` from user-service login
- [ ] Base URL includes the `/foundry` prefix
- [ ] Branch on `code`, never on `message`
- [ ] `loyaltyUserId` ≠ JWT `userId` — never send the JWT one
- [ ] Unwrap paginated payloads twice: `data.content`
- [ ] `pointsDelta` is signed; `balanceAfter` is always null on the statement
- [ ] `invoiceId: null` renders as "not invoiced", not as an error
- [ ] Render a voucher's `value` as money in its `currency` — there is no `valueType`
- [ ] Keep voucher `code` a string; show it in groups of four; send it back as typed
- [ ] Holder, sender and issuer are three different people on a voucher
- [ ] Store the NEW refresh token on every `/loyalty/session/refresh`
- [ ] Get loyalty user ids and tenant ids from `GET /loyalty/users/me`
- [ ] Vouchers expire, points do not
- [ ] Disable *Send* on an already-transferred voucher — one hop only
- [ ] Idempotency `reference` generated on user intent, not per retry
- [ ] Send `deviceFingerprint` on voucher redemption
- [ ] Till app: no codes from the by-phone list, no transfer, no QR consume,
      guest checkout on the cashier's own shop only (§10)
- [ ] Nothing in §11 or §12 is in the production build
