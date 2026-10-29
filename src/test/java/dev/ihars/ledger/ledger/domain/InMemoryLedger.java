package dev.ihars.ledger.ledger.domain;

import dev.ihars.ledger.ledger.domain.PostingPlan.BalanceChange;
import dev.ihars.ledger.ledger.domain.PostingPlan.PlannedEntry;
import dev.ihars.ledger.shared.money.CurrencyUnit;
import dev.ihars.ledger.shared.money.Money;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reference model of the ledger for property tests: accounts, balances and entries in memory,
 * posted through the same {@link PostingCalculator} as the database engine.
 */
public final class InMemoryLedger {

    public record Account(long id, CurrencyUnit currency, AccountType type, OverdraftPolicy policy) {}

    public record Entry(long journalNo, long accountId, CurrencyUnit currency, Direction direction, Money amount,
            long accountSeq, Money balanceAfter) {}

    private final Map<Long, Account> accounts = new HashMap<>();
    private final Map<Long, AccountSnapshot> snapshots = new HashMap<>();
    private final List<Entry> entries = new ArrayList<>();
    private long nextAccountId = 1;
    private long journals;

    public Account open(CurrencyUnit currency, AccountType type, OverdraftPolicy policy) {
        Account account = new Account(nextAccountId++, currency, type, policy);
        accounts.put(account.id(), account);
        snapshots.put(account.id(), new AccountSnapshot(account.id(), currency, type.normalBalance(),
                AccountStatus.ACTIVE, Money.zero(currency), policy.minBalance(currency).orElse(null), 0));
        return account;
    }

    /** Posts the draft, or returns the rejection and leaves the ledger untouched. */
    public Optional<JournalRejectedException> post(JournalDraft draft, LocalDate today) {
        PostingPlan plan;
        try {
            plan = PostingCalculator.plan(draft, snapshots, today);
        } catch (JournalRejectedException rejected) {
            return Optional.of(rejected);
        }
        journals++;
        for (PlannedEntry entry : plan.entries()) {
            entries.add(new Entry(journals, entry.accountId(), draft.currency(), entry.direction(), entry.amount(),
                    entry.accountSeq(), entry.balanceAfter()));
        }
        for (BalanceChange change : plan.balanceChanges()) {
            AccountSnapshot before = snapshots.get(change.accountId());
            snapshots.put(change.accountId(), new AccountSnapshot(before.accountId(), before.currency(),
                    before.normalBalance(), before.status(), change.newBalance(), before.minBalance(),
                    change.newLastSeq()));
        }
        return Optional.empty();
    }

    public Money balance(long accountId) {
        return snapshots.get(accountId).balance();
    }

    public long lastSeq(long accountId) {
        return snapshots.get(accountId).lastEntrySeq();
    }

    public List<Account> accounts() {
        return List.copyOf(accounts.values());
    }

    public List<Account> accountsIn(CurrencyUnit currency) {
        return accounts.values().stream().filter(a -> a.currency().equals(currency)).sorted(
                java.util.Comparator.comparingLong(Account::id)).toList();
    }

    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    public long journalCount() {
        return journals;
    }
}
