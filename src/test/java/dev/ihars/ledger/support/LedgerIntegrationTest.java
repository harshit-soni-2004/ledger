package dev.ihars.ledger.support;

import dev.ihars.ledger.accounts.AccountService;
import dev.ihars.ledger.ledger.integrity.IntegrityChecker;
import dev.ihars.ledger.ledger.posting.PostingEngine;
import dev.ihars.ledger.ledger.query.LedgerQueries;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

/** Integration test with the ledger services and {@link LedgerTestData} at hand. */
public abstract class LedgerIntegrationTest extends IntegrationTest {

    protected static final int CHECK_CONSTRAINT_VIOLATED = 3819;
    protected static final int FOREIGN_KEY_VIOLATED = 1452;
    protected static final int DUPLICATE_KEY = 1062;
    /** Raised by SIGNAL SQLSTATE '45000' in the append-only triggers. */
    protected static final int SIGNALED_ERROR = 1644;

    @Autowired
    protected AccountService accounts;

    @Autowired
    protected PostingEngine engine;

    @Autowired
    protected LedgerQueries queries;

    @Autowired
    protected IntegrityChecker integrity;

    @Autowired
    protected Clock clock;

    protected LedgerTestData data;

    @BeforeEach
    void ledgerTestData() {
        data = new LedgerTestData(accounts, engine, clock);
    }

    protected static long count(String sql, Object... params) {
        var statement = privilegedJdbc().sql(sql);
        for (Object param : params) {
            statement = statement.param(param);
        }
        return statement.query(Long.class).single();
    }
}
