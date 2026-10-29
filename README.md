# Ledger

A double-entry ledger and bank reconciliation service built to stay correct under
concurrency, retries, and crashes. Java 21, Spring Boot 4.1, MySQL 8.4, Flyway.

**Status:** Phases 0–4 are implemented (posting engine, accounts API, API-key auth,
idempotency, integrity checks). Transfers, bank movements, CSV ingestion, and reconciliation
are planned — see [`PROJECT_OVERVIEW.md`](PROJECT_OVERVIEW.md) and the
[roadmap](docs/10-roadmap.md).

[![ci](https://github.com/harshit-soni-2004/ledger/actions/workflows/ci.yml/badge.svg)](https://github.com/harshit-soni-2004/ledger/actions/workflows/ci.yml)

## Quick start

Prerequisites: JDK 21 and Docker. Maven is provided by the wrapper.

```bash
cp .env.example .env                 # then replace every change-me value
docker compose up -d mysql           # MySQL 8.4; creates the migration and app users
./mvnw spring-boot:run               # Windows: .\mvnw.cmd spring-boot:run
```

- Health: `GET http://localhost:8080/actuator/health/readiness`
- OpenAPI (local profile only): run with `SPRING_PROFILES_ACTIVE=local`, then open
  `http://localhost:8080/swagger-ui.html`

The application reads `.env` itself (`spring.config.import`); real environment variables
take precedence. If port 3306 or 8080 is taken, set `LEDGER_DB_HOST_PORT` (and the port in
`LEDGER_DB_URL`) or `LEDGER_HTTP_PORT`.

## Database users

| User | Used by | Privileges |
|---|---|---|
| `ledger_migrator` | Flyway only | All privileges on the `ledger` schema |
| `ledger_app` | The running application | Only the table grants listed in `R__runtime_grants.sql` |

Application sessions run with READ COMMITTED isolation, UTC, and strict SQL mode.

## Tests

```bash
./mvnw verify
```

- `*Test` — unit and architecture tests (Surefire, no Docker).
- `*IT` — integration tests against MySQL 8.4 in Testcontainers (Failsafe, Docker required).
  They use the same user-creation script as Docker Compose, so privilege separation is tested.

## Documentation

- [Project overview (implemented vs planned)](PROJECT_OVERVIEW.md)
- [Overview and scope](docs/00-overview.md)
- [Architecture](docs/01-architecture.md) and [ADRs](docs/README.md#architecture-decision-records)
- [Roadmap](docs/10-roadmap.md)

## License

[MIT](LICENSE)
