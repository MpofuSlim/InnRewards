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
Flyway, `ddl-auto: validate`). Current head is **V27**; never edit an applied
migration — add the next version.

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
  (`github.event.repository.visibility == 'public'`) because the action needs
  GitHub's Dependency Graph, which on a **private** repo requires paid GitHub
  Advanced Security — without it the action hard-errors and reds every PR. This
  repo is currently private without GHAS, so the job self-skips here; it
  auto-re-enables if the repo goes public or GHAS is licensed. This drops only
  the PR-time *direct-dependency* advisory surface — transitive/library CVEs
  remain covered by the Release workflow's Trivy image scan. (The public
  `ticketing-system` repo runs this job normally.)

## Local build (no Docker in some sandboxes)

`@SpringBootTest`/Testcontainers need a Docker daemon; where absent, write
pure-JUnit tests (Validator, WireMock, Mockito) and let CI run the
container-backed ones. Compile/verify a single test with
`./mvnw -Dtest=<Test> -DfailIfNoTests=false test`.
