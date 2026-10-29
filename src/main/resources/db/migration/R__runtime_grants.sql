-- Least-privilege grants for the runtime application user, re-applied whenever this file
-- changes. Every new table must be listed here explicitly; there are no schema-wide grants.
-- Append-only tables get SELECT, INSERT only (docs/adr/0008-immutability-and-reversals.md).
GRANT SELECT ON currencies TO '${app_user}'@'%';
