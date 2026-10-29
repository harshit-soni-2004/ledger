# Ledger and Reconciliation Platform — Planning Documentation

Status: **Phase 2 (accounting domain, ledger schema, internal posting engine) implemented —
awaiting review before the next phase.** Scope of Phase 2: [10](10-roadmap.md).
Last updated: 2026-10-29.

These documents are the blueprint for a portfolio-grade double-entry ledger and bank
reconciliation system. Every implementation phase must
keep these documents current; a design change lands as a doc/ADR change in the same
pull request as the code.

## Reading order

| # | Document | What it answers |
|---|---|---|
| 00 | [Overview and scope](00-overview.md) | What we build, what we deliberately do not build, assumptions |
| 01 | [Architecture](01-architecture.md) | Modules, diagrams, data flow, package layout |
| 02 | [Accounting model](02-accounting-model.md) | Account types, normal balances, debit/credit semantics, reversals |
| 03 | [Database schema](03-database-schema.md) | Tables, constraints, indexes, immutability, locking |
| 04 | [API contract](04-api-contract.md) | Endpoints, idempotency, errors, examples |
| 05 | [Invariants](05-invariants.md) | Invariants, enforcement layers, verifying tests |
| 06 | [Concurrency and failure modes](06-concurrency-and-failure-modes.md) | Races, locking, deadlocks, crashes, retries |
| 07 | [Reconciliation](07-reconciliation.md) | Matching algorithm, discrepancy taxonomy, resolution workflow |
| 08 | [Statement CSV format](08-statement-csv-format.md) | Documented import format, validation, duplicates |
| 09 | [Testing strategy](09-testing-strategy.md) | Test catalogue with expected outcomes |
| 10 | [Roadmap](10-roadmap.md) | Phased plan, acceptance criteria, commit checkpoints |
| 11 | [Operations and CI](11-operations-and-ci.md) | Local setup, Docker Compose, CI, observability, performance |
| 12 | [README outline and demo](12-readme-outline-and-demo.md) | Future project README and demo scenarios |
| 13 | [Interview preparation](13-interview-prep.md) | Resume outcomes and the measurements behind them; interview questions |
| 14 | [Decisions and open questions](14-decisions-and-open-questions.md) | Decision log and unresolved items |

## Architecture Decision Records

| ADR | Title | Status |
|---|---|---|
| [0001](adr/0001-modular-monolith.md) | Modular monolith | Accepted |
| [0002](adr/0002-technology-stack-and-versions.md) | Technology stack and Spring Boot version | Accepted (verified in Phase 1) |
| [0003](adr/0003-jdbc-for-persistence.md) | Spring JDBC (`JdbcClient`) instead of JPA | Accepted |
| [0004](adr/0004-money-representation.md) | `BigDecimal` + `DECIMAL(19,4)` with explicit currency | Accepted |
| [0005](adr/0005-locking-and-isolation.md) | READ COMMITTED + ordered pessimistic row locks | Accepted |
| [0006](adr/0006-idempotency-in-posting-transaction.md) | Idempotency recorded in the posting transaction | Accepted |
| [0007](adr/0007-single-currency-journals.md) | Single-currency journals; no cross-currency MVP | Accepted |
| [0008](adr/0008-immutability-and-reversals.md) | Append-only ledger; corrections by reversal | Accepted |
| [0009](adr/0009-authentication.md) | API-key authentication with roles | Proposed |
| [0010](adr/0010-deferred-infrastructure.md) | Defer Kafka, Redis, Kubernetes, heavy monitoring | Accepted |

ADR statuses: *Proposed* (default chosen, awaiting confirmation), *Accepted*, *Superseded*.
