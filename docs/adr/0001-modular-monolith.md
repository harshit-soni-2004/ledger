# ADR-0001: Modular monolith

- Status: Accepted
- Date: 2026-10-29

## Context

A transfer must atomically write an idempotency record, a journal with its entries,
balance projections, a transfer record, and an audit event. Reconciliation reads ledger
data and may post adjustments. All of these share one consistency boundary.

## Decision

Build one Spring Boot application with internal modules (`accounts`, `ledger`,
`idempotency`, `transfers`, `bankmovements`, `statements`, `reconciliation`, `audit`,
`ops`) and one MySQL schema. Module boundaries are enforced with ArchUnit tests.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Microservices (ledger, reconciliation, payments) | Atomic posting would require sagas or distributed transactions. More failure modes, no correctness gain, no independent teams to justify it. |
| Single package "big ball of mud" | Boundaries erode; reconciliation would start writing ledger tables directly. |
| Spring Modulith | Viable, but ArchUnit is version-agnostic and sufficient for our rules. Revisit if we adopt its event publication registry. |

## Consequences

- One local ACID transaction per financial operation — the core correctness guarantee.
- Simple local development and CI (one app + one database).
- If a module is ever extracted, its public API and an outbox would be the seam; this is
  documented as an extension, not a goal.
- Interview talking point: "When would you split this?" — when independent scaling,
  ownership, or deployment cadence justifies giving up local transactions.
