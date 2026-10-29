# Project overview

Double-entry ledger and bank reconciliation platform (modular monolith: Java 21, Spring Boot
4.1, MySQL 8.4). The goal is to show why financial systems are not naive CRUD: append-only
journals, transactional idempotency, ordered row locks, integrity checks, and immutable
history with reversals only (planned).

Full design lives under [`docs/`](docs/README.md).

## Implemented (Phases 0–4)

| Area | What ships today |
|---|---|
| Infrastructure | Docker Compose MySQL 8.4, Flyway, split migrator/app DB users, GitHub Actions CI |
| Domain & schema | Currencies, accounts, balances, journal transactions, ledger entries, audit events, immutability triggers |
| Posting | `PostingEngine` with ordered locking, business dates (`Asia/Kolkata`, configurable), integrity checker |
| Accounts API | Open account (role policy), freeze/unfreeze/close, balances, entry listing, ADMIN opening journals |
| Security | API-key auth, roles (CLIENT / OPERATOR / ADMIN), client management |
| Idempotency | Transactional `Idempotency-Key` claim, store, replay for mutating operations |
| Operations | Recorded integrity-check runs, ADMIN API, nightly scheduled job |
| Tests | 130+ integration tests on real MySQL (Testcontainers), ArchUnit, property tests |

Verified locally with `.\mvnw.cmd -B -ntp verify` (Surefire + Failsafe).

## Planned (Phases 5–11)

Not implemented yet: wallet transfers API, reversals, simulated bank deposits/payouts, CSV
statement ingestion, reconciliation engine, resolution workflows, demo scripts, performance
benchmarks, and release hardening. See [roadmap](docs/10-roadmap.md).

## License

[MIT](LICENSE) — Copyright (c) 2026 Harshit Soni.
