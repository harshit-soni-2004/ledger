-- API clients, their keys (ADR-0009) and account ownership.
--
-- A key is stored only as the SHA-256 of the full token; the plaintext is shown once at
-- creation. Keys and clients are never deleted: revocation and disabling are status changes,
-- so every journal and audit event keeps a resolvable actor.

CREATE TABLE api_clients (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    public_id  VARCHAR(32)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name       VARCHAR(128) NOT NULL,
    role       ENUM('CLIENT','OPERATOR','ADMIN') NOT NULL,
    status     ENUM('ACTIVE','DISABLED') NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6)  NOT NULL,
    updated_at DATETIME(6)  NOT NULL,
    CONSTRAINT pk_api_clients PRIMARY KEY (id),
    CONSTRAINT uk_api_clients_public_id UNIQUE (public_id),
    CONSTRAINT ck_api_clients_public_id CHECK (public_id REGEXP '^cli_[0-9A-HJKMNP-TV-Z]{26}$'),
    CONSTRAINT ck_api_clients_name CHECK (CHAR_LENGTH(TRIM(name)) > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE api_keys (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    public_id  VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    client_id  BIGINT      NOT NULL,
    -- First characters of the token: identifies a key in logs and support without revealing it.
    key_prefix VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    key_hash   BINARY(32)  NOT NULL,
    status     ENUM('ACTIVE','REVOKED') NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NULL,
    revoked_at DATETIME(6) NULL,
    CONSTRAINT pk_api_keys PRIMARY KEY (id),
    CONSTRAINT uk_api_keys_public_id UNIQUE (public_id),
    CONSTRAINT uk_api_keys_hash UNIQUE (key_hash),
    INDEX idx_api_keys_client (client_id, status),
    CONSTRAINT fk_api_keys_client FOREIGN KEY (client_id) REFERENCES api_clients (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_api_keys_public_id CHECK (public_id REGEXP '^key_[0-9A-HJKMNP-TV-Z]{26}$'),
    CONSTRAINT ck_api_keys_revoked CHECK ((status = 'REVOKED') = (revoked_at IS NOT NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- NULL owner: a system account, or a platform account opened by an operator or admin.
ALTER TABLE accounts
    ADD COLUMN owner_client_id BIGINT NULL AFTER public_id,
    ADD INDEX idx_accounts_owner (owner_client_id, id),
    ADD CONSTRAINT fk_accounts_owner FOREIGN KEY (owner_client_id) REFERENCES api_clients (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    ADD CONSTRAINT ck_accounts_system_unowned CHECK (system_code IS NULL OR owner_client_id IS NULL),
    -- Postings route through system accounts; freezing or closing one would stop the ledger.
    ADD CONSTRAINT ck_accounts_system_active CHECK (system_code IS NULL OR status = 'ACTIVE');
