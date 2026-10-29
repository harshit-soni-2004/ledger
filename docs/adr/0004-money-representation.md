# ADR-0004: Money representation — `BigDecimal` + `DECIMAL(19,4)` with explicit currency

- Status: Accepted
- Date: 2026-10-29

## Context

Monetary amounts must be exact. Different currencies have different precision
(ISO 4217 minor units: INR 2, USD 2, JPY 0, KWD 3). Floating point (`double`, `float`,
JSON numbers parsed as doubles) cannot represent most decimal fractions exactly.

## Decision

1. **Java:** a `Money` value object holding a `BigDecimal` amount and a `CurrencyUnit`.
   - The canonical constructor normalizes the amount with
     `amount.setScale(currency.minorUnits(), RoundingMode.UNNECESSARY)`. Excess precision
     throws, which we translate to `AMOUNT_PRECISION_EXCEEDED`. **We reject, never round,
     user-supplied amounts.** Trailing zeros beyond the currency scale (`"10.500"` INR) are
     accepted, because dropping them loses nothing. More than 15 integer digits is rejected
     (`OUT_OF_RANGE`), matching `DECIMAL(19,4)`.
   - Arithmetic (`plus`, `minus`, `negate`, `isNegative`) requires matching currencies;
     mixing currencies throws `CurrencyMismatchException`.
   - Comparisons use `compareTo`, never `BigDecimal.equals` (which also compares scale).
     Because amounts are normalized to the currency scale, `Money.equals` is still safe.
   - Construct `BigDecimal` only from `String` or `long`. ArchUnit forbids calls to
     `new BigDecimal(double)` and `BigDecimal.valueOf(double)`.
   - Rounding modes appear only in future FX code (`HALF_EVEN`, documented per use).
2. **Database:** `DECIMAL(19,4)` for every amount and balance column.
   - Scale 4 covers all supported currencies (max 3 minor units today) with headroom.
   - 15 integer digits (up to 999 trillion) — far beyond any realistic single balance.
   - MySQL `DECIMAL` arithmetic (`SUM`, `+`, `-`) is exact.
   - Values read from the DB (scale 4) are re-normalized to the currency scale; a stored
     value that cannot be normalized exactly indicates corruption and fails loudly.
3. **API:** amounts are **JSON strings** (`"1500.00"`) plus an explicit `currency`.
   JSON numbers for amounts are rejected with 400.
4. **Currency table:** `currencies(code, minor_units, active)` seeded with the supported set.
   The application validates currency codes against it, and accounts reference it by FK.

## Alternatives considered

| Alternative | Trade-off |
|---|---|
| `BIGINT` minor units (Stripe style) | Exact and compact; makes sub-unit values impossible by construction. Rejected per project directive to use `BigDecimal`; conversion at every boundary is error-prone for learners. Valid alternative to discuss in interviews. |
| `DECIMAL(19,2)` | Cannot represent 3-decimal currencies (KWD, BHD). |
| `double` / `FLOAT` | Inexact. Never acceptable for money. |
| Joda-Money / JSR 354 (Moneta) | Good libraries, but a small in-house `Money` type is more instructive and has no dependency risk. |

## Consequences

- The DB cannot by itself enforce "scale ≤ currency minor units" (it would need a
  cross-table check). Enforcement: the `Money` constructor + API validation + an
  integrity-check query that finds entries whose amount has more decimals than the
  account currency allows.
- Every amount column has `CHECK (amount > 0)` where direction is stored separately.
- Overflow is practically impossible with `DECIMAL(19,4)`; the integrity checker and a
  domain guard (max single amount configurable) bound it anyway.
