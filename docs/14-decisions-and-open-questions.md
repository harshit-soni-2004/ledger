# 14 — Decision Log and Open Questions

## Decisions made (Phase 0)

| # | Decision | Source | Record |
|---|---|---|---|
| D1 | Modular monolith: Java 21, Spring Boot, Maven, MySQL 8.4, Flyway | Directive 1 | [ADR-0001](adr/0001-modular-monolith.md) |
| D2 | Spring Boot **4.1.x** (OSS support to 2027-07-31); 3.4/3.5 rejected as out of OSS support; Flyway ≥ 11.13.3 required for MySQL 8.4 (Boot 4.1 manages 12.x) | Directive 1 + verification 2026-10-29 | [ADR-0002](adr/0002-technology-stack-and-versions.md) |
| D3 | Spring JDBC (`JdbcClient`) for persistence | Architecture analysis | [ADR-0003](adr/0003-jdbc-for-persistence.md) |
| D4 | `BigDecimal` in Java, `DECIMAL(19,4)` in MySQL, string amounts in JSON, reject excess precision | Directive 3 | [ADR-0004](adr/0004-money-representation.md) |
| D5 | No cross-currency transfers in MVP; single-currency journals enforced by composite FKs | Directive 3 | [ADR-0007](adr/0007-single-currency-journals.md) |
| D6 | READ COMMITTED + ordered `SELECT … FOR UPDATE` on per-account balance rows; DB CHECK floor backstop; whole-transaction retry on deadlock | Directive 4 | [ADR-0005](adr/0005-locking-and-isolation.md) |
| D7 | Idempotency record reserved and completed inside the posting transaction | Directive 4 | [ADR-0006](adr/0006-idempotency-in-posting-transaction.md) |
| D8 | Append-only ledger enforced by code, grants, and triggers; corrections via reversal journals | Directives 2, 5 | [ADR-0008](adr/0008-immutability-and-reversals.md) |
| D9 | CSV ingestion, deterministic reconciliation, discrepancy taxonomy, auditable resolutions | Directive 6 | [07](07-reconciliation.md), [08](08-statement-csv-format.md) |
| D10 | MySQL 8.4 Testcontainers for all DB-critical tests; no H2 | Directive 7 | [09](09-testing-strategy.md) |
| D11 | Kafka, Redis, Kubernetes, heavy monitoring deferred | Directive 8 | [ADR-0010](adr/0010-deferred-infrastructure.md) |
| D12 | CI and the integration test harness built in Phase 1; concurrency tests ship with transfers | Roadmap analysis | [10](10-roadmap.md) |

## Open questions (need your answer; default applies if unanswered)

| # | Question | Proposed default | Impact if changed later | Needed by |
|---|---|---|---|---|
| Q1 | Base package / Maven `groupId` | `dev.ihars.ledger` | Package rename (cheap early, costly later) | **Phase 1** |
| Q2 | Authentication approach | API keys with roles ([ADR-0009](adr/0009-authentication.md)) | Security filter rewrite | Phase 4 |
| Q3 | Replay business rejections (e.g. `INSUFFICIENT_FUNDS`) for the same idempotency key? (A5) | Yes — one key = one attempt | Idempotency semantics and tests | Phase 5 |
| Q4 | Reversals when recipient already spent funds (A6) | Reject with `INSUFFICIENT_FUNDS`; no override in MVP | Need an audited overdraft-policy-change workflow | Phase 6 |
| Q5 | Business-date time zone | `Asia/Kolkata` (configurable) | Date boundaries in matching and as-of balances | Phase 4 |
| Q6 | Supported currencies seed | INR, USD, EUR, JPY, KWD (JPY/KWD to exercise 0 and 3 decimals) | Seed migration | Phase 1 |
| Q7 | Store raw statement files in MySQL (`LONGBLOB`) or on disk | MySQL (≤ 10 MB) for transactional simplicity | Storage adapter | Phase 8 |
| Q8 | Default reconciliation date tolerance | 2 days | Category distribution | Phase 9 |
| Q9 | Payout model for MVP | Direct to Cash at Bank; clearing lifecycle as extension | Posting patterns, reconciliation of payouts | Phase 7 |
| Q10 | Public repository on GitHub from Phase 1? | Yes, with CI on GitHub Actions | CI host | Phase 1 |
| Q11 | License | MIT | — | Phase 11 |

## Known risks

| Risk | Mitigation |
|---|---|
| Boot 4 ecosystem differences (Jackson 3, Testcontainers 2) slow early phases | Phase 1 verification gate; follow official docs over older blog posts |
| Windows host + Docker volume/line-ending issues | `.gitattributes`; named volumes instead of bind mounts for MySQL data |
| Concurrency tests flaky in CI | Barriers, order-independent assertions, adequate pool size, treat flakiness as a bug |
| Scope creep into extensions | Extensions backlog is explicitly post-MVP |
| Trigger creation requires binlog privilege settings | Documented compose flag; migration user privileges |
