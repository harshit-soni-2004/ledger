package dev.ihars.ledger.ledger.domain;

import dev.ihars.ledger.shared.money.Money;
import java.util.List;

/** Everything a posting writes, computed before any SQL runs. */
public record PostingPlan(JournalDraft draft, List<PlannedEntry> entries, List<BalanceChange> balanceChanges) {

    public PostingPlan {
        entries = List.copyOf(entries);
        balanceChanges = List.copyOf(balanceChanges);
    }

    /**
     * One ledger entry.
     *
     * @param lineNo 1-based position in the journal
     * @param accountSeq the account's gapless entry sequence number
     * @param balanceAfter the account's balance (normal orientation) after this entry
     */
    public record PlannedEntry(int lineNo, long accountId, Direction direction, Money amount, long accountSeq,
            Money balanceAfter) {}

    /** The projection update for one account, in ascending account id order. */
    public record BalanceChange(long accountId, Money previousBalance, Money newBalance, long previousLastSeq,
            long newLastSeq) {}
}
