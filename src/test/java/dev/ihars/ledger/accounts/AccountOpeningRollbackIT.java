package dev.ihars.ledger.accounts;

import static dev.ihars.ledger.support.LedgerTestData.ACTOR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ihars.ledger.support.FailingAuditTrail;
import dev.ihars.ledger.support.LedgerIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/** Opening an account writes the account, its balance row and its audit event, or nothing. */
@Import(FailingAuditTrail.Configuration.class)
class AccountOpeningRollbackIT extends LedgerIntegrationTest {

    @Autowired
    FailingAuditTrail audit;

    @AfterEach
    void restoreAuditTrail() {
        audit.setFailing(false);
    }

    @Test
    void aFailureAfterTheAccountAndBalanceAreWrittenLeavesNothingBehind() {
        String name = "Rollback probe " + UUID.randomUUID();
        long accountRows = count("SELECT COUNT(*) FROM accounts");
        long balanceRows = count("SELECT COUNT(*) FROM account_balances");
        long auditEvents = count("SELECT COUNT(*) FROM audit_events");

        // The audit event is written after the account and balance rows, then the failure fires.
        audit.setFailing(true);
        assertThatThrownBy(() -> accounts.open(OpenAccountCommand.wallet(name, "INR"), ACTOR, "req-rollback-1"))
                .hasMessage(FailingAuditTrail.FAILURE_MESSAGE);

        assertThat(count("SELECT COUNT(*) FROM accounts WHERE name = ?", name)).isZero();
        assertThat(count("SELECT COUNT(*) FROM accounts")).isEqualTo(accountRows);
        assertThat(count("SELECT COUNT(*) FROM account_balances")).isEqualTo(balanceRows);
        assertThat(count("SELECT COUNT(*) FROM audit_events")).isEqualTo(auditEvents);
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE request_id = 'req-rollback-1'")).isZero();
        assertThat(integrity.check().violations()).isEmpty();
    }

    @Test
    void theSameRequestSucceedsOnceTheFailureIsGone() {
        String name = "Rollback probe " + UUID.randomUUID();

        Account account = accounts.open(OpenAccountCommand.wallet(name, "INR"), ACTOR, "req-rollback-2");

        assertThat(count("SELECT COUNT(*) FROM accounts WHERE name = ?", name)).isEqualTo(1);
        assertThat(queries.balance(account.id())).isPresent();
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_id = ?", account.publicId())).isEqualTo(1);
    }
}
