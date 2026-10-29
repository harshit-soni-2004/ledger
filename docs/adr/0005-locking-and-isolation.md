# ADR-0005: READ COMMITTED isolation with ordered pessimistic row locks

- Status: Accepted
- Date: 2026-10-29

## Context

Concurrent debits against the same account must never overdraw it. MySQL InnoDB defaults
to REPEATABLE READ, where plain `SELECT`s read a transaction snapshot and locking reads
take next-key (gap) locks.

## Decision

1. Money-moving transactions run at **READ COMMITTED**.
2. Each account has one row in `account_balances`. That row is the **lock point**.
3. Every money-moving transaction locks the balance rows of all accounts it touches,
   **one statement per account, in ascending internal `account_id` order**, using
   `SELECT … FROM account_balances WHERE account_id = ? FOR UPDATE`.
4. The overdraft check is performed against the **locked** balance value.
5. The balance update is `balance = balance + delta` and is backed by a DB `CHECK`
   (`balance >= min_balance` when a floor exists) as a last line of defense.
6. Global lock order for every transaction type:
   (1) idempotency key unique-index insert → (2) balance rows ascending → (3) inserts.
7. Deadlocks (MySQL 1213) and lock-wait timeouts (1205) are retried by re-running the
   **whole** transaction (max 3 attempts, jittered backoff) from a wrapper bean *outside*
   the `@Transactional` proxy. `innodb_lock_wait_timeout` is set to ~5 s for app sessions.

Full analysis: [06-concurrency-and-failure-modes.md](../06-concurrency-and-failure-modes.md).

## How it prevents concurrent overspending

Two withdrawals of 80 from a balance of 100:

1. T1 locks the balance row, reads 100, passes the check, writes 20, commits.
2. T2's `FOR UPDATE` blocks until T1 commits, then reads the **latest committed** value 20
   (locking reads always read the newest version), fails the check, and records a
   rejected transfer.

Without the lock, both would read 100 and both would succeed (balance −60).

## Alternatives considered

| Alternative | Why not chosen (as primary) |
|---|---|
| REPEATABLE READ (default) + `FOR UPDATE` | Works, but gap/next-key locks raise deadlock frequency, and a *non-locking* read inside the same transaction would see a stale snapshot — an easy bug to write. |
| SERIALIZABLE | InnoDB converts plain reads into shared locks; two transactions holding S locks that both upgrade to X deadlock frequently. |
| Optimistic locking (`version` column, retry on conflict) | Correct, but under contention on hot accounts produces retry storms; harder to reason about for multi-account journals. |
| Guarded atomic update (`UPDATE … SET balance = balance - ? WHERE account_id = ? AND balance - ? >= 0`) | Excellent for a single debit; we keep the equivalent semantics as a DB `CHECK` backstop. We still need the locked read to compute `account_seq` and `balance_after`. |
| Computing balance via `SUM(entries)` per request | No natural lock point; gets slower as history grows. |
| Application-level locks (`synchronized`, Redis locks) | Do not span multiple app instances (synchronized) or the DB transaction (Redis). |

## Consequences

- Correctness does not depend on the isolation level alone; it depends on explicit locks.
- Hot accounts (e.g. `Cash at Bank`) serialize writers. Measured in Phase 11; mitigation
  documented (skip running balance for unlimited-overdraft system accounts).
- Binary logging must be row-based (MySQL 8.4 default) for READ COMMITTED.
