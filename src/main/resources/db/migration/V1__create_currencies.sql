-- Supported currencies and their ISO 4217 minor units (decimal places).
-- Amount precision is validated against minor_units (docs/adr/0004-money-representation.md).
CREATE TABLE currencies (
    code        CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    minor_units TINYINT NOT NULL,
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT pk_currencies PRIMARY KEY (code),
    CONSTRAINT ck_currencies_code CHECK (code REGEXP '^[A-Z]{3}$'),
    CONSTRAINT ck_currencies_minor_units CHECK (minor_units BETWEEN 0 AND 4)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

INSERT INTO currencies (code, minor_units) VALUES
    ('EUR', 2),
    ('INR', 2),
    ('JPY', 0),
    ('KWD', 3),
    ('USD', 2);
