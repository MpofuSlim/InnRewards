# InnRewards — Loyalty & Voucher Management Platform (LVMP)

Multi-tenant loyalty, points, voucher and QR platform. Extracted from the
`MpofuSlim/ticketing-system` monorepo into its own repository — loyalty is
independent of ticketing and is meant to be reused across products.

The service is unchanged by the extraction: it still registers with the same
Eureka registry as **`loyalty-service`**, is routed by the ticketing API
gateway at `/loyalty/**` **by service name** (not by repo), and still publishes
the same container image **`ghcr.io/mpofuslim/loyalty-service`**. Front-end and
gateway wiring are untouched.

## Runtime shape

- **Spring Boot 4.1** (webmvc), Java 21.
- **Postgres** owns the schema via **Flyway** migrations
  (`src/main/resources/db/migration`, V1–V27), `ddl-auto: validate`.
- **Eureka client** for service discovery; siblings resolved by name via Spring
  Cloud LoadBalancer.
- **Redis** — read side of the shared cross-service logout-token denylist.
- HTTP port **8086** (`SERVER_PORT`), actuator health at `/actuator/health`.
- Timestamps are `Instant` (UTC-inherent); containers also pin
  `-Duser.timezone=UTC`.

## Building

```sh
./mvnw -B -ntp verify          # unit + integration tests (Testcontainers → Docker)
./mvnw -B -ntp -DskipTests package spring-boot:repackage   # fat jar
docker build -t loyalty-service .                          # container image
```

Integration tests (`*IT.java`) use Testcontainers and need a Docker daemon; CI
runs them. On a Docker-less box, run the pure-JUnit tests only.

## Required secrets (production profile)

`ProductionSecretsGuard` refuses to boot under a deployment profile (any active
profile set that contains no `dev`/`test`/`it`/`local` profile — including an
empty set) while any of these still hold their `change-me` placeholder:

| Env var | Property | Purpose |
|---|---|---|
| `JWT_SECRET` | `jwt.secret` | Verifies user-service-minted HS256 tokens (shared fleet secret). ≥ 32 chars. |
| `INTERNAL_API_TOKEN` | `innbucks.internal-api-token` | `X-Internal-Token` shared secret for `/loyalty/internal/**`. |
| `LOYALTY_VOUCHER_SECRET` | `loyalty.voucher.secret` | HMAC key sealing voucher payloads. |
| `LOYALTY_QR_SECRET` | `loyalty.qr.secret` | HMAC key sealing QR payloads. |
| `REDIS_PASSWORD` | `spring.data.redis.password` | Redis auth (denylist + rate-limit state). |

Generate each per cell with `openssl rand -base64 48`.

## CI/CD

- **CI** (`.github/workflows/ci.yml`): `./mvnw verify` + JaCoCo summary, plus a
  diff-scoped `dependency-review` SCA gate on PRs.
- **Release** (`.github/workflows/release.yml`): build → **Trivy** scan
  (CRITICAL/HIGH, os+library, gates the push) → push to GHCR → **SLSA
  build-provenance attestation + SBOM**. All third-party actions are SHA-pinned.
- **Dependabot**: `maven`, `docker` (root Dockerfile), and `github-actions`.

Verify a deployed image digest:

```sh
gh attestation verify oci://ghcr.io/mpofuslim/loyalty-service@<digest> \
  --repo MpofuSlim/InnRewards
```

## Deployment

Loyalty runs in the shared `ticketing` k3s cell today, so its k8s
Deployment/Service manifests remain in `ticketing-system` under `deploy/k8s/`
(they reference the shared `cell-zw` ConfigMap/Secret and namespace). This repo
only builds and publishes the image; the operator rolls it with:

```sh
kubectl -n ticketing rollout restart deployment/loyalty-service
kubectl -n ticketing rollout status  deployment/loyalty-service
```

See `CLAUDE.md` for the full conventions inherited from the monorepo.
