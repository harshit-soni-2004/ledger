# 08 — Statement CSV Format (mapping profile `default-v1`)

The project uses a documented, simulated bank-statement format. Other layouts are
supported through mapping profiles (column names, date format, amount style), but the
canonical internal representation is always the one below.

## File-level rules

| Rule | Value | Violation outcome |
|---|---|---|
| Encoding | UTF-8 (BOM tolerated and stripped) | `REJECTED`: `INVALID_ENCODING` |
| Format | RFC 4180 CSV; comma separator; `"` quoting; CRLF or LF | Malformed quoting → affected rows `INVALID` (`MALFORMED_CSV`), or `REJECTED` if the parser cannot resynchronize |
| Header | Required, first line, case-insensitive, trimmed | Missing required column → `REJECTED`: `MISSING_COLUMN` |
| Size | ≤ 10 MB | `413` before storage |
| Rows | 1–100,000 data rows | 0 → `REJECTED`: `EMPTY_FILE`; > 100,000 → `REJECTED`: `TOO_MANY_ROWS` |
| Line length | ≤ 4,096 chars | Row `INVALID`: `LINE_TOO_LONG` |
| Optional trailer | Lines starting with `#` are comments (used for opening/closing balance metadata) | — |

## Columns

| Column | Required | Format | Notes |
|---|---|---|---|
| `bank_txn_id` | Recommended | `[A-Za-z0-9_-]{1,64}` | Bank's unique ID; drives duplicate detection |
| `value_date` | Yes | `YYYY-MM-DD` | Date funds moved; used for matching |
| `booking_date` | No | `YYYY-MM-DD` | Date bank booked it |
| `direction` | Yes | `CREDIT` or `DEBIT` (bank's perspective) | CREDIT = money into our bank account |
| `amount` | Yes | Positive decimal, `.` separator, no grouping, no sign, scale ≤ currency minor units | `1000.00` ✓ `1,000.00` ✗ `-5` ✗ `1e3` ✗ |
| `currency` | Yes | ISO 4217 code in `currencies`, must equal the bank account currency | Mismatch → row `INVALID`: `CURRENCY_NOT_ALLOWED` |
| `reference` | No | ≤ 64 chars after normalization | Matched against `journal.external_reference` |
| `description` | No | ≤ 255 chars | Free text, stored as-is (escaped on output) |
| `running_balance` | No | Signed decimal | Used for continuity checks if present |

Metadata comment lines (optional):

```text
# opening_balance=100000.00
# closing_balance=112485.00
# period_start=2026-10-01
# period_end=2026-10-31
```

If opening and closing balances are present, the importer checks
`opening + Σ credits − Σ debits (valid canonical rows) = closing` → `balance_check = PASSED/FAILED`.
A failed check is a warning on the batch, not a rejection (the data is still evidence).

## Example

```text
# opening_balance=100000.00
# closing_balance=112485.00
bank_txn_id,value_date,booking_date,direction,amount,currency,reference,description,running_balance
BNK-20261001-0001,2026-10-01,2026-10-01,CREDIT,15000.00,INR,DEP-7Q2K9,NEFT IN ACME TRADERS,115000.00
BNK-20261002-0002,2026-10-02,2026-10-02,DEBIT,2500.00,INR,PAY-3M8X1,IMPS OUT VENDOR ONE,112500.00
BNK-20261031-0003,2026-10-31,2026-10-31,DEBIT,15.00,INR,,ACCOUNT MAINTENANCE FEE,112485.00
```

## Row validation error codes

| Code | Example |
|---|---|
| `REQUIRED_FIELD_MISSING` | empty `value_date` |
| `INVALID_DATE` | `2026-02-30`, `31/10/2026` |
| `INVALID_AMOUNT` | `abc`, `1,000.00`, `1e3` |
| `NON_POSITIVE_AMOUNT` | `0`, `-5` |
| `AMOUNT_PRECISION_EXCEEDED` | `10.005` for INR |
| `UNKNOWN_CURRENCY` | `XYZ` |
| `CURRENCY_NOT_ALLOWED` | `USD` row in an INR bank account |
| `INVALID_DIRECTION` | `CR`, `IN` |
| `FIELD_TOO_LONG` | 300-char description |
| `COLUMN_COUNT_MISMATCH` | extra/missing cells |
| `MALFORMED_CSV` | unbalanced quotes |

A row may have several errors; all are recorded in `errors` JSON. Invalid rows are never
discarded: `raw_line` is always stored.

## Duplicate detection

| Situation | Outcome |
|---|---|
| Same file (same SHA-256) for the same bank account | `409 DUPLICATE_IMPORT`; no new batch |
| `bank_txn_id` already seen (this file or earlier import) | Row `DUPLICATE`, `duplicate_of_row_id` set, `dedup_key` NULL |
| No `bank_txn_id` and identical fingerprint (`value_date, direction, amount, currency, reference, description`) to an existing row | Row `POSSIBLE_DUPLICATE` — **not** auto-discarded, because two identical real transactions are possible |
| Overlapping statement periods (Oct 1–15 and Oct 10–31) | Overlap rows become `DUPLICATE` via `bank_txn_id` |

## Security

- Files are parsed as data only; never executed or evaluated.
- Size/row/line limits bound memory and CPU.
- Values beginning with `=`, `+`, `-`, `@`, tab, or CR are prefixed with `'` when exported
  to CSV (formula-injection protection).
- Raw files are stored in the database (MVP) and never served back with an executable
  content type.

## Mapping profiles

A profile defines: column name aliases, date pattern, amount style
(`SINGLE_UNSIGNED_WITH_DIRECTION` | `SINGLE_SIGNED` | `SEPARATE_DEBIT_CREDIT_COLUMNS`),
and decimal separator. MVP ships `default-v1` and one alternate profile
(`signed-amount-v1`) defined in configuration to prove the abstraction. A DB-managed
profile table is an extension.

## Sample files (to be added in Phase 8 under `samples/statements/`)

| File | Purpose |
|---|---|
| `clean-2026-10.csv` | All rows valid; all match |
| `with-discrepancies-2026-10.csv` | Bank fee, amount mismatch, missing reference, late payout, unknown deposit |
| `malformed-2026-10.csv` | One example per row error code |
| `overlap-2026-10-10_31.csv` | Overlaps `clean` to exercise duplicates |
| `signed-amount-2026-10.csv` | Alternate mapping profile |
