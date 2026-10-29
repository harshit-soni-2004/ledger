package dev.ihars.ledger.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ihars.ledger.support.LedgerIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;

class AuditTrailIT extends LedgerIntegrationTest {

    @Autowired
    AuditTrail audit;

    @Test
    void anEventCannotBeWrittenOutsideTheTransactionOfTheChangeItRecords() {
        AuditEvent event = new AuditEvent(Actor.system("test"), "SOMETHING_HAPPENED", "TEST", "test_1", null, null,
                Map.of());

        assertThatThrownBy(() -> audit.append(event)).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_id = 'test_1'")).isZero();
    }

    @Test
    void openingAnAccountIsAudited() {
        var account = data.wallet("EUR");

        var row = privilegedJdbc()
                .sql("""
                        SELECT action, entity_type, payload->>'$.overdraftPolicy' AS policy
                          FROM audit_events WHERE entity_id = ?
                        """)
                .param(account.publicId())
                .query()
                .singleRow();
        assertThat(row).containsEntry("action", "ACCOUNT_OPENED")
                .containsEntry("entity_type", "ACCOUNT")
                .containsEntry("policy", "FORBIDDEN");
    }
}
