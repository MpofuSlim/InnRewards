# InnRewards (loyalty-service) — Claude memory

Project-wide instructions for Claude when working in this repo. This service
was extracted from `MpofuSlim/ticketing-system`; the conventions below are the
loyalty-relevant subset of that monorepo's `CLAUDE.md`.

> [!IMPORTANT]
> **Branch naming: `feature/<short-kebab-description>`, cut from `master`.**
> Sessions often start on an auto-assigned `claude/<random-words>` branch — that
> is a harness artifact, not our convention. Create the `feature/*` branch from
> the latest `master` before committing, push with `git push -u origin <branch>`,
> and open a **draft** PR. One feature per branch.

## Frontend integration docs on merge (standing)

> [!IMPORTANT]
> **Every time a PR that adds or changes a frontend-facing HTTP surface merges to
> `master`, automatically produce a frontend integration Markdown guide for that
> change and deliver it to the user in-session (`SendUserFile`) — without being
> asked, in the same session that observes the merge.** This holds in every
> session, not just the one that wrote the code. **Skip only when an equivalent
> guide for the same change already exists** (the user made it, or one was
> produced earlier for that surface).

- **"Frontend-facing"** = a new endpoint, or a changed request/response shape,
  auth, headers, status/error codes, or client-visible behaviour. Pure
  backend/infra/schema/CI/test/doc changes with **no** client surface need no
  guide — say so briefly instead of inventing one.
- **Shape** — mirror the guides already shared with the FE (the
  `*-Frontend-Integration.md` deliverables: Redemption, My-Tickets,
  ShopUser-Bulk-Upload): base URL + auth + required headers (note when a call
  needs `X-Tenant-Id` and when it doesn't), each endpoint with request/response
  JSON, error handling split into top-level vs per-row/field, realistic request
  examples, and a gotchas checklist. Anchor every field to the merged code, not
  memory.
- Loyalty note: the customer-app surface is the **`/loyalty/public/**` staging
  endpoints** (unauthenticated today, planned `x-api-key`), whose authenticated
  twins are the production target — keep new guides consistent with that
  authenticated-public-for-staging posture and map public → authenticated where
  it applies.

## Extraction context — what stayed behind in ticketing-system

- **The API gateway route stays in `ticketing-system`.** The gateway routes
  `/loyalty/**` → `lb://loyalty-service` **by Eureka service name**, so it keeps
  working across repos as long as this service still registers as
  `loyalty-service` on the **same** discovery-server. When you add a new HTTP
  endpoint here, the gateway route in `ticketing-system`
  (`api-gateway/src/main/resources/application.yaml`) must be updated in
  lock-step — mirror the predicate prefix + rate limiter as the other routes.
- **The k8s Deployment/Service manifests stay in `ticketing-system`**
  (`deploy/k8s/04-services.yaml`) — loyalty runs in the shared `ticketing` cell
  and reuses its `cell-zw` ConfigMap/Secret. This repo only builds/publishes the
  image `ghcr.io/mpofuslim/loyalty-service`.
- **Runtime couplings are config, not code**: shared HS256 `JWT_SECRET`, shared
  `INTERNAL_API_TOKEN`, shared Redis logout denylist, and the `UserServiceClient`
  call to `GET /users/internal/{uuid}/contact` on ticketing's user-service. A
  future non-ticketing consumer will need these made pluggable (RS256/JWKS auth,
  a configurable contact provider, configurable sibling endpoints) — do that
  incrementally when the first real second-consumer lands, not upfront.

## Internal endpoints — controller + SecurityConfig must agree (gateway lives in ticketing)

An internal-only endpoint (`/loyalty/internal/**`) is only correct when:

1. **The controller** declares the mapping AND enforces the shared secret
   (`X-Internal-Token`) with a constant-time compare.
2. **`SecurityConfig`** has a `.requestMatchers(HttpMethod.X, "/loyalty/internal/...")
   .permitAll()` for the exact same path — otherwise Spring Security's
   `.anyRequest().authenticated()` 401s the call before the controller's token
   check runs.
3. **The gateway `*-internal-deny` route** (in `ticketing-system`) forwards the
   path to `forward:/__edge_deny__` so it's unreachable from the public internet.

Test assertions for these endpoints use `.isBadRequest()` / `.isUnauthorized()`
(specific code) — never `.is4xxClientError()`, which silently passes for a
Spring-Security 401 even when the controller never ran.

## External-service contract tests (WireMock)

Every client that calls an external HTTP service (`UserServiceClient`, the
SMS/WhatsApp/Email notification clients) MUST have a WireMock-driven contract
test pinning one assertion per response shape observed in production — pure
JUnit + WireMock, **no `@SpringBootTest`**. Cover the happy 2xx, each distinct
non-2xx envelope, a connect-refused/fault case, the outbound wire contract
(`matchingJsonPath`), and the guard rails (blank inputs → `verify(0, ...)`).
Use the `wiremock-standalone` (shaded) classifier.

## Swagger response examples

Every endpoint MUST have meaningful `@ApiResponses` with `@ExampleObject`
bodies using the project's `ApiResult` envelope (`{ "code", "message", "data" }`)
— never the springdoc placeholder. Document success + realistic failure shapes
(400/401/403/404) with real messages thrown by the service code. `MerchantController`
and `ShopController` are the canonical shape.

## A plain CUSTOMER is exempt from tenant membership — so ownership checks are now load-bearing

> [!IMPORTANT]
> **Every new endpoint a `CUSTOMER` can reach MUST carry its own ownership
> check.** `TenantContext.verifyMembership` no longer catches the omission for
> them. Bind the acted-on account to the caller — `requireCallerOwns` (strict, no
> admin bypass) on anything that mints or moves value, `requireCallerOwnsOrIsAdmin`
> where support staff legitimately act on behalf.

**Why the exemption exists.** `tenant_members` has exactly two writers:
`TenantService.addMember`, called from ONE place (`TenantService:70`, inside
tenant creation, attaching the creator), and `TenantMemberBackfill`, which is
email-keyed off `tenant.ownerEmail`. `TenantController` has **no**
`POST /{id}/members`, and the string `CUSTOMER` appears nowhere in it. So a
customer could not self-join and no operator could add them: for a customer the
check was not a gate but a wall, and `POST /loyalty/transfer` and
`POST /loyalty/redeem` would have 403'd every one of them. That is why the
customer app has only ever reached loyalty through `/loyalty/public/**`.

**Why skipping it was safe.** All nine tenant-scoped endpoints a CUSTOMER can
reach already bind the acted-on account to the caller, and the mint/drain paths
use the STRICT check: transfer (`requireCallerOwns`), redeem
(`requireCallerOwnsOrIsAdmin`), `/users/{id}/transactions`, voucher redeem
(assignee phone), voucher transfer (`requireCallerMayViewVoucher`),
vouchers-by-phone (`requireCallerOwnsPhoneOrIsAdmin`), QR issue
(`requireCallerAdministersMerchant` / `requireCallerOwns`), QR consume
(`requireCallerOwns`). The ninth, `GET /loyalty/mini-apps/manifest`, returns the
tenant's mini-app catalogue — storefront content whose role list already names
CUSTOMER.

**"Plain" is role-set EQUALITY** (`{ROLE_CUSTOMER}`), not a deny-list of today's
staff roles — a role invented later fails closed instead of inheriting the
exemption. A mixed CUSTOMER+staff token still needs membership, so this can never
widen a staff caller's reach. `SERVICE_*`, `TIER_*` and `VERIFIED` are filtered
out: they describe the token, not a role.

**What it does NOT change:** tenant RESOLUTION. A customer still needs a valid
`X-Tenant-Id`/`X-Tenant-Code` (400 without, 404 for an unknown one) — only the
membership check is skipped. And `/loyalty/public/**` is untouched: it never
consulted `TenantContext` at all.

## The fraud auto-block may only ever act on the CALLER

`FraudService.record` writes an evidence row and, past the velocity threshold,
can set `LoyaltyUser.status = BLOCKED`. Two rules, both learned the hard way:

- **The block subject is resolved from the security context, never from a
  parameter.** It used to block the `userId` argument, which on the voucher
  path is `req.userId()` — a raw body field passed in on the FIRST branch of
  `VoucherService.doRedeem`, before the voucher is known to exist and before any
  ownership check. Five malformed redeems naming a victim's UUID blocked that
  victim, in any tenant. Only a plain `ROLE_CUSTOMER` caller whose `userId` claim
  resolves to a row matching their `phoneNumber` claim can be blocked, and only
  ever themselves.
- **`fraud_attempts.user_id` is a CLAIM, not an attribution.** Several callers
  store an unvalidated body field there, so the row may name someone with no
  connection to the attempt. Never block an account on the strength of one.

Consequences worth knowing: a **staff-operated till and every S2S path now block
nobody** — the velocity signal is keyed by device, and at a till the device is
the shop's while the person presenting bad codes is a customer, so the old
behaviour let any customer disable a cashier. Attempts are still recorded and
still counted (`fraud_attempts`, `loyalty.fraud.rejected`); only the automatic
punishment is withheld where it cannot be aimed.

`POST /loyalty/users/{userId}/unblock` (SUPER_ADMIN / MERCHANT_ADMIN,
tenant-scoped) is the **only** way out of BLOCKED — nothing else in the service
clears it. It refuses a non-BLOCKED account rather than becoming a general
make-it-active lever that bypasses PENDING/INACTIVE.

## Timestamps — UTC

Loyalty maps timestamps as `Instant`, which is always UTC. Containers also pass
`-Duser.timezone=UTC`. If you ever add a `LocalDateTime`, use
`LocalDateTime.now(ZoneOffset.UTC)`, never bare `.now()`.

## Schema changes (Flyway)

New schema goes in `src/main/resources/db/migration/V<N>__*.sql` (PostgreSQL +
Flyway, `ddl-auto: validate`). Current head is **V42**; never edit an applied
migration — add the next version.

## Registration is a property of the PHONE (V40)

**`phone_registrations` is the source of truth for "the owner of this number has
proven they hold it". `loyalty_users.status` is a per-projection CACHE of it.**

- **Why it moved.** A `LoyaltyUser` is a per-tenant projection, so storing "is
  registered" on it re-asks the question every time the customer touches a new
  merchant, and answers it wrongly: a customer promoted last year got a fresh
  PENDING row at a new merchant and was refused at that till. Registration is a
  fact about the human holding the SIM.
- **Never gate a spend on `status == PENDING`.** Ask
  `UserService.isRegistrationPending(u)`, which consults the phone-level fact.
  Both spend gates do (`requireSpendable`, and `VoucherService.redeem`'s own
  branch); a third gate added elsewhere must too, or registered customers get
  refused there and nowhere else.
- **`registerPhone` is the ONLY writer.** Both proofs route through it —
  ticketing's OTP webhook (`promoteByPhone` is now a one-line delegate,
  `source = TICKETING_OTP`, wire contract unchanged) and
  `POST /loyalty/partner/registrations`. It promotes PENDING and revives
  INACTIVE/`PENDING_EXPIRED`, and **never touches BLOCKED or
  INACTIVE/`OPERATOR`** — a fraud hold and a deliberate deactivation are not
  things a customer logging in may undo. That is what `status_reason` exists
  for; `deactivate()` stamps `OPERATOR`.
- **The sweeper has two arms** and only ages out phones with **no** registration
  (`findStaleUnregistered`, `NOT EXISTS`). Don't revert it to
  `findByStatusAndCreatedAtBefore`: that selects on status and age alone and
  would sweep a proven customer into a state the old promote refused to recover.
  The heal arm converges rows the spend gate hasn't touched.
  `loyalty.pending.ttl-days` is finally env-bound (`LOYALTY_PENDING_TTL_DAYS`).
- **V40 does NOT backfill registrations from `status`.** V1 created
  `loyalty_users` with `status DEFAULT 'ACTIVE'` and PENDING only appears in V6,
  so pre-V6 ACTIVE rows are a database default, not a proof, and nothing
  distinguishes them. The operator query is documented in the migration for
  whoever decides that population is worth registering anyway. Existing ACTIVE
  rows keep spending — the ACTIVE branch is untouched.
- **The partner endpoint is off by default** (`404`), and enabled-but-unprovisioned
  is `503` plus a boot HALF-PROVISIONED error. Four auth modes:
  `assertion` (default — RS/ES-signed, phone in the signed `sub`, bounded TTL,
  monotonic replay guard; loyalty holds only the public key), `key`
  (`X-Partner-Key`, constant-time compare) for a partner that cannot sign,
  `veengu` (V41 — validates the customer's Veengu access token against Veengu's
  `GET /auth/identity`), and `innbucks` (V42). **Shared-key mode means whoever
  holds the key can register ANY phone** — it logs a boot WARN, is guarded by
  `ProductionSecretsGuard`, and must never reach a mobile client.
- **`veengu` shipped first and is SUPERSEDED — do not build on it.** The
  partner's own Postman collections showed the app authenticates against the
  InnBucks **Client Service** API, not Veengu directly, so a Veengu access token
  is not what the app holds. The mode is left in place because V41 is applied
  history and an idle mode costs nothing; `innbucks` is what the mobile app uses.

### `innbucks` mode — the ONLY mode a mobile client may call (V42)

The app authenticates its customers against the InnBucks Client Service API
(`POST /auth/client-service/user/login`, username + PIN block → a user token),
not against our fleet. That token is a possession proof we cannot read — the
API exposes **no identity endpoint** (confirmed by reading all 89 request
definitions in the partner's Postman collections). So the question is asked
backwards: the caller sends `X-Innbucks-User-Token` **and the phone it claims**,
and `InnbucksSessionClient` asks the middleware to read *that* msisdn under
*that* token. The middleware binds a user token to its own msisdn, so an answer
IS the proof.

- **`/auth/client-service/msisdn/{msisdn}/validate` is NOT the proof and must
  never become the probe path.** It is authorized by the APP's own credentials
  and answers success for **every real InnBucks customer**, so it proves the
  number EXISTS, never that the caller holds it — registering on it would let
  anyone name any customer's number and then spend their points, the exact thing
  PENDING exists to prevent. It stays useful to the FE as an onboarding
  pre-check (name, `pinSet`); it is simply never the proof. `probe-path` is
  configurable, so `PartnerRegistrationProvisioningCheck` logs a boot ERROR if
  it is ever pointed at a `/validate` endpoint.
- **A 2xx is not automatically a yes.** The platform reports business failures
  with HTTP 200 and a non-success `responseCode` (`"00"`/`"000"`/`0` succeed).
  Reading a bare 2xx as proof would accept the very cross-customer refusal this
  mode detects. Pinned by
  `InnbucksSessionClientContractTest.verify_2xxWithFailureCode_isRejected`;
  removing the code check fails exactly that test and nothing else.
- **Rejected vs Unavailable is load-bearing.** 401/403/404 or a 2xx with a
  failure code = the middleware answered and said no → opaque `401`. Connect
  failure, 5xx, an unexpected 4xx, or a 2xx that is not a JSON object (the
  EcoCash WAF-block-page lesson) = no answer → retryable
  `503 REGISTRATION_UPSTREAM_UNAVAILABLE`. Neither ever registers.
- **Normalise before probing.** The controller canonicalises through
  `UserService.normalizePhone` and probes *that* value, so the spelling proved
  is the spelling stored; the client strips the `+` for the platform's bare
  msisdn format.
- **Known limitation:** the collections contain an AGENT-role lookup
  (`Get User Cards (Agent Lookup)`) that reads *other* customers. If an
  agent-role token were ever used here, it could register numbers it does not
  own. This mode is for ordinary customer tokens only.
- **The safety of this mode rests on the middleware's binding.** If InnBucks
  ever relaxes it, our proof silently weakens with no error on our side — which
  is why `INNBUCKS_SESSION` is its own source value, revocable as a batch.
- **Never add an activation path under `/loyalty/public/**`.** Those endpoints
  are unauthenticated; activation there would let anyone who guesses a phone
  number activate and then drain it, which is precisely what PENDING exists to
  prevent.
- **The gateway route lives in `ticketing-system`** and IS added (ticketing
  PR #543): `loyalty-partner-registration-route`, POST-only, IP-keyed fail-safe
  limiter, ordered before `loyalty-service-route` and pinned in
  `GatewayRouteTableTest`. In `innbucks` mode the callers are mobile clients on
  customer IPs, which is exactly what an IP-keyed limiter is shaped for.

## Multi-currency — USD base, allowlist, bank-rate default + tenant override (V36)

The cell supports USD (BASE) + ZAR + ZWG. Points stay ONE currency-neutral
pool anchored to USD; any non-USD money amount crosses `ExchangeRateService`
exactly once (earn: local → USD → points; redeem: points → USD → local).

- **`SupportedCurrencies` is the allowlist** — configured set
  (`LOYALTY_SUPPORTED_CURRENCIES`, default `USD`) ∪ BASE ∪ the cell currency
  (`INNBUCKS_CURRENCY`). Every write entry point that accepts or defaults a
  currency (merchant create, QR issue, voucher template, earn, redeem)
  resolves through it and FAILS CLOSED (`UNSUPPORTED_CURRENCY`) on anything
  outside — the currency analogue of `KNOWN_COUNTRIES`.
- **`exchange_rates` (V36) is append-only + effective-dated** (the
  `redemption_rates` model) with TWO scopes and this resolution precedence
  (mirrors the loyalty_rules global/merchant inheritance): **tenant override
  (`tenant_id` set) → platform ADMIN (`tenant_id` NULL) → platform FEED**.
  The "bank rate" (platform scope; feed job is a later phase) applies ONLY
  when nobody set one — a tenant-set rate overrides it for that tenant, and a
  platform-admin rate overrides the feed for everyone, regardless of recency.
  Pinned by `ExchangeRateResolutionPrecedenceTest`. USD is never stored
  (`FX_BASE_IMMUTABLE`); no seed — a supported currency with no in-force rate
  refuses with `NO_FX_RATE`, never silently prices at 1.0.
  `FxProvisioningCheck` logs a boot-time HALF-PROVISIONED error for a
  supported-but-rateless currency.
- **Going back to the bank rate is a TOMBSTONE, not a delete (V39).**
  `DELETE /loyalty/exchange-rates/override` appends a tenant-scoped row with
  `cleared = true` and `rate_per_usd = NULL`; resolution sees it as the latest
  in-force tenant row and falls through to the platform scope. Append-only,
  attributable and effective-dated, and the history still reads as the true
  sequence of decisions. **Do NOT "clear" by writing an override equal to
  today's bank rate** — that looks equivalent but re-freezes the tenant at a
  stale number the moment the bank rate next moves. Only the LATEST in-force
  tenant row is inspected, so an override set after a clear is live again.
  Clearing when nothing is in force is refused (`FX_NO_OVERRIDE`) so stray
  tombstones don't accumulate; platform scope can't be cleared at all
  (`FX_CANNOT_CLEAR_PLATFORM` — "no bank rate" is just `NO_FX_RATE`).
- **`setRate` sanity band** (`LOYALTY_FX_MAX_CHANGE_PERCENT`, default 25):
  a change beyond the band vs the in-force rate for the same scope needs
  `force=true` WITH a note (`FX_RATE_OUT_OF_BAND` / `FX_FORCE_NEEDS_NOTE`).
  Endpoints: `POST /loyalty/exchange-rates` (SUPER_ADMIN, platform),
  `POST /loyalty/exchange-rates/override` (tenant admins, X-Tenant header),
  `GET /loyalty/exchange-rates?currency=` (effective, staff-readable),
  `GET /loyalty/exchange-rates/history` (SUPER_ADMIN, all scopes).
- **Earn is USD-anchored and frozen (V37).** `TransactionService.post` converts
  the transacted amount to USD via `fx.toBaseWithRate(...)` and evaluates
  `RulesEngine` on the **base** amount — the engine is currency-blind by design
  and must only ever see BASE. The ledger keeps all three: `amount` +
  `currency` (what the customer transacted), `base_amount` (the USD value
  points were awarded on) and `fx_rate_id` (the `exchange_rates` row that
  justifies it). **Read `base_amount` back; never recompute it** — re-deriving
  at a newer rate restates history, which for ZWG means the same row is worth a
  different number of dollars every day. `fx_rate_id` is NULL for a USD
  transaction (identity, no rate row exists) and for pre-V37 rows; `base_amount`
  is NULL only for a pre-V37 non-USD row (V37 backfilled USD history) and must
  never be read as zero.
- **`loyalty_rules.points_per_unit` means points-per-USD** (V37), and
  `min_transaction_amount` is a **USD** floor. No data migration: every existing
  rule was authored against a USD-only cell, so the numbers already mean that.
- **Redeem is USD-anchored too.** `RedemptionService` takes an optional request
  `currency` (defaulting to the merchant's), converts a requested local
  `amount` to USD, applies the redemption rate **in USD**, then converts the
  resulting liability back for the receipt. The row freezes all of it:
  `amount` + `currency` (local value off the bill), `base_amount` (the USD
  liability the platform owes) and `fx_rate_id`.
- **The redemption rate is read at BASE regardless of transaction currency.**
  One USD-denominated rate is what keeps a point worth the same real value
  everywhere; deriving local figures through FX means there is no second,
  independently-drifting per-currency rate to arbitrage. A non-USD row in
  `redemption_rates` is therefore never consulted — don't add one expecting it
  to take effect.
- **Voucher liability freezes at ISSUE (V38).** `vouchers.base_value` +
  `fx_rate_id` pin the USD worth of an issued voucher at the rate in force
  *when it was issued*, because that is when the platform makes the promise.
  Revaluing the outstanding book at today's rate would swing the liability
  daily on FX alone, with nothing issued and nothing redeemed.
  **Only `valueType = AMOUNT` is converted** — a PERCENT voucher's value is a
  *percentage* and FREE_ITEM/COMBO have no money face value, so running them
  through a rate would mint a confident, meaningless figure. Those stay NULL
  forever, which is why a liability report must filter on value type rather
  than treating NULL as zero.
- **QR needs no FX code of its own.** A QR carries an amount + currency and
  `consume` hands both to `TransactionService.post`, so it converts at
  scan time through the earn path above — correct, since the earn happens
  when scanned, and QR TTLs are short.
- **Money aggregations sum `baseValue` / `base_amount`, never the local
  amount.** Summing a local money column across a scope that mixes currencies
  adds ZWG to USD and returns a plausible number that is money in no currency —
  a regression that keeps working silently, which is why
  `VoucherMoneySumUnitTest` pins the column choice. Both voucher money sums
  (`reportSummaryByStatus`, `sumRedeemedValueByMerchantId`) are USD. **Every
  points aggregation is currency-neutral and correct as-is** — don't "fix"
  those. Report DTOs label their money figures as USD.
  Side effect worth knowing: PERCENT/FREE_ITEM/COMBO vouchers have a NULL
  `baseValue`, so SQL `SUM` drops them from money totals while `COUNT` still
  includes them. That is a correction — a "10% off" voucher used to contribute
  a literal `10` to a money total.
- The temporary `requireBaseFor` rollout guard is **gone** (both paths now
  convert); don't reintroduce it.

## Transactions carry the invoice that billed them (V33, IN-9)

`loyalty_transactions.invoice_id` back-references the invoice whose billing
period covered the row, so a points report can name the bill a row was counted
on instead of reconstructing it from date ranges.

- **Direction matters.** An invoice does NOT generate points — points generate
  the invoice. `InvoicingService` sums `loyalty_transactions` over the billing
  window to produce `invoices.points_issued` / `points_redeemed`. This column is
  traceability, not a funding link. (The ticket's phrasing, "the invoice used to
  generate the points", reads the causality backwards.)
- **The stamping predicate MUST stay character-identical to `sumPointsIssued` /
  `sumPointsRedeemed`** (merchant + `createdAt` in `[from, to)` + status POSTED).
  The rows stamped have to be exactly the rows summed, or a report would cite an
  invoice whose printed `pointsIssued` doesn't account for the row — worse than
  no link, because it looks authoritative. `stampInvoice` and both sums live
  next to each other in `LoyaltyTransactionRepository` for that reason.
- **Claim-once**: `stampInvoice` only touches rows where `invoice_id IS NULL`, so
  a later invoice never re-attributes rows an earlier one billed. This matters
  when periods overlap (a merchant switching DAILY → MONTHLY can have a day
  covered twice). First invoice to bill a row owns it.
- **NULL is a real answer, not missing data.** Invoice totals come from *voucher*
  fees, not points, and `InvoicingService` skips zero-total invoices entirely —
  so a period with points but no billable voucher activity produces no invoice at
  all, and its rows keep `invoice_id = NULL`. Reports must render that as "not
  invoiced" rather than implying a gap.
- Surfaced as `invoiceId` on `Dtos.TransactionResponse` and as an
  `invoiceNumber` column on `GET /loyalty/reports/transactions/export` (the CSV
  resolves ids → numbers in one batched lookup per page, cached across pages).

## Points do NOT expire (V31)

Points carried a 30-day per-lot expiry and released the unspent remainder to the
ledger as breakage. **They no longer expire at all.**

- "Never expires" is `point_lot.expires_at IS NULL`, not a far-future sentinel.
  NULL is the honest representation, and it makes every expiry query skip the row
  **for free** via SQL three-valued logic (`NULL <= now()` is UNKNOWN, never
  true). `findDueForExpiry`, `findWalletsWithDueLots`, `findWalletsWithLotsToWarn`
  and `findWarnableLots` therefore needed no change — **do not "fix" them by
  coalescing the NULL to a date**, that would resurrect expiry.
- **`findLiveForConsumption` DID need an explicit `IS NULL` branch.** `NULL >
  :now` is UNKNOWN, so without it every non-expiring lot silently drops out of the
  burn list and the customer's whole balance becomes unspendable (`INSUFFICIENT_FUNDS`
  on a positive balance). Its `ORDER BY` also leads with a `CASE` putting expiring
  lots first and never-expiring last — written out rather than relying on Postgres
  sorting NULLs last in ASC, which is a dialect detail. Consequence: burn order is
  *not* plain earned-order FIFO — a newer lot with a deadline is spent before an
  older one without, so the customer keeps the points that never lapse.
- **The mechanism is off, not deleted.** `loyalty.points.expiry-days`
  (`LOYALTY_POINTS_EXPIRY_DAYS`) defaults to **0**, and `WalletService` treats any
  non-positive value as "never expires". A positive value re-enables per-lot expiry
  for newly earned points; existing NULL lots stay non-expiring.
- **V31 also cleared the expiry on every lot with `remaining_amount > 0`** — not
  just future-dated ones, since a lot whose timestamp has passed but which the
  hourly sweep hasn't released is still counted in the wallet balance. Lots at
  `remaining_amount = 0` are left alone: they're history (spent, or already
  released as breakage with a matching ledger entry), and reversing past breakage
  means crediting balances back, which needs its own ledger entries rather than a
  silent UPDATE.
- **Vouchers are unaffected** — `loyalty.voucher.default-validity-days` is still
  365 and the voucher expiry sweep/warning still runs. Only *points* stopped
  expiring.

## Rules are the tenant STANDARD — earning floor + voucher fees (V29)

`loyalty_rules` carries the commercial config, with the same two-tier
inheritance the earn rate has always used (global rule = tenant template,
merchant rule = override, `LoyaltyRuleRepository.findApplicable` returns
merchant-specific first):

- **`min_transaction_amount`** — the earning floor. A transaction strictly
  below it completes normally but earns **ZERO** points. `RulesEngine` reads
  the chosen rule's floor and falls back to the first time-valid GLOBAL rule's
  floor when the merchant rule leaves it null, so a merchant inherits the
  standard without restating it. The floored evaluation still carries the
  `ruleId` + pocket so the ledger records *why* nothing was earned.
- **`fee_issued_*` / `fee_redeemed_*`** — the per-voucher fees the merchant is
  billed, same shapes as the merchant-record columns (percentage is
  whole-number, 2.5 = 2.5%). Resolution lives in **one** place,
  `EffectiveFees.resolve`, and each side resolves independently:
  **merchant rule → merchant record (only when explicitly configured, i.e.
  anything other than the onboarding default FIXED 0/0) → global rule → no
  fee**. A **zero ISSUE fee is refused on any rule** (`RULE_ZERO_ISSUE_FEE`) —
  on a global rule it would make every merchant free at once, and on a merchant
  rule it would silently undo the guard that refused that merchant at creation.
  The only sanctioned way to be unbilled is `merchants.fee_waived` (V30), which
  records who decided it and why. The REDEEM side may be zero freely.

Every fee call-site goes through `EffectiveFees` (invoicing and both reporting
estimates) so the previewed figure and the eventual bill can't drift —
`MerchantFeeCalculator` still owns the arithmetic but must not be called
directly with a `Merchant` for new billing code. Fee lookups ride the
**PURCHASE** applicable-rule list; when a report already holds every tenant
rule, use `EffectiveFees.applicable(...)` (the in-memory twin of the repository
query) rather than re-querying per merchant.

All V29 columns are nullable — null means "not configured at this level,
inherit" — so existing rows keep their pre-V29 behaviour. `Dtos.RuleRequest`
keeps a back-compat 8-arg constructor for callers built against the old shape.

**No free merchants (V30).** `POST /loyalty/merchants` REFUSES creation when the
effective **voucher-issue** fee resolves to zero — nothing on the merchant, none
on its rule, and no tenant standard — with `MERCHANT_ZERO_ISSUE_FEE`. Issuing is
the event we bill for, so a zero there means the platform runs that merchant for
free forever and nothing else ever surfaces it. The **redeem** side may be zero
freely: billing only issuance is a normal commercial arrangement, so it is
reported by the audit and never refused. An operator can still onboard an
unbilled merchant with `waiveFees: true` + a mandatory `waiveFeesReason`, which
persists to `merchants.fee_waived` / `fee_waived_reason` — that is what makes
"free on purpose" distinguishable from "free by accident".
`GET /loyalty/merchants/fee-audit` lists every merchant issuing for free with
that distinction, resolving all merchants from ONE rule query via
`EffectiveFees.applicable` rather than an N+1. Pre-V30 rows default to
`fee_waived = false`, so every merchant already onboarded free shows up as
unwaived — deliberately, since that backlog is the point.

**Onboarding shortcut:** `POST /loyalty/merchants` takes an optional
`loyaltyOverride` block (earn rate, floor, both fee schedules) and creates the
merchant's own rule in the same transaction, so an operator never onboards a
merchant and then forgets to POST its rule. The override lands in
`loyalty_rules` — NOT on new merchant columns — so there stays one home for rule
config and the existing merchant-beats-global precedence applies unchanged.
`MerchantService` injects `LoyaltyRuleRepository` (not `RuleAdminService`, which
already depends on `MerchantService` — that edge back would be a bean cycle) and
shares the mapping/validation through the static `RuleAdminService.build`. Add
new rule fields there, not in a second mapper.

## Cryptography & key management (OWASP A02)

At-rest sensitive fields are keyed/hashed, never plaintext: loyalty voucher/QR
payloads via **HMAC-SHA256** (keyed by `loyalty.voucher.secret` /
`loyalty.qr.secret`); denylist tokens via SHA-256. Every keyed secret is an env
var and guarded by `ProductionSecretsGuard`, which fails boot under a deployment
profile (an active-profile set with no `dev`/`test`/`it`/`local` — including the
empty set) on a `change-me` placeholder or a too-short `JWT_SECRET`. Contract
pinned by `ProductionSecretsGuardTest`. Boot-required: `JWT_SECRET`,
`INTERNAL_API_TOKEN`, `LOYALTY_VOUCHER_SECRET`, `LOYALTY_QR_SECRET`,
`REDIS_PASSWORD`.

JWT verification is **dual-alg** (Stage-1 of the fleet's HS256→RS256 migration):
`JwtUtil` selects the verification key by the token's own `alg` header — RS* →
optional `jwt.public-key` (PEM), else the HS256 `jwt.secret`. Keys are optional
env vars (`JWT_PUBLIC_KEY`); default is HS256.

## CI/CD & supply-chain integrity (OWASP A08)

Invariants — weakening any needs a deliberate, called-out reason:

- **Every third-party GitHub Action is pinned to an immutable commit SHA** with a
  trailing `# vX.Y.Z` comment — never a movable tag. Dependabot's
  `github-actions` ecosystem bumps the SHA + comment together.
- **Every workflow declares least-privilege `permissions:`.** Default
  `contents: read`; escalate per-job only where needed (`pull-requests: write`
  for dependency-review; `packages/id-token/attestations: write` on Release).
- **Release scans before it pushes, then signs.** Trivy scans the locally-loaded
  image (CRITICAL/HIGH, os+library, `--ignorefile .trivyignore`) and gates the
  push; only then is the image pushed with SLSA provenance + SBOM
  (`provenance: mode=max`, `sbom: true`) and a GitHub-native build-provenance
  attestation (with the OIDC retry step for transient token flakiness).
- **`.trivyignore` is a governed waiver list** — every entry needs an owner +
  reason + review-date comment. Prefer fixing/upgrading over waiving; POM CVE
  overrides live in `pom.xml`.
- **PR-time SCA**: `ci.yml`'s `dependency-review` flags any *new* High/Critical
  direct dependency a PR introduces (diff-scoped). **Called-out exception:** the
  `dependency-review` job is **gated to public repos**
  (`github.event.repository.private == false`) AND its step carries
  `continue-on-error: true`, because the action needs GitHub's Dependency Graph,
  which on a **private** repo requires paid GitHub Advanced Security — without it
  the action hard-errors and reds every PR. Belt-and-suspenders: an earlier gate
  on `repository.visibility == 'public'` did NOT skip (that payload field read
  as truthy on this private repo, so the job ran and failed), so the `if` now
  uses the canonical `repository.private` boolean AND `continue-on-error`
  guarantees the "not supported" error can never red a PR even if the metadata
  is wrong again. This repo is currently private without GHAS, so the job
  self-skips; it auto-re-enables if the repo goes public or GHAS is licensed.
  This drops only the PR-time *direct-dependency* advisory surface —
  transitive/library CVEs remain covered by the Release workflow's Trivy image
  scan. (The public `ticketing-system` repo runs this job normally.)

## Local build (no Docker in some sandboxes)

`@SpringBootTest`/Testcontainers need a Docker daemon; where absent, write
pure-JUnit tests (Validator, WireMock, Mockito) and let CI run the
container-backed ones. Compile/verify a single test with
`./mvnw -Dtest=<Test> -DfailIfNoTests=false test`.
