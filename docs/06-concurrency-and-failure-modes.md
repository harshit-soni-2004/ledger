# 06 — Concurrency and Failure-Mode Analysis

## 1. The overspend race

Account A (wallet, floor 0) has balance 100.00. Two requests withdraw 80.00 concurrently.

### Naive design (unsafe)

```text
T1: SELECT balance → 100        T2: SELECT balance → 100
T1: 100 >= 80 ✓                 T2: 100 >= 80 ✓
T1: INSERT entries, UPDATE       T2: INSERT entries, UPDATE
T1: COMMIT                       T2: COMMIT            → balance −60  ✗
```

Variants that are **also unsafe**:

| Variant | Why it still fails |
|---|---|
| Check `SUM(ledger_entries)` instead of a balance column | Each transaction cannot see the other's uncommitted entries |
| REPEATABLE READ plain `SELECT` after some waiting | Reads the transaction's snapshot, which can be stale even after the other commits |
| Idempotency key on each request | Keys are different; idempotency prevents *duplicates*, not *overspending* |
| `synchronized` in Java | Doesn't span multiple app instances or protect against other code paths |
| SERIALIZABLE | Correct but both take shared locks then try to upgrade → frequent deadlocks |

### Chosen design

```text
T1: SELECT … FROM account_balances WHERE account_id = A FOR UPDATE  → 100 (row locked)
T2: SELECT … FOR UPDATE                                             → blocks
T1: 100 − 80 = 20 ≥ 0 ✓ ; INSERT journal/entries ; UPDATE balance = 20 ; COMMIT (lock released)
T2: (unblocks) reads latest committed → 20
T2: 20 − 80 < 0 ✗ ; INSERT transfer REJECTED ; store 422 in idempotency ; COMMIT
```

Backstop: even if the application check had a bug, `ck_balances_floor` rejects the
`UPDATE` that would set balance below `min_balance`, rolling back T2.

## 2. Lock ordering and deadlocks

### Global lock order (every money-moving transaction)

1. `INSERT` into `idempotency_records` (unique-index record lock on the key).
2. Business row being changed, if any (e.g. original transfer during reversal) `FOR UPDATE`.
3. `account_balances` rows for all involved accounts, **ascending internal `account_id`**,
   one `SELECT … FOR UPDATE` per account.
4. Inserts (journal, entries, transfer, audit) and balance updates.

### Classic deadlock this prevents

T1 transfers A→B and locks A then B; T2 transfers B→A and locks B then A → cycle. With
ascending order both lock min(A,B) first, so one simply waits for the other.

### Deadlocks that can still occur

InnoDB can still deadlock on secondary-index or foreign-key checks (FK checks take shared
locks on parent rows), and on unique-key inserts racing with each other. Therefore:

- MySQL errors **1213** (deadlock) and **1205** (lock wait timeout) are translated by Spring
  to `PessimisticLockingFailureException` subclasses.
- A `RetryingTransferService` (separate bean, **outside** the transaction) re-executes the
  whole `@Transactional` method: up to 3 attempts, backoff 20 ms × 2ⁿ with ±50 % jitter.
- Retrying is safe because the idempotency reservation is part of the rolled-back
  transaction — nothing partial survives.
- After the last attempt: `503 CONTENTION_RETRY_EXHAUSTED`; the client retries with the
  same key.
- Metric `ledger.lock.retries{operation,outcome}` and a WARN log with the deadlock victim
  operation. `SHOW ENGINE INNODB STATUS` is the diagnostic tool, documented in ops notes.

Common mistake to avoid: retrying **inside** a `@Transactional` method. After a deadlock
the transaction is already rolled back; continuing in it is meaningless.

## 3. Idempotency races

| Scenario | Behavior |
|---|---|
| Two identical requests (same key) arrive concurrently | Second `INSERT` into `idempotency_records` blocks on the unique key. If the first commits → 1062 duplicate → replay. If the first rolls back → second proceeds as the original. |
| Same key, different payload, concurrently | Same blocking; on 1062 the hash differs → `422 IDEMPOTENCY_KEY_REUSED`. |
| First request still running > lock wait timeout | `409 REQUEST_IN_PROGRESS`, `Retry-After: 1`. |
| Different keys, same intent (client bug) | Two transfers. Mitigation is client-side (generate key once per intent) plus `clientReference` lookup. Documented, not prevented. |

## 4. Failure modes

| # | Failure | What happens | Client-visible outcome | Test |
|---|---|---|---|---|
| F1 | App crashes before commit | InnoDB rolls back the open transaction when the connection drops | No effect; retry with same key executes normally | `CrashBeforeCommitIT` (kill connection mid-transaction) |
| F2 | Commit succeeds, response lost (timeout, crash after commit) | Effect durable; idempotency row has stored response | Retry with same key → original 201 replayed | `LostResponseRetryIT` |
| F3 | Commit outcome unknown (connection dropped during COMMIT) | Either committed or not — app cannot know | App returns 500; client retries with same key; idempotency resolves either way | `CommitUncertaintyIT` (optional, Toxiproxy) |
| F4 | Deadlock / lock timeout | Transaction rolled back, retried ≤ 3 times | Success, or 503 → client retries same key | `OpposingTransfersDeadlockIT` |
| F5 | Constraint violation mid-posting (bug) | Whole transaction rolls back | 500 `INTERNAL_ERROR`; nothing persisted; alert via metric | `PostingRollbackIT` |
| F6 | Integrity drift detected (should be impossible) | Checker records violation; metric increments; **no auto-repair** | Ops investigates; correction via reversal/adjustment | `IntegrityCheckerIT` with drift injected by a privileged test connection |
| F7 | Crash during CSV parsing | File + batch already committed (txn 1). Batch remains `PARSING`. Row inserts idempotent via `uk_rows_batch_line` | Startup recovery job resumes or marks `REJECTED` with reason; operator sees status | `ImportRecoveryIT` |
| F8 | Duplicate file uploaded concurrently | `uk_batches_file` lets one insert win; the other gets 409 | One batch | `ConcurrentDuplicateImportIT` |
| F9 | Crash during reconciliation run | Run row stays `RUNNING` with stale `heartbeat_at`; no items committed (items written in final txn) | Sweeper marks `FAILED` after 5 min without heartbeat; new run allowed | `StaleRunRecoveryIT` |
| F10 | Two runs started concurrently for one bank account | `uk_runs_active` lets one win | Other gets `409 RUN_ALREADY_ACTIVE` | `ConcurrentRunStartIT` |
| F11 | Resolution posting an adjustment retried | Idempotency + `uk_resolutions_subject` | Single adjustment journal | `ResolutionIdempotencyIT` |
| F12 | Database unavailable | Readiness probe fails; requests fail fast (Hikari timeout ~3 s) | 503; no partial state | Manual/compose demo |
| F13 | Clock skew / time-zone boundary | Instants in UTC; business dates computed with configured zone via injected `Clock` | Deterministic dates | `EffectiveDateTimeZoneTest` |
| F14 | Process restart while requests in flight | Graceful shutdown (`server.shutdown=graceful`, 20 s) completes in-flight transactions; others roll back (F1) | Retry with same key is safe | Covered by F1/F2 |

## 5. Timeouts

| Setting | Value (initial) | Reason |
|---|---|---|
| `innodb_lock_wait_timeout` (session) | 5 s | Fail fast on contention; retry policy handles transient waits |
| Transaction timeout (`@Transactional(timeout)`) | 10 s for posting | Bound worst case |
| Hikari `connectionTimeout` | 3 s | Fail fast when DB is unavailable |
| Hikari `maximumPoolSize` | 20 (tune in Phase 11) | Must exceed concurrency-test parallelism or tests measure pool waits |
| HTTP server request timeout | 30 s | Client timeouts are expected; idempotency covers them |

## 6. Hot accounts

Every deposit and payout locks `ASSET.BANK.OPERATING`. Writers to the same hot account
serialize, capping throughput at roughly `1 / (lock hold time)`.

Plan: measure in Phase 11 (k6 scenario "all deposits"). If it matters, options in order of
simplicity:

1. Keep lock hold time minimal (no remote calls inside the transaction — already a rule).
2. For accounts with `UNLIMITED` overdraft (system accounts), skip the running balance:
   no `FOR UPDATE`, `account_seq`/`balance_after` NULL for those entries, balance derived
   by `SUM` or a periodically materialized snapshot. Requires a schema tweak and an ADR.
3. Sub-account sharding (`Cash at Bank #0..#N`) aggregated for reporting.

We will not pre-optimize; we will measure and document.
