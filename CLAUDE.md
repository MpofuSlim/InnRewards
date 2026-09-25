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
  endpoints** (no customer auth; an OPTIONAL shared `x-api-key` — see below),
  whose authenticated twins are the production target — keep new guides consistent with that
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
(400/401/403/404) with the real **`code` AND `message`** thrown by the service
code. `MerchantController` and `ShopController` are the canonical shape.

**Both halves, and `code` is the one that matters** — clients are told
throughout to branch on it, so an example with the right message and the wrong
code is the more expensive mistake, and the one a message-literal grep misses.
An audit of every annotation in this service found ~30 wrong, almost all of one
shape: a `LoyaltyException` keeps its **domain** code verbatim
(`NOT_MERCHANT_OWNER`, `ORDER_ALREADY_PAID`, `UNSUPPORTED_CURRENCY`), and the
example said `"403 FORBIDDEN"` / `"409 CONFLICT"` / `"400 BAD_REQUEST"` instead.
The status-format code comes only from the GENERIC handlers — and from
`LoyaltyException.notFound`, which is the deliberate exception at
`"404 NOT_FOUND"` / `"<thing> not found"`.

**Bean validation has ONE shape, and it is not the obvious one.** `@Valid` on a
body always renders `{"code":"400 BAD_REQUEST","message":"Validation failed",
"data":{"<field>":"<message>"}}` — the field detail is in `data`, never in
`message`. Nine examples across six controllers wrote `"message": "value: must
not be null"`, which is neither.

**Never declare the same `responseCode` twice on one handler.** OpenAPI keys
responses by status, so the second block is silently dropped from the published
spec — it compiles, it boots, and half the documentation simply never renders.
Use one `@ApiResponse` per status with several named `@ExampleObject`s.
`SwaggerResponseCodeUniquenessTest` walks every `@RestController` in the build
and fails on a duplicate; it found two live ones (`VoucherController.redeem`,
`TransactionController.transfer`) the moment it was written.

**A Swagger example is code that nothing executes**, which is why these rot
silently and why the checks above are worth the words. When you change a thrown
code or message, grep the controllers for the old literal in the same commit.

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

## A merchant belongs to an ORGANIZATION, not an email (V51)

**`merchants.organization_id` is who a merchant belongs to.** It replaced
`merchants.admin_email` (V50's binding), which had been the ownership key, the
authorization rule AND the notification address at once — for loyalty, and
through user-service's login-time claim lookup, for the marketplace too. One
person per merchant, no colleagues, and an admin running two businesses got no
`merchantId` claim anywhere. user-service V39 made the BUSINESS the tenant;
this is loyalty's half of that (step 2 of the organizations plan, shipped in
lock-step with user-service dropping the `merchantId` claim and marketplace
re-keying sellers).

- **Who is a merchant admin here is decided from the ORGANIZATION claims,
  never the role alone.** `JwtFilter` grants `ROLE_MERCHANT_ADMIN` iff the token
  carries `orgId` + `orgRole` ∈ {OWNER, ADMIN} + `products` ∋ `loyalty`
  (`loyaltyOrganizationOf`), and puts that org on
  `CallerDetails.organizationId`. **A bare `MERCHANT_ADMIN` in the roles claim
  grants nothing** — otherwise a marketplace-only business, or a pre-V39 token,
  would administer loyalty. The upside is the point: an ADMIN colleague added
  through `/organizations` with no staff role at all is a merchant admin here.
  STAFF is not (a cashier does not run the business). Product match is exact
  and lowercase, as user-service mints it. Pinned by
  `JwtFilterLoyaltyOrganizationTest`.
- **`MerchantAuthz` / `ReportingService` compare the merchant's
  `organizationId` with the caller's.** SUPER_ADMIN is exempt, SHOP_ADMIN is
  still pinned by its token `merchantId` (a row-stamped claim user-service
  keeps for shop staff). An unowned merchant (`organization_id IS NULL`) is
  reachable by SUPER_ADMIN only.
- **`POST /loyalty/merchants` takes an optional `organizationId`.** Omitted →
  the caller's own organization; a caller acting for none is **403
  `ORGANIZATION_SCOPE_MISSING`** (never an unowned merchant nobody can
  manage). Naming a different organization → SUPER_ADMIN only, anyone else
  **403 `ORGANIZATION_NOT_PERMITTED`** — refused rather than ignored, so a
  client never believes it onboarded a merchant for someone it did not.
  SUPER_ADMIN omitting it creates an unowned merchant, which is what service
  fixtures rely on (`testsupport/MerchantFixtures`).
- **Tenant membership has an ORGANIZATION path, checked first**
  (`TenantContext`): a caller acting for a business is a member of the program
  that business created (`tenants.organization_id`, stamped at create) and of
  any program where it owns a merchant (`TenantCachedLookup.organizationOwnsMerchantIn`
  — deliberately NOT cached: it must turn false the moment a merchant moves).
  `tenant_members` still works for everyone it always did. Pinned end to end
  by `MerchantOrganizationOwnershipSecurityTest`, including the case where one
  business's admin works in another's program only on its own merchant.
- **Invoices go to the organization's OWNERs and ADMINs, resolved at send time**
  (`UserServiceClient.organizationAdminEmails` →
  `GET /users/internal/organizations/{id}/admins`), so a colleague added today
  gets the next invoice and one removed does not. Best-effort per recipient; an
  unowned merchant emails nobody and never asks user-service.
- **S2S:** `GET /loyalty/internal/merchants/ids-by-organization?organizationId=`
  (plain map `{organizationId, merchantIds}`) is what user-service's
  `ShopStaffService` uses to scope a merchant admin's shop-staff calls.
- **Gone, with the binding:** `adminEmail` on the create request and response,
  `PUT`/`DELETE /loyalty/merchants/{id}/admin-email`,
  `GET /loyalty/merchants?unassigned=true`, and the internal `by-admin`,
  `ids-by-admin`, `{id}/admin-email` and `names` lookups (marketplace now reads
  seller names from user-service's organization directory). The
  `MerchantAdminChange` entity went too.
- **`admin_email` and `merchant_admin_changes` are left DORMANT, not dropped** —
  same call as `event_outbox`. They are the only record of who each pre-V51
  merchant belonged to, which is exactly what the staging remap reads
  (`admin_email` / `tenants.owner_email` → user → the organization it OWNS).
  V51 backfills nothing because this database holds no users or organizations
  to map an email to.
- **Not done, deliberately:** moving shop staff (SHOP_ADMIN / SHOP_USER) under
  organization membership. They keep their row-stamped `merchantId`/`shopId`
  claims; that move is a separate design change.

## Timestamps — UTC

Loyalty maps timestamps as `Instant`, which is always UTC. Containers also pass
`-Duser.timezone=UTC`. If you ever add a `LocalDateTime`, use
`LocalDateTime.now(ZoneOffset.UTC)`, never bare `.now()`.

## Schema changes (Flyway)

New schema goes in `src/main/resources/db/migration/V<N>__*.sql` (PostgreSQL +
Flyway, `ddl-auto: validate`). Current head is **V51**; never edit an applied
migration — add the next version.

> [!IMPORTANT]
> **Removing a value from a `@Enumerated(EnumType.STRING)` enum is a DATA
> migration, not a code change.** Hibernate cannot hydrate a row holding a
> string the Java enum no longer has: it throws per row at query EXECUTION and
> `GlobalExceptionHandler` renders that as an opaque 500 on every read path
> that touches the table. **There is no compile, boot or CI signal** — every
> `@SpringBootTest` applies Flyway first, so the suite stays green and the
> breakage appears only against a cell with real history. Always ship the
> `UPDATE` that rewrites existing rows in the same migration, BEFORE narrowing
> any CHECK constraint (see V48).

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
- **`veengu` (V41) and `innbucks` (V42) are BOTH dead — do not build on either,
  and never enable them.** `veengu` was superseded when the partner's Postman
  collections showed the app authenticates against the InnBucks **Client
  Service** API, not Veengu, so a Veengu access token is not what the app holds.
  `innbucks` replaced it and then failed on measurement: its proof assumed the
  platform binds a user token to its own msisdn, and it does not (see the
  `innbucks` section below for the evidence). Both are left in place only
  because V41/V42 are applied history; both stay off.
- **The live registration paths are ticketing's OTP webhook**
  (`source = TICKETING_OTP`), which reaches `registerPhone` through
  `promoteByPhone`, **and — where a cell enables it — the `innbucks_validate`
  eligibility mode (V44, see its own section below)**, called by the app after
  each middleware login and by the backlog sweeper. `assertion` and `key`
  remain available for a partner *backend* registering on a customer's behalf.
  None of these returns a session — the customer app's session still comes
  from ticketing's OTP verify.

### Registration hands back a SESSION — but only to the customer's own device

A proof is worth nothing to the app if it cannot then act on it. The
self-service modes therefore return `loyaltyToken` + `expiresInSeconds`
alongside the registration, minted by `LoyaltySessionIssuer` and accepted by
`JwtFilter` — so one call both registers the phone and yields the bearer for
loyalty's authenticated transfer/redeem endpoints.

> [!IMPORTANT]
> **The session machinery is sound and is the keeper; the proof channel it was
> built for is not.** Both self-service modes (`innbucks`, `veengu`) are
> disabled, so in practice the only thing minting a loyalty session today is
> **ticketing's OTP verify** (PR #545), which carries the `loyalty-otp` scope.
> Everything below about token shape, scope markers and revocation applies
> unchanged to that path — `JwtFilter` accepts either marker. Note this makes
> `selfServiceMode()` currently unreachable in production: keep it and its tests,
> because it is the guard that stops a future mode leaking sessions to partner
> backends.

- **`innbucks` / `veengu` get a session; `assertion` / `key` NEVER do**, and
  `selfServiceMode()` is an allow-list so a mode added later fails closed. The
  partner modes' caller is a BACKEND proving a phone on someone's behalf:
  handing it a live customer session would let it act AS every customer it
  registers. Registering for someone is a legitimate partner power; becoming
  them is not. Widening this would be a SILENT escalation — nothing errors, the
  partner simply starts receiving tokens that spend other people's points —
  which is why `PartnerRegistrationSessionScopingTest` pins both directions,
  including that the `key` response shape is byte-for-byte what it was before
  sessions existed.
- **The token's safety is its SHAPE: an empty roles list.** Every other service
  gates customer endpoints on `hasRole('CUSTOMER')`, so a roles-empty token is
  inert fleet-wide and loyalty alone grants the role for it. Minting with
  `roles: [CUSTOMER]` would turn a phone proof into a passwordless login for the
  whole platform. `LoyaltySessionIssuerTest` pins the empty list first.
- **loyalty minting is a new USE, not a new capability** — it already holds the
  shared HS256 `jwt.secret` to verify. Routing this through user-service would
  add an internal endpoint, a network hop on the registration path, and a state
  where the phone is registered but the token mint failed, for a token loyalty
  is the only consumer of.
- **Two scope markers, both accepted**: user-service's `loyalty-otp` and this
  service's `loyalty-session`. They grant the same role and differ only in
  recording HOW the phone was proved, so an incident on one proof channel can be
  scoped to the tokens it minted. `loyalty-otp` is cross-repo and drift-prone;
  `loyalty-session` is read straight from `LoyaltySessionIssuer`.
- **The access token's TTL is still its only self-contained end**
  (`LOYALTY_SESSION_TTL_SECONDS`, 12h to match user-service). No `userId` claim
  means the fleet's tokenVersion denylist — keyed by user UUID — cannot reach
  these tokens. What changed in V43 is that the TTL is no longer *also* the
  revocation story: see the refresh section below.
- **A repeat registration still returns a fresh session.** `newlyRegistered:
  false` means the phone was already proved, not that the app holds a live
  token; withholding one would strand a returning customer.

### The session RENEWS — a rotating refresh token, not a longer TTL (V43)

**`loyalty_refresh_tokens` is what lets a customer stay signed in without a
second SMS.** Registration is a permanent phone-level fact (V40), so an OTP
should cost **one SMS per customer for life** — but with a 12h session and no
renewal it cost one SMS every twelve hours, per device. That is the price that
kept the customer app on `/loyalty/public/**`.

Three endpoints under `/loyalty/session`, and which credential each takes is the
whole design:

- `POST /exchange` — **bearer: a live phone-scoped session**. Trades it for a
  chain. Called ONCE, right after the OTP verify that minted the session.
- `POST /refresh` — **credential: the refresh token in the BODY**. Rotates it
  and returns a fresh access token.
- `POST /logout` — same credential; revokes the chain.

- **Do NOT "fix" this by lengthening the TTL or letting the access token renew
  itself.** Both make the access token a long-lived bearer that nothing can
  withdraw, and the self-renewing version is the worse of the two: a stolen copy
  renews alongside the legitimate one forever, and because *both keep working*
  there is no moment at which the theft is observable. A separate, rotating,
  server-recorded credential exists precisely so a second holder becomes
  **detectable**.
- **Reuse detection is the load-bearing property.** Each refresh stamps
  `used_at` on the presented row and issues a successor in the same `chain_id`.
  Presenting a spent row means two parties hold credentials from one chain, and
  nothing distinguishes them — so the WHOLE chain is revoked (the legitimate
  device included) and the customer re-proves. Revoking only the replayed row
  would leave the attacker's copy of the *current* token working, which is the
  opposite of the point. `LoyaltySessionServiceTest.reusingASpentTokenRevokesTheWholeChain`
  pins it; alert on `loyalty_session_rejected_total{reason="reuse_detected"}`,
  which should be zero.
- **Only the SHA-256 is stored, and bare SHA-256 is correct here.** The token is
  32 random bytes, so there is no space to enumerate — the fleet's keyed-HMAC
  rule exists for LOW-entropy secrets (a six-digit OTP, a voucher code). Don't
  "upgrade" this to an HMAC on pattern-match.
- **The window SLIDES** (`LOYALTY_SESSION_REFRESH_TTL_DAYS`, 90). Every rotation
  issues a successor with a fresh window, so an app in ordinary use never
  re-proves; a chain untouched for the whole window ages out, which is what
  stops an abandoned device being a permanent credential.
- **A refresh continues a proof; it never performs one.** Every refresh re-asks
  `UserService.isPhoneRegistered`, so revoking the V40 registration tombstone
  signs the customer out at their next renewal instead of being quietly outlived
  by a live chain. `revokeAllForPhone` is the operator's "sign them out
  everywhere" lever — deliberately NOT an endpoint, since it is aimed at a phone
  number.
- **`/refresh` and `/logout` are `permitAll` AND in `JwtFilter`'s excluded
  paths** — exact paths, never the `/loyalty/session` prefix. Their credential is
  the body token and the access token they exist to replace is normally expired
  by the time they are called, so requiring a live bearer would make renewal
  possible only while renewal was unnecessary; and an app whose HTTP client
  attaches its stored bearer to everything would be 401'd out of the one call
  that would have fixed it. The sibling `/exchange` keeps running through the
  filter and stays `authenticated()`.
- **`/exchange` is gated on the SCOPE MARKER, not `isAuthenticated()`.** A chain
  is a long-lived phone-scoped credential, so only a caller already holding a
  phone-proved session may open one — a staff token's phone claim is an
  employee's number. The `@PreAuthorize` strings must be literals (the
  annotation takes a compile-time constant), so nothing but
  `LoyaltySessionControllerTest.authorityStringsMatchTheScopeMarkers` couples
  them to `LOYALTY_SESSION_SCOPE`; a drift would 403 every customer.
- **Every refusal is one opaque `401 SESSION_REFRESH_REJECTED`** and sign-out is
  always `200`, even for an unknown token — otherwise either becomes an oracle
  for whether a token exists. The client behaviour is the same for all of them:
  get a fresh phone proof.
- The gateway route lives in `ticketing-system`:
  `loyalty-session-refresh-route`, POST-only, the two exact paths, IP-keyed
  fail-safe limiter, ordered before `loyalty-service-route` and pinned in
  `GatewayRouteTableTest`. The catch-all's `gatewayKeyResolver` keys on the raw
  `Authorization` header, which these callers do not send — under it the whole
  internet would share one bucket.

### …and `GET /loyalty/users/me` turns that session into something spendable

A phone-scoped session authenticates the caller but does not tell them **who
they are in loyalty's own tables**, and the spend endpoints are addressed by
account UUID: `POST /loyalty/transfer` takes a `@NotNull fromUserId`,
`POST /loyalty/redeem` a `@NotNull userId`, and every tenant-scoped call needs
an `X-Tenant-Id`. An app holding a phone and a token has none of those, so the
authenticated surface was documented but **not actually callable** — which is
why the customer app was still on `/loyalty/public/**`. This endpoint closes
that, returning one row per tenant the caller has transacted with:
`{ userId, tenantId, merchantId, status }`.

- **It needs no ownership check, and that is a property of its shape, not an
  exemption.** The phone comes from the token; there is no path variable, body
  or filter. With no caller-supplied input there is nothing to bind against and
  nothing to point at another customer, which is what
  `MeAccountsTest.lookupUsesTheTokenPhoneOnly` pins. Do **not** add a
  `?phoneNumber=` or `/{id}` variant later without the ownership check the
  CUSTOMER rule above demands — that would turn a safe self-lookup into a
  directory of everyone's account ids.
- **PENDING rows are RETURNED, not filtered out.** The status is the reason a
  redeem gets refused, so hiding the row leaves the app unable to explain the
  refusal — it would show "no accounts" to a customer who plainly has one.
  Report the status; let the client decide what to say.
- **An empty list is a normal 200.** A customer who proved their phone but has
  never transacted has no projection anywhere. Points and vouchers are still
  readable via `/loyalty/users/me/wallet`, which is keyed by phone, not by
  projection — the two endpoints answer different questions and an empty
  `accounts` does not imply an empty wallet.
- A token with no `phoneNumber` claim (any staff token) is a `400
  NO_PHONE_CLAIM` rather than an empty list, so a mis-aimed caller is told the
  endpoint isn't for them instead of being shown a plausible "you have nothing".
- No gateway change was needed: the existing `loyalty-service-route`
  (`Path=/loyalty/**`) already covers it. There is no mapping conflict with
  `/loyalty/users/{userId}/unblock` (POST) or `/loyalty/users/{id}/transactions`
  (a deeper path).

### `innbucks_validate` (V44): ELIGIBILITY by owner decision — never identity

**Platform-owner decision (2026-09, reaffirmed explicitly): every InnBucks
customer is eligible to spend loyalty points.** Under that rule, "is this
msisdn a real InnBucks customer" — answered by the app-authorized
`GET /auth/client-service/msisdn/{msisdn}/validate` — is a sufficient basis to
REGISTER a phone (`source = INNBUCKS_VALIDATE`) and promote its projections.
This is the same endpoint the V42 post-mortem below disqualifies as an
OWNERSHIP probe, used deliberately for a different question: it answers "00"
for every real customer whoever asks, which is disqualifying for identity and
exactly the point for eligibility. Do not "fix" either section to match the
other — they answer different questions.

- **Three consumers of `InnbucksCustomerValidateClient`:** the
  `auth-mode=innbucks_validate` branch of `POST /loyalty/partner/registrations`
  (the app calls it after each middleware phone+PIN login; anyone MAY call it —
  the effect is only that a real customer's phone becomes spendable),
  `InnbucksValidateBacklogSweeper` (random bounded samples of NEVER-registered
  PENDING / `PENDING_EXPIRED` phones per run, so the pre-existing backlog
  drains without waiting for logins; a phone with a REVOKED registration is
  never re-sampled — a revocation is an operator decision the sweep must not
  undo, which is what keeps the batch-revocation lever below effective;
  aborts the run on the first Unavailable;
  sends NO customer notification — a bulk-backfill SMS campaign is a marketing
  decision, not a side effect), and **`OnDemandEligibilityCheck`** (below).
- **`OnDemandEligibilityCheck` is what makes the rule work with NO client
  involvement**, and it is the one to reach for first. The endpoint above has to
  be CALLED, so a customer's first spend depended on a client remembering to
  fire a request after sign-in — and a client that forgets, ships late or drops
  the response leaves that customer refused, with nothing here able to tell that
  apart from a phone that genuinely is not a customer. **The FE asked for this
  to be a backend concern and was right** (its own framing — "the FE can be
  exploited" — is not the reason: see the ownership note below). So
  `requireSpendable`'s PENDING arm now ASKS the directory at the moment it is
  about to refuse, registers the confirmed customer (`source =
  INNBUCKS_VALIDATE`, `source_ref = on-demand-spend`) and lets the spend
  through. The registration endpoint stays — it is still the faster path when a
  client does call it, and the sweeper still converges everything else.
  - **At the spend gate ONLY.** It is the one place the answer changes an
    outcome, and an already-ACTIVE customer never reaches it, so the common path
    pays nothing. Deliberately **not** on the earn path: `findOrCreatePending`
    runs with a cashier waiting at a till.
  - **It re-reads the FACT after registering, and that is not belt-and-braces.**
    A confirmed customer is not always a registered phone — `registerPhone`
    leaves a REVOKED registration revoked for an eligibility-only proof, which
    is exactly what keeps the batch-revocation lever below working. Promoting on
    the directory's yes alone would wave the spend through with the row still
    revoked. Pinned by
    `requireSpendable_pending_confirmedButRevoked_isStillRefused`.
  - **The throttle is load-bearing, so no throttle means no check.** A spend
    attempt is caller-triggered and repeatable, so the cooldown key is claimed
    in Redis (`SETNX`) BEFORE the call, and with no Redis template available the
    check is **skipped** rather than run unthrottled (watch
    `outcome=no_throttle`). An `Unavailable` shortens the window rather than
    locking the phone out, and is never read as "not a customer".
  - **Nothing in it throws.** It runs inside a customer's spend transaction, so
    every failure path returns false and the caller falls back to the ordinary
    `USER_PENDING` — the pre-existing behaviour. Same reason an outage is not a
    503 there: the account really is not spendable yet, and a retryable status
    invites a retry that cannot change.
  - `registerPhone` is reached by SELF-invocation, so it joins the spend
    transaction instead of opening its own. That is wanted, and matches the heal
    arm above: a registration earned on a spend that then fails rolls back with
    it, and the sweeper converges the phone anyway. Don't "fix" it into
    `REQUIRES_NEW` expecting an independent commit.
  - Off by default: `LOYALTY_INNBUCKS_VALIDATE_ON_DEMAND_ENABLED`. Enabled
    without credentials is a HALF-PROVISIONED boot ERROR, because its failure is
    otherwise silent — no runs to log, every affected customer just keeps seeing
    `USER_PENDING`.
- **The load-bearing boundary: this mode NEVER mints a session.**
  `selfServiceMode()` excludes it and `PartnerRegistrationSessionScopingTest`
  pins it. A session here would be a passwordless login to any customer account
  by naming their number. Registering makes the phone's (already eligible)
  owner spendable; it grants the caller nothing. Identity remains the OTP /
  assertion channels' job.
  **Which is also why moving the check from a client into the spend gate does
  not make the phone trustworthy** — the directory answers whether a number
  exists, not who holds it, so neither placement is an ownership proof. What the
  backend placement genuinely buys is that no client can forget it, skip it, or
  send a number the user typed: the phone is read off the loyalty account the
  spend is already being performed against.
- **Accepted residual exposure, stated once:** wherever spends are bound to the
  caller only by account status — today that is the unauthenticated
  `/loyalty/public/**` staging surface (`.../points/send`, `.../points/redeem`,
  live only where `loyalty.public-test.enabled=true`) — promoting every
  InnBucks customer removes PENDING as the last per-account guard there. The
  authenticated surface is unaffected (`requireCallerOwns*` binds the session
  phone). The owner accepted this with the decision; the durable fix is moving
  app spends to the authenticated twins (OTP session + refresh) or retiring the
  public spend endpoints.
- **Ops:** client credentials default to the fleet `BANK_API_*` set (overrides:
  `LOYALTY_INNBUCKS_VALIDATE_*`); enable the endpoint with
  `LOYALTY_PARTNER_REGISTRATION_ENABLED=true` +
  `LOYALTY_PARTNER_REGISTRATION_AUTH_MODE=innbucks_validate`, the sweep with
  `LOYALTY_INNBUCKS_VALIDATE_SWEEP_ENABLED=true` — per host, in the gitignored
  `cell.<iso>.local.env`. Half-provisioning is a boot ERROR
  (`PartnerRegistrationProvisioningCheck` / `InnbucksValidateProvisioningCheck`).
  The 404→NotACustomer mapping in the client is an ASSUMPTION (unmeasured):
  after any config change, a run of `not_customer` outcomes across every phone
  in `loyalty.registration.backlog.checked` means the validate-path is wrong,
  not that the backlog is empty.
- **Reversal lever:** the registrations are batch-revocable —
  `WHERE source = 'INNBUCKS_VALIDATE'` — then re-PENDING the projections, per
  the V40 revocation notes. This lever is only correct because
  `registerPhone` treats INNBUCKS_VALIDATE as a WEAK signal that never
  overrides a stronger recorded fact: it does **not** overwrite an existing
  stronger `source` (an OTP/assertion/key-proven phone the app later re-touches
  keeps its real source, so the revoke query never captures a genuinely-proven
  customer, and `source` stays consistent with the FIRST-proof `registered_at`),
  and it does **not** reinstate a revoked row (only a real proof reinstates, so
  the app firing eligibility on every login can't resurrect an operator's
  revocation one customer at a time). A real proof still overwrites an
  eligibility-only source, so a customer who later OTP-verifies graduates out of
  the revocable population. Pinned by the four `registerPhone_*eligibility*` /
  `*RevokedRegistration` cases in `UserServiceTest`. Every other source's
  behaviour in `registerPhone` is unchanged.

### `innbucks` mode is UNSOUND and must stay disabled (V42)

> [!CAUTION]
> **Never set `LOYALTY_PARTNER_REGISTRATION_AUTH_MODE=innbucks`.** The mode's
> proof does not hold. Enabling it would let anyone holding *any* InnBucks
> customer token register *any* other customer's phone — and, because
> `innbucks` is a `selfServiceMode()`, receive a live `loyaltyToken` for it and
> spend that customer's points. It is off by default (`..._ENABLED=false`);
> leave it off. The code is retained only because V42 is applied history.

**The design.** The app authenticates its customers against the InnBucks Client
Service API (`POST /auth/client-service/user/login`, username + PIN block → a
user token), not against our fleet. That token is a possession proof we cannot
read — the API exposes **no identity endpoint**. So the question was asked
backwards: the caller sends `X-Innbucks-User-Token` **and the phone it claims**,
and `InnbucksSessionClient` asks the platform to read *that* msisdn under *that*
token, treating an answer as proof.

**Why it fails.** That is sound only if the platform refuses when the token does
not own the msisdn. It does not. Measured against `staging.innbucks.co.zw` with
an **app/merchant** bearer minted from our own `BANK_API_*` credentials — i.e.
credentials that prove nothing about any customer:

| Call | Result |
|---|---|
| `GET /api/v1/account/msisdn/{any}/details` | `200` `responseCode 000` + that customer's **name and account numbers** |
| `POST /bank/api/account/balance` `{accountNumber}` | `200` + that customer's **balance** |
| `POST /bank/api/account/mini-statement` `{accountNumber}` | `403` — the *only* refusal observed |

The probe endpoint is a **directory lookup**, not a token-bound read. Its
sibling `GET /api/account/msisdn/{msisdn}` is step 1 of the collection's own
**P2P recipient lookup** — cross-customer by design, which is what a directory
is for.

**The one contrary signal, and why it does not rescue the mode.** The
mini-statement `403` proves the platform does per-**client-type**
authorization (the merchant client lacks that permission). It does **not** show
per-**object** authorization (a token restricted to its own accounts). Those are
different checks. Mini-statement was evaluated as a replacement probe and
rejected: it takes a caller-supplied `accountNumber`, which an attacker harvests
from the open `/details` lookup given only the victim's phone number — and its
structural twin `POST /bank/api/account/balance` (same prefix, same body shape,
same `{{user_token}}` auth) provably returns `200` for an arbitrary account.

**All 88 request definitions across both partner collections were inventoried
looking for any endpoint that could serve as an ownership proof. There is none**
— no `/me`, no token introspection, nothing self-scoped. Every customer-token
read either takes a caller-supplied `msisdn`/`accountNumber` (aimable at anyone)
or is a global reference catalogue carrying no customer data. `GET /api/card/{msisdn}`
is not an exception: its "Agent Lookup" twin is the same path against the
collection's own demo number under the same `{{user_token}}`, and a card *list*
could not work as a proof anyway because its negative case is a `200` empty
array, indistinguishable from a legitimate owner who has linked no card.

**The durable lesson.** This mode was built, reviewed, documented and declared
complete on the strength of one sentence — *"the middleware binds a user token to
its own msisdn"* — that came from the frontend team and was never tested. It read
as a fact in this file for weeks. **A security property of someone else's
platform is an assumption until you have measured it**; write it down as an
assumption and test it before anything depends on it.

**What replaces it: SMS OTP, which is cheaper than it sounds.** Registration is a
permanent *phone-level* fact (V40, above), so an OTP costs **one SMS per customer
for life**, not one per session — and ticketing PR #545 already wires OTP verify →
`registerPhone` → `loyaltyToken`. The remaining gap is a refresh path so the 12h
session TTL never forces a second SMS.

**What would make an InnBucks-token proof viable** (only these; do not improvise):
1. InnBucks exposes a token-introspection endpoint authorized **solely** by the
   `user_token`, with no caller-supplied identifier in path, query or body,
   returning the msisdn bound to that token; **or**
2. InnBucks confirms in writing that a specific read endpoint performs
   object-level authorization, *and* a two-customer test confirms it: customer
   A's token addressing customer B's identifier must be refused, with a control
   proving B's account is real and readable by B.

The notes below record the engineering that is still correct in itself — the
`/validate` prohibition, the responseCode handling, the Rejected/Unavailable
split. They describe a client that must not be switched on.

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
- **The probe path is configurable, and no value of it makes the mode safe.**
  `PartnerRegistrationProvisioningCheck` still refuses a `/validate` path, but
  that guard now protects a mode that must not run at all — do not read a clean
  boot log as a green light. Every candidate replacement path was inventoried
  and rejected (above).
- **`INNBUCKS_SESSION` is its own `phone_registrations.source` value**, which is
  what makes any rows it ever wrote revocable as a batch:
  `SELECT * FROM phone_registrations WHERE source = 'INNBUCKS_SESSION'` should
  return **zero rows** — the mode has never been enabled on any cell. If it ever
  returns rows, treat every one as an unproven registration and revoke it.
- **Never add an activation path under `/loyalty/public/**`.** Those endpoints
  have no CUSTOMER authentication; activation there would let anyone who guesses
  a phone number activate and then drain it, which is precisely what PENDING
  exists to prevent. The `x-api-key` gate does not change this — it identifies
  the app, not the phone's owner, so a key holder is still "anyone".
- **The gateway route lives in `ticketing-system`** and IS added (ticketing
  PR #543): `loyalty-partner-registration-route`, POST-only, IP-keyed fail-safe
  limiter, ordered before `loyalty-service-route` and pinned in
  `GatewayRouteTableTest`. The route stays: `assertion` / `key` still reach the
  endpoint from outside the cluster, and the IP-keyed fail-safe limiter is what
  caps brute-forcing the shared key. It was also shaped for the mobile-client
  traffic `innbucks` would have carried; with that mode dead, no mobile client
  calls this path at all.

## `/loyalty/public/**` has an OPT-IN `x-api-key` gate

`PublicTestApiKeyFilter` checks one header for the whole prefix, in constant
time, before any mapping in `PublicTestController` runs — **on the cells that
configure a key**. Blank (the default, and the ZW staging cell today) means the
surface behaves exactly as it did before the filter existed. Where a key is set
it lives in the super app's **Firebase Remote Config**, which is the property
that matters: it can be rotated or revoked without an app release.

- **It authenticates the APP, not the customer, and that is the whole limit of
  what it buys.** The key ships inside a client, so anyone who can read that
  client's config can read the key. It stops casual traffic and drive-by
  scanners and gives us a kill switch; it does NOT make these endpoints safe —
  the phone number in the URL is still the only identity, so a key holder can
  still spend any phone's points. **`loyalty.public-test.enabled=false` on
  production is still the control that matters.** Don't let the key be read as
  promoting this surface toward production-worthy.
- **A filter, not a per-method check.** The prefix is `permitAll()`, so nothing
  in the security chain asks who is calling. A check inside each handler has to
  be remembered by whoever adds the next mapping, and the one that forgets is a
  live unauthenticated spend. Covering the prefix by shape means a new endpoint
  is gated the moment it exists.
- **Three states.** Off → the filter is inert and the controller's 404 stands (a
  401 would confirm there is something behind a path that is meant to look
  absent). On with a blank key → the filter is **inert**, and a boot WARN says
  `Public test surface is UNGATED`. On with a key → one opaque **401** for
  missing and wrong alike; which it was lives in
  `loyalty.public.test.rejected{reason}`, never in the body.
- **The blank case deliberately does NOT fail closed, and this was reversed
  once — do not "fix" it back.** It shipped fail-closed (503 + a
  HALF-PROVISIONED ERROR) on the reasoning that a blank key must never read as
  "no key required". That reasoning ignored who was already calling: merging the
  filter instantly broke a client that had never needed a key, on a cell that
  had been serving this surface openly for weeks (operator's call to reverse it,
  2026-09-17). The safety argument survives intact anyway, because the switch
  ABOVE it fails closed: `public-test.enabled` defaults to false and its whole
  documented meaning is "this cell serves an unauthenticated surface where the
  phone in the URL is the identity". Anyone who set it already accepted that. So
  a blank key returns the surface to its own documented baseline rather than
  weakening anything, and the gate engages the moment a key is provisioned.
- **Checked in `shouldNotFilter`, not in the body.** An ungated cell never
  enters the filter at all, so there is no refusal to reach by accident and no
  per-call counter measuring ordinary traffic.
- **`OPTIONS` is never gated.** A CORS preflight carries no custom headers by
  construction, so gating it 401s the preflight and the browser never sends the
  real request.
- **Config:** `LOYALTY_PUBLIC_TEST_API_KEY`, committed BLANK in
  `ticketing-system`'s `deploy/cells/cell.zw.env` (the cell-wide ConfigMap is
  shared by both ZW hosts — a value there would be a committed credential) and
  set per host in the gitignored `cell.zw.local.env`, where the Secret wins.
  Setting it is a tightening that requires the client to send the header in the
  same change; leaving it blank is the supported default.
- **Watch `reason=bad_key`** — from real traffic it means the app is still
  shipping a key we have rotated. There is no meter for the ungated state by
  design; "is this surface gated" is answered by the boot line, not a counter.

## Multi-currency — USD base, allowlist, bank-rate default + tenant override (V36)

The cell supports USD (BASE) + ZAR + ZWG. Points stay ONE currency-neutral
pool anchored to USD; any non-USD money amount crosses `ExchangeRateService`
exactly once (earn: local → USD → points; redeem: points → USD → local).

- **`SupportedCurrencies` is the allowlist** — configured set
  (`LOYALTY_SUPPORTED_CURRENCIES`, default `USD`) ∪ BASE ∪ the cell currency
  (`INNBUCKS_CURRENCY`). Every write entry point that accepts or defaults a
  currency (merchant create, QR issue, voucher issue, earn, redeem)
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
  **Since V45 every voucher is a money AMOUNT, so the conversion is
  unconditional at issue.** Legacy PERCENT/FREE_ITEM/COMBO rows keep a NULL
  `base_value` forever — a percentage run through a rate would have minted a
  confident, meaningless figure — which is why a liability report still must
  never read NULL as zero.
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

## Vouchers are TEMPLATE-LESS, amount-only, two types (V45)

**Owner decision (2026-09-17): voucher templates are retired.** A voucher is
issued directly — `POST /loyalty/vouchers/issue` / `/issue-bulk` carry the
type, the money value and the currency; there is no template between the
operator and the voucher. The `voucher_templates` table is dormant history
(same call as `event_outbox`), kept mapped ONLY as a read model so pre-V45
vouchers can resolve a template name in reports. **Do not add a template write
path back.**

- **Value types are GONE.** `VoucherTemplate.ValueType` (PERCENT / FREE_ITEM /
  COMBO) no longer exists on the issue path: a voucher's `value` is always a
  money AMOUNT in an explicit `currency`. This is also what makes the
  per-voucher fee arithmetic sound — `EffectiveFees.faceValue` multiplies
  money now, never a percentage masquerading as one. `vouchers.value_type`
  stays as an unmapped legacy column.
- **One voucher type is issuable: `SINGLE_USE`.** CAMPAIGN / REFERRAL /
  CORPORATE went in V45 — distribution labels, not redemption semantics — and
  **`MULTI_USE` was retired after it** (owner decision, 2026-09-18). See the
  section below; `usageLimit` is retired with it and anything but 1 is refused.
- **Expiry is commercial config on `loyalty_rules.voucher_validity_days`**,
  with the same two-tier inheritance as the floor and the fees: merchant rule
  → tenant's global rule → the platform default
  (`loyalty.voucher.default-validity-days`, 365). Resolution lives in
  `EffectiveFees.resolveVoucherValidityDays` — one home, same time-valid +
  merchant-first filters as the fee sides. Resolved per ISSUE, so a rule
  change applies to the next voucher, never retroactively. There is no
  per-issue validity override.
- **Currency is per issue, allowlist-validated, fail closed.** Absent inherits
  the merchant's currency; anything outside `SupportedCurrencies` refuses
  (`UNSUPPORTED_CURRENCY`), and a supported non-USD currency with no in-force
  exchange rate refuses (`NO_FX_RATE`) — the ZW cell ships
  `LOYALTY_SUPPORTED_CURRENCIES=USD,ZAR,ZWG`, but **supported is not rated**:
  post a rate before issuing in ZAR/ZWG or the issue fails closed.
  `base_value` conversion is now unconditional (every voucher is money).
- **Issue is object-level authorized now, and that is a tightening.** The
  template check was tenant-scoped only, so a MERCHANT_ADMIN could issue from
  a sibling merchant's template. Issue resolves the merchant via
  `CallerDetails.resolveMerchantId` and runs
  `MerchantAuthz.requireCallerAdministersMerchant` (SUPER_ADMIN exempt,
  SHOP_ADMIN pinned by the JWT claim).
- **The signature payload is back-compat by construction.**
  `VoucherService.signPayload` signs `tenant:templateId:code` when the stored
  row has a template id (every pre-V45 voucher) and `tenant:-:code` when it
  does not, and verify/rotate sites always recompute from the STORED row — so
  legacy vouchers keep verifying with no re-signing pass.
  `VoucherSignatureTamperingTest.legacyTemplateSignedVoucherStillVerifies`
  pins it; do not "simplify" the payload to drop the stored template id.
- **Response shapes changed**: `VoucherResponse` lost `templateId`/`valueType`
  and gained `voucherType`; `RedemptionResponse` lost `valueType`; the voucher
  report/CSV column `valueType` became `voucherType`. `MerchantRuleOverride`
  and `RuleRequest` gained `voucherValidityDays` (back-compat constructors for
  the old arities exist on both).
- **Sender identity (V46): a voucher knows who it is FROM, and both parties are
  messaged.** `IssueVoucherRequest` gained optional `senderName` + `senderPhone`
  (back-compat constructor for the pre-V46 arity); both are stamped on
  `vouchers.sender_name` / `sender_phone` and surface on `VoucherResponse`.
  These are PRESENTATION facts, distinct from the `issuer_*` audit columns
  (always from the JWT, never the body) — a staff member issuing on a
  customer's behalf makes the two differ legitimately. **`senderPhone` is the
  request's or nobody's — it is NEVER taken from the caller's token.** It used
  to fall back to the issuing caller's JWT phone, on the reasoning that a
  customer gifting from the app should not have to restate their number. **That
  caller cannot exist**: `/issue`, `/issue-bulk` and `/vouchers/purchase` are
  all `MERCHANT_ADMIN`/`SHOP_ADMIN`/`SUPER_ADMIN` only, so the fallback resolved
  to a STAFF phone every single time. The real flow is a customer at a till —
  the cashier types the customer's number as the sender and the recipient's as
  the assignee — and the fallback sent the sender's confirmation, **voucher code
  included**, to the cashier's own handset for a gift between two other people.
  On the purchase-order path it was worse than a disclosure: `payerPhone` falls
  back to `senderPhone`, so an order created without an explicit sender aimed
  the **EcoCash PIN prompt at the cashier**, asking a staff member to pay for a
  customer's voucher. Bulk stock stays sender-less as before (a per-voucher
  sender copy would message one phone `quantity` times). Pinned by
  `VoucherSenderIdentityTest.senderPhone_isNeverTakenFromTheIssuingStaffMembersToken`
  + `theTillFlow_*` on both paths. A named sender turns the
  recipient's message into "Tawanda Mpofu sent you an InnBucks voucher …", and
  `NotificationGateway.deliverSenderCopy` sends the sender their own
  WhatsApp-first/SMS-fallback confirmation (recipient name + number + the
  code — no new disclosure at issue: the issuer already holds the code in the
  API response). **Issue-path ONLY, never transfer** — transfer rotates the
  code away from the sender by design, and a sender copy there would hand the
  rotation right back; transfer keeps its code-less `notifyVoucherSent`.
  Skipped when sender == recipient phone (one message, not two). Pinned by
  `VoucherSenderIdentityTest` + the sender cases in `NotificationGatewayTest`.

### The voucher list row carries all three people — including the cashier

**Owner decision (2026-09-18): one `VoucherResponse` shape on every surface, the
issuing staff member's identity included, because this cell is staging.** There
is deliberately NO per-audience redaction, so `GET /loyalty/vouchers`, the
customer wallet views and the unauthenticated `/loyalty/public/**` endpoints all
serve the same record.

- **Three distinct people can appear on one voucher and the console must not
  conflate them.** The **assignee** holds it (`assignedUserId` / `assigneePhone`
  / `assigneeName`), the **sender** gifted it (`senderName` / `senderPhone` —
  V46 presentation facts taken from the request body, never a JWT), and the
  **issuer** keyed it in (`issuerUserId` / `issuerPhone` / `issuerEmail` — from
  the caller's JWT, audit). The real till flow makes all three different people:
  a cashier issuing a gift from Tawanda to Sedrick is only ever the issuer. PR
  #129 removed the write-path fallback that made the cashier the *sender*;
  `VoucherResponseMappingTest.theSenderAndTheIssuerAreNeverTheSameField` keeps
  the read path from re-merging them.
- **Why serving issuer PII publicly is acceptable HERE and nowhere else.** The
  public surface already returns the redeemable **code** for any phone anyone
  names, so a staff phone number is not what makes it unsafe; the control that
  matters remains `loyalty.public-test.enabled=false` on production. If that
  surface is ever promoted toward production, split the shape THEN — the
  single mapper `VoucherService.toResponse` is the one place to do it.
- **`issuedAt` was always a full instant.** A console rendering "17 Sept 2026"
  is discarding the time we already send; that is a client display fix, not a
  backend gap. Pinned by `issuedAtCarriesTheTimeOfDay_notJustTheDate`.
- **The mapping test is exhaustive on purpose.** The record is 28 positional
  components with long runs of adjacent same-typed ones (three UUIDs, four
  Strings, six Instants). Swapping neighbours compiles cleanly and no compiler
  can catch it — the symptom is the issuer's phone appearing in the sender
  column. Every component therefore gets a DISTINCT fixture value and its own
  assertion; a shared value would let exactly that swap pass. `withoutCode()`
  (formerly `redactCode`) moved onto the record for the same reason: it rebuilds
  positionally, so it belongs beside the component list it mirrors.
- **The CSV export's header is now tied to `VoucherDetail` by a test.** Its
  Swagger has always claimed "same columns as VoucherDetail" while the header
  was a hand-written string in another file — and that drift happened on this
  very change. `VoucherCsvHeaderTest` compares by SET (not order) because new
  columns are **appended** after `redemptionCount` so a positional parser of the
  old export keeps working; `redemptions` is the one allowed omission.

### Delivery is WhatsApp-then-SMS, always — `deliveryChannel` never routed anything

**Owner decision (2026-09-18): the channel selector goes.** `NotificationGateway.deliver`
has always done WhatsApp first, SMS fallback, and read `deliveryChannel` ONLY to
decide whether to send at all. Every non-`NONE` value took the identical path, so
a voucher issued "by EMAIL" was sent a WhatsApp, "by PUSH" was sent a WhatsApp,
and "by SMS" was sent a WhatsApp and reached SMS only if WhatsApp threw. The enum
advertised five transports the service cannot perform, and the console rendered
them as a choice. `NotificationGatewayTest` had shown this for as long as it has
existed: its fallback case passes `SMS` and asserts WhatsApp was tried.

- **The load-bearing change is that an ABSENT channel now DELIVERS.** It used to
  suppress, which made "the field was not sent" mean "never contact this
  customer" — so a client dropping an inert field would have silently stopped
  delivering every voucher it issued: issued fine, code in the API response,
  nothing reaching the holder. **This must deploy BEFORE any client stops
  sending the field.** Pinned by
  `NotificationGatewayTest.noChannelAtAll_STILL_DELIVERS` and
  `VoucherIssuedStatusTest.issuingWithNO_CHANNEL_deliversAndStampsTheAttempt`.
- **`NONE` still suppresses and is now the only value that does anything.** It
  is what bulk stock and POS printing use. Note bulk does not rely on it —
  `issueBulk` has no assignee, and the gateway returns early on a blank phone —
  which is why `bulkStock_withNoChannelAtAll_isStillUndispatched` proves the
  quiet comes from the missing phone rather than the channel.
- **Every constant stays on the enum.** `delivery_channel` is
  `@Enumerated(EnumType.STRING)` and historical rows hold all six; deleting one
  throws per row at query execution with no compile, boot or CI signal — the
  same rule that keeps `VoucherType.MULTI_USE` after V49. They are accepted and
  ignored, not a menu. The request `@Schema`s now advertise `NONE` alone.
- **`deliveredAt` got stricter in the same change, and this is a fix not a
  side effect.** It was stamped whenever a channel was set, including for a
  voucher with NO reachable phone — where the gateway returns early logging
  *"still issued"* while the column asserted a dispatch. That is precisely the
  lie V48 retired the DELIVERED *status* for, surviving one field over. It now
  stamps only when a send is genuinely attempted: not `NONE`, and a holder phone
  present.

## Vouchers are PAID FOR before they exist (V47)

**Owner decision (2026-09-17): the console's issue flow collects payment
first.** `voucher_purchase_orders` snapshots one issue request (V46 sender
identity AND the creating caller's JWT identity — the confirmation arrives S2S
with no caller context) plus the money to collect; the voucher is minted only
at confirmation, through the SAME `createVoucher` path as a direct issue
(fees, FX freeze, expiry-from-rules, recipient WhatsApp + sender copy all ride
along via `VoucherService.issueFromOrder` → `finishIssue`).

- **Flow:** `POST /loyalty/vouchers/purchase` (staff, same object-level authz
  as issue) → pay: electronic rails via ticketing payment-service's
  `POST /payments` with `orderType=LOYALTY_VOUCHER` + the `orderRef`
  (`VCH-<12 hex>`; InnBucks 2D code+QR default, `ECOCASH` PIN prompt,
  `ZIMSWITCH_CARD` widget), or CASH via
  `POST /loyalty/vouchers/purchase/{ref}/confirm-cash` (staff-only — the
  cashier's confirmation IS the payment proof, and WHO confirmed is recorded
  in `cash_confirmed_by`) → console polls `GET .../​{ref}` until `PAID`, which
  carries the issued voucher. **`POST /loyalty/vouchers/issue` remains** for
  direct/promotional issuance — the payment gate is a business flow, not a
  security boundary (staff who can issue could always issue).
- **Everything issue would refuse is refused at CREATE**, on the staff caller
  — never after the customer paid: type/usageLimit contract, currency
  allowlist, in-force FX rate (`NO_FX_RATE` probed at creation), positive
  whole-cent value (`AMOUNT_PRECISION` — the rails collect integer cents).
  `payerPhone` (the EcoCash prompt target) defaults sender → assignee and is
  required (`PAYER_PHONE_REQUIRED`).
- **The S2S surface** (`/loyalty/internal/voucher-orders/{ref}` get /
  `extend-expiry` / `confirm-payment`) mirrors marketplace-service's internal
  order endpoints so payment-service's `LoyaltyVoucherOrderGateway` is a
  near-clone of its marketplace one. Loyalty serves DECIMAL major units —
  payment-service's gateway is the major↔minor conversion point, per its
  OrderGateway contract. Confirm is idempotent by `paymentRef`; a different
  ref on a paid order is 409, a cents mismatch is **422 AMOUNT_MISMATCH**
  (the 100x guard's confirm leg), and the voucher is issued IN the confirm
  transaction — a 200 means the voucher exists, and an issue failure rolls
  the PAID flip back for payment-service's retry sweep.
- **Expiry is LAZY and asymmetric, deliberately.** Nothing is reserved by an
  order, so no sweeper: past `expires_at` it just stops being payable /
  extendable / cash-confirmable (create a fresh order). But a LATE
  `confirm-payment` on an expired-yet-uncancelled order is HONOURED — the
  customer's money has already moved, and refusing would strand them on an
  operator queue for a bookkeeping deadline. Cash on an expired order IS
  refused: that money is being taken now, and a fresh order costs nothing.
  Pinned by `confirmPayment_lateButUncancelled_isStillHonoured` /
  `confirmCash_onAnExpiredOrder_isRefused`.
- **Double-payment guards:** confirm-cash on an order already paid
  electronically is 409 (`ORDER_ALREADY_PAID`) and vice versa; both
  confirmation writers take a pessimistic lock (`lockByOrderRef`) so a cash
  confirm racing a gateway confirm cannot issue two vouchers.
- Config: `loyalty.voucher.purchase-order-ttl` (`LOYALTY_VOUCHER_ORDER_TTL`,
  default PT30M); payment-service extends the window past its instrument TTL
  via extend-expiry (1..60 min, never shortens). No gateway route changes:
  `/loyalty/vouchers/purchase/**` rides `loyalty-service-route`, the internal
  surface is already covered by `loyalty-internal-deny`.

## A voucher is ISSUED until it is used — there is no DELIVERED (V48)

**Owner decision (2026-09-17): `DELIVERED` is merged into `ISSUED`.**
`Voucher.Status` is now `ISSUED, VIEWED, REDEEMED, PARTIALLY_USED, EXPIRED,
REVOKED`.

- **Why it had to go.** `finishIssue` flipped the status to DELIVERED
  SYNCHRONOUSLY at save time, before the `@Async` WhatsApp/SMS send ran, and
  never revised it — so a voucher whose WhatsApp failed AND whose SMS fallback
  failed still read DELIVERED, as did one with no reachable phone (the
  gateway's own log line for that case says *"still issued"* while the row said
  otherwise). It reported an intention as an outcome. And because every
  voucher issued to a named person carries a channel, the flip was immediate
  and universal: a single-issued voucher **never spent a moment in ISSUED**, so
  the console's ISSUED tab was permanently empty except for bulk stock (which
  `/issue-bulk` never attempts to deliver at all). Two statuses, one meaning,
  one of them misleading.
- **`vouchers.delivered_at` STAYS and is still stamped** — the honest version
  of what DELIVERED reached for ("dispatch was ATTEMPTED at T"), already
  surfaced as `deliveredAt` on the report row DTO and the report CSV. It is
  stamped **only inside the same channel guard** that used to set the status;
  do not "simplify" that away, or the column starts asserting a dispatch for
  `NONE`/POS vouchers that were never sent anywhere — the same lie, relocated.
  Pinned by `VoucherIssuedStatusTest.issuingWithChannelNONE_*`.
- **Do NOT reintroduce a delivery state on `Status`.** An outbound-message
  outcome is not a stage of a voucher's life. If delivery *confirmation* is
  ever wanted it needs its own column fed by a gateway receipt (and a resend
  path — there is none today), not a lifecycle value set optimistically.
- **`Voucher.LIVE_STATUSES` is now the ONE definition of an outstanding
  voucher** (`ISSUED, VIEWED, PARTIALLY_USED`). That set had been copy-pasted
  into six places — three lookups in `VoucherService`, the report's
  `OUTSTANDING` filter, the expiring-soon query, `PublicTestController` — which
  is exactly why retiring one value touched twelve files. The two JPQL `IN`
  lists in `VoucherRepository` can't reference a constant and carry
  change-together NOTEs instead — and `VoucherLiveStatusJpqlTest` turns those
  notes into an enforced invariant, reading the status names straight off the
  two `@Query` annotations and failing on the commit that changes the constant
  without them. That matters because a stale list there fails at BOOT (Spring
  Data validates a declared `@Query` when the repository bean is created), which
  in this repo is only reachable from a Docker-backed `@SpringBootTest` and so
  only in CI — and a list left merely *wrong* rather than unparseable boots fine
  and silently returns the wrong rows. `ReportingService.OUTSTANDING` is
  `EnumSet.copyOf(LIVE_STATUSES)`, derived rather than restated.
- **`LIVE_STATUSES` is NOT the transfer guard, and must not become it.**
  Transfer eligibility (`VoucherService.transfer`) is deliberately narrower —
  `ISSUED` or `VIEWED` only, excluding `PARTIALLY_USED` — because transferring a
  part-used voucher would split one voucher's value across two holders. "Is this
  voucher outstanding" and "may this voucher change hands" are different
  questions; the invitation to unify them is the one real trap this cleanup
  created. Pinned by `VoucherTransferTest.aPartiallyUsedVoucher_cannotBeTransferred`.
  Same shape one level down: `markViewed`'s `if (status == ISSUED)` now reads
  like a tautology with only one pre-view status left, but it is what stops a
  `PARTIALLY_USED` voucher being downgraded to `VIEWED` by a later view event.
- **`markDelivered(UUID)` is deleted.** It had no endpoint and no caller —
  nothing ever promoted a voucher to DELIVERED after the fact, which is part of
  why the status could only ever mean "we tried".
- **`?status=DELIVERED` is still ACCEPTED on input**, as an alias for ISSUED —
  `VoucherStatusConverter`, counted by `loyalty.voucher.status.legacy_alias`.
  Seven endpoints bind a `Voucher.Status` request param, and the console ships
  a DELIVERED filter tab today, so without the alias a backend deploy would
  break that tab until a separate frontend release landed — and, per the
  handler note below, break it as an opaque **500**, not a 400. The alias
  returns the right rows (V48 rewrote them), and the service NEVER emits the
  value.
  **Registering that converter REPLACES Spring's default enum binding**, so it
  must keep handling every live value — a gap there would 400 a request that
  used to work; `VoucherStatusConverterTest` iterates the whole enum for that
  reason. Delete the converter, the counter and the test once the meter
  flatlines in production.
- Client-visible fallout beyond the alias: the report's `byStatus` /
  `countByStatus` maps simply stop emitting a `DELIVERED` key, so a console
  rendering a fixed column list shows an empty column rather than erroring.

### A mistyped request parameter was a 500, fleet-wide on this service

Found while measuring what the retired `?status=DELIVERED` would actually have
returned. **`GlobalExceptionHandler`'s `@ExceptionHandler(Exception.class)`
catch-all shadows Spring's own status mapping**, because the
`@ExceptionHandler` resolver is consulted BEFORE
`DefaultHandlerExceptionResolver`. So `MethodArgumentTypeMismatchException` —
Spring's own 400 — was being answered *"Something went wrong on our end. Please
try again."* with a **500**, on every endpoint in the service, for any
unconvertible query param or path variable (`?status=FOO`, a non-UUID id,
`?page=abc`). A client error read as a service fault and invited a retry that
could never succeed.

- **It is now a 400** naming the parameter, and for an enum target the values it
  accepts. The rejected value is deliberately NOT echoed (caller-controlled →
  reflected into the body) and the conversion cause is logged, not returned —
  the narrow, type-bound version of the `IllegalArgumentException` handler this
  file's catch-all notes as removed for leaking library messages.
- **A BLANK value was a 500 by a second, separate route, and that one has a real
  client trigger.** `?status=` (a filter UI's "All" tab — a natural thing for a
  console to send) does not reach the handler above at all: an empty string
  converts to `null` for an enum target on BOTH paths — Spring's converter
  factory returns null outright, and `TypeConverterDelegate` reaches the same
  answer for a custom converter by catching its refusal and applying its
  empty-enum-identifier rule — and then
  `RequestParamMethodArgumentResolver` rejects a required parameter that *"is
  present but converted to null"* with `MissingServletRequestParameterException`.
  Also now a 400, with a message distinguishing **blank** from **absent**,
  because the two need different fixes. **`VoucherStatusConverter` is not the
  cause and returning null for blank would not help** — the default binder
  produced the identical exception before the converter existed; it would only
  make an unknown value and a blank one behave alike. Pinned by
  `aBlankStatusIs400_andSaysWhatToDoAboutIt`.
- **This class of bug is invisible to a unit test of the handler**: the defect
  was in *which* handler Spring picks. `GlobalExceptionHandlerDispatchTest`
  therefore goes through real dispatch (standalone MockMvc + the advice), and it
  is the pattern to copy — it fails with `expected:<400> but was:<500>` the
  moment the handler is removed, which is how the 500 was confirmed rather than
  assumed.
- **The three handlers above it exist for the same reason** and each says so
  (`ResponseStatusException`, `HttpMessageNotReadableException`,
  `NoResourceFoundException`). Treat the catch-all as *hostile to Spring's
  defaults*: when adding an endpoint whose failure mode is a standard Spring MVC
  exception, check there is a handler for it, or it will 500.
- **The whole shadowed family is now mapped**, each measured at 500 first and
  each with a handler carrying its own rationale comment: a mistyped parameter
  or path variable (400), a blank or absent required parameter (400), the rest
  of the request-binding family via `ServletRequestBindingException` — missing
  header, cookie, matrix variable (400; no live caller, since nothing in
  `src/main` declares a `@RequestHeader`, so it is there to make the next one
  correct by default), and **the wrong HTTP verb on a real path (405)**. The
  405 sets `Allow` from OUR mapping, because a 405 without it tells the caller
  they were wrong and never what would be right; the attempted method is a
  caller-controlled token and is deliberately neither echoed nor named.
- **The durable rule, which outlives this list: treat that catch-all as hostile
  to Spring's defaults.** It is a `@RestControllerAdvice`, and the
  `@ExceptionHandler` resolver runs before `DefaultHandlerExceptionResolver`, so
  every standard Spring MVC exception without its own handler here becomes a 500
  no matter what Spring would have returned. When you add an endpoint whose
  failure mode is one of those, check for a handler — and add the test to
  `GlobalExceptionHandlerDispatchTest`, not to the unit test, because the defect
  is never in the handler's body.

## Voucher redemption binds to the VOUCHER's holder, never to the request

**Voucher redemption is not new** — `POST /loyalty/vouchers/redeem` →
`VoucherService.doRedeem`, and `voucher_redemptions` dates to `V1__init.sql`.
V45–V48 changed only issue-side concerns and left it untouched, which is how
three of its guards came to guard nothing. The rules below are what they now do.

- **The holder is resolved from the voucher, by ONE method, with ONE
  precedence.** `holderPhone` is the assignee phone, else the assigned user's
  phone. It used to be called `resolveDeliveryPhone` and be consulted only when
  choosing where to send the code, while both ownership checks compared the
  caller against the raw `assigneePhone` column — **and the two disagree about
  what counts as "no phone"**: `holderPhone` treats a BLANK phone as absent and
  falls back to the assigned user's number, a bare column comparison does not.
  So a voucher issued with an explicitly blank `assigneePhone` alongside an
  `assignedUserId` was DELIVERED to that user and then refused to that same
  user, the check comparing their live phone claim against `""`.
  `createVoucher`'s backfill tested `== null` only, which is how a blank
  survived; it now normalises blank too, and resolving through `holderPhone`
  covers the rows already written that way.
  **Be exact about the trigger** — an earlier draft of this section claimed
  `createVoucher` "never backfills" the phone and that a voucher issued by user
  id ALONE was refused. It does backfill (`VoucherService`, in the
  `assignedUserId != null` branch), so that shape is unreachable through any
  issue path and only a legacy row can hold it. The blank is the reachable one.
- **`holderAccount` must share `holderPhone`'s precedence**, and its first draft
  did not: it preferred `assignedUserId` while `holderPhone` prefers the
  assignee phone. On a voucher carrying BOTH — which the issue API allows
  without cross-validating that they name the same person — the ownership check
  then admitted the phone's owner while the account gate inspected the id's
  owner, so a blocked holder walked through the gate that exists to stop them.
  Whatever order is chosen, one order. Pinned by
  `VoucherRedemptionGuardsTest.theOwnershipCheckAndTheAccountGateAreAboutTheSAMEPerson`.
- **A voucher with NO holder is not redeemable by a customer bearer.** Resolving
  the holder must not turn "nobody owns this" into "everybody owns this": bulk
  stock has nothing to match, so a customer is refused and only a till redeems
  it.
- **The account gates read the voucher's holder, not `req.userId()`.** Both
  BLOCKED and registration checks used to sit inside `if (req.userId() != null)`
  — a nullable, unvalidated body field never compared to the voucher's own
  holder. That made them **opt-in for the caller**: a till posting only the code
  performed no account checks at all, and naming any unrelated ACTIVE account
  satisfied them. `userId` is still recorded, as a CLAIM, exactly like
  `fraud_attempts.user_id`.
- **`UserService.spendabilityOf` is the ONE spend decision; only the wording is
  local.** The voucher gate had hand-rolled its own branch and drifted three ways
  from `requireSpendable` — no PENDING heal, no V44 on-demand eligibility check,
  and **no INACTIVE refusal at all**, so an operator-deactivated holder could
  still redeem. It now delegates. The copy stays separate deliberately: the
  points wording says points "keep accruing", which is meaningless read aloud to
  someone holding a gift voucher, and `VoucherController`'s 403 docs promise
  `message` is customer-safe. Add a third spend gate and it delegates too.
- **Redeem runs object-level merchant authz for STAFF callers**
  (`requireCallerAdministersMerchant`), mirroring issue. `requireMerchant` only
  proved the merchant existed in the tenant, so a caller with no `merchantId`
  claim to pin it — a multi-merchant MERCHANT_ADMIN, deliberately given none —
  could name a merchant it does not administer and burn that merchant's
  voucher. **A non-staff caller keeps `requireMerchant`, and that branch is
  load-bearing**: a CUSTOMER redeeming their own voucher administers no
  merchant, and `PublicTestController.asCustomer` installs no authentication at
  all for unassigned bulk stock — so requiring administration of either would
  have refused every self-redeem and the whole `/loyalty/public/**` surface
  `NOT_MERCHANT_OWNER`. The first draft of the change did exactly that. Those
  callers are already pinned by the holder check plus `WRONG_MERCHANT`.
  Side effect, **staff-only**: for a staff caller a cross-tenant merchant id is
  now `404` rather than `403 CROSS_TENANT`, which is the no-existence-oracle
  behaviour the rest of the service already had; the non-staff branch still
  throws `403 CROSS_TENANT` from `MerchantService.requireMerchant`. All three
  shapes — staff refused, customer self-redeem, unauthenticated bulk stock —
  are pinned by `VoucherRedemptionGuardsTest`.
- **REVOKED is checked before exhaustion.** Clients branch on `code`, and the old
  order made a voucher an operator had cancelled after its last use report
  itself as merely spent.

### A REJECTED redemption row cannot be written where it is decided

**`voucher_redemptions` could only ever hold SUCCESS rows.** Six refusal
branches carefully wrote `Result.REJECTED` and then threw, from inside a
class-level `@Transactional` service — so every one rolled back with the refusal
it was documenting. So did the `EXPIRED` status flip the controller's Swagger
promises.

- **It cannot be fixed by copying `FraudService.record`'s `REQUIRES_NEW`**, which
  solves the identical problem one line away, and the reason is a schema detail:
  `fraud_attempts` has **no foreign key**, while
  `voucher_redemptions.voucher_id REFERENCES vouchers(id)` does. The refusing
  transaction holds a `PESSIMISTIC_WRITE` lock on that voucher row
  (`lockByCode`), and Postgres takes a `FOR KEY SHARE` lock on the parent to
  validate the FK — which conflicts. A second transaction would block on a lock
  only the first can release while the first waits for it to return, and Postgres
  cannot break it as a deadlock because from its side the outer session is merely
  idle in transaction. **It would hang the redeem, not fail it.** Same reasoning
  forbids a `REQUIRES_NEW` update for the EXPIRED flip.
- **So refusals publish** `VoucherRedemptionRejectedEvent` and
  `VoucherRedemptionAuditWriter` writes the row on
  `@TransactionalEventListener(AFTER_ROLLBACK)` + `REQUIRES_NEW` — after the lock
  is released and while the parent row still exists. Mirrors this repo's existing
  `InvoiceGeneratedEvent` / AFTER_COMMIT pattern. Nothing in the listener may
  escape: a refusal that could not be recorded is a WARN and still a refusal.
- **That last promise needed `saveAndFlush` to be true, and the catch has to
  abandon the transaction.** `VoucherRedemption.id` is `@GeneratedValue` on a
  UUID, so Hibernate assigns it in memory and a plain `save` issues **no SQL** —
  the INSERT defers to the flush at commit, which happens in the transaction
  interceptor and therefore OUTSIDE the try. A constraint violation could not
  reach the handler written to handle it, so the documented WARN never fired and
  a stack trace escaped to the framework instead. Flushing in-method puts the
  failure back in reach; the catch then calls `setRollbackOnly()` on this
  transaction's OWN status, which is a **local** rollback and so rolls back
  quietly, where a globally-marked participating transaction would raise
  `UnexpectedRollbackException` on the way out and re-open the same hole.
  `VoucherRedemptionAuditWriterTest` pins the flush, both failure paths, the
  listener phase and the propagation — the class shipped with **no test at all**,
  which is how a promise that was never kept read as kept for a whole PR.
- **The listener must stay synchronous.** `VoucherController`'s Swagger says the
  EXPIRED flip lands BEFORE the refusal is returned, which is true only while
  this runs inside the redeem call — Spring invokes an AFTER_ROLLBACK listener
  from the rollback processing, still inside the service proxy. (That Swagger
  line previously said the opposite, promising clients a window in which a
  re-read might still show the old status. There is no such window.)
- **The SUCCESS row stays inside the transaction**, and must: if the redemption
  rolls back, the row saying it happened has to roll back with it. The two halves
  are mirror images, not an inconsistency.
- **The EXPIRED flip rides the same event**, applied through
  `VoucherRepository.markExpiredIfDue`, whose predicate re-checks the deadline
  and the live-status list so it can only ever write a fact that is already true
  and can never clobber a REDEEMED/REVOKED transition that landed in between.
  Idempotent and safe to lose — the expiry sweeper converges anything missed.
  It shares the audit row's transaction, so a failed audit write loses the flip
  too; that is the accepted trade, since splitting it into a second transaction
  buys a guaranteed status update at the price of a second failure mode on an
  error path that must stay simple.

### The redeem request's free-text fields are bounded, and were not

`outletCode`, `deviceFingerprint` and `ipAddress` on `RedeemVoucherRequest` are
written verbatim into `VARCHAR(80)` / `(128)` / `(64)` and carried **no
`@Size`**. (`code` joined them with `@Size(max = 64)`: an unknown code is
recorded — normalised — in `fraud_attempts.voucher_code VARCHAR(64)`, and
`VoucherCodes.normalize` never lengthens its input, which is what makes the
bound hold after normalisation.) An over-long value was caught nowhere until the INSERT, so a
**legitimate** redemption became a `500` with the burn rolled back — the client
told the server had broken when its own request was at fault, and invited to
retry something that could never succeed. It is now a `400` naming the field.

- **This is the same family as the `GlobalExceptionHandler` catch-all above**: a
  client error surfacing as a service fault. Different mechanism — a column
  width rather than a shadowed handler — same misdiagnosis for whoever is
  paged.
- **Keep each `@Size` in lock-step with its column.** They are a pair; widening
  one without the other restores the 500.
- The public surface was never exposed: `PublicTestController` passes `null` for
  all three.

**Still open, and needing a platform-owner decision rather than a patch:**
`redeemedAt` is stamped only at exhaustion, so a partially used voucher is never
billed a redeem-side fee and never counted as redeemed. The false javadoc that
claimed otherwise (`sumRedeemedValueByMerchantId`, `Dtos.VoucherSummary`) is
corrected. The other half of that pair — the full face value returned on every
use — was decided and is below.

## Voucher codes: 16 digits, a check digit, raw everywhere a machine reads

**Owner decision (2026-09-25): a voucher code is 16 digits, shown to people in
groups of four** (`9087 8765 9876 4566`), replacing the 12-character
alphanumeric. All the rules live in `util/VoucherCodes`; nothing else may
invent its own.

- **Format.** First digit 1–9 (a spreadsheet drops a leading zero), fourteen
  random digits, then a check digit = (Luhn digit + 5) mod 10 —
  `CryptoSigner.randomNumericVoucherCode`. 9×10¹⁴ codes. The check digit
  catches every single mistyped digit and every adjacent swap except 09↔90.
  **The +5 offset is load-bearing, not decoration:** it guarantees no code
  ever passes the plain Luhn test, because a Luhn-valid 16-digit number is,
  to every PCI/DLP scanner in mail, file shares and messaging gateways, a live
  card number — exports get quarantined and codes masked in WhatsApp. About
  2.3% of unconstrained random codes did. **Never change `LUHN_OFFSET`**: it
  invalidates the check digit of every issued code.
- **RAW at rest and on every machine surface.** The column, the API JSON, the
  HMAC signature payload (`signPayload`) and S2S bodies all carry the code
  exactly as stored. **Grouping is for text a PERSON reads, and only
  that**: `display()` (spaces) in WhatsApp/SMS copy, `forExport()` (hyphens)
  in the CSV. Putting a grouped code in a signature payload or an API field
  would break verification or every client's lookup.
- **Why the CSV uses hyphens, not spaces or raw.** Spreadsheets hold 15
  significant digits, so a raw 16-digit code opened from a CSV silently loses
  its last digit and never redeems. Spaces are a digit-grouping symbol in
  some locales (en-ZA, fr-FR), so a lenient parse can still read a
  space-grouped code as a number. No locale groups with a hyphen.
- **Every lookup by a typed code goes through `VoucherService.findByTypedCode` /
  `lockByTypedCode`**: the normalised form first, then the input exactly as
  typed (stripped). `normalize()` removes whitespace of every kind (including
  NBSP, which `isWhitespace` misses), dashes of every kind plus U+2212, and
  invisible format characters, and upper-cases **ASCII only** — full Unicode
  case mapping lengthens `ß`→`SS`, which would overflow the fraud column. The
  exact-as-typed second probe exists for rows whose stored code is not in
  canonical form (hand-made or pre-extraction rows with a hyphen or lower
  case); without it they could never be redeemed again.
- **Never run any of this on another identifier.** A tenant code, a `VCH-`
  purchase-order reference and a QR token are different things; normalising
  or grouping them breaks them. `display()`/`forExport()` also return any
  stored code that is not plain `[A-Z0-9]+` UNCHANGED, so a legacy
  non-canonical code prints as stored instead of as something that matches
  no row.
- **Legacy 12-character codes stay valid forever** — they are rows, not a
  format we can migrate (the code is inside the HMAC signature). They group
  and normalise the same way; they simply have no check digit, so
  `isWellFormedNumeric` returns false for them and that must never be read as
  "typo".
- **Never log a voucher code.** It is a bearer credential. Log the voucher id.
- **JavaScript clients must keep `code` a string** — 16 digits exceed 2^53.
  The Swagger descriptions and the FE guide both say so.

## A voucher is worth its face value, ONCE — MULTI_USE is retired

**Owner decision (2026-09-18).** Asked whether a $5 voucher with 3 uses hands
the till $5 three times or $5 once, the answer was once — and that the type
should go rather than grow a drawdown balance.

- **Why it could not stay as it was.** `vouchers.value` is a face AMOUNT and
  `uses_remaining` a bare counter; there is no remaining-value column, and
  `RedeemVoucherRequest` carries no amount. `doRedeem` decremented the counter
  and returned `v.getValue()` unchanged, so a MULTI_USE voucher told the till
  its full face value on **every** use. The liability frozen at issue
  (`base_value`) is ONE face value, and the redeem-side fee is charged once, so
  the money model only ever described a single-use voucher.
- **Issuing one is REFUSED, not silently downgraded** (`MULTI_USE_RETIRED`). A
  caller asking for three uses has priced something; quietly giving them one is
  the kind of change that surfaces at a till. `usageLimit` is retired with the
  type — anything but 1 is `USAGE_LIMIT_CONFLICT`.
- **One gate, all three issue paths.** `VoucherService.resolveUsageLimit` is
  where the rule lives, and `VoucherPurchaseService.create` runs it at order
  creation, so a customer is never asked to pay for a voucher issue would refuse.
- **The outstanding stock was COLLAPSED in V49** — operator's call, the cell
  being in test phase. Refusing to MINT one was only half the retirement, and
  the half that does not hold the money: a live MULTI_USE row went on paying its
  full face value per use whatever the issue endpoint accepted, so a "$5, three
  uses" voucher stayed a $15 liability. V49 gives every **live** MULTI_USE
  voucher exactly one use, retypes every row SINGLE_USE, and narrows
  `chk_vouchers_voucher_type` to the one value.
  - **A part-used voucher keeps its one remaining use** rather than being
    treated as already finished. The kinder of the two readings, and the cost is
    stated rather than hidden: such a voucher will have paid its face value
    twice across the two regimes.
  - **Nobody is compensated for lost uses.** Collapsing the uses INTO the value
    (3 × $5 → one $10) was considered and rejected: it mints face values nobody
    issued, moves the outstanding liability, and would have to re-freeze
    `base_value`/`fx_rate_id` at today's rate — where V38's whole point is that
    a voucher's USD worth is fixed when the promise was made.
  - **Terminal rows (REDEEMED / EXPIRED / REVOKED) keep their counters**; only
    the type is rewritten. The counter is history, and the type describes
    semantics that no longer exist.
  - **The UPDATE runs BEFORE the CHECK narrows** — the EnumType.STRING rule
    above, in its other direction: narrowing first fails the ALTER on every
    surviving row and the migration cannot apply at all.
  - `VoucherLiveStatusMigrationTest` ties V49's status list to
    `Voucher.LIVE_STATUSES`, the third hand-spelled copy after the two JPQL
    ones. The stakes are higher here: a migration applies ONCE, so a status
    missing from that list is not a bug a later edit can fix — it is a set of
    live vouchers silently skipped on every cell, permanently. Adding a live
    status needs a NEW migration; never edit V49.
- **`Voucher.VoucherType.MULTI_USE` STAYS on the enum and must not be deleted**,
  even though V49 leaves no row holding it. The reason is hydration safety, not
  live stock: `voucher_type` is `@Enumerated(EnumType.STRING)`, so a row holding
  a string the enum lacks makes Hibernate throw per row at query execution, with
  no compile, boot or CI signal (the rule above). A restore from a pre-V49
  backup, a lagging replica, or any row written before the migration would take
  out every read path touching it. The constant costs nothing; deleting it buys
  tidiness and risks an outage.

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
  is wrong again. **This repo is now PUBLIC** (it was private when the gate was
  written), so the job no longer self-skips — the auto-re-enable has already
  fired and `dependency-review` runs here normally, as it does on
  `ticketing-system`. The gate and `continue-on-error` stay: they cost nothing
  while public and are what stops every PR reddening if the repo is ever made
  private again.

  **Being public is worth remembering when you write a test fixture or an
  example.** Anything committed here is world-readable, and git history keeps it
  after a later scrub. A stub transcribed verbatim from a live response once put
  a real customer's name and bank account number in this repo on exactly that
  basis — see `InnbucksSessionClientContractTest`, where the shape is real and
  the values are now placeholders.

## Local build (no Docker in some sandboxes)

`@SpringBootTest`/Testcontainers need a Docker daemon; where absent, write
pure-JUnit tests (Validator, WireMock, Mockito) and let CI run the
container-backed ones. Compile/verify a single test with
`./mvnw -Dtest=<Test> -DfailIfNoTests=false test`.
