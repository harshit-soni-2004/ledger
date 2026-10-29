# 12 — Project README Outline and Demo Scenarios

The repository `README.md` is written incrementally (minimal in Phase 1, complete in
Phase 11). Target outline:

1. **Title and one-paragraph pitch** — "A double-entry ledger and bank reconciliation
   service that stays correct under concurrency, retries, and crashes."
2. **Why this isn't CRUD** — three bullets: append-only ledger, transactional idempotency,
   locked overdraft checks; link to [05-invariants](05-invariants.md).
3. **Architecture diagram** — module view from [01](01-architecture.md).
4. **Quick start** — three commands (`.env`, `docker compose up -d mysql`, `./mvnw spring-boot:run`) and the Swagger UI URL.
5. **Accounting primer** — normal balances, posting patterns table, bank sign inversion.
6. **Invariants table** — invariant, enforcement, test name.
7. **API tour** — curl examples for the demo flow.
8. **Testing** — how to run; highlight the concurrency tests and what they prove.
9. **Benchmarks** — table from `docs/benchmarks.md` with hardware and methodology.
10. **Design decisions** — ADR index with one-line summaries.
11. **Limitations and extensions** — honest list.
12. **License.**

## Demo scenarios (scripted in Phase 11 as `scripts/demo.http` or a shell script)

| # | Scenario | What it shows | Expected visible result |
|---|---|---|---|
| D1 | Seed capital, create two wallets, deposit into wallet A, transfer A → B | Double-entry posting | Entries on cash, A, B; trial balance 0 |
| D2 | Send the same transfer twice with the same `Idempotency-Key` | Idempotency | Second response identical, `Idempotent-Replayed: true`; one journal |
| D3 | Same key, different amount | Conflict detection | 422 `IDEMPOTENCY_KEY_REUSED` |
| D4 | Fire 50 concurrent withdrawals of 10 against a balance of 100 | Overdraft protection under concurrency | 10 succeed, 40 rejected; balance 0; integrity check clean |
| D5 | Reverse a transfer; try again | Immutable history, single reversal | Original + reversal visible; second attempt 409 |
| D6 | Payouts with references; one near period end | Bank-facing activity | Payout journals with external references |
| D7 | Upload `with-discrepancies` statement | Ingestion | Batch summary with invalid/duplicate counts; row errors listed |
| D8 | Upload the same file again | Duplicate import protection | 409 with original import ID |
| D9 | Run reconciliation | Matching + taxonomy | Report: categories, match rate, balance proof |
| D10 | Resolve the bank fee with `POST_ADJUSTMENT`; rerun | Auditable correction | New adjustment journal; fee row now `MATCHED`; audit event with reason |
| D11 | Rerun reconciliation with no changes | Deterministic, side-effect-free | Identical results; ledger counts unchanged |
| D12 | `POST /ops/integrity-checks` | Detective controls | Zero violations |
