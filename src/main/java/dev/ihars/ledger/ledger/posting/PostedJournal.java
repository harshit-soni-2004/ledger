package dev.ihars.ledger.ledger.posting;

import dev.ihars.ledger.ledger.domain.Direction;
import dev.ihars.ledger.ledger.domain.JournalType;
import dev.ihars.ledger.shared.money.CurrencyUnit;
import dev.ihars.ledger.shared.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** A journal as committed to the ledger. */
public record PostedJournal(long id, String publicId, JournalType type, CurrencyUnit currency, Money total,
        LocalDate effectiveDate, Instant postedAt, List<Entry> entries) {

    public PostedJournal {
        entries = List.copyOf(entries);
    }

    /**
     * @param balanceAfter the account's balance (normal orientation) after this entry
     */
    public record Entry(int lineNo, long accountId, Direction direction, Money amount, long accountSeq,
            Money balanceAfter) {}
}
