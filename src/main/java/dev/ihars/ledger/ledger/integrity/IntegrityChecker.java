package dev.ihars.ledger.ledger.integrity;

import dev.ihars.ledger.ledger.integrity.IntegrityReport.Check;
import dev.ihars.ledger.ledger.integrity.IntegrityReport.Violation;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Detective control: recomputes the ledger invariants from the stored rows. The preventive
 * layers (JournalDraft, constraints, the posting engine) should make every check pass; a
 * violation means a bug or an out-of-band change. Full scans; meant for tests and scheduled
 * jobs, not request paths.
 */
@Service
public class IntegrityChecker {

    private static final String SIGNED_AMOUNT =
            "CASE WHEN (e.direction = 'DEBIT') = (a.normal_balance = 'DEBIT') THEN e.amount ELSE -e.amount END";

    private static final List<Query> QUERIES = List.of(
            new Query(Check.JOURNAL_UNBALANCED, """
                    SELECT j.public_id
                      FROM journal_transactions j
                      LEFT JOIN ledger_entries e ON e.journal_id = j.id
                     GROUP BY j.id, j.public_id, j.total_debit, j.total_credit
                    HAVING COUNT(e.id) < 2
                        OR COALESCE(SUM(CASE WHEN e.direction = 'DEBIT' THEN e.amount END), 0) <> j.total_debit
                        OR COALESCE(SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount END), 0) <> j.total_credit
                    """),
            new Query(Check.TRIAL_BALANCE, """
                    SELECT CONCAT('entries:', currency)
                      FROM ledger_entries
                     GROUP BY currency
                    HAVING SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END) <> 0
                    UNION ALL
                    SELECT CONCAT('balances:', b.currency)
                      FROM account_balances b
                      JOIN accounts a ON a.id = b.account_id
                     GROUP BY b.currency
                    HAVING SUM(CASE WHEN a.normal_balance = 'DEBIT' THEN b.balance ELSE -b.balance END) <> 0
                    """),
            new Query(Check.MISSING_BALANCE_ROW, """
                    SELECT a.public_id
                      FROM accounts a
                      LEFT JOIN account_balances b ON b.account_id = a.id
                     WHERE b.account_id IS NULL
                    """),
            new Query(Check.PROJECTION_DRIFT, """
                    SELECT a.public_id
                      FROM accounts a
                      JOIN account_balances b ON b.account_id = a.id
                      LEFT JOIN ledger_entries e ON e.account_id = a.id
                     GROUP BY a.id, a.public_id, b.balance, b.last_entry_seq
                    HAVING COALESCE(SUM(%s), 0) <> b.balance
                        OR COUNT(e.id) <> b.last_entry_seq
                    """.formatted(SIGNED_AMOUNT)),
            new Query(Check.SEQUENCE_GAP, """
                    SELECT a.public_id
                      FROM ledger_entries e
                      JOIN accounts a ON a.id = e.account_id
                     GROUP BY a.id, a.public_id
                    HAVING MIN(e.account_seq) <> 1 OR MAX(e.account_seq) <> COUNT(*)
                    """),
            new Query(Check.BALANCE_CHAIN_BROKEN, """
                    SELECT CONCAT(x.public_id, '#', x.account_seq)
                      FROM (SELECT a.public_id, e.account_seq, %s AS signed_amount,
                                   e.balance_after - LAG(e.balance_after, 1, 0)
                                       OVER (PARTITION BY e.account_id ORDER BY e.account_seq) AS delta
                              FROM ledger_entries e
                              JOIN accounts a ON a.id = e.account_id) x
                     WHERE x.delta <> x.signed_amount
                    """.formatted(SIGNED_AMOUNT)),
            new Query(Check.SCALE_VIOLATION, """
                    SELECT CONCAT('entry:', j.public_id, '#', e.line_no)
                      FROM ledger_entries e
                      JOIN journal_transactions j ON j.id = e.journal_id
                      JOIN currencies c ON c.code = e.currency
                     WHERE e.amount <> TRUNCATE(e.amount, c.minor_units)
                        OR e.balance_after <> TRUNCATE(e.balance_after, c.minor_units)
                    UNION ALL
                    SELECT CONCAT('journal:', j.public_id)
                      FROM journal_transactions j
                      JOIN currencies c ON c.code = j.currency
                     WHERE j.total_debit <> TRUNCATE(j.total_debit, c.minor_units)
                        OR j.total_credit <> TRUNCATE(j.total_credit, c.minor_units)
                    UNION ALL
                    SELECT CONCAT('balance:', a.public_id)
                      FROM account_balances b
                      JOIN accounts a ON a.id = b.account_id
                      JOIN currencies c ON c.code = b.currency
                     WHERE b.balance <> TRUNCATE(b.balance, c.minor_units)
                        OR b.min_balance <> TRUNCATE(b.min_balance, c.minor_units)
                        OR a.overdraft_limit <> TRUNCATE(a.overdraft_limit, c.minor_units)
                    """),
            new Query(Check.FLOOR_MISMATCH, """
                    SELECT a.public_id
                      FROM accounts a
                      JOIN account_balances b ON b.account_id = a.id
                     WHERE NOT (b.min_balance <=> CASE a.overdraft_policy
                                                     WHEN 'FORBIDDEN' THEN 0
                                                     WHEN 'LIMITED' THEN -a.overdraft_limit
                                                     ELSE NULL END)
                    """),
            new Query(Check.ENTRY_EFFECTIVE_DATE_MISMATCH, """
                    SELECT CONCAT(j.public_id, '#', e.line_no)
                      FROM ledger_entries e
                      JOIN journal_transactions j ON j.id = e.journal_id
                     WHERE e.effective_date <> j.effective_date
                    """),
            new Query(Check.ENTRY_POSTED_AT_MISMATCH, """
                    SELECT CONCAT(j.public_id, '#', e.line_no)
                      FROM ledger_entries e
                      JOIN journal_transactions j ON j.id = e.journal_id
                     WHERE e.posted_at <> j.posted_at
                    """),
            new Query(Check.CLOSED_ACCOUNT_NONZERO, """
                    SELECT a.public_id
                      FROM accounts a
                      JOIN account_balances b ON b.account_id = a.id
                     WHERE a.status = 'CLOSED' AND b.balance <> 0
                    """));

    private final JdbcClient jdbc;

    public IntegrityChecker(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Runs every check against one consistent snapshot. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public IntegrityReport check() {
        List<Violation> violations = new ArrayList<>();
        for (Query query : QUERIES) {
            for (String subject : jdbc.sql(query.sql()).query(String.class).list()) {
                violations.add(new Violation(query.check(), subject));
            }
        }
        return new IntegrityReport(violations);
    }

    private record Query(Check check, String sql) {}
}
