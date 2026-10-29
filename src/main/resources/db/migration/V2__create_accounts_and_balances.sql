-- Chart of accounts and the per-account balance projection (docs/03-database-schema.md).
-- Account ownership (owner_client_id -> api_clients) arrives with authentication.

CREATE TABLE accounts (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    public_id        VARCHAR(32)   CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    system_code      VARCHAR(64)   CHARACTER SET ascii COLLATE ascii_bin NULL,
    name             VARCHAR(128)  NOT NULL,
    type             ENUM('ASSET','LIABILITY','EQUITY','REVENUE','EXPENSE') NOT NULL,
    normal_balance   ENUM('DEBIT','CREDIT') NOT NULL,
    currency         CHAR(3)       CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    overdraft_policy ENUM('FORBIDDEN','LIMITED','UNLIMITED') NOT NULL,
    overdraft_limit  DECIMAL(19,4) NULL,
    status           ENUM('ACTIVE','FROZEN','CLOSED') NOT NULL DEFAULT 'ACTIVE',
    created_at       DATETIME(6)   NOT NULL,
    updated_at       DATETIME(6)   NOT NULL,
    CONSTRAINT pk_accounts PRIMARY KEY (id),
    CONSTRAINT uk_accounts_public_id UNIQUE (public_id),
    CONSTRAINT uk_accounts_system_code_currency UNIQUE (system_code, currency),
    -- Target of the composite foreign keys that tie entry currency to account currency.
    CONSTRAINT uk_accounts_id_currency UNIQUE (id, currency),
    CONSTRAINT fk_accounts_currency FOREIGN KEY (currency) REFERENCES currencies (code)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_accounts_public_id CHECK (public_id REGEXP '^acct_[0-9A-HJKMNP-TV-Z]{26}$'),
    CONSTRAINT ck_accounts_name CHECK (CHAR_LENGTH(TRIM(name)) > 0),
    CONSTRAINT ck_accounts_normal_balance CHECK (
           (type IN ('ASSET','EXPENSE') AND normal_balance = 'DEBIT')
        OR (type IN ('LIABILITY','EQUITY','REVENUE') AND normal_balance = 'CREDIT')),
    CONSTRAINT ck_accounts_overdraft CHECK (
           (overdraft_policy = 'LIMITED' AND overdraft_limit IS NOT NULL AND overdraft_limit > 0)
        OR (overdraft_policy <> 'LIMITED' AND overdraft_limit IS NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- One row per account: the balance projection and the posting lock point (ADR-0005).
-- balance is in the account's normal orientation. min_balance is the floor derived from the
-- overdraft policy (0 = FORBIDDEN, -limit = LIMITED, NULL = UNLIMITED).
CREATE TABLE account_balances (
    account_id     BIGINT        NOT NULL,
    currency       CHAR(3)       CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    balance        DECIMAL(19,4) NOT NULL DEFAULT 0,
    min_balance    DECIMAL(19,4) NULL,
    last_entry_seq BIGINT        NOT NULL DEFAULT 0,
    updated_at     DATETIME(6)   NOT NULL,
    CONSTRAINT pk_account_balances PRIMARY KEY (account_id),
    CONSTRAINT fk_balances_account FOREIGN KEY (account_id, currency) REFERENCES accounts (id, currency)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_balances_floor CHECK (min_balance IS NULL OR balance >= min_balance),
    CONSTRAINT ck_balances_min_balance CHECK (min_balance IS NULL OR min_balance <= 0),
    CONSTRAINT ck_balances_last_entry_seq CHECK (last_entry_seq >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
