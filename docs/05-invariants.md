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
| I1 | Every posted journal balances: Σ debits = Σ credits, single currency; entries carry the journal's `effective_date` and `posted_at` | APP (`JournalDraft`, single writer `PostingEngine`), DB-C (`ck_jtx_balanced` on the header row only, composite currency FKs), TX, DET (header-vs-entries query, entry/header date queries, trial balance) | `JournalDraftTest`, `JournalPropertiesTest`, `LedgerSchemaConstraintsIT`, `IntegrityCheckerIT` |
| I2 | Posted journals/entries cannot be edited or deleted | APP (insert-only repositories), DB-G (no UPDATE/DELETE grant; triggers), DB-C (`RESTRICT` FKs) | `LedgerImmutabilityIT` (triggers), `DatabasePrivilegesIT` (grants), `ArchitectureTest` (single writer) |
| I3 | A failed posting leaves no partial entries; a failed account opening leaves no account, balance row or audit event | TX (one transaction for idempotency, journal, entries, balances, business row, audit) | `PostingRollbackIT` (fault injected mid-posting), `AccountOpeningRollbackIT` (fault injected after the account and balance rows) |
| I4 | Duplicate submissions cannot create duplicate financial effects | DB-C (`uk_idem_scope`, `uk_transfers_client_key`, `uk_transfers_journal`, `bank_movements` external reference unique), TX (key stored with effect) | `IdempotencyServiceIT` (replay, reuse, rollback, concurrent duplicate), `AccountsApiIT`, `JournalsApiIT`; later `TransferIdempotencyIT`, `LostResponseRetryIT` |
| I5 | Concurrent operations cannot violate overdraft rules | LOCK (`FOR UPDATE` on balance rows, ascending order), APP (check on locked value), DB-C (`ck_balances_floor` backstop), DB-C (`uk_entries_account_seq`) | `ConcurrentPostingIT` (Phase 2), `ConcurrentOverspendIT`, `OpposingTransfersDeadlockIT`, `LedgerSchemaConstraintsIT` |
| I6 | Reversals preserve original history; a journal is reversed at most once | APP (mirror journal), DB-C (`uk_jtx_reverses`, `ck_jtx_reversal_link`), DB-G (immutability) | `ReversalIT`, `ConcurrentReversalIT` |
| I7 | Imported statement rows and reconciliation outcomes remain auditable | DB-G (append-only `statement_files`, `reconciliation_items`, `discrepancy_resolutions`, `audit_events`), APP (every input row stored with status), DB-C (`uk_rows_batch_line`) | `StatementImportIT` (row count = input lines), `ReconciliationAuditIT` |
| I8 | Re-importing or re-running cannot create duplicate financial effects | DB-C (`uk_batches_file`, `uk_rows_dedup`, `uk_runs_active`, `uk_resolutions_subject`), APP (reconciliation is read-only on the ledger; adjustments only through idempotent resolutions) | `DuplicateImportIT`, `ReconciliationRerunIT` (identical results, ledger row counts unchanged) |
| I9 | Calculations use exact decimals with correct currency precision | APP (`Money` with `BigDecimal`, `RoundingMode.UNNECESSARY`, string-only parsing; ArchUnit bans `double` constructors), DB-C (`DECIMAL(19,4)`), DET (scale-violation query) | `MoneyTest`, `MoneyPropertiesTest`, `AmountParsingApiIT`, `ArchitectureTest` |
| I10 | Untrusted or malformed input cannot create invalid financial records | APP (Bean Validation, strict CSV parsing, unknown-field rejection), DB-C (CHECKs, ENUMs, FKs, strict SQL mode) | `TransferValidationApiIT`, `CsvParserTest`, `MalformedStatementIT`, `StrictSqlModeIT` |
| I11 | Balance projection equals the sum of entries; `balance_after` chain and `account_seq` are gapless | APP (single writer `PostingEngine`), TX, LOCK, DB-C (`uk_entries_account_seq`), DET (projection and chain queries) | `RandomizedLedgerSimulationIT` + `IntegrityCheckerIT` |
| I12 | The global trial balance per currency is zero | Follows from I1; DET (trial-balance query; metric `ledger.integrity.violations`) | `RandomizedLedgerSimulationIT`, `LedgerModelPropertiesTest`, `IntegrityCheckerIT` |
| I13 | Entry currency = account currency = journal currency | DB-C (composite FKs) | `LedgerSchemaConstraintsIT` |
| I14 | Only one active reconciliation run per bank account | DB-C (`uk_runs_active`, `ck_runs_lock`) | `ConcurrentRunStartIT` |
| I15 | A discrepancy subject receives at most one final resolution; an adjustment is posted at most once | DB-C (`uk_resolutions_subject`), idempotency | `ResolutionIdempotencyIT` |
| I16 | Every `/api` route requires a valid API key and admits only its roles; unlisted routes are denied; a CLIENT sees only accounts it owns (others are 404) | APP (Spring Security filter chain, deny-by-default, ownership filter in the controllers) | `RoleMatrixIT`, `ApiKeyAuthenticationIT`, `AccountsApiIT` |
| I17 | API keys are never stored, logged or audited in plaintext; revocation and disabling take effect on the next request | APP (SHA-256 only, token shown once, no cache), DB-C (`BINARY(32)` unique hash, `ck_api_keys_revoked`) | `ApiClientsApiIT`, `ApiKeyAuthenticationIT` |
| I18 | A FROZEN or CLOSED account receives no postings, even if the status changes while a posting waits; only zero-balance accounts close; CLOSED is terminal | LOCK (lifecycle and posting share the balance-row lock; status read `FOR SHARE` after it), APP (guarded transitions, zero check under the lock), DB-G (column-level `UPDATE (status, updated_at)` only), DET (`CLOSED_ACCOUNT_NONZERO`) | `AccountLifecycleIT` (two latch-driven races), `IntegrityCheckerIT` |
| I19 | System accounts are unowned and always ACTIVE | DB-C (`ck_accounts_system_unowned`, `ck_accounts_system_active`), APP (lifecycle rejects system accounts) | `AccessSchemaConstraintsIT`, `AccountLifecycleIT` |
| I20 | No account with an UNLIMITED overdraft is created through the API; CLIENTs and OPERATORs open only FORBIDDEN liability wallets | APP only (`AccountOpeningPolicy`); the database and `AccountService` allow it because migrations and tests need it | `AccountsApiIT` |
| I21 | An `OPENING` journal is only ever the first entry of every non-system account in it | LOCK + APP (`PostingEngine.postOpening` checks `last_entry_seq = 0` under the balance locks; `post` refuses `OPENING`) | `JournalsApiIT` (incl. two concurrent openings) |
| I22 | Integrity-check results cannot be changed or deleted | DB-G (`trg_icr_no_update`, `trg_icr_no_delete`, `INSERT`-only grant) | `FlywayMigrationIT`, `DatabasePrivilegesIT` |

## Why each layer exists

- **Domain validation** gives fast, precise errors and keeps rules testable without a DB.
- **Constraints** are the last line of defense against bugs in the application layer,
  manual SQL, or future code that bypasses the domain.
- **Transactions** make multi-row invariants atomic: no reader or crash ever observes a
  half-posted journal.
- **Locks** make check-then-act sequences safe under concurrency.
- **Detective controls** catch what preventive controls cannot express (cross-row sums)
  and prove correctness continuously, the way real ledgers run daily balance checks.

I1 (across rows) and I11 are **application-enforced**. No database constraint ties a
journal header to its entries or a balance row to the sum of its entries, and the runtime
user `ledger_app` has the `INSERT`/`UPDATE` grants that posting needs. The guarantee is that
only `PostingEngine` writes the ledger, in one transaction; the integrity checker detects a
violation after it happens but cannot prevent it. Anyone holding the runtime database
credentials can bypass these invariants with direct SQL, so those credentials must be
protected like the ledger itself (see [03](03-database-schema.md#enforcing-the-journal-balances-across-rows)).

I16, I17 (plaintext handling), I18 (the "no posting to a frozen account" part), I20 and I21
are also **application-enforced**: the runtime database user could violate them with direct
SQL. I19 and I22 are database-enforced.

## Invariants that are explicitly *not* claimed

- Correctness of external statement data (the bank can be wrong; that's what
  `MARK_BANK_ERROR` is for).
- Protection against a malicious DB administrator (an audit hash chain is an extension).
- Exactly-once delivery to external systems (no external integrations in MVP).
