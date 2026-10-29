-- Least-privilege grants for the runtime application user, re-applied whenever this file
-- changes. Every new table must be listed here explicitly; there are no schema-wide grants.
-- Append-only tables get SELECT, INSERT only (docs/adr/0008-immutability-and-reversals.md).
-- GRANT is additive: removing a line here does not revoke it; add an explicit REVOKE instead.
GRANT SELECT ON currencies TO '${app_user}'@'%';

-- Accounts are opened by the application. Lifecycle updates (name, status) get column-level
-- UPDATE grants when that feature lands; type, currency and normal_balance never change.
GRANT SELECT, INSERT ON accounts TO '${app_user}'@'%';

-- The posting engine locks balance rows (SELECT ... FOR UPDATE needs an UPDATE privilege) and
-- may change only the projection columns.
GRANT SELECT, INSERT ON account_balances TO '${app_user}'@'%';
GRANT UPDATE (balance, last_entry_seq, updated_at) ON account_balances TO '${app_user}'@'%';

GRANT SELECT, INSERT ON journal_transactions TO '${app_user}'@'%';
GRANT SELECT, INSERT ON ledger_entries TO '${app_user}'@'%';
GRANT SELECT, INSERT ON audit_events TO '${app_user}'@'%';
