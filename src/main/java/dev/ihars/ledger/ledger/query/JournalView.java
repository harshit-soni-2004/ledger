package dev.ihars.ledger.ledger.query;

import dev.ihars.ledger.ledger.domain.JournalType;
import dev.ihars.ledger.shared.money.CurrencyUnit;
import dev.ihars.ledger.shared.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** A posted journal header with its entries in line order. */
public record JournalView(long id, String publicId, JournalType type, CurrencyUnit currency, Money total,
        Long reversesJournalId, String externalReference, String description, String reason, LocalDate effectiveDate,
        Instant postedAt, String postedBy, String requestId, String correlationId, List<LedgerEntryView> entries) {

    public JournalView {
        entries = List.copyOf(entries);
    }
}
