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

## Timestamps — UTC

Loyalty maps timestamps as `Instant`, which is always UTC. Containers also pass
`-Duser.timezone=UTC`. If you ever add a `LocalDateTime`, use
`LocalDateTime.now(ZoneOffset.UTC)`, never bare `.now()`.

## Schema changes (Flyway)

New schema goes in `src/main/resources/db/migration/V<N>__*.sql` (PostgreSQL +
Flyway, `ddl-auto: validate`). Current head is **V31**; never edit an applied
migration — add the next version.

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
