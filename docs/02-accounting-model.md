# 02 — Accounting Model

## Perspective

The ledger is kept from the **platform's** point of view. The platform holds real money in
a bank account and owes its customers the balances of their wallets.

## Account types and normal balances

| Type | Normal balance | Increases with | Example |
|---|---|---|---|
| ASSET | Debit | Debit | `ASSET.BANK.OPERATING` — Cash at Bank (the platform's real bank account) |
| LIABILITY | Credit | Credit | Customer wallets; `LIABILITY.SUSPENSE` (unidentified funds) |
| EQUITY | Credit | Credit | `EQUITY.OWNER_CAPITAL` (opening balances) |
| REVENUE | Credit | Credit | `REVENUE.FEES` |
| EXPENSE | Debit | Debit | `EXPENSE.BANK_CHARGES` |

**Displayed balance** is expressed in the account's normal orientation:

- debit-normal: `balance = Σ debits − Σ credits`
- credit-normal: `balance = Σ credits − Σ debits`

A positive wallet balance therefore means "the platform owes the customer this much".

Contra accounts (e.g. allowance accounts) are out of scope; the DB `CHECK` tying type to
normal balance would need relaxing to support them.

## Chart of system accounts (seeded per currency)

| Code | Type | Overdraft policy | Purpose |
|---|---|---|---|
| `ASSET.BANK.OPERATING` | ASSET | UNLIMITED* | Mirrors the platform's bank account; reconciled against statements |
| `LIABILITY.SUSPENSE` | LIABILITY | UNLIMITED | Holds unidentified incoming funds pending investigation |
| `EQUITY.OWNER_CAPITAL` | EQUITY | UNLIMITED | Opening balances and demo funding |
| `REVENUE.FEES` | REVENUE | UNLIMITED | Fee income (extension: transfer fees) |
| `EXPENSE.BANK_CHARGES` | EXPENSE | UNLIMITED | Bank fees discovered during reconciliation |

\* A real bank account can go negative only with an overdraft facility; for the demo we
allow it so that ordering of simulated movements never blocks. This is documented, not hidden.

Customer wallets: `LIABILITY`, `overdraft_policy = FORBIDDEN` (floor 0).

## Posting patterns

| Business event | Debit | Credit | Journal type |
|---|---|---|---|
| Customer deposits ₹1,000 into the bank (simulated) | Cash at Bank 1,000 | Wallet A 1,000 | `DEPOSIT` |
| Wallet A pays Wallet B ₹300 | Wallet A 300 | Wallet B 300 | `TRANSFER` |
| Payout ₹200 from Wallet B to an external bank account | Wallet B 200 | Cash at Bank 200 | `PAYOUT` |
| Reversal of the ₹300 transfer | Wallet B 300 | Wallet A 300 | `REVERSAL` |
| Bank charged a ₹15 fee (found in reconciliation) | Bank Charges 15 | Cash at Bank 15 | `ADJUSTMENT` |
| Unidentified ₹500 deposit on statement | Cash at Bank 500 | Suspense 500 | `ADJUSTMENT` |
| Platform owner seeds capital | Cash at Bank X | Owner Capital X | `OPENING` |

Payout in the MVP posts directly against Cash at Bank. The clearing lifecycle
(`Wallet → Payouts Clearing` at initiation, `Payouts Clearing → Cash at Bank` on confirmed
settlement) is an extension and illustrates "matching vs settlement confirmation".

## Sign conventions with the bank

| Bank statement says | Meaning for the platform | Ledger entry on `ASSET.BANK.OPERATING` |
|---|---|---|
| `CREDIT` | Money arrived in our bank account | **DEBIT** (asset increases) |
| `DEBIT` | Money left our bank account | **CREDIT** (asset decreases) |

The bank describes the account from *its* perspective (our deposit is its liability).
Getting this inversion wrong is a classic reconciliation bug.

## Journal transaction rules

A journal can be posted only if all of these hold (checked by `JournalDraft` before any SQL):

1. At least two entries.
2. All entries share the journal currency; each entry's account has that currency.
3. Every entry amount is strictly positive and has scale ≤ currency minor units.
4. Σ debit amounts = Σ credit amounts (exact `BigDecimal.compareTo == 0`).
5. No account is both debited and credited in the same journal (prevents no-op noise; an
   explicit rule we can relax later).
6. All accounts are `ACTIVE` (checked again under lock).
7. Accounts with a floor do not go below it after applying the journal.

Every entry carries its journal's `effective_date` and `posted_at`. The entry columns are
copies kept for indexing (balance-as-of-date and statement queries read entries alone); the
journal header is the source of truth. `PostingEngine` writes both from the same values in
the same transaction, and the integrity checker reports any entry whose copy differs
(`ENTRY_EFFECTIVE_DATE_MISMATCH`, `ENTRY_POSTED_AT_MISMATCH`).

## Ledger balance vs available balance

- **Ledger balance:** sum of all posted entries for the account (normal orientation).
- **Available balance:** what may be spent now = ledger balance − active holds + overdraft
  limit (if any).

MVP has **no holds**, so for wallets `available = ledger`. The API returns both fields so
adding holds later is non-breaking.

Holds extension semantics (deferred):

- `holds(account_id, amount, status ACTIVE|CAPTURED|RELEASED|EXPIRED, expires_at)`.
- Creating a hold locks the balance row and checks `available ≥ amount`.
- Capture posts a journal and marks the hold `CAPTURED` in one transaction.
- Release/expiry changes only the hold, never the ledger.

## Reversal and correction semantics

- Full reversal only (MVP): new `REVERSAL` journal mirroring the original with directions
  flipped, same currency and amounts, `reverses_journal_id` set (unique).
- Reversing a reversal is **not allowed** (return 422 `CANNOT_REVERSE_REVERSAL`); re-apply
  the original intent with a new transfer instead. This keeps chains simple and auditable.
- Correction = reversal + new correct journal, linked by `correlation_id`.
- Overdraft: reversals obey floors with no override in the MVP; if the recipient already
  spent the funds, the reversal fails with `INSUFFICIENT_FUNDS` (assumption A6, pending
  confirmation).

## Currency precision

| Currency | Minor units | Valid | Invalid (rejected) |
|---|---|---|---|
| INR | 2 | `"10.50"`, `"10.5"`, `"10"` | `"10.505"` |
| USD | 2 | `"0.01"` | `"0.001"` |
| JPY | 0 | `"100"` | `"100.5"` |
| KWD | 3 | `"1.234"` | `"1.2345"` |

`"10.5"` is accepted and normalized to `10.50`. Trailing zeros beyond the currency scale
are accepted because no value is lost (`"10.500"` INR → `10.50`); any non-zero digit beyond
it is rejected, never rounded. Negative, zero, exponent (`"1e3"`), grouping separators
(`"1,000.00"`), and non-numeric values are rejected. At most 15 integer digits (the range of
`DECIMAL(19,4)`).
