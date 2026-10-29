package dev.ihars.ledger.ledger.infrastructure;

import dev.ihars.ledger.ledger.domain.Direction;
import dev.ihars.ledger.ledger.domain.JournalType;
import dev.ihars.ledger.ledger.query.AccountBalance;
import dev.ihars.ledger.ledger.query.JournalView;
import dev.ihars.ledger.ledger.query.LedgerEntryView;
import dev.ihars.ledger.shared.money.CurrencyUnit;
import dev.ihars.ledger.shared.money.Money;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Ledger read queries. */
@Repository
public class JdbcLedgerReader {

    private static final String ENTRY_COLUMNS = """
            j.public_id AS journal_public_id, e.line_no, e.account_id, e.currency, c.minor_units, e.direction,
            e.amount, e.account_seq, e.balance_after, e.effective_date, e.posted_at
            """;

    private final JdbcClient jdbc;

    public JdbcLedgerReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AccountBalance> balance(long accountId) {
        return jdbc.sql("""
                SELECT b.account_id, b.currency, c.minor_units, a.normal_balance, b.balance, b.min_balance,
                       b.last_entry_seq
                  FROM account_balances b
                  JOIN accounts a ON a.id = b.account_id
                  JOIN currencies c ON c.code = b.currency
                 WHERE b.account_id = :accountId
                """)
                .param("accountId", accountId)
                .query((rs, n) -> {
                    CurrencyUnit currency = currency(rs);
                    BigDecimal minBalance = rs.getBigDecimal("min_balance");
                    return new AccountBalance(rs.getLong("account_id"), currency,
                            Direction.valueOf(rs.getString("normal_balance")),
                            Money.of(rs.getBigDecimal("balance"), currency),
                            minBalance == null ? null : Money.of(minBalance, currency),
                            rs.getLong("last_entry_seq"));
                })
                .optional();
    }

    public Optional<Money> balanceAsOf(long accountId, LocalDate date) {
        return jdbc.sql("""
                SELECT a.currency, c.minor_units,
                       COALESCE(SUM(CASE WHEN (e.direction = 'DEBIT') = (a.normal_balance = 'DEBIT')
                                         THEN e.amount ELSE -e.amount END), 0) AS balance
                  FROM accounts a
                  JOIN currencies c ON c.code = a.currency
                  LEFT JOIN ledger_entries e ON e.account_id = a.id AND e.effective_date <= :asOf
                 WHERE a.id = :accountId
                 GROUP BY a.id, a.currency, c.minor_units
                """)
                .param("accountId", accountId)
                .param("asOf", date)
                .query((rs, n) -> Money.of(rs.getBigDecimal("balance"), currency(rs)))
                .optional();
    }

    public List<LedgerEntryView> entries(long accountId, long afterSeq, int limit) {
        return jdbc.sql("SELECT " + ENTRY_COLUMNS + """
                  FROM ledger_entries e
                  JOIN journal_transactions j ON j.id = e.journal_id
                  JOIN currencies c ON c.code = e.currency
                 WHERE e.account_id = :accountId AND e.account_seq > :afterSeq
                 ORDER BY e.account_seq
                 LIMIT :limit
                """)
                .param("accountId", accountId)
                .param("afterSeq", afterSeq)
                .param("limit", limit)
                .query((rs, n) -> entry(rs))
                .list();
    }

    public Optional<JournalView> journal(String publicId) {
        List<LedgerEntryView> entries = jdbc.sql("SELECT " + ENTRY_COLUMNS + """
                  FROM journal_transactions j
                  JOIN ledger_entries e ON e.journal_id = j.id
                  JOIN currencies c ON c.code = e.currency
                 WHERE j.public_id = :publicId
                 ORDER BY e.line_no
                """)
                .param("publicId", publicId)
                .query((rs, n) -> entry(rs))
                .list();
        return jdbc.sql("""
                SELECT j.id, j.public_id, j.type, j.currency, c.minor_units, j.total_debit, j.reverses_journal_id,
                       j.external_reference, j.description, j.reason, j.effective_date, j.posted_at, j.posted_by,
                       j.request_id, j.correlation_id
                  FROM journal_transactions j
                  JOIN currencies c ON c.code = j.currency
                 WHERE j.public_id = :publicId
                """)
                .param("publicId", publicId)
                .query((rs, n) -> {
                    CurrencyUnit currency = currency(rs);
                    return new JournalView(rs.getLong("id"), rs.getString("public_id"),
                            JournalType.valueOf(rs.getString("type")), currency,
                            Money.of(rs.getBigDecimal("total_debit"), currency),
                            rs.getObject("reverses_journal_id", Long.class), rs.getString("external_reference"),
                            rs.getString("description"), rs.getString("reason"),
                            rs.getObject("effective_date", LocalDate.class),
                            rs.getObject("posted_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                            rs.getString("posted_by"), rs.getString("request_id"), rs.getString("correlation_id"),
                            entries);
                })
                .optional();
    }

    private static LedgerEntryView entry(ResultSet rs) throws SQLException {
        CurrencyUnit currency = currency(rs);
        return new LedgerEntryView(rs.getString("journal_public_id"), rs.getInt("line_no"), rs.getLong("account_id"),
                Direction.valueOf(rs.getString("direction")), Money.of(rs.getBigDecimal("amount"), currency),
                rs.getLong("account_seq"), Money.of(rs.getBigDecimal("balance_after"), currency),
                rs.getObject("effective_date", LocalDate.class),
                rs.getObject("posted_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
    }

    private static CurrencyUnit currency(ResultSet rs) throws SQLException {
        return new CurrencyUnit(rs.getString("currency"), rs.getInt("minor_units"));
    }
}
