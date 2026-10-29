# ADR-0009: API-key authentication with roles

- Status: Proposed (default chosen; pending confirmation)
- Date: 2026-10-29

## Decision

1. Clients authenticate with `Authorization: Bearer <api_key>`.
2. Keys are random (≥ 32 bytes, base62), shown once at creation, and stored as SHA-256
   hashes in `api_clients.key_hash` (unique). A short non-secret prefix is stored for
   identification in logs.
3. Roles:
   - `CLIENT` — owns wallet accounts; may create transfers from accounts it owns and read its own data.
   - `OPERATOR` — deposits/payouts simulation, statement imports, reconciliation runs, non-financial resolutions.
   - `ADMIN` — reversals, adjustment resolutions, integrity checks, client management.
4. Ownership: `accounts.owner_id` must equal the caller's client ID for CLIENT-scoped access.
5. Local/demo keys are seeded from environment variables, never committed.

## Alternatives considered

| Alternative | Trade-off |
|---|---|
| OAuth2 resource server with Keycloak / JWT issuer | Realistic for user-facing apps, but adds infrastructure unrelated to ledger correctness. Possible extension. |
| HTTP Basic | Weak ergonomics; credentials sent every request. |
| No auth | Unacceptable even for a demo of a financial API. |

## Consequences

- Simple, realistic (payment APIs commonly use API keys).
- No user login UI or token refresh complexity.
- Key rotation: create new key, revoke old (status change) — documented, not automated.
