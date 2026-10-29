# ADR-0006: Idempotency recorded inside the posting transaction

- Status: Accepted
- Date: 2026-10-29

## Context

Clients retry: after timeouts, network errors, or double clicks. A retry is **not** proof
that the first attempt failed — the first may have committed and only the response was
lost. Idempotency and concurrency must be designed together: an idempotency key prevents
*duplicate execution of the same request*; it does **not** prevent two *different*
requests from overspending (that is ADR-0005's job).

## Decision

1. Money-moving `POST` endpoints require an `Idempotency-Key` header (client-generated,
   1–128 chars, `[A-Za-z0-9_-]`; UUIDs recommended).
2. Scope: `(client_id, operation, idempotency_key)` — unique index.
3. `request_hash` = SHA-256 of the **normalized command** (parsed values, amounts at
   currency scale, canonical field order), so formatting differences do not count as a
   different request.
4. Flow, all inside **one** transaction:
   1. `INSERT` the idempotency row (response columns `NULL`). The unique index makes a
      concurrent duplicate **block** on this insert.
   2. Lock balances, validate, post (or decide a business rejection).
   3. `UPDATE` the idempotency row with the response status and body.
   4. `COMMIT`.
5. On duplicate-key error (1062): roll back, read the committed record in a new
   transaction, then:
   - hash matches → replay stored status/body with header `Idempotent-Replayed: true`;
   - hash differs → `422 IDEMPOTENCY_KEY_REUSED`, no side effects.
6. If the competing transaction is still running past the lock-wait timeout →
   `409 REQUEST_IN_PROGRESS` with `Retry-After`.
7. Business rejections (`INSUFFICIENT_FUNDS`, `ACCOUNT_NOT_ACTIVE`) **commit** a
   `REJECTED` transfer + idempotency record and are replayed for the same key
   (assumption A5, pending confirmation). Validation errors (400) occur before the
   transaction and are not stored. Server errors roll back everything and are not stored,
   so retrying the same key re-executes.
8. Retention: 30 days, then a purge job deletes expired idempotency rows. Transfers keep
   their own unique `(client_id, idempotency_key)` permanently, so a very late retry
   cannot create a duplicate transfer even after the replay cache is purged.

## Phase 4 implementation notes

Pulled forward from Phase 5 (`IdempotencyService`, `V7__create_idempotency_records.sql`).

- Guarded endpoints: `POST /accounts`, freeze/unfreeze/close, `POST /journal-transactions/openings`.
  Client and key management is not guarded: replaying it would require storing the token.
- `request_hash` is SHA-256 over the operation name and the JSON of the parsed request
  record (plus path ids for lifecycle calls). Amounts are hashed as sent, so `"10.0"` and
  `"10.00"` count as different requests; the result is a `422`, never a duplicate effect.
- Point 7 (committing business rejections) applies to transfers and is not used yet: in
  Phase 4 every rejection rolls back, so the same key can be retried after fixing the request.
- `409 REQUEST_IN_PROGRESS` carries `Retry-After: 1`. The session lock-wait timeout is 5 s.
- The purge job is deferred: the runtime user has no `DELETE` on `idempotency_records`, and an
  expired key keeps replaying (`expires_at` is informational until the purge lands).

## Why no committed "IN_PROGRESS" state

Because the reservation and the effect commit together, there is never a committed key
without an outcome. A crash mid-transaction rolls back both. This removes a whole class of
recovery logic (stuck in-progress keys) that designs with a separate idempotency store need.

## Alternatives considered

| Alternative | Problem |
|---|---|
| Redis `SETNX` key store | Not in the DB transaction: key set but posting fails (or vice versa) → stuck or duplicated effects. |
| Separate idempotency transaction (reserve, then post, then complete) | Needs IN_PROGRESS recovery, timeouts, and reconciliation of orphaned keys. |
| Dedup only on business fields (same amount/accounts within N seconds) | Blocks legitimate identical payments; misses retries after the window. |

## Consequences

- Duplicate requests serialize on the unique index; throughput impact is negligible.
- Clients must generate the key **before** the first attempt and reuse it on retry.
- `GET /transfers?clientReference=` lets clients investigate unknown outcomes.
