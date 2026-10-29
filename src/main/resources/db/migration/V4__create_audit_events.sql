-- Who did what, when and why. Written in the same transaction as the change it records.

CREATE TABLE audit_events (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    occurred_at    DATETIME(6)  NOT NULL,
    actor_type     ENUM('CLIENT','OPERATOR','ADMIN','SYSTEM') NOT NULL,
    actor_id       VARCHAR(64)  NOT NULL,
    action         VARCHAR(64)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    entity_type    VARCHAR(32)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    entity_id      VARCHAR(32)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id     VARCHAR(64)  CHARACTER SET ascii COLLATE ascii_bin NULL,
    correlation_id VARCHAR(64)  CHARACTER SET ascii COLLATE ascii_bin NULL,
    -- No secrets; amounts allowed; account numbers masked.
    payload        JSON         NULL,
    CONSTRAINT pk_audit_events PRIMARY KEY (id),
    INDEX idx_audit_entity (entity_type, entity_id, occurred_at),
    INDEX idx_audit_time (occurred_at),
    INDEX idx_audit_correlation (correlation_id),
    CONSTRAINT ck_audit_action CHECK (action REGEXP '^[A-Z][A-Z_]*$')
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TRIGGER trg_audit_no_update BEFORE UPDATE ON audit_events FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_events is append-only';

CREATE TRIGGER trg_audit_no_delete BEFORE DELETE ON audit_events FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_events is append-only';
