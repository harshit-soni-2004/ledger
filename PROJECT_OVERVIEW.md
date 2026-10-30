# Project overview

Double-entry ledger platform (modular monolith: Java 21, Spring Boot 4.1, MySQL 8.4). The goal
is to show why financial systems are not naive CRUD: append-only journals, transactional
idempotency, ordered row locks, database-enforced invariants, integrity checks, and immutable
history with corrections by reversal (designed).

Full design lives under [`docs/`](docs/README.md); setup and API usage in the [README](README.md).

## Implemented (Phases 0–5, parts of 11)

| Area | What ships today |
|---|---|
| Infrastructure | Docker image (JRE, non-root) and Compose stack (MySQL + app), Flyway, split migrator/app DB users, GitHub Actions CI (tests, secret scan, container smoke test), Dependabot, tag-triggered GHCR release |
| Domain & schema | Currencies, accounts, balances, journal transactions, ledger entries, audit events, transfers, immutability triggers |
| Posting | `PostingEngine` with ordered locking, business dates (`Asia/Kolkata`, configurable), integrity checker |
| Accounts API | Open account (role policy), freeze/unfreeze/close, balances, entry listing, ADMIN opening journals |
| Transfers | Wallet-to-wallet transfers with overdraft protection, recorded and replayed rejections, deadlock/lock-timeout retry, listing and lookup |
| Security | API-key auth, roles (CLIENT / OPERATOR / ADMIN), client and key management |
| Idempotency | Transactional `Idempotency-Key` claim, store, replay for mutating operations |
| Operations | Recorded integrity-check runs, ADMIN API, nightly scheduled job, health probes, ECS JSON logs |
| Tests | Unit, property-based and architecture tests; integration tests on real MySQL (Testcontainers) including concurrency races and an HTTP end-to-end workflow |

Verified with `./mvnw verify` (Surefire + Failsafe) and a Docker Compose smoke test.

## Planned (Phases 6–11)

Not implemented yet: reversals, simulated bank deposits/payouts, CSV statement ingestion,
reconciliation engine, resolution workflows, metrics, performance benchmarks, and the
idempotency purge job. See [roadmap](docs/10-roadmap.md).

## License

[MIT](LICENSE) — Copyright (c) 2026 Harshit Soni.
