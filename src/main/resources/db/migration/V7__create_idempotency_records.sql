-- Request deduplication and response replay (ADR-0006). A record is inserted and completed in
-- the same transaction as the effect it guards, so there is never a committed key without
-- an outcome. The unique scope makes a concurrent duplicate block on the insert.

CREATE TABLE idempotency_records (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    client_id       BIGINT       NOT NULL,
    operation       VARCHAR(32)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash    BINARY(32)   NOT NULL,
    -- NULL only inside the creating transaction.
    response_status SMALLINT     NULL,
    response_body   JSON         NULL,
    resource_id     VARCHAR(32)  CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at      DATETIME(6)  NOT NULL,
    expires_at      DATETIME(6)  NOT NULL,
    CONSTRAINT pk_idempotency_records PRIMARY KEY (id),
    CONSTRAINT uk_idem_scope UNIQUE (client_id, operation, idempotency_key),
    INDEX idx_idem_expires (expires_at),
    CONSTRAINT fk_idem_client FOREIGN KEY (client_id) REFERENCES api_clients (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_idem_operation CHECK (operation REGEXP '^[A-Z][A-Z_]*$'),
    CONSTRAINT ck_idem_key CHECK (idempotency_key REGEXP '^[A-Za-z0-9_-]{1,128}$'),
    CONSTRAINT ck_idem_status CHECK (response_status IS NULL OR response_status BETWEEN 200 AND 599)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
