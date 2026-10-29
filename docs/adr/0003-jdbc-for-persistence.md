# ADR-0003: Spring JDBC (`JdbcClient`) instead of JPA

- Status: Accepted
- Date: 2026-10-29

## Context

Ledger posting needs explicit control over: lock acquisition order, `SELECT … FOR UPDATE`,
insert-only behavior for immutable tables, batch inserts, and exact SQL for integrity checks.

## Decision

Use Spring's `JdbcClient` (with `JdbcTemplate` for batch inserts) for **all** modules.
SQL lives in repository classes in each module's `infrastructure` package.

## Alternatives considered

| Alternative | Trade-off |
|---|---|
| Spring Data JPA / Hibernate | Dirty checking can emit `UPDATE` on rows we consider immutable; flush order obscures lock order; lazy loading hides queries; `@Lock(PESSIMISTIC_WRITE)` works but is less visible. Fine for simple CRUD, risky for the posting path. |
| Mixed (JPA for CRUD, JDBC for ledger) | Two mental models and two transaction-resource subtleties for little benefit in a small codebase. |
| jOOQ | Type-safe SQL is attractive, but adds code generation and another tool to learn. Reasonable future refactor. |

## Consequences

- Every query is visible and reviewable; `EXPLAIN` plans can be attached to docs.
- More boilerplate (row mappers). Mitigated with small, focused repositories and records.
- No accidental writes to immutable tables from ORM state tracking.
