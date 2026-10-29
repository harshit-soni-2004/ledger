# 00 — Overview and Scope

## Executive summary

We are building a **double-entry ledger and bank reconciliation platform** as a single
Spring Boot application (a modular monolith) backed by MySQL 8.4.

The system:

- records money movements as **balanced, append-only journal transactions**;
- derives balances from ledger entries (with a locked, verifiable balance projection);
- prevents **duplicate financial effects** (idempotency) and **overspending under
  concurrency** (ordered pessimistic row locks) in the *same* database transaction;
- corrects mistakes only through **reversal transactions**, never by editing history;
- imports **CSV bank statements** as auditable data, never as money movements;
- **reconciles** statement rows against ledger entries deterministically, classifies
  discrepancies, and requires an **auditable resolution** for every difference.

The project's purpose is to demonstrate *why* financial systems cannot be naive CRUD:
balances must be provable, operations must be safe under retries and concurrency, and
history must be immutable.

## Goals

1. Correctness first: every invariant in [05-invariants.md](05-invariants.md) holds under
   concurrent requests, retries, crashes, and restarts — verified on real MySQL.
2. Simplicity: one deployable, one database, local transactions only.
3. Explainability: every design choice has an ADR with alternatives considered.
4. Evidence: every resume claim is backed by a test or a recorded measurement.

## MVP scope

| Area | Included in MVP |
|---|---|
| Accounts | Asset, liability, equity, revenue, expense accounts; ownership; freeze/close |
| Ledger | Single-currency balanced journals; append-only entries; balance projection; integrity checker |
| Transfers | Same-currency wallet-to-wallet transfers with idempotency and overdraft protection |
| Corrections | Full reversals with audit trail |
| Bank movements | Simulated deposits and payouts carrying external bank references |
| Statements | CSV upload, validation, row-level status, duplicate detection, raw-file retention |
| Reconciliation | Deterministic matching, discrepancy taxonomy, reports, balance proof |
| Resolution | Auditable resolution actions, including ADMIN-only adjustment journals |
| Operations | API-key auth with roles, Problem Details errors, correlation IDs, structured logs, health checks, basic metrics |
| Delivery | Docker Compose (MySQL + app), GitHub Actions CI, OpenAPI docs |

## Optional extensions (post-MVP)

Ordered roughly by learning value:

1. Holds/authorizations and a true *available balance* (< ledger balance).
2. Payout clearing lifecycle (`Payouts Clearing` → `Cash at Bank` on settlement confirmation).
3. Maker-checker approval for adjustments and reversals.
4. Cross-currency transfers via FX quotes and per-currency balanced journals.
5. One-to-many reconciliation (one bank line settling several payouts).
6. Transactional outbox + Kafka for downstream consumers.
7. Tamper-evident audit hash chain.
8. OpenTelemetry tracing; Grafana dashboards beyond the basic one.
9. Partitioning/archival of `ledger_entries`; asynchronous imports.

## Explicit non-goals

- Real bank or payment-provider API integration (statements are simulated CSV).
- Microservices, Kubernetes, distributed transactions, sagas.
- Redis (would sit outside the database transaction; see [ADR-0006](adr/0006-idempotency-in-posting-transaction.md)).
- Regulatory compliance claims (PCI DSS, SOC 2). We follow good practices but certify nothing.
- A frontend. The API and OpenAPI UI are the interface.

## Assumptions (and their implications)

| # | Assumption | Implication |
|---|---|---|
| A1 | Single tenant: one platform operating many customer wallets | No tenant ID column; adding multi-tenancy later touches every table |
| A2 | Every account holds exactly one currency | Cross-currency needs two accounts and an FX model (deferred) |
| A3 | Wallets cannot go negative by default (`overdraft_policy = FORBIDDEN`) | Transfers fail with `INSUFFICIENT_FUNDS` instead of overdrawing |
| A4 | Available balance = ledger balance in MVP (no holds) | API returns both fields so adding holds later is non-breaking |
| A5 | A business rejection (e.g. `INSUFFICIENT_FUNDS`) is stored and replayed for the same idempotency key | Clients must use a new key to retry after topping up — *pending confirmation* |
| A6 | Reversals obey overdraft rules with no override in the MVP | A reversal fails with `INSUFFICIENT_FUNDS` if the recipient already spent the funds; recovering such funds is a separate (future) collections workflow — *pending confirmation* |
| A7 | Statement files are ≤ 10 MB and ≤ 100,000 rows; processed synchronously | Larger files need async processing (extension) |
| A8 | The bank provides a unique transaction ID per statement row in most cases | Rows without it get fingerprint-based *possible duplicate* flags, not silent dedup |
| A9 | Reconciliation compares statements only against the mapped *cash* ledger account | Wallet-to-wallet transfers never appear on a bank statement and are not reconciled |
| A10 | All timestamps are UTC; business dates (`effective_date`, `value_date`) are calendar dates in the platform's reporting time zone (Asia/Kolkata by default, configurable) | Date-boundary tests must cover the time-zone shift |

## Unsafe designs this project explicitly rejects

| Unsafe design | Why it is unsafe | What we do instead |
|---|---|---|
| Updating a `balance` column without an auditable ledger | No history, no proof, no way to detect corruption | Balance is a projection of append-only entries, verified by an integrity checker |
| Posting unbalanced transactions | Money is created or destroyed | Domain validation + header CHECK + single transaction + integrity checker |
| Checking balance in application code without a lock | Two concurrent withdrawals both pass the check (lost update) | `SELECT … FOR UPDATE` on the balance row, ordered lock acquisition, DB CHECK backstop |
| Treating a retry as proof the first attempt failed | Timeouts after commit cause duplicate transfers | Client-generated idempotency key stored in the same transaction; replay on retry |
| Mutating posted entries to fix mistakes | Destroys audit trail; breaks reconciliation history | Reversal journals; DB grants and triggers forbid UPDATE/DELETE |
| Treating every unmatched statement row as an error | Timing differences and bank fees are normal | Discrepancy taxonomy distinguishes pending/timing/fees from real mismatches |
| Auto-editing the ledger to force reconciliation | Hides real problems; unauditable | Resolutions are explicit, reasoned, role-gated, and audited |
| Claiming throughput without benchmarks | Not credible in interviews | Performance targets are hypotheses until measured ([11](11-operations-and-ci.md)) |
| Adding microservices/Kafka to look sophisticated | Adds failure modes without improving correctness | Modular monolith; extensions only after the core is proven |
