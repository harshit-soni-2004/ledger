-- Least-privilege grants for the runtime application user, re-applied whenever this file
-- changes. Every new table must be listed here explicitly; there are no schema-wide grants.
-- Append-only tables get SELECT, INSERT only (docs/adr/0008-immutability-and-reversals.md).
-- GRANT is additive: removing a line here does not revoke it; add an explicit REVOKE instead.
GRANT SELECT ON currencies TO '${app_user}'@'%';

-- Accounts are opened by the application. Only the lifecycle status may change afterwards;
-- name, type, currency, normal_balance, overdraft and owner never change.
GRANT SELECT, INSERT ON accounts TO '${app_user}'@'%';
GRANT UPDATE (status, updated_at) ON accounts TO '${app_user}'@'%';

-- The posting engine locks balance rows (SELECT ... FOR UPDATE needs an UPDATE privilege) and
-- may change only the projection columns.
GRANT SELECT, INSERT ON account_balances TO '${app_user}'@'%';
GRANT UPDATE (balance, last_entry_seq, updated_at) ON account_balances TO '${app_user}'@'%';

GRANT SELECT, INSERT ON journal_transactions TO '${app_user}'@'%';
GRANT SELECT, INSERT ON ledger_entries TO '${app_user}'@'%';
GRANT SELECT, INSERT ON audit_events TO '${app_user}'@'%';

-- Clients are disabled and keys revoked by status; neither is ever deleted.
GRANT SELECT, INSERT ON api_clients TO '${app_user}'@'%';
GRANT UPDATE (status, updated_at) ON api_clients TO '${app_user}'@'%';
GRANT SELECT, INSERT ON api_keys TO '${app_user}'@'%';
GRANT UPDATE (status, revoked_at) ON api_keys TO '${app_user}'@'%';

-- An idempotency record is completed (response columns) in its creating transaction. The
-- retention purge (DELETE) arrives with its job in Phase 5.
GRANT SELECT, INSERT ON idempotency_records TO '${app_user}'@'%';
GRANT UPDATE (response_status, response_body, resource_id) ON idempotency_records TO '${app_user}'@'%';

GRANT SELECT, INSERT ON integrity_check_runs TO '${app_user}'@'%';

-- Transfers are written once (POSTED or REJECTED). The status change to REVERSED needs an
-- UPDATE grant that arrives with reversals.
GRANT SELECT, INSERT ON transfers TO '${app_user}'@'%';
