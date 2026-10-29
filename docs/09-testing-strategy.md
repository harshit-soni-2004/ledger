# 09 — Testing Strategy

## Principles

1. **Database-critical behavior is tested on MySQL 8.4 via Testcontainers.** H2 is not
   used: locking, CHECK constraints, triggers, error codes, and isolation differ.
2. **Concurrency tests ship with the feature**, not in a later "testing phase".
3. Every test states its **expected outcome**; assertions check the database state, not
   just the HTTP status.
4. Invariant-driven: after every integration test that moves money, the integrity checker
   runs and must report zero violations (shared JUnit extension).
5. Tests must be deterministic. Concurrency tests use start barriers and assert outcomes
   that are order-independent (counts, sums), never timing.

## Test layers

| Layer | Naming | Runner | Infrastructure | Scope |
|---|---|---|---|---|
| Unit | `*Test` | Surefire | None | Domain (`Money`, `JournalDraft`, matching passes, CSV parsing), mappers |
| Property | `*PropertiesTest` | Surefire (jqwik engine) | None | Accounting invariants over random operation sequences |
| Integration | `*IT` | Failsafe | Testcontainers MySQL 8.4 (reused per run), full Spring context | Repositories, transactions, locking, constraints, API |
| Architecture | `ArchitectureTest` | Surefire | None | ArchUnit module and coding rules |
| Performance | k6 scripts | Manual / scheduled | Docker Compose | Latency and throughput hypotheses (Phase 11) |

Shared MySQL container: one per test JVM with `@ServiceConnection`; each test class
cleans data with a privileged *test-only* connection (`TRUNCATE` in FK-safe order), since
the runtime user cannot delete ledger rows by design.

## Test catalogue

### Money and domain (unit / property)

| Test | Expected outcome |
|---|---|
| `MoneyTest.parsesValidAmounts` | `"10.5"` INR → `10.50`; `"100"` JPY → `100`; `"1.234"` KWD → `1.234` |
| `MoneyTest.rejectsExcessPrecision` | `"10.005"` INR → `AmountPrecisionExceededException` |
| `MoneyTest.rejectsMalformed` | `"1e3"`, `"1,000"`, `" 10"`, `"-5"`, `"0"`, `""`, `"NaN"` → rejected with specific reasons |
| `MoneyTest.currencyMismatch` | INR + USD → `CurrencyMismatchException` |
| `MoneyTest.equalityIgnoresInputScale` | `Money("10.5", INR)` equals `Money("10.50", INR)` |
| `JournalDraftTest.unbalancedRejected` | Debits 100, credits 99.99 → exception, no draft |
| `JournalDraftTest.singleLineRejected` | One entry → exception |
| `JournalDraftTest.mixedCurrencyRejected` | INR + USD lines → exception |
| `JournalDraftTest.sameAccountBothSidesRejected` | Exception |
| `NormalBalanceTest` | For each of 5 types, debit/credit effect sign is correct |
| `JournalPropertiesTest` | For random valid drafts: Σ debits = Σ credits; mirror (reversal) of a draft is valid; applying draft then its mirror leaves all balances unchanged |
| `LedgerModelPropertiesTest` | For random sequences of transfers/reversals on an in-memory model: per-currency trial balance = 0; no FORBIDDEN account < 0; result independent of interleaving for commutative ops |

### Schema and migrations (integration)

| Test | Expected outcome |
|---|---|
| `FlywayMigrationIT` | Empty DB migrates to latest; `flyway validate` passes; re-run is a no-op |
| `StrictSqlModeIT` | `@@sql_mode` contains `STRICT_TRANS_TABLES`; inserting `'abc'` into a DECIMAL fails |
| `LedgerImmutabilityIT.triggerBlocksUpdate` | Privileged `UPDATE ledger_entries` → SQLSTATE `45000` |
| `LedgerImmutabilityIT.runtimeUserCannotUpdate` | App user `UPDATE`/`DELETE` → access denied (1142) |
| `JournalHeaderConstraintIT` | Inserting header with `total_debit ≠ total_credit` → CHECK violation (3819) |
| `CurrencyConsistencyConstraintIT` | Entry with currency ≠ account currency → FK violation |
| `BalanceFloorConstraintIT` | Direct `UPDATE account_balances SET balance = -1` on floor-0 account → CHECK violation |
| `ReversalUniquenessConstraintIT` | Two reversal headers for the same journal → unique violation |

### Ledger posting (integration)

| Test | Expected outcome |
|---|---|
| `PostingEngineIT.postsBalancedJournal` | 1 header, N entries, `account_seq` increments by 1, `balance_after` correct, balance rows updated, 1 audit event |
| `PostingRollbackIT` | Fault injector throws after first entry insert → 0 headers, 0 entries, balances unchanged, 0 audit events, 0 idempotency rows |
| `BalanceQueryIT.asOfDate` | Balance as of each historical date equals `balance_after` of last entry on/before that date |
| `IntegrityCheckerIT.clean` | After a scripted scenario: zero violations |
| `IntegrityCheckerIT.detectsDrift` | Privileged connection alters a balance row → checker reports exactly that account; metric incremented |

### Transfers, idempotency, concurrency (integration)

| Test | Setup | Expected outcome |
|---|---|---|
| `TransferApiIT.happyPath` | A=1000, B=0 | 201; A=700, B=300 after transfer of 300; 2 entries; transfer `POSTED` |
| `TransferValidationApiIT` | Various bad payloads | 400/403/422 with exact codes; **no** rows in transfers/journals/idempotency |
| `TransferIdempotencyIT.sequentialReplay` | Same key twice | Same body; second has `Idempotent-Replayed: true`; exactly 1 journal |
| `TransferIdempotencyIT.differentPayload` | Same key, different amount | 422 `IDEMPOTENCY_KEY_REUSED`; exactly 1 journal |
| `TransferIdempotencyIT.concurrentSameKey` | 20 threads, same key and body, start barrier | Exactly 1 journal; 20 identical bodies (1 original + 19 replays); A debited once |
| `TransferIdempotencyIT.rejectionReplayed` | A=0; transfer 10 → 422; fund A; same key again | Still 422 `INSUFFICIENT_FUNDS` (stored outcome); new key → 201 |
| `ConcurrentOverspendIT` | A=100.00; 50 threads × withdraw 10.00 (distinct keys) | Exactly 10 × 201 and 40 × 422; A = 0.00; A has 11 entries (seq 1..11, gapless); integrity clean |
| `ConcurrentOverspendIT.unevenAmounts` | A=100; threads withdraw random 1–30 | Σ successful ≤ 100; A ≥ 0; A = 100 − Σ successful |
| `OpposingTransfersDeadlockIT` | A=B=10,000; 200 concurrent transfers A→B and B→A of 1 | All 2xx (no 5xx); A + B = 20,000; retry metric recorded (may be 0) |
| `ManyAccountsConcurrencyIT` | 10 accounts, 500 random transfers on 32 threads | All balances ≥ 0; Σ balances conserved; integrity clean |
| `FrozenAccountRaceIT` | Freeze A concurrently with transfers from A | Every transfer either committed before freeze or rejected `ACCOUNT_NOT_ACTIVE`; none after freeze timestamp |
| `LostResponseRetryIT` | Execute transfer; discard response; retry same key | Replay 201; 1 journal |
| `CrashBeforeCommitIT` | Test hook kills the JDBC connection before commit | No rows; retry same key → 201 once |

### Reversals and audit (integration)

| Test | Expected outcome |
|---|---|
| `ReversalIT.reversesTransfer` | New `REVERSAL` journal mirrors original; original unchanged; balances restored; transfer `REVERSED`; audit events `JOURNAL_POSTED` + `TRANSFER_REVERSED` with reason |
| `ReversalIT.secondReversalRejected` | 409 `ALREADY_REVERSED` |
| `ReversalIT.reversalOfReversalRejected` | 422 `CANNOT_REVERSE_REVERSAL` |
| `ReversalIT.respectsFloor` | B already spent the funds → 422 `INSUFFICIENT_FUNDS`; no journal; original transfer still `POSTED` (assumption A6) |
| `ConcurrentReversalIT` | 10 threads reverse same journal (distinct keys) | Exactly 1 × 201, 9 × 409; 1 reversal journal |
| `AuditTrailIT` | Scripted scenario | Every financial state change has exactly one audit event with request and correlation IDs |

### Statement ingestion (unit + integration)

| Test | Expected outcome |
|---|---|
| `CsvParserTest` (per error code in [08](08-statement-csv-format.md)) | Each malformed line → `INVALID` with the expected code(s) |
| `CsvParserTest.bomAndCrlf` | Parses identically to plain LF file |
| `CsvParserTest.quotedComma` | `"VENDOR, INC"` parsed as one field |
| `StatementImportIT.clean` | `COMPLETED`; rows = data lines; all `VALID`; `balance_check = PASSED` |
| `MalformedStatementIT` | `COMPLETED_WITH_ERRORS`; every input line present in `statement_rows` with `raw_line`; invalid count matches fixture |
| `MissingHeaderIT` | Batch `REJECTED` with `MISSING_COLUMN`; file retained |
| `OversizeUploadIT` | 413; no rows stored |
| `DuplicateImportIT` | Same file again → 409 with original ID; row count unchanged |
| `OverlapImportIT` | Overlapping file → overlap rows `DUPLICATE` with `duplicate_of_row_id` |
| `PossibleDuplicateIT` | Two identical rows without IDs → second `POSSIBLE_DUPLICATE`, first `VALID` |
| `ImportRecoveryIT` | Batch left in `PARSING` with partial rows → recovery completes it without duplicate rows |
| `ConcurrentDuplicateImportIT` | Same file uploaded concurrently → one batch, one 409 |

### Reconciliation (unit + integration)

| Test | Expected outcome |
|---|---|
| `MatchingPassesTest` (one case per category) | Each crafted input yields exactly the expected category and method |
| `MatchingPassesTest.tieIsAmbiguous` | Two equal-amount candidates same day → both `AMBIGUOUS`, never matched |
| `MatchingPassesTest.directionInversion` | Bank CREDIT matches ledger DEBIT on cash account; bank CREDIT vs ledger CREDIT → `DIRECTION_MISMATCH` |
| `MatchingPassesTest.toleranceBoundary` | Date diff = tolerance → `MATCHED`; tolerance + 1 → `MATCHED_TIMING_DIFFERENCE` |
| `MatchingDeterminismPropertiesTest` | Shuffling input order yields identical item sets |
| `SeededDiscrepancyIT` | `with-discrepancies` sample + scripted ledger → category counts exactly equal the fixture's expected table; balance proof unexplained difference = 0 |
| `ReconciliationRerunIT` | Two runs on same inputs → equal `input_fingerprint`, equal item sets; ledger row counts unchanged |
| `ConcurrentRunStartIT` | Two starts at once → one 201, one 409 |
| `StaleRunRecoveryIT` | Run with stale heartbeat → sweeper marks `FAILED`; new run allowed |

### Resolution and ops (integration)

| Test | Expected outcome |
|---|---|
| `ResolutionIT.postAdjustmentForFee` | ADMIN resolves bank fee → `ADJUSTMENT` journal (Bank Charges / Cash), reason stored, audit event; rerun → row `MATCHED` by reference |
| `ResolutionIT.operatorCannotAdjust` | OPERATOR `POST_ADJUSTMENT` → 403 |
| `ResolutionIT.actionNotAllowedForCategory` | `IGNORE_DUPLICATE` on `AMOUNT_MISMATCH` → 422 `RESOLUTION_NOT_ALLOWED` |
| `ResolutionIdempotencyIT` | Same key twice → one resolution, one journal; different key same subject → 409 `ALREADY_RESOLVED` |
| `OpsReportsIT` | Open discrepancies and import failures endpoints return expected counts and paging |

### Security and API (integration)

| Test | Expected outcome |
|---|---|
| `AuthIT` | Missing/invalid key → 401; CLIENT reading another client's account → 404 (no existence leak) |
| `RoleIT` | Role matrix from [04](04-api-contract.md) enforced |
| `ErrorFormatIT` | All errors are Problem Details with `code` and `requestId`; no stack traces |
| `LoggingIT` | Log output for a transfer contains request/correlation IDs and no API key or full request body |

### Architecture (ArchUnit)

- Only `ledger.posting` writes to `journal_transactions`, `ledger_entries`, `account_balances`.
- `reconciliation` does not depend on `ledger.infrastructure`.
- `*.domain` packages do not depend on Spring or `java.sql`.
- No calls to `new BigDecimal(double)`, `BigDecimal.valueOf(double)`, `LocalDateTime.now()`, `Instant.now()` outside `shared.time`.
- No `@Transactional` on controllers; retry wrappers are not `@Transactional`.

## Coverage and quality gates

- JaCoCo: ≥ 90 % line coverage on `*.domain` and `ledger.posting`; overall reported, not gated.
- Concurrency ITs run in CI on every push; a flaky concurrency test is treated as a bug.
- Mutation testing (PIT) on `Money` and `JournalDraft` — optional, high learning value.
