# 05 — Invariants and Enforcement Matrix

An invariant is only real if it holds under **concurrent requests, retries, application
crashes, and process restarts** — not just in a single-threaded happy-path test. Every row
below therefore has at least one test running against real MySQL (Testcontainers).

Enforcement layers legend:

- **DB-C** database constraint (CHECK, FK, UNIQUE, NOT NULL, ENUM, strict mode)
- **DB-G** database grants / triggers
- **TX** transaction boundary (atomic commit/rollback)
- **LOCK** pessimistic row locking with ordered acquisition
- **APP** application/domain validation
- **DET** detective control (integrity checker, metrics, alerts)

## Matrix

| # | Invariant | Enforcement | Verified by (see [09](09-testing-strategy.md)) |
|---|---|---|---|
| I1 | Every posted journal balances: Σ debits = Σ credits, single currency | APP (`JournalDraft`), DB-C (`ck_jtx_balanced`, composite currency FKs), TX, DET (header-vs-entries query, trial balance) | `JournalDraftTest`, `JournalPropertiesTest`, `JournalHeaderConstraintIT`, `IntegrityCheckerIT` |
| I2 | Posted journals/entries cannot be edited or deleted | APP (insert-only repositories), DB-G (no UPDATE/DELETE grant; triggers), DB-C (`RESTRICT` FKs) | `LedgerImmutabilityIT` (trigger and grant), `ArchitectureTest` |
| I3 | A failed posting leaves no partial entries | TX (one transaction for idempotency, journal, entries, balances, business row, audit) | `PostingRollbackIT` (fault injected mid-posting) |
| I4 | Duplicate submissions cannot create duplicate financial effects | DB-C (`uk_idem_scope`, `uk_transfers_client_key`, `uk_transfers_journal`, `bank_movements` external reference unique), TX (key stored with effect) | `TransferIdempotencyIT` (sequential + 20-thread concurrent), `LostResponseRetryIT` |
| I5 | Concurrent operations cannot violate overdraft rules | LOCK (`FOR UPDATE` on balance rows, ascending order), APP (check on locked value), DB-C (`ck_balances_floor` backstop), DB-C (`uk_entries_account_seq`) | `ConcurrentOverspendIT`, `OpposingTransfersDeadlockIT`, `BalanceFloorConstraintIT` |
| I6 | Reversals preserve original history; a journal is reversed at most once | APP (mirror journal), DB-C (`uk_jtx_reverses`, `ck_jtx_reversal_link`), DB-G (immutability) | `ReversalIT`, `ConcurrentReversalIT` |
| I7 | Imported statement rows and reconciliation outcomes remain auditable | DB-G (append-only `statement_files`, `reconciliation_items`, `discrepancy_resolutions`, `audit_events`), APP (every input row stored with status), DB-C (`uk_rows_batch_line`) | `StatementImportIT` (row count = input lines), `ReconciliationAuditIT` |
| I8 | Re-importing or re-running cannot create duplicate financial effects | DB-C (`uk_batches_file`, `uk_rows_dedup`, `uk_runs_active`, `uk_resolutions_subject`), APP (reconciliation is read-only on the ledger; adjustments only through idempotent resolutions) | `DuplicateImportIT`, `ReconciliationRerunIT` (identical results, ledger row counts unchanged) |
| I9 | Calculations use exact decimals with correct currency precision | APP (`Money` with `BigDecimal`, `RoundingMode.UNNECESSARY`, string-only parsing; ArchUnit bans `double` constructors), DB-C (`DECIMAL(19,4)`), DET (scale-violation query) | `MoneyTest`, `MoneyPropertiesTest`, `AmountParsingApiIT`, `ArchitectureTest` |
| I10 | Untrusted or malformed input cannot create invalid financial records | APP (Bean Validation, strict CSV parsing, unknown-field rejection), DB-C (CHECKs, ENUMs, FKs, strict SQL mode) | `TransferValidationApiIT`, `CsvParserTest`, `MalformedStatementIT`, `StrictSqlModeIT` |
| I11 | Balance projection equals the sum of entries; `balance_after` chain and `account_seq` are gapless | TX, LOCK, DB-C (`uk_entries_account_seq`), DET (projection and chain queries) | `RandomizedLedgerSimulationIT` + `IntegrityCheckerIT` |
| I12 | The global trial balance per currency is zero | Follows from I1; DET (trial-balance query; metric `ledger.integrity.violations`) | `RandomizedLedgerSimulationIT` |
| I13 | Entry currency = account currency = journal currency | DB-C (composite FKs) | `CurrencyConsistencyConstraintIT` |
| I14 | Only one active reconciliation run per bank account | DB-C (`uk_runs_active`, `ck_runs_lock`) | `ConcurrentRunStartIT` |
| I15 | A discrepancy subject receives at most one final resolution; an adjustment is posted at most once | DB-C (`uk_resolutions_subject`), idempotency | `ResolutionIdempotencyIT` |

## Why each layer exists

- **Domain validation** gives fast, precise errors and keeps rules testable without a DB.
- **Constraints** are the last line of defense against bugs in the application layer,
  manual SQL, or future code that bypasses the domain.
- **Transactions** make multi-row invariants atomic: no reader or crash ever observes a
  half-posted journal.
- **Locks** make check-then-act sequences safe under concurrency.
- **Detective controls** catch what preventive controls cannot express (cross-row sums)
  and prove correctness continuously, the way real ledgers run daily balance checks.

## Invariants that are explicitly *not* claimed

- Correctness of external statement data (the bank can be wrong; that's what
  `MARK_BANK_ERROR` is for).
- Protection against a malicious DB administrator (an audit hash chain is an extension).
- Exactly-once delivery to external systems (no external integrations in MVP).
