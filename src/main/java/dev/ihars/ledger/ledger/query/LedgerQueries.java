package dev.ihars.ledger.ledger.query;

import dev.ihars.ledger.ledger.infrastructure.JdbcLedgerReader;
import dev.ihars.ledger.shared.money.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only views of the ledger. Nothing here takes locks. */
@Service
@Transactional(readOnly = true)
public class LedgerQueries {

    public static final int MAX_PAGE_SIZE = 500;

    private final JdbcLedgerReader reader;

    public LedgerQueries(JdbcLedgerReader reader) {
        this.reader = reader;
    }

    /** Current balance from the projection (O(1)). */
    public Optional<AccountBalance> balance(long accountId) {
        return reader.balance(accountId);
    }

    /**
     * Balance including every entry with {@code effective_date <= date}, recomputed from the
     * entries. Postings may be backdated, so this is not the {@code balance_after} of any
     * single entry: {@code balance_after} follows posting order.
     */
    public Optional<Money> balanceAsOf(long accountId, LocalDate date) {
        return reader.balanceAsOf(accountId, date);
    }

    /** Entries with {@code account_seq > afterSeq} in sequence order (keyset pagination). */
    public List<LedgerEntryView> entries(long accountId, long afterSeq, int limit) {
        if (afterSeq < 0 || limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("afterSeq must be >= 0 and limit 1-" + MAX_PAGE_SIZE);
        }
        return reader.entries(accountId, afterSeq, limit);
    }

    public Optional<JournalView> journal(String publicId) {
        return reader.journal(publicId);
    }
}
