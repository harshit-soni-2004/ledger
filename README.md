# Ledger

A double-entry ledger service built to stay correct under concurrency, retries and crashes:
append-only journals, transactional idempotency, ordered row locks, database-enforced
invariants and a recorded integrity checker. Java 21, Spring Boot 4.1, MySQL 8.4, Flyway.

[![ci](https://github.com/harshit-soni-2004/ledger/actions/workflows/ci.yml/badge.svg)](https://github.com/harshit-soni-2004/ledger/actions/workflows/ci.yml)

It is a backend REST API (no UI). Bank movements, CSV statement import, reconciliation and
reversals are designed but not built yet; see [What is not implemented](#what-is-not-implemented).

## Features

| Area | What works today |
|---|---|
| Accounts | Open wallets and accounts per role policy; freeze, unfreeze, close (zero balance only); balances (current and as of a business date); entry history, newest first, keyset-paginated |
| Transfers | Same-currency wallet-to-wallet transfers with overdraft protection; insufficient funds and inactive accounts are recorded as `REJECTED` transfers and replayed for the same key; lookup by id, status or client reference |
| Journals | Journal lookup; ADMIN opening balances (reason required, only for accounts without history, backdating allowed) |
| Posting engine | One writer for journals, entries and balances; balance rows locked in ascending account order; debits = credits enforced in code, CHECKs and the integrity checker |
| Idempotency | `Idempotency-Key` on every mutating money or lifecycle call, claimed and completed in the same transaction as the effect; replays carry `Idempotent-Replayed: true` |
| Concurrency | Deadlocks and lock-wait timeouts retry the whole transaction (3 attempts, jittered backoff); a 16-thread overspend test proves balances never go below their floor |
| Security | API keys (`Authorization: Bearer lk_…`, SHA-256 at rest, rotation, revocation); roles CLIENT, OPERATOR, ADMIN; clients only see their own accounts and transfers |
| Operations | Integrity checks on demand (ADMIN) and nightly, stored append-only; health probes; ECS JSON logs with request and correlation ids |
| Database | Flyway migrations; split migration and runtime users; the runtime user has only per-table grants and cannot update or delete history; triggers block edits even by privileged sessions |

## Architecture

A modular monolith ([ADR-0001](docs/adr/0001-modular-monolith.md)) with plain JDBC
([ADR-0003](docs/adr/0003-jdbc-for-persistence.md)). Modules under `dev.ihars.ledger`:

| Module | Responsibility |
|---|---|
| `ledger` | Pure accounting domain, `PostingEngine` (the only ledger writer), queries, integrity checker |
| `accounts` | Account opening, lifecycle and the accounts API |
| `transfers` | Transfer API and service; posts through the `PostingEngine` |
| `journals` | Journal lookup and opening balances |
| `idempotency` | Transactional `Idempotency-Key` claim, store and replay |
| `security` | API keys, roles, client management |
| `ops` | Recorded and scheduled integrity checks |
| `audit`, `shared` | Audit trail; money, ids, time, errors, web plumbing |

ArchUnit tests enforce the boundaries (no module cycles, framework-free domain, single
ledger writer, injected clocks). Details: [architecture](docs/01-architecture.md),
[accounting model](docs/02-accounting-model.md), [schema](docs/03-database-schema.md),
[invariants](docs/05-invariants.md), [concurrency](docs/06-concurrency-and-failure-modes.md).

## Technology

Java 21 · Spring Boot 4.1 (Web MVC, Security, JDBC, Actuator, Validation) · MySQL 8.4 ·
Flyway · springdoc-openapi · JUnit 5, AssertJ, jqwik, ArchUnit, Testcontainers · Maven
wrapper · Docker / Docker Compose · GitHub Actions.

## Prerequisites

- Docker with Compose v2 (for the stack and for the integration tests)
- JDK 21, only to build or run outside Docker (Maven comes with the wrapper)

## Quick start (Docker)

```bash
cp .env.example .env        # Windows: Copy-Item .env.example .env
```

Edit `.env`: replace every `change-me` value and set `LEDGER_BOOTSTRAP_ADMIN_KEY` to a fresh
key (`lk_` + 43 base62 characters). For example:

```bash
echo "lk_$(openssl rand -hex 32 | cut -c1-43)"
```

```powershell
$b = [byte[]]::new(32); [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b)
"lk_" + (-join ($b | ForEach-Object { $_.ToString('x2') })).Substring(0, 43)
```

Then start MySQL and the application:

```bash
docker compose up -d --build --wait
curl http://localhost:8080/actuator/health/readiness      # {"status":"UP"}
```

On first start, MySQL creates the migration and runtime users
(`docker/mysql/init/01-users.sh`), the application applies the Flyway migrations as the
migration user, then serves requests as the least-privilege runtime user. Data lives in the
`ledger-mysql-data` volume; `docker compose down` keeps it, `docker compose down -v` deletes it.
Ports are bound to `127.0.0.1` only; change them with `LEDGER_HTTP_PORT` and
`LEDGER_DB_HOST_PORT`. Variables already set in your shell override `.env` for Compose, so
check `env | grep LEDGER_` if the stack does not pick up a value.

## Local development

```bash
docker compose up -d --wait mysql     # database only
./mvnw spring-boot:run                # Windows: .\mvnw.cmd spring-boot:run
```

The application reads `.env` itself (`spring.config.import`); real environment variables take
precedence. With `SPRING_PROFILES_ACTIVE=local`, Swagger UI is served at
`http://localhost:8080/swagger-ui.html` and the OpenAPI document at `/v3/api-docs`; both are
off by default.

## Configuration

| Variable | Required | Purpose |
|---|---|---|
| `MYSQL_ROOT_PASSWORD` | Compose | MySQL root password (container only) |
| `LEDGER_DB_URL` | yes, outside Compose | JDBC URL; must keep `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true` |
| `LEDGER_DB_MIGRATION_USER` / `_PASSWORD` | yes | DDL user, used only by Flyway |
| `LEDGER_DB_APP_USER` / `_PASSWORD` | yes | Runtime user with per-table grants |
| `LEDGER_BOOTSTRAP_ADMIN_KEY` | no | Registers the first ADMIN key when no active admin exists |
| `LEDGER_HTTP_PORT` | no | HTTP port (default 8080; in Compose, the host port) |
| `LEDGER_DB_HOST_PORT` | no | Host port for MySQL in Compose (default 3306) |
| `LEDGER_REPORTING_ZONE` | no | Business-date time zone (default `Asia/Kolkata`) |
| `LEDGER_INTEGRITY_SCHEDULE_ENABLED` | no | Nightly integrity check (default `true`) |
| `LEDGER_INTEGRITY_CRON` | no | Its Spring cron in the business zone (default `0 30 2 * * *`) |
| `SPRING_PROFILES_ACTIVE` | no | `local` enables Swagger UI; leave empty in production |

## API

Base path `/api/v1`. Every route except health needs `Authorization: Bearer <api key>`;
mutating money and lifecycle calls need an `Idempotency-Key` header (1–128 of
`A-Za-z0-9_-`). Errors are RFC 9457 Problem Details with a stable `code` and the `requestId`.

| Method | Path | Roles |
|---|---|---|
| POST | `/accounts` | CLIENT (own wallets), OPERATOR (wallets for a client), ADMIN |
| GET | `/accounts`, `/accounts/{id}`, `/accounts/{id}/balance`, `/accounts/{id}/entries` | all (CLIENT: own) |
| POST | `/accounts/{id}/freeze` | OPERATOR, ADMIN |
| POST | `/accounts/{id}/unfreeze`, `/accounts/{id}/close` | ADMIN |
| POST | `/transfers` | CLIENT (from an owned wallet) |
| GET | `/transfers`, `/transfers/{id}` | all (CLIENT: own) |
| GET | `/journal-transactions/{id}` | OPERATOR, ADMIN |
| POST | `/journal-transactions/openings` | ADMIN |
| POST, GET | `/api-clients`, `/api-clients/{id}`, `/keys`, `/keys/{keyId}/revoke`, `/disable` | ADMIN |
| POST, GET | `/ops/integrity-checks`, `/ops/integrity-checks/latest` | ADMIN |

Full contract: [docs/04-api-contract.md](docs/04-api-contract.md).

### Walkthrough

A runnable bash session (needs `curl` and `jq`); `ADMIN` is your `LEDGER_BOOTSTRAP_ADMIN_KEY`.

```bash
BASE=http://localhost:8080/api/v1
ADMIN=lk_...
key() { cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen; }
api() {  # api METHOD PATH TOKEN [JSON] -- adds an Idempotency-Key to every POST
  local args=(-sS -X "$1" "$BASE$2" -H "Authorization: Bearer $3" -H 'Content-Type: application/json')
  if [ -n "${4:-}" ]; then args+=(-d "$4"); fi
  if [ "$1" = POST ]; then args+=(-H "Idempotency-Key: $(key)"); fi
  curl "${args[@]}"
}

# 1. ADMIN onboards two clients; each response shows the client's API key once.
ASHA=$(api POST /api-clients "$ADMIN" '{"name": "Asha", "role": "CLIENT"}' | jq -r .key.token)
RAVI=$(api POST /api-clients "$ADMIN" '{"name": "Ravi", "role": "CLIENT"}' | jq -r .key.token)

# 2. Each client opens an INR wallet.
WALLET='{"name": "INR wallet", "type": "LIABILITY", "currency": "INR"}'
A=$(api POST /accounts "$ASHA" "$WALLET" | jq -r .id)
R=$(api POST /accounts "$RAVI" "$WALLET" | jq -r .id)

# 3. ADMIN funds Asha's wallet with an opening balance against the bank system account.
BANK=$(api GET '/accounts?limit=200' "$ADMIN" \
  | jq -r '.data[] | select(.systemCode == "ASSET.BANK.OPERATING" and .currency == "INR") | .id')
api POST /journal-transactions/openings "$ADMIN" "$(jq -n --arg bank "$BANK" --arg a "$A" --arg d "$(date +%F)" '{
  currency: "INR", effectiveDate: $d, reason: "Opening balance", lines: [
    {accountId: $bank, direction: "DEBIT",  amount: {value: "500.00", currency: "INR"}},
    {accountId: $a,    direction: "CREDIT", amount: {value: "500.00", currency: "INR"}}]}')" | jq .total

# 4. Asha pays Ravi, then tries to overspend (422 INSUFFICIENT_FUNDS, recorded as REJECTED).
PAY=$(jq -n --arg a "$A" --arg r "$R" \
  '{sourceAccountId: $a, destinationAccountId: $r, amount: {value: "120.25", currency: "INR"}}')
api POST /transfers "$ASHA" "$PAY" | jq '{id, status}'
api POST /transfers "$ASHA" "$(echo "$PAY" | jq '.amount.value = "1000.00"')" | jq '{code, transferId}'

# 5. Balances, history, transfers and an integrity check.
api GET /accounts/$A/balance "$ASHA" | jq .ledgerBalance       # 379.75 INR
api GET /accounts/$R/entries "$RAVI" | jq '.data[0]'
api GET /transfers "$ASHA" | jq '[.data[] | {id, status}]'
api POST /ops/integrity-checks "$ADMIN" | jq '{clean, violationCount}'
```

To see idempotent replay, send the same POST twice with a fixed `Idempotency-Key` header: the
second response is identical and carries `Idempotent-Replayed: true`. An opening's
`effectiveDate` must not be after today's date in `LEDGER_REPORTING_ZONE`.

## Tests

```bash
./mvnw verify          # Windows: .\mvnw.cmd -B -ntp verify
```

- `*Test`: unit, property-based (jqwik) and architecture (ArchUnit) tests; no Docker needed.
- `*IT`: integration tests against a real MySQL 8.4 in Testcontainers (Docker required). They
  use the same user-creation script as Compose, so the privilege separation is tested too, and
  cover concurrency (overspend races, duplicate keys, freeze vs. posting), rollback, the role
  matrix and an HTTP end-to-end workflow.
- JaCoCo writes a coverage report to `target/site/jacoco`.

CI ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)) runs on pushes to `main` and
`develop` and on pull requests: the full `verify`, a Gitleaks scan of the whole history, and a
Docker job that builds the image, starts the Compose stack and smoke-tests it. Dependabot
keeps Maven, Actions and Docker dependencies current.

## Deployment

The image is a self-contained Spring Boot service; any container platform with a managed
MySQL 8.4 works. [docs/15-deployment.md](docs/15-deployment.md) covers database provisioning
and users, secrets, migrations, health checks, logging, shutdown, TLS and the production
checklist.

```bash
docker build -t ledger:1.0 .
```

## What is not implemented

Designed in [docs](docs/README.md) and the [roadmap](docs/10-roadmap.md), not built:
reversals (Phase 6), bank accounts, deposits and payouts (7), CSV statement import (8),
reconciliation (9–10), metrics, load tests and benchmarks (11). Also open: the idempotency
record purge job, date and direction filters on the entries endpoint, rate limiting, and a
cluster-wide guard for concurrent integrity checks (the current guard is per instance).

## Documentation

[Project overview](PROJECT_OVERVIEW.md) · [Design docs and ADRs](docs/README.md) ·
[Roadmap](docs/10-roadmap.md) · [Operations and CI](docs/11-operations-and-ci.md) ·
[Deployment](docs/15-deployment.md)

## License

[MIT](LICENSE)
