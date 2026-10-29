# ADR-0008: Append-only ledger; corrections by reversal

- Status: Accepted
- Date: 2026-10-29

## Decision

1. `journal_transactions`, `ledger_entries`, `audit_events`, `statement_files`,
   `reconciliation_items`, and `discrepancy_resolutions` are **append-only**.
2. Enforcement in three layers:
   - **Code:** repositories for these tables expose only `insert` and `select`.
   - **Grants:** the runtime DB user has only `SELECT, INSERT` on these tables. Flyway runs
     as a separate migration user with DDL rights.
   - **Triggers:** `BEFORE UPDATE` and `BEFORE DELETE` triggers `SIGNAL SQLSTATE '45000'`.
   - Foreign keys use `ON DELETE RESTRICT` (no cascades).
3. A journal has no mutable status. Only posted journals exist. "Reversed" is derived from
   the existence of a reversal journal pointing at it.
4. A **reversal** is a new journal of type `REVERSAL` with every entry's direction flipped,
   `reverses_journal_id` set, and a required reason. `reverses_journal_id` is `UNIQUE`, so
   a journal is reversed at most once — enforced by the DB even under concurrency.
5. A **correction** is a reversal plus a new correct journal sharing a `correlation_id`.
6. Business records (`transfers`) may change status (`POSTED → REVERSED`) with an audit
   event; the financial record underneath never changes.
7. Reversals respect overdraft rules with **no override** in the MVP: the DB floor CHECK
   (`ck_balances_floor`) would reject a negative wallet balance anyway, and allowing one
   would require an explicit, audited overdraft-policy change first (assumption A6,
   pending confirmation).
8. Partial reversals are deferred.

## Alternatives considered

| Alternative | Problem |
|---|---|
| Soft delete / `is_void` flag on entries | Mutates history; balance queries must remember to filter; easy to bypass. |
| Editing amounts in place | Destroys audit trail; breaks reconciliation and point-in-time balances. |
| Triggers only | Bypassable by a privileged user; grants are the stronger control. Triggers alone also don't document intent in code. |

## Consequences

- Point-in-time balances are always reproducible.
- Triggers on MySQL with binary logging require `log_bin_trust_function_creators=1` (or
  SUPER) for the migration user — handled in local Docker config and documented.
- `TRUNCATE`/`DROP` bypass triggers; grants prevent the runtime user from issuing them.
