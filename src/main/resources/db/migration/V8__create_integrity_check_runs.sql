-- Results of integrity checks (manual or scheduled). One row per completed run, never changed.
-- Violations hold check names and public ids only, never amounts.

CREATE TABLE integrity_check_runs (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    public_id       VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    trigger_type    ENUM('MANUAL','SCHEDULED') NOT NULL,
    triggered_by    VARCHAR(64) NOT NULL,
    started_at      DATETIME(6) NOT NULL,
    finished_at     DATETIME(6) NOT NULL,
    violation_count INT         NOT NULL,
    violations      JSON        NOT NULL,
    CONSTRAINT pk_integrity_check_runs PRIMARY KEY (id),
    CONSTRAINT uk_icr_public_id UNIQUE (public_id),
    INDEX idx_icr_finished (finished_at, id),
    CONSTRAINT ck_icr_public_id CHECK (public_id REGEXP '^ichk_[0-9A-HJKMNP-TV-Z]{26}$'),
    CONSTRAINT ck_icr_count CHECK (violation_count >= 0),
    CONSTRAINT ck_icr_times CHECK (finished_at >= started_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TRIGGER trg_icr_no_update BEFORE UPDATE ON integrity_check_runs FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'integrity_check_runs is append-only';

CREATE TRIGGER trg_icr_no_delete BEFORE DELETE ON integrity_check_runs FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'integrity_check_runs is append-only';
