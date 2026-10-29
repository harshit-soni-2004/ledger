package dev.ihars.ledger.ledger.domain;

import dev.ihars.ledger.ledger.domain.JournalRejectedException.Code;
import dev.ihars.ledger.ledger.domain.PostingPlan.BalanceChange;
import dev.ihars.ledger.ledger.domain.PostingPlan.PlannedEntry;
import dev.ihars.ledger.shared.money.InvalidAmountException;
import dev.ihars.ledger.shared.money.Money;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Applies a {@link JournalDraft} to account snapshots and checks the rules that need account
 * state (docs/02-accounting-model.md, rules 2, 6, 7). Pure: the same function backs the
 * database posting engine and the in-memory ledger model used by property tests.
 */
public final class PostingCalculator {

    private PostingCalculator() {}

    /**
     * Effect of an entry on a balance kept in the account's normal orientation: an entry on
     * the normal side increases it, an entry on the other side decreases it.
     */
    public static Money signedEffect(Direction normalBalance, Direction entryDirection, Money amount) {
        return entryDirection == normalBalance ? amount : amount.negate();
    }

    /**
     * @param accounts snapshots of every account the draft touches, read under lock
     * @param today the current business date (UTC); postings may not be future-dated
     */
    public static PostingPlan plan(JournalDraft draft, Map<Long, AccountSnapshot> accounts, LocalDate today) {
        if (draft.effectiveDate().isAfter(today)) {
            throw new JournalRejectedException(Code.EFFECTIVE_DATE_IN_FUTURE,
                    "Effective date %s is after %s".formatted(draft.effectiveDate(), today));
        }
        Map<Long, Running> running = new TreeMap<>();
        for (JournalLine line : draft.lines()) {
            running.computeIfAbsent(line.accountId(), id -> new Running(snapshotFor(draft, accounts, id)));
        }

        List<PlannedEntry> entries = new ArrayList<>(draft.lines().size());
        int lineNo = 0;
        for (JournalLine line : draft.lines()) {
            Running account = running.get(line.accountId());
            try {
                account.balance = account.balance.plus(
                        signedEffect(account.snapshot.normalBalance(), line.direction(), line.amount()));
            } catch (InvalidAmountException e) {
                throw new JournalRejectedException(Code.AMOUNT_OUT_OF_RANGE,
                        "Balance of account %d would exceed the supported range".formatted(line.accountId()));
            }
            account.lastSeq++;
            entries.add(new PlannedEntry(++lineNo, line.accountId(), line.direction(), line.amount(),
                    account.lastSeq, account.balance));
        }

        List<BalanceChange> changes = new ArrayList<>(running.size());
        for (Running account : running.values()) {
            AccountSnapshot before = account.snapshot;
            boolean decreased = account.balance.isLessThan(before.balance());
            if (decreased && before.minBalance() != null && account.balance.isLessThan(before.minBalance())) {
                throw new JournalRejectedException(Code.INSUFFICIENT_FUNDS,
                        "Account %d would fall below its minimum balance".formatted(before.accountId()));
            }
            changes.add(new BalanceChange(before.accountId(), before.balance(), account.balance,
                    before.lastEntrySeq(), account.lastSeq));
        }
        return new PostingPlan(draft, entries, changes);
    }

    private static AccountSnapshot snapshotFor(JournalDraft draft, Map<Long, AccountSnapshot> accounts, long id) {
        AccountSnapshot snapshot = accounts.get(id);
        if (snapshot == null) {
            throw new JournalRejectedException(Code.ACCOUNT_NOT_FOUND, "Account %d does not exist".formatted(id));
        }
        if (!snapshot.currency().equals(draft.currency())) {
            throw new JournalRejectedException(Code.CURRENCY_MISMATCH,
                    "Account %d is in %s, journal is in %s".formatted(id, snapshot.currency(), draft.currency()));
        }
        if (snapshot.status() != AccountStatus.ACTIVE) {
            throw new JournalRejectedException(Code.ACCOUNT_NOT_ACTIVE,
                    "Account %d is %s".formatted(id, snapshot.status()));
        }
        return snapshot;
    }

    private static final class Running {
        private final AccountSnapshot snapshot;
        private Money balance;
        private long lastSeq;

        private Running(AccountSnapshot snapshot) {
            this.snapshot = snapshot;
            this.balance = snapshot.balance();
            this.lastSeq = snapshot.lastEntrySeq();
        }
    }
}
