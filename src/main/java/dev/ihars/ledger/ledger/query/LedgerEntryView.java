package dev.ihars.ledger.ledger.query;

import dev.ihars.ledger.ledger.domain.Direction;
import dev.ihars.ledger.ledger.domain.JournalType;
import dev.ihars.ledger.shared.money.Money;
import java.time.Instant;
import java.time.LocalDate;

/** One ledger entry as stored. {@code balanceAfter} follows posting order, not effective date. */
public record LedgerEntryView(String journalPublicId, JournalType journalType, int lineNo, long accountId,
        String accountPublicId, Direction direction, Money amount, long accountSeq, Money balanceAfter,
        LocalDate effectiveDate, Instant postedAt) {}
