-- Append-only journal headers and ledger entries (ADR-0008).
--
-- A CHECK constraint sees one row, so "debits = credits" across entry rows cannot be a
-- constraint. It is enforced in layers (docs/03-database-schema.md): JournalDraft, the
-- header CHECK below, one transaction per posting, a single writer, and the integrity checker.

CREATE TABLE journal_transactions (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    public_id           VARCHAR(32)   CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    type                ENUM('TRANSFER','DEPOSIT','PAYOUT','REVERSAL','ADJUSTMENT','OPENING') NOT NULL,
    currency            CHAR(3)       CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    total_debit         DECIMAL(19,4) NOT NULL,
    total_credit        DECIMAL(19,4) NOT NULL,
    reverses_journal_id BIGINT        NULL,
    external_reference  VARCHAR(64)   CHARACTER SET ascii COLLATE ascii_bin NULL,
    description         VARCHAR(255)  NULL,
    reason              VARCHAR(500)  NULL,
    effective_date      DATE          NOT NULL,
    posted_at           DATETIME(6)   NOT NULL,
    posted_by           VARCHAR(64)   NOT NULL,
    request_id          VARCHAR(64)   CHARACTER SET ascii COLLATE ascii_bin NULL,
    correlation_id      VARCHAR(64)   CHARACTER SET ascii COLLATE ascii_bin NULL,
    CONSTRAINT pk_journal_transactions PRIMARY KEY (id),
    CONSTRAINT uk_jtx_public_id UNIQUE (public_id),
    -- A journal is reversed at most once, even under concurrency.
    CONSTRAINT uk_jtx_reverses UNIQUE (reverses_journal_id),
    CONSTRAINT uk_jtx_id_currency UNIQUE (id, currency),
    INDEX idx_jtx_external_reference (external_reference),
    INDEX idx_jtx_effective_date (effective_date, id),
    INDEX idx_jtx_correlation (correlation_id),
    CONSTRAINT fk_jtx_currency FOREIGN KEY (currency) REFERENCES currencies (code)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    -- Composite: a reversal is in the same currency as the journal it reverses.
    CONSTRAINT fk_jtx_reverses FOREIGN KEY (reverses_journal_id, currency) REFERENCES journal_transactions (id, currency)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_jtx_public_id CHECK (public_id REGEXP '^jtx_[0-9A-HJKMNP-TV-Z]{26}$'),
    CONSTRAINT ck_jtx_balanced CHECK (total_debit = total_credit AND total_debit > 0),
    CONSTRAINT ck_jtx_reversal_link CHECK ((type = 'REVERSAL') = (reverses_journal_id IS NOT NULL)),
    -- A CHECK that evaluates to NULL passes, hence the explicit IS NOT NULL.
    CONSTRAINT ck_jtx_reason CHECK (
        type NOT IN ('REVERSAL','ADJUSTMENT') OR (reason IS NOT NULL AND CHAR_LENGTH(TRIM(reason)) > 0))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE ledger_entries (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    journal_id     BIGINT        NOT NULL,
    line_no        SMALLINT      NOT NULL,
    account_id     BIGINT        NOT NULL,
    currency       CHAR(3)       CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    direction      ENUM('DEBIT','CREDIT') NOT NULL,
    amount         DECIMAL(19,4) NOT NULL,
    -- 1, 2, 3, ... per account with no gaps; a duplicate means a lost update and is rejected.
    account_seq    BIGINT        NOT NULL,
    -- Account balance (normal orientation) after this entry, in posting order.
    balance_after  DECIMAL(19,4) NOT NULL,
    effective_date DATE          NOT NULL,
    posted_at      DATETIME(6)   NOT NULL,
    CONSTRAINT pk_ledger_entries PRIMARY KEY (id),
    CONSTRAINT uk_entries_line UNIQUE (journal_id, line_no),
    CONSTRAINT uk_entries_account_seq UNIQUE (account_id, account_seq),
    INDEX idx_entries_account_date (account_id, effective_date, account_seq),
    -- Composite: entry currency = journal currency and entry currency = account currency,
    -- so a journal cannot mix currencies.
    CONSTRAINT fk_entries_journal FOREIGN KEY (journal_id, currency) REFERENCES journal_transactions (id, currency)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_entries_account FOREIGN KEY (account_id, currency) REFERENCES accounts (id, currency)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_entries_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_entries_line_no CHECK (line_no > 0),
    CONSTRAINT ck_entries_account_seq CHECK (account_seq > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Triggers stop even privileged sessions from editing history; grants stop the runtime user
-- (who also cannot TRUNCATE or DROP, which bypass triggers).
CREATE TRIGGER trg_jtx_no_update BEFORE UPDATE ON journal_transactions FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'journal_transactions is append-only';

CREATE TRIGGER trg_jtx_no_delete BEFORE DELETE ON journal_transactions FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'journal_transactions is append-only';

CREATE TRIGGER trg_entries_no_update BEFORE UPDATE ON ledger_entries FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'ledger_entries is append-only';

CREATE TRIGGER trg_entries_no_delete BEFORE DELETE ON ledger_entries FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'ledger_entries is append-only';
