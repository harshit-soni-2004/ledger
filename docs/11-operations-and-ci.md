# 11 — Local Setup, CI/CD, Observability, Performance

## Local development (target state after Phase 1)

Prerequisites: JDK 21, Docker (Compose v2). Maven is provided by the wrapper.

```text
cp .env.example .env              # fill local-only passwords
docker compose up -d mysql        # MySQL 8.4, healthcheck, users created by init script
./mvnw spring-boot:run            # Flyway migrates as ledger_migrator; app runs as ledger_app
./mvnw verify                     # unit + integration tests (Testcontainers)
```

Optional later: `docker compose --profile app up` (app container) and
`docker compose --profile observability up` (Prometheus + Grafana).

### Docker Compose (planned shape)

| Service | Image | Notes |
|---|---|---|
| `mysql` | `mysql:8.4` | Port 3306 bound to localhost only; named volume; `--log-bin-trust-function-creators=1`; healthcheck `mysqladmin ping` (no credentials); init script `docker/mysql/init/01-users.sh`; host port overridable with `LEDGER_DB_HOST_PORT` |
| `app` (profile `app`) | built from `Dockerfile` | Waits for `mysql` healthy; env from `.env`; non-root user |
| `prometheus`, `grafana` (profile `observability`) | official images | Phase 11, optional |

### Configuration and secrets

| Variable | Purpose |
|---|---|
| `LEDGER_DB_URL` | JDBC URL incl. `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true` |
| `LEDGER_DB_APP_USER` / `LEDGER_DB_APP_PASSWORD` | Runtime (least privilege) |
| `LEDGER_DB_MIGRATION_USER` / `LEDGER_DB_MIGRATION_PASSWORD` | Flyway only |
| `LEDGER_BOOTSTRAP_ADMIN_KEY` | Seeds an ADMIN API key hash on first start when no active ADMIN exists (local/demo only; `lk_` + 43 base62 characters) |
| `LEDGER_REPORTING_ZONE` | Business-date time zone (default `Asia/Kolkata`) |
| `LEDGER_INTEGRITY_SCHEDULE_ENABLED` | Nightly integrity check on/off (default `true`; tests set `false`) |
| `LEDGER_INTEGRITY_CRON` | Spring cron for the nightly check, in the business zone (default `0 30 2 * * *`) |

Rules: `.env` is git-ignored; `.env.example` holds placeholders only; no secrets in
`application.yml`; secrets never logged; Gitleaks in CI.

### MySQL initialization and migrations

- The init script (runs once on an empty volume) creates the database, `ledger_migrator`
  (DDL) and `ledger_app` (DML per [03](03-database-schema.md#grants-local-and-ci)).
- Grants for tables created by later migrations are applied by a repeatable Flyway
  migration (`R__runtime_grants.sql`) executed as the migrator, so grants never drift from
  schema. The init script is `docker/mysql/init/01-users.sh` (shell, so credentials come
  from environment variables); Testcontainers mounts the same file.
- The application also reads `.env` directly via `spring.config.import`, so
  `./mvnw spring-boot:run` works without exporting variables.
- Flyway naming: `V<n>__<snake_case_description>.sql`; migrations are never edited after
  merge — fixes are new migrations. `flyway validate` runs on startup and in CI.

## Health checks

- `/actuator/health/liveness` — process alive.
- `/actuator/health/readiness` — DB reachable, Flyway complete, disk space OK.
- Graceful shutdown (`server.shutdown=graceful`, 20 s).

## Structured logging

- Boot's built-in structured logging (`logging.structured.format.console=ecs` or `logstash`).
- MDC fields: `requestId`, `correlationId`, `clientId`, `operation`.
- Log **IDs, outcomes, durations, error codes** — not request bodies, API keys, full bank
  account numbers, or raw CSV lines. Amounts appear in audit events (DB), not routine logs.
- One INFO line per financial operation outcome; WARN for retries and rejected imports;
  ERROR only for unexpected failures (with stack trace).

## Metrics (Micrometer → `/actuator/prometheus`)

| Metric | Type | Tags | Why |
|---|---|---|---|
| `ledger.transfer.duration` | Timer (p50/p95/p99) | `outcome` (posted, rejected, replayed, error) | Latency SLO hypothesis |
| `ledger.posting.failures` | Counter | `reason` | Unexpected posting errors |
| `ledger.idempotency.replays` | Counter | `operation` | Client retry behavior |
| `ledger.idempotency.conflicts` | Counter | `type` (reused, in_progress) | Client bugs |
| `ledger.lock.retries` | Counter | `operation`, `outcome` | Contention/deadlocks |
| `ledger.integrity.violations` | Gauge | `check` | Must be 0 |
| `statements.rows` | Counter | `status` | Data quality |
| `statements.import.failures` | Counter | `reason` | Rejected files |
| `recon.items` | Counter | `category` | Discrepancy mix |
| `recon.open_discrepancies` | Gauge | `bank_account` | Ops backlog |
| `recon.run.duration` | Timer | `outcome` | Run performance |

Start with Actuator + one Grafana dashboard (transfer latency, retries, integrity
violations, open discrepancies). Alerting rules are documented examples, not deployed.

## Performance (Phase 11) — hypotheses, not claims

| Hypothesis | Scenario | Measure |
|---|---|---|
| H1: Transfer p95 < 50 ms at 100 req/s | k6 constant arrival rate, 10,000 wallets, uniform pairs | p50/p95/p99, error rate, retries |
| H2: Hot account caps throughput | All deposits through one cash account | Max sustainable req/s; lock wait time |
| H3: Balance read p95 < 5 ms at 10M entries | Read `account_balances` | Compare with `SUM(entries)` and `balance_after` as-of query |
| H4: Entry history page p95 < 20 ms at 10M entries | Keyset pagination, deep pages | Compare with OFFSET pagination |
| H5: 100k-row statement imports < 30 s | Single upload | Duration, memory |
| H6: Reconciliation of 100k rows < 60 s | Single run | Duration, memory |

Method: record CPU, RAM, disk, Docker resources, MySQL config, dataset size, warm-up, run
length, and use k6 *arrival-rate* executors (avoid coordinated omission). Attach
`EXPLAIN ANALYZE` output for each key query. Results go in `docs/benchmarks.md`. Numbers
are only quoted on a resume after they are recorded there.

## CI/CD (GitHub Actions)

| Job | Trigger | Steps |
|---|---|---|
| `verify` | push, PR | checkout → JDK 21 (Temurin) + Maven cache → `./mvnw -B verify` (compile, unit, Testcontainers ITs, Flyway migrate/validate, ArchUnit, JaCoCo report) → upload test reports on failure |
| `security` | push, PR | Gitleaks secret scan; Dependabot (config file) for Maven and Actions |
| `image` | push to `main` (Phase 11) | Build container image; optional Trivy scan; no registry push required |

Branch protection on `main`: `verify` must pass.

## Container (Phase 11)

- Multi-stage build: Maven build stage → `eclipse-temurin:21-jre` runtime (or Jib).
- Non-root user, read-only filesystem where possible, `JAVA_TOOL_OPTIONS` for memory limits.
- Healthcheck hits readiness endpoint.
- Configuration exclusively via environment variables.
