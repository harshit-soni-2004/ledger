# ADR-0007: Single-currency journals; no cross-currency transfers in the MVP

- Status: Accepted
- Date: 2026-10-29

## Context

Double-entry balance ("debits = credits") is only meaningful within one currency.
Cross-currency movement requires an FX rate source, quote expiry, rounding policy, and
FX position/gain-loss accounts — significant scope that does not strengthen the core
correctness story.

## Decision

1. Every account has exactly one currency (immutable after creation).
2. Every journal transaction has exactly one currency; all its entries use it.
3. Database enforcement via composite foreign keys:
   - `ledger_entries(account_id, currency) → accounts(id, currency)`
   - `ledger_entries(journal_id, currency) → journal_transactions(id, currency)`
4. Transfers between accounts of different currencies are rejected:
   `422 CURRENCY_MISMATCH`.

## Future extension (documented, not built)

A conversion is **two journals in one DB transaction**, each balanced in its own currency,
linked by an `fx_conversions` record (rate `DECIMAL(20,10)`, quote ID, expiry):

- USD journal: debit `Wallet USD`, credit `FX Position USD`
- INR journal: debit `FX Position INR`, credit `Wallet INR`
- Rounding with `HALF_EVEN` at the target currency scale; residual to `FX Gain/Loss`.

## Consequences

- The balancing invariant is simple and partly DB-enforced.
- Multi-currency platforms are still supported (one account per currency per owner).
