package dev.ihars.ledger.ledger.posting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ihars.ledger.accounts.Account;
import dev.ihars.ledger.audit.AuditEvent;
import dev.ihars.ledger.audit.AuditTrail;
import dev.ihars.ledger.ledger.query.AccountBalance;
import dev.ihars.ledger.support.LedgerIntegrationTest;
import java.time.Clock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** A posting is all or nothing (docs/02-accounting-model.md, rule 4). */
@Import(PostingRollbackIT.FailingAuditConfiguration.class)
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
        AccountBalance payerBefore = queries.balance(payer.id()).orElseThrow();

        // The audit event is the last write of a posting, after header, entries and balances.
        audit.setFailing(true);
        assertThatThrownBy(() -> data.transfer(payer, payee, "4.00")).hasMessage("audit store unavailable");

        assertThat(count("SELECT COUNT(*) FROM journal_transactions")).isEqualTo(journals);
        assertThat(count("SELECT COUNT(*) FROM ledger_entries")).isEqualTo(entries);
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

    /** Writes the event, then fails. Toggled through a method: the bean is a transactional proxy. */
    static class FailingAuditTrail extends AuditTrail {

        private volatile boolean failing;

        FailingAuditTrail(JdbcClient jdbc, JsonMapper json, Clock clock) {
            super(jdbc, json, clock);
        }

        public void setFailing(boolean failing) {
            this.failing = failing;
        }

        @Override
        public void append(AuditEvent event) {
            super.append(event);
            if (failing) {
                throw new IllegalStateException("audit store unavailable");
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FailingAuditConfiguration {

        @Bean
        @Primary
        FailingAuditTrail failingAuditTrail(JdbcClient jdbc, JsonMapper json, Clock clock) {
            return new FailingAuditTrail(jdbc, json, clock);
        }
    }
}
