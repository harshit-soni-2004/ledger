#!/bin/bash
# Creates the two application database users. The MySQL image runs this once, on first start
# with an empty data directory. Shared by docker-compose.yml and the Testcontainers test base.
#
#   migration user: owns DDL for the ledger schema; used only by Flyway. WITH GRANT OPTION lets
#                   Flyway's R__runtime_grants.sql grant table-level privileges to the app user.
#   app user:       no privileges here; every table grant is explicit in R__runtime_grants.sql.
#
# Passwords must not contain quotes (single or double) or backslashes.
# No `set -u` or `exit`: the entrypoint sources this file when it is not executable.

: "${MYSQL_ROOT_PASSWORD:?MYSQL_ROOT_PASSWORD is required}"
: "${MYSQL_DATABASE:?MYSQL_DATABASE is required}"
: "${LEDGER_DB_MIGRATION_USER:?LEDGER_DB_MIGRATION_USER is required}"
: "${LEDGER_DB_MIGRATION_PASSWORD:?LEDGER_DB_MIGRATION_PASSWORD is required}"
: "${LEDGER_DB_APP_USER:?LEDGER_DB_APP_USER is required}"
: "${LEDGER_DB_APP_PASSWORD:?LEDGER_DB_APP_PASSWORD is required}"

# The password goes through an option file, not argv, so it never shows in the process list.
mysql --defaults-extra-file=<(printf '[client]\npassword="%s"\n' "${MYSQL_ROOT_PASSWORD}") \
    --protocol=socket -uroot <<EOSQL
CREATE USER '${LEDGER_DB_MIGRATION_USER}'@'%' IDENTIFIED BY '${LEDGER_DB_MIGRATION_PASSWORD}';
GRANT ALL PRIVILEGES ON \`${MYSQL_DATABASE}\`.* TO '${LEDGER_DB_MIGRATION_USER}'@'%' WITH GRANT OPTION;
CREATE USER '${LEDGER_DB_APP_USER}'@'%' IDENTIFIED BY '${LEDGER_DB_APP_PASSWORD}';
EOSQL
