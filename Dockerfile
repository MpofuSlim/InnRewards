# syntax=docker/dockerfile:1.7
# Build from repo root:
#     docker build -f loyalty-service/Dockerfile -t ticketing-loyalty-service .

FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw
RUN for i in 1 2 3; do ./mvnw -B -ntp -version && break || sleep 15; done

COPY api-gateway/pom.xml api-gateway/
COPY user-service/pom.xml user-service/
COPY event-service/pom.xml event-service/
COPY seat-service/pom.xml seat-service/
COPY booking-service/pom.xml booking-service/
COPY payment-service/pom.xml payment-service/
COPY discovery-server/pom.xml discovery-server/
COPY loyalty-service/pom.xml loyalty-service/

RUN ./mvnw -B -pl loyalty-service -am -DskipTests dependency:go-offline || true

COPY loyalty-service/src loyalty-service/src
RUN ./mvnw -B -pl loyalty-service -am -DskipTests package spring-boot:repackage \
    && cp loyalty-service/target/loyalty-service-*.jar /workspace/app.jar

FROM eclipse-temurin:21-jre-alpine AS runtime
# Refresh the apk index + force-upgrade OpenSSL by name. Without naming the
# packages, Docker reuses a stale layer hash and the cached upgrade doesn't
# re-run when a new Alpine point release ships the fix.
# CVE-2026-45447 (HIGH) — libssl3 / libcrypto3 / openssl PKCS#7 / S/MIME
# signed-message handling. Fixed in alpine 3.5.7-r0.
# CVE-2026-2100 (HIGH) — p11-kit / p11-kit-trust NULL-deref via C_DeriveKey with
# specific NULL params. Fixed in alpine p11-kit 0.26.2-r0.
RUN apk update \
 && apk --no-cache upgrade libssl3 libcrypto3 openssl p11-kit p11-kit-trust
RUN addgroup -S app && adduser -S -G app app \
    && mkdir -p /app/data \
    && chown -R app:app /app
WORKDIR /app
USER app

COPY --from=builder --chown=app:app /workspace/app.jar /app/app.jar

EXPOSE 8086

# k8s-style probe: hits actuator/health from inside the container. Spring Boot
# returns 200 + {"status":"UP"} once the context is fully refreshed; before
# that, hits to /actuator/health return 503 and the orchestrator holds traffic.
# start-period gives the JVM + Flyway + Hibernate enough time to come up before
# failures start counting against retries.
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
    CMD wget --quiet --tries=1 --spider http://localhost:8086/actuator/health || exit 1

ENTRYPOINT ["java", "-Duser.timezone=UTC", "-jar", "/app/app.jar"]
