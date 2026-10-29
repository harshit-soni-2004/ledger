# 13 — Interview Preparation

## Resume-worthy outcomes (fill brackets only from recorded evidence)

| Claim template | Evidence required before use |
|---|---|
| "Designed and built a double-entry ledger on MySQL enforcing [N] financial invariants through constraints, ordered row locking, and transactional idempotency." | Invariants matrix with passing tests ([05](05-invariants.md)) |
| "Proved overdraft safety under concurrency with [50]-thread Testcontainers tests (exactly-k-succeed assertions) running on every CI build." | Test names and CI history |
| "Eliminated duplicate financial effects from client retries by storing idempotency keys and responses in the posting transaction; verified with concurrent same-key tests." | `TransferIdempotencyIT` |
| "Sustained [X] transfers/s at p95 [Y] ms on [hardware]; identified a hot-account ceiling of [Z]/s and documented mitigation." | `docs/benchmarks.md` with methodology |
| "Built deterministic bank reconciliation classifying [12] discrepancy types with a bank reconciliation proof; 100 % correct classification on a labeled dataset of [K] injected discrepancies; reconciles [R] rows in [T] s." | `SeededDiscrepancyIT`, benchmark H6 |
| "Enforced append-only financial history via least-privilege DB grants and triggers; corrections via reversal journals." | `LedgerImmutabilityIT` |

Do not state throughput, latency, or scale numbers that are not in `docs/benchmarks.md`.

## Questions this project should prepare you to answer

### Accounting and modeling

1. Why double-entry instead of a `balance` column? What does it let you prove?
2. What are normal balances? Why is a customer wallet a liability on the platform's books?
3. How do you represent money? `BigDecimal` vs minor-unit `long` — trade-offs? Why never `double`?
4. How do you handle currencies with 0 or 3 decimal places?
5. Ledger balance vs available balance — how would holds change your overdraft check?
6. How would you add cross-currency transfers without breaking the balancing invariant?
7. How do you correct a wrong posting? Why not just update the row?

### Consistency and concurrency

8. Two withdrawals of 80 hit a balance of 100 simultaneously. Walk through your design.
9. Why isn't an idempotency key enough to prevent overspending?
10. `SELECT … FOR UPDATE` vs optimistic locking vs guarded `UPDATE … WHERE balance >= ?` — when each?
11. Why READ COMMITTED? What does MySQL REPEATABLE READ do to plain reads vs locking reads?
12. How do you avoid deadlocks? Which can still happen, and how do you retry safely?
13. Why must the retry be outside the `@Transactional` boundary?
14. How do you enforce "debits = credits" when a CHECK constraint can only see one row?
15. Your hot cash account limits throughput. What are your options?

### Idempotency and failures

16. A client timed out. Did the money move? What should the client do?
17. Same idempotency key, different body — what do you return and why?
18. Why not store idempotency keys in Redis?
19. Where would a committed "in progress" idempotency state cause problems, and how does your design avoid it?
20. The DB connection drops during `COMMIT`. What does the client see, and why is it still safe?
21. How long do you keep idempotency keys, and what happens after expiry?

### Reconciliation

22. A bank statement CREDIT corresponds to which side in your ledger, and why?
23. Walk through your matching passes. Why is the algorithm deterministic?
24. Two candidates match equally. What happens?
25. Why isn't an unmatched statement row necessarily an error?
26. Matching vs settlement confirmation — what's the difference?
27. How do re-imports and re-runs avoid duplicate effects?
28. How would you handle one bank line that settles twenty payouts?
29. What is a bank reconciliation proof and what does a non-zero difference mean?

### Operations, security, architecture

30. How do you prove balances are correct in production (detective controls)?
31. What do you log, and what must you never log?
32. How do you prevent the application from mutating history even if there's a bug?
33. Why a modular monolith? When would you split it, and what would the seam be (outbox)?
34. How do you test concurrency deterministically and avoid flaky tests?
35. Why Testcontainers instead of H2 for this project?
36. How would you scale beyond one MySQL primary (read replicas for queries, partitioning, sharding by account)?
37. What would you change to support multiple tenants?
