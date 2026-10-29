package dev.ihars.ledger.ledger.posting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ihars.ledger.accounts.Account;
import dev.ihars.ledger.ledger.query.AccountBalance;
import dev.ihars.ledger.support.FailingAuditTrail;
import dev.ihars.ledger.support.LedgerIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

/** A posting is all or nothing (docs/02-accounting-model.md, rule 4). */
@Import(FailingAuditTrail.Configuration.class)
class PostingRollbackIT extends LedgerIntegrationTest {

    @Autowired
    FailingAuditTrail audit;

    @Autowired
    TransactionTemplate transactions;

    @AfterEach
    void restoreAuditTrail() {
        audit.setFailing(false);
    }

    @Test
    void aFailureAfterTheEntriesAreWrittenRollsBackTheWholePosting() {
        Account payer = data.wallet("INR");
        Account payee = data.wallet("INR");
        data.deposit(payer, "10.00");
        long journals = count("SELECT COUNT(*) FROM journal_transactions");
        long entries = count("SELECT COUNT(*) FROM ledger_entries");
        long auditEvents = count("SELECT COUNT(*) FROM audit_events");
        AccountBalance payerBefore = queries.balance(payer.id()).orElseThrow();

        // The audit event is the last write of a posting, after header, entries and balances.
        audit.setFailing(true);
        assertThatThrownBy(() -> data.transfer(payer, payee, "4.00")).hasMessage(FailingAuditTrail.FAILURE_MESSAGE);

        assertThat(count("SELECT COUNT(*) FROM journal_transactions")).isEqualTo(journals);
        assertThat(count("SELECT COUNT(*) FROM ledger_entries")).isEqualTo(entries);
        assertThat(count("SELECT COUNT(*) FROM audit_events")).isEqualTo(auditEvents);
        assertThat(queries.balance(payer.id())).contains(payerBefore);
        assertThat(queries.balance(payee.id()).orElseThrow().lastEntrySeq()).isZero();
        assertThat(queries.entries(payee.id(), 0, 10)).isEmpty();
        assertThat(integrity.check().violations()).isEmpty();
    }

    @Test
    void aPostingJoinsTheCallersTransactionAndRollsBackWithIt() {
        Account payer = data.wallet("INR");
        Account payee = data.wallet("INR");
        data.deposit(payer, "10.00");
        AccountBalance payerBefore = queries.balance(payer.id()).orElseThrow();

        transactions.executeWithoutResult(status -> {
            data.transfer(payer, payee, "4.00");
            status.setRollbackOnly();
        });

        assertThat(queries.balance(payer.id())).contains(payerBefore);
        assertThat(queries.entries(payee.id(), 0, 10)).isEmpty();
    }
}
