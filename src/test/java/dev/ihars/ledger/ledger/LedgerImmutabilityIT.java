package dev.ihars.ledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ihars.ledger.accounts.Account;
import dev.ihars.ledger.ledger.posting.PostedJournal;
import dev.ihars.ledger.support.LedgerIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

/**
 * Posted history cannot be edited or deleted, not even by a session with full table
 * privileges (ADR-0008). The runtime user is additionally stopped by its grants
 * (DatabasePrivilegesIT).
 */
class LedgerImmutabilityIT extends LedgerIntegrationTest {

    private static final int ROW_IS_REFERENCED = 1451;

    @Test
    void postedJournalsAndEntriesCannotBeUpdatedOrDeletedEvenWithFullPrivileges() {
        Account wallet = data.wallet("INR");
        PostedJournal posted = data.deposit(wallet, "25.00");

        assertBlockedByTrigger("UPDATE journal_transactions SET description = 'edited' WHERE id = " + posted.id());
        assertBlockedByTrigger("DELETE FROM journal_transactions WHERE id = " + posted.id());
        assertBlockedByTrigger("UPDATE ledger_entries SET amount = 1 WHERE journal_id = " + posted.id());
        assertBlockedByTrigger("DELETE FROM ledger_entries WHERE journal_id = " + posted.id());

        assertThat(queries.journal(posted.publicId()).orElseThrow().entries()).hasSize(2);
        assertThat(queries.entries(wallet.id(), 0, 10).getFirst().amount().toPlainString()).isEqualTo("25.00");
    }

    @Test
    void auditEventsCannotBeUpdatedOrDeletedEvenWithFullPrivileges() {
        Account wallet = data.wallet("INR");
        String where = " WHERE entity_id = '" + wallet.publicId() + "'";

        assertBlockedByTrigger("UPDATE audit_events SET actor_id = 'someone-else'" + where);
        assertBlockedByTrigger("DELETE FROM audit_events" + where);
        assertThat(count("SELECT COUNT(*) FROM audit_events" + where)).isEqualTo(1);
    }

    @Test
    void accountsWithEntriesCannotBeDeleted() {
        Account wallet = data.wallet("INR");
        data.deposit(wallet, "1.00");

        assertThatThrownBy(() -> privilegedJdbc().sql("DELETE FROM accounts WHERE id = ?")
                        .param(wallet.id()).update())
                .isInstanceOf(DataAccessException.class)
                .satisfies(e -> assertThat(mysqlErrorCode(e)).contains(ROW_IS_REFERENCED));
        assertThat(accounts.findById(wallet.id())).isPresent();
    }

    private static void assertBlockedByTrigger(String sql) {
        assertThatThrownBy(() -> privilegedJdbc().sql(sql).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("is append-only")
                .satisfies(e -> assertThat(mysqlErrorCode(e)).contains(SIGNALED_ERROR));
    }
}
