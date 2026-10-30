# 15 — Deployment

The ledger ships as one container image (`Dockerfile`) and needs one MySQL 8.4 database. It is
an HTTP API for server-to-server use; there is no frontend to host. Any platform that runs
containers and offers MySQL 8.4 works (a VM with Docker Compose, Kubernetes, ECS, Cloud Run
with Cloud SQL, Azure Container Apps with Azure Database for MySQL, ...). GitHub Pages cannot
host it.

Nothing in this repository creates cloud resources. The steps below are what an operator does.

## 1. Build and publish the image

```bash
docker build -t ledger:1.0.0 .
```

The build compiles inside Docker; tests are run by CI (`./mvnw verify`), not in the image
build. The optional workflow [`.github/workflows/release.yml`](../.github/workflows/release.yml)
builds the image and pushes it to the GitHub Container Registry (`ghcr.io/<owner>/ledger`)
when a tag `v*` is pushed, using only the built-in `GITHUB_TOKEN`. It does not deploy anything.

The image runs as UID 10001, needs no writable filesystem except `/tmp`, listens on 8080 and
has a `HEALTHCHECK` on the readiness probe.

## 2. Provision the database

Requirements:

- MySQL 8.4, InnoDB, a database named `ledger` (utf8mb4 is the server default).
- `log_bin_trust_function_creators = 1` when binary logging is on (managed services usually
  enable it): the migrations create immutability triggers. On managed MySQL set it in the
  parameter group / server parameters.
- `max_connections` of at least 20 × number of application instances (Hikari pool size 20).
- Automated backups with point-in-time recovery. History is append-only, so a restore never
  needs to merge edits.

Create the two users once, as an administrator (the same statements as
`docker/mysql/init/01-users.sh`):

```sql
CREATE USER 'ledger_migrator'@'%' IDENTIFIED BY '<strong password>';
GRANT ALL PRIVILEGES ON `ledger`.* TO 'ledger_migrator'@'%' WITH GRANT OPTION;
CREATE USER 'ledger_app'@'%' IDENTIFIED BY '<another strong password>';
```

Do not grant anything to `ledger_app`: Flyway's `R__runtime_grants.sql` grants exactly the
per-table privileges it needs. Restrict `'%'` to the application's network where the platform
allows it, and require TLS to the database (`sslMode=REQUIRED` in the JDBC URL).

## 3. Configure

All configuration is environment variables (README, "Configuration"). In production:

| Variable | Value |
|---|---|
| `LEDGER_DB_URL` | `jdbc:mysql://<host>:3306/ledger?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&sslMode=REQUIRED` |
| `LEDGER_DB_MIGRATION_USER` / `_PASSWORD` | from the secret store |
| `LEDGER_DB_APP_USER` / `_PASSWORD` | from the secret store |
| `LEDGER_BOOTSTRAP_ADMIN_KEY` | set for the first start only, then remove it and keep the key in the secret store (or create a new admin key through the API and revoke the bootstrap one) |
| `SPRING_PROFILES_ACTIVE` | empty (Swagger UI and `/v3/api-docs` stay off) |
| `LEDGER_INTEGRITY_SCHEDULE_ENABLED` | `true` on exactly one instance, `false` on the others (the in-progress guard is per instance) |
| `JAVA_TOOL_OPTIONS` | optional; the image defaults to `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError` |

Secrets come from the platform's secret store (or GitHub Actions secrets in a deployment
workflow). They are never baked into the image (`.dockerignore` excludes `.env`) and never
logged; logs show at most an API key's non-secret prefix.

## 4. Migrations

The application runs Flyway on startup as the migration user, then opens its runtime pool as
the app user. Flyway takes a lock in `flyway_schema_history`, so instances starting together
are safe; one migrates and the others wait. Migrations are forward-only and never edited once
released. Roll back application code only to a version that understands the current schema.
`spring.flyway.clean-disabled` is true, so a misconfiguration cannot wipe the schema.

## 5. Network, TLS and probes

- Put a TLS-terminating load balancer or reverse proxy in front. API keys are bearer
  credentials: plain HTTP must not be reachable from outside.
- No CORS headers are sent, so browsers on other origins cannot call the API. It is meant
  for server-to-server clients.
- Probes: liveness `GET /actuator/health/liveness`, readiness
  `GET /actuator/health/readiness` (includes the database). Only `health` and `info` are
  exposed; details are hidden.
- Request ids: clients may send `X-Correlation-Id`; every response carries `X-Request-Id`.

## 6. Runtime behaviour

- **Logging:** ECS JSON on stdout with `requestId`, `correlationId` and `clientId`. Ship stdout
  with the platform's log agent; no files are written.
- **Shutdown:** on SIGTERM the server stops accepting requests and lets in-flight ones finish
  for up to 20 s. Give the platform a termination grace period of at least 30 s.
- **Sizing:** 512 MB–1 GB memory per instance is enough for the current feature set; the JVM
  uses 75 % of the container limit.
- **Contention:** lock waits time out after 5 s per statement; deadlocks and timeouts in
  transfers are retried three times, then answered with `503 RESOURCE_BUSY` and `Retry-After`.

## 7. Production checklist

- [ ] TLS in front of the service; HTTP not publicly reachable
- [ ] Strong, unique database passwords from a secret store; `ledger_app` has no manual grants
- [ ] TLS to MySQL; backups and point-in-time recovery enabled; restore tested
- [ ] `LEDGER_BOOTSTRAP_ADMIN_KEY` removed after the first start; admin keys rotated
- [ ] `SPRING_PROFILES_ACTIVE` empty
- [ ] Nightly integrity check enabled on one instance; alert on `Integrity check ... found` ERROR logs
- [ ] Termination grace period ≥ 30 s; readiness probe wired to the load balancer
- [ ] Dependabot PRs reviewed; CI green on the deployed commit

## Not provided yet

Metrics endpoint (`/actuator/prometheus`), rate limiting, an idempotency-record purge job and a
cluster-wide integrity-check lock are on the roadmap (Phase 11 and the Phase 5 leftovers).
