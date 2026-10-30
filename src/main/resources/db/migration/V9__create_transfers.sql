-- Wallet-to-wallet transfers (docs/03-database-schema.md, Phase 5). One row per accepted
-- request: POSTED with its journal, or REJECTED with the business rule that refused it, so a
-- retried key replays the same outcome (docs/14 Q3). REVERSED is reserved for Phase 6.

CREATE TABLE transfers (
    id                     BIGINT        NOT NULL AUTO_INCREMENT,
    public_id              VARCHAR(32)   CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    client_id              BIGINT        NOT NULL,
    idempotency_key        VARCHAR(128)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_account_id      BIGINT        NOT NULL,
    destination_account_id BIGINT        NOT NULL,
    currency               CHAR(3)       CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    amount                 DECIMAL(19,4) NOT NULL,
    status                 ENUM('POSTED','REJECTED','REVERSED') NOT NULL,
    rejection_code         VARCHAR(40)   CHARACTER SET ascii COLLATE ascii_bin NULL,
    journal_id             BIGINT        NULL,
    reversal_journal_id    BIGINT        NULL,
    client_reference       VARCHAR(64)   CHARACTER SET ascii COLLATE ascii_bin NULL,
    description            VARCHAR(255)  NULL,
    created_at             DATETIME(6)   NOT NULL,
    updated_at             DATETIME(6)   NOT NULL,
    CONSTRAINT pk_transfers PRIMARY KEY (id),
    CONSTRAINT uk_transfers_public_id UNIQUE (public_id),
    -- Permanent: survives any future purge of idempotency_records.
    CONSTRAINT uk_transfers_client_key UNIQUE (client_id, idempotency_key),
    CONSTRAINT uk_transfers_journal UNIQUE (journal_id),
    CONSTRAINT uk_transfers_reversal UNIQUE (reversal_journal_id),
    INDEX idx_transfers_client (client_id, id),
    INDEX idx_transfers_client_reference (client_id, client_reference),
    INDEX idx_transfers_source (source_account_id),
    INDEX idx_transfers_destination (destination_account_id),
    CONSTRAINT fk_transfers_client FOREIGN KEY (client_id) REFERENCES api_clients (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    -- Composite: both accounts and the journal are in the transfer currency.
    CONSTRAINT fk_transfers_source FOREIGN KEY (source_account_id, currency) REFERENCES accounts (id, currency)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_transfers_destination FOREIGN KEY (destination_account_id, currency)
        REFERENCES accounts (id, currency) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_transfers_journal FOREIGN KEY (journal_id, currency) REFERENCES journal_transactions (id, currency)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_transfers_reversal FOREIGN KEY (reversal_journal_id, currency)
        REFERENCES journal_transactions (id, currency) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_transfers_public_id CHECK (public_id REGEXP '^trf_[0-9A-HJKMNP-TV-Z]{26}$'),
    CONSTRAINT ck_transfers_key CHECK (idempotency_key REGEXP '^[A-Za-z0-9_-]{1,128}$'),
    CONSTRAINT ck_transfers_distinct CHECK (source_account_id <> destination_account_id),
    CONSTRAINT ck_transfers_amount CHECK (amount > 0),
    CONSTRAINT ck_transfers_status CHECK (
           (status = 'POSTED'   AND journal_id IS NOT NULL AND rejection_code IS NULL AND reversal_journal_id IS NULL)
        OR (status = 'REJECTED' AND journal_id IS NULL     AND rejection_code IS NOT NULL
                                AND reversal_journal_id IS NULL)
        OR (status = 'REVERSED' AND journal_id IS NOT NULL AND rejection_code IS NULL
                                AND reversal_journal_id IS NOT NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TRIGGER trg_transfers_no_delete BEFORE DELETE ON transfers FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'transfers are never deleted';
