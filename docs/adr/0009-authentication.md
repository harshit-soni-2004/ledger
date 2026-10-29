# ADR-0009: API-key authentication with roles

- Status: Accepted (implemented in Phase 4)
- Date: 2026-10-29

## Decision

1. Clients authenticate with `Authorization: Bearer <api_key>`. Requests without the header
   are anonymous and reach only the public routes (health probes, `/error`, OpenAPI in the
   local profile); every `/api` route needs a key, and `/api` routes not in the role matrix
   are denied for everyone.
2. Tokens are `lk_` + 43 base62 characters from `SecureRandom` (about 256 bits), shown once
   at creation and stored only as SHA-256 in `api_keys.key_hash` (`BINARY(32)`, unique). A
   fast hash is enough because the token is random, not a password. The first 11 characters
   are stored as a non-secret display prefix for logs and support.
3. Keys live in their own table so a client can rotate: issue a second key, switch, revoke
   the old one. At most two active keys per client (application rule). Revoking a key or
   disabling a client is a status change, checked on every request with no cache, so it
   takes effect immediately.
4. Roles and what they may do in Phase 4:

   | Route | CLIENT | OPERATOR | ADMIN |
   |---|---|---|---|
   | `POST /accounts` | own FORBIDDEN liability wallets | FORBIDDEN liability wallets for an `ownerClientId` | any type, FORBIDDEN or LIMITED |
   | `GET /accounts`, `/{id}`, `/balance`, `/entries` | own accounts only (others 404) | all | all |
   | `POST /accounts/{id}/freeze` | — | yes | yes |
   | `POST /accounts/{id}/unfreeze`, `/close` | — | — | yes |
   | `GET /journal-transactions/{id}` | — | yes | yes |
   | `POST /journal-transactions/openings` | — | — | yes |
   | `/ops/**`, `/api-clients/**` | — | — | yes |

   Nobody may open an `UNLIMITED` overdraft account through the API; only the
   migration-seeded system accounts have one.
5. Ownership: `accounts.owner_client_id` must equal the caller's client id for CLIENT access.
   Other accounts, including system accounts, are reported as `404 ACCOUNT_NOT_FOUND` so ids
   cannot be probed.
6. Local/demo bootstrap: `LEDGER_BOOTSTRAP_ADMIN_KEY` registers one ADMIN client on start-up
   when no active ADMIN exists. The value comes from the environment and is never committed.
   Client and key creation are deliberately not idempotent-replayable: replay would mean
   storing the plaintext token.

## Alternatives considered

| Alternative | Trade-off |
|---|---|
| OAuth2 resource server with Keycloak / JWT issuer | Realistic for user-facing apps, but adds infrastructure unrelated to ledger correctness. Possible extension. |
| HTTP Basic | Weak ergonomics; credentials sent every request. |
| No auth | Unacceptable even for a demo of a financial API. |
| Slow KDF (bcrypt/argon2) for key hashes | Protects low-entropy secrets; a 256-bit random token gains nothing and every request pays the cost. |

## Consequences

- Simple, realistic (payment APIs commonly use API keys).
- No user login UI or token refresh complexity.
- One indexed lookup per request; no session state (the security filter chain is stateless,
  CSRF is off because there are no cookies).
- Authorization is application-enforced; the database grants limit what the runtime user can
  do but cannot tell roles apart.
