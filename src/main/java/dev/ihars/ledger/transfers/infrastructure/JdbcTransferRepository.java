package dev.ihars.ledger.transfers.infrastructure;

import dev.ihars.ledger.shared.money.CurrencyUnit;
import dev.ihars.ledger.shared.money.Money;
import dev.ihars.ledger.transfers.Transfer;
import dev.ihars.ledger.transfers.Transfer.TransferStatus;
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
public class JdbcTransferRepository {

    private static final String SELECT = """
            SELECT t.id, t.public_id, t.client_id, src.public_id AS source_public_id,
                   dst.public_id AS destination_public_id, t.currency, c.minor_units, t.amount, t.status,
                   t.rejection_code, j.public_id AS journal_public_id, t.description, t.client_reference,
                   t.created_at
              FROM transfers t
              JOIN accounts src ON src.id = t.source_account_id
              JOIN accounts dst ON dst.id = t.destination_account_id
              JOIN currencies c ON c.code = t.currency
              LEFT JOIN journal_transactions j ON j.id = t.journal_id
            """;

    private final JdbcClient jdbc;

    public JdbcTransferRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** What a new row needs besides its outcome; account ids are internal. */
    public record NewTransfer(String publicId, long clientId, String idempotencyKey, long sourceAccountId,
            long destinationAccountId, Money amount, String description, String clientReference) {}

    /** Inserts a POSTED ({@code journalId} set) or REJECTED ({@code rejectionCode} set) transfer. */
    public long insert(NewTransfer transfer, TransferStatus status, Long journalId, String rejectionCode,
            Instant now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO transfers (public_id, client_id, idempotency_key, source_account_id,
                                       destination_account_id, currency, amount, status, rejection_code,
                                       journal_id, client_reference, description, created_at, updated_at)
                VALUES (:publicId, :clientId, :key, :source, :destination, :currency, :amount, :status,
                        :rejectionCode, :journalId, :clientReference, :description, :now, :now)
                """)
                .param("publicId", transfer.publicId())
                .param("clientId", transfer.clientId())
                .param("key", transfer.idempotencyKey())
                .param("source", transfer.sourceAccountId())
                .param("destination", transfer.destinationAccountId())
                .param("currency", transfer.amount().currency().code())
                .param("amount", transfer.amount().amount())
                .param("status", status.name())
                .param("rejectionCode", rejectionCode)
                .param("journalId", journalId)
                .param("clientReference", transfer.clientReference())
                .param("description", transfer.description())
                .param("now", LocalDateTime.ofInstant(now, ZoneOffset.UTC))
                .update(keys, "id");
        return keys.getKeyAs(Number.class).longValue();
    }

    public Optional<Transfer> findByPublicId(String publicId) {
        return jdbc.sql(SELECT + " WHERE t.public_id = :publicId")
                .param("publicId", publicId)
                .query((rs, n) -> transfer(rs))
                .optional();
    }

    /**
     * Newest first. Every filter is optional; {@code beforeId} is the internal id of the last row
     * of the previous page.
     */
    public List<Transfer> page(Long clientId, TransferStatus status, String clientReference, Long beforeId,
            int limit) {
        return jdbc.sql(SELECT + """
                 WHERE (:clientId IS NULL OR t.client_id = :clientId)
                   AND (:status IS NULL OR t.status = :status)
                   AND (:clientReference IS NULL OR t.client_reference = :clientReference)
                   AND (:beforeId IS NULL OR t.id < :beforeId)
                 ORDER BY t.id DESC
                 LIMIT :limit
                """)
                .param("clientId", clientId)
                .param("status", status == null ? null : status.name())
                .param("clientReference", clientReference)
                .param("beforeId", beforeId)
                .param("limit", limit)
                .query((rs, n) -> transfer(rs))
                .list();
    }

    private static Transfer transfer(ResultSet rs) throws SQLException {
        CurrencyUnit currency = new CurrencyUnit(rs.getString("currency"), rs.getInt("minor_units"));
        return new Transfer(rs.getLong("id"), rs.getString("public_id"), rs.getLong("client_id"),
                rs.getString("source_public_id"), rs.getString("destination_public_id"),
                Money.of(rs.getBigDecimal("amount"), currency), TransferStatus.valueOf(rs.getString("status")),
                rs.getString("rejection_code"), rs.getString("journal_public_id"), rs.getString("description"),
                rs.getString("client_reference"),
                rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
    }
}
