package dev.ihars.ledger.accounts.infrastructure;

import dev.ihars.ledger.accounts.Account;
import dev.ihars.ledger.ledger.domain.AccountStatus;
import dev.ihars.ledger.ledger.domain.AccountType;
import dev.ihars.ledger.ledger.domain.Direction;
import dev.ihars.ledger.ledger.domain.OverdraftPolicy;
import dev.ihars.ledger.shared.money.CurrencyUnit;
import dev.ihars.ledger.shared.money.Money;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAccountRepository {

    private static final String SELECT = """
            SELECT a.id, a.public_id, a.system_code, a.owner_client_id, a.name, a.type, a.normal_balance,
                   a.currency, c.minor_units, a.overdraft_policy, a.overdraft_limit, a.status, a.created_at
              FROM accounts a
              JOIN currencies c ON c.code = a.currency
            """;

    private final JdbcClient jdbc;

    public JdbcAccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Account insert(String publicId, String name, AccountType type, CurrencyUnit currency,
            OverdraftPolicy policy, Long ownerClientId, Instant now) {
        LocalDateTime timestamp = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO accounts (public_id, name, type, normal_balance, currency, overdraft_policy,
                                      overdraft_limit, owner_client_id, status, created_at, updated_at)
                VALUES (:publicId, :name, :type, :normalBalance, :currency, :policy, :limit, :owner, 'ACTIVE',
                        :now, :now)
                """)
                .param("publicId", publicId)
                .param("name", name)
                .param("type", type.name())
                .param("normalBalance", type.normalBalance().name())
                .param("currency", currency.code())
                .param("policy", policy.kind().name())
                .param("limit", policy.limit() == null ? null : policy.limit().amount())
                .param("owner", ownerClientId)
                .param("now", timestamp)
                .update(keys, "id");
        return new Account(keys.getKeyAs(Number.class).longValue(), publicId, null, ownerClientId, name, type,
                type.normalBalance(), currency, policy, AccountStatus.ACTIVE, now);
    }

    public Optional<Account> findById(long id) {
        return jdbc.sql(SELECT + " WHERE a.id = :id").param("id", id).query((rs, n) -> map(rs)).optional();
    }

    public Optional<Account> findByPublicId(String publicId) {
        return jdbc.sql(SELECT + " WHERE a.public_id = :publicId")
                .param("publicId", publicId)
                .query((rs, n) -> map(rs))
                .optional();
    }

    public Optional<Account> findSystemAccount(String systemCode, String currency) {
        return jdbc.sql(SELECT + " WHERE a.system_code = :systemCode AND a.currency = :currency")
                .param("systemCode", systemCode)
                .param("currency", currency)
                .query((rs, n) -> map(rs))
                .optional();
    }

    /**
     * Keyset page in creation order.
     *
     * @param ownerClientId only accounts of this owner, or all accounts when null
     * @param afterId exclusive lower bound on the internal id, or null for the first page
     */
    public List<Account> page(Long ownerClientId, Long afterId, int limit) {
        return jdbc.sql(SELECT + """
                 WHERE (:owner IS NULL OR a.owner_client_id = :owner)
                   AND (:afterId IS NULL OR a.id > :afterId)
                 ORDER BY a.id
                 LIMIT :limit
                """)
                .param("owner", ownerClientId)
                .param("afterId", afterId)
                .param("limit", limit)
                .query((rs, n) -> map(rs))
                .list();
    }

    private static Account map(ResultSet rs) throws SQLException {
        CurrencyUnit currency = new CurrencyUnit(rs.getString("currency"), rs.getInt("minor_units"));
        BigDecimal limit = rs.getBigDecimal("overdraft_limit");
        OverdraftPolicy policy = new OverdraftPolicy(OverdraftPolicy.Kind.valueOf(rs.getString("overdraft_policy")),
                limit == null ? null : Money.of(limit, currency));
        return new Account(rs.getLong("id"), rs.getString("public_id"), rs.getString("system_code"),
                rs.getObject("owner_client_id", Long.class), rs.getString("name"),
                AccountType.valueOf(rs.getString("type")), Direction.valueOf(rs.getString("normal_balance")),
                currency, policy, AccountStatus.valueOf(rs.getString("status")),
                rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
    }
}
