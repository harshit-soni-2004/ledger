package dev.ihars.ledger.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ihars.ledger.ledger.domain.InMemoryLedger.Account;
import dev.ihars.ledger.ledger.domain.InMemoryLedger.Entry;
import dev.ihars.ledger.ledger.domain.JournalRejectedException.Code;
import dev.ihars.ledger.shared.money.CurrencyUnit;
import dev.ihars.ledger.shared.money.Money;
import dev.ihars.ledger.support.SeededRandomExtension;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Random sequences of postings and reversals on the in-memory ledger. After every step the
 * accounting invariants must hold (docs/05-invariants.md I1, I5, I11, I12).
 */
@ExtendWith(SeededRandomExtension.class)
class LedgerModelPropertiesTest {

    static final int TRIES = 1_000;
    static final int STEPS = 40;
    static final LocalDate TODAY = LocalDate.of(2026, 10, 29);
    static final List<CurrencyUnit> CURRENCIES = List.of(new CurrencyUnit("INR", 2), new CurrencyUnit("KWD", 3));

    @Test
    void invariantsHoldAfterEveryStep(RandomGenerator random) {
        for (int i = 0; i < TRIES; i++) {
            InMemoryLedger ledger = newLedger(random);
            List<JournalDraft> reversible = new ArrayList<>();

            for (int step = 0; step < STEPS; step++) {
                List<Entry> entriesBefore = ledger.entries();
                JournalDraft draft = nextJournal(random, ledger, reversible);
                Optional<JournalRejectedException> rejection = ledger.post(draft, TODAY);

                if (rejection.isPresent()) {
                    assertThat(rejection.get().code()).isEqualTo(Code.INSUFFICIENT_FUNDS);
                    assertThat(ledger.entries()).isEqualTo(entriesBefore);
                } else if (draft.type() != JournalType.REVERSAL) {
                    reversible.add(draft);
                }
                assertInvariants(ledger);
            }
        }
    }

    private static InMemoryLedger newLedger(RandomGenerator random) {
        InMemoryLedger ledger = new InMemoryLedger();
        for (CurrencyUnit currency : CURRENCIES) {
            ledger.open(currency, AccountType.ASSET, OverdraftPolicy.unlimited());
            ledger.open(currency, AccountType.EQUITY, OverdraftPolicy.unlimited());
            ledger.open(currency, AccountType.REVENUE, OverdraftPolicy.unlimited());
            ledger.open(currency, AccountType.EXPENSE, OverdraftPolicy.unlimited());
            for (int wallet = 0; wallet < 4; wallet++) {
                OverdraftPolicy policy = random.nextInt(4) == 0
                        ? OverdraftPolicy.limited(RandomJournals.minor(1 + random.nextLong(5_000), currency))
                        : OverdraftPolicy.forbidden();
                ledger.open(currency, AccountType.LIABILITY, policy);
            }
        }
        return ledger;
    }

    private static JournalDraft nextJournal(RandomGenerator random, InMemoryLedger ledger, List<JournalDraft> reversible) {
        if (!reversible.isEmpty() && random.nextInt(5) == 0) {
            JournalDraft original = reversible.remove(random.nextInt(reversible.size()));
            return original.mirror(1L, "property-test reversal", TODAY);
        }
        CurrencyUnit currency = CURRENCIES.get(random.nextInt(CURRENCIES.size()));
        List<Long> ids = ledger.accountsIn(currency).stream().map(Account::id).toList();
        return RandomJournals.balanced(random, currency, ids, 4, 20_000, TODAY);
    }

    private static void assertInvariants(InMemoryLedger ledger) {
        List<Entry> entries = ledger.entries();
        for (CurrencyUnit currency : CURRENCIES) {
            Money net = entries.stream()
                    .filter(e -> e.currency().equals(currency))
                    .map(e -> e.direction() == Direction.DEBIT ? e.amount() : e.amount().negate())
                    .reduce(Money.zero(currency), Money::plus);
            assertThat(net).as("trial balance %s", currency).isEqualTo(Money.zero(currency));
        }
        for (Account account : ledger.accounts()) {
            List<Entry> own = entries.stream().filter(e -> e.accountId() == account.id()).toList();
            Money fromEntries = Money.zero(account.currency());
            for (int n = 0; n < own.size(); n++) {
                Entry entry = own.get(n);
                fromEntries = fromEntries.plus(PostingCalculator.signedEffect(
                        account.type().normalBalance(), entry.direction(), entry.amount()));
                assertThat(entry.accountSeq()).as("gapless seq of %d", account.id()).isEqualTo(n + 1L);
                assertThat(entry.balanceAfter()).as("balance_after chain of %d", account.id()).isEqualTo(fromEntries);
            }
            Money balance = ledger.balance(account.id());
            assertThat(balance).as("projection of %d", account.id()).isEqualTo(fromEntries);
            assertThat(ledger.lastSeq(account.id())).isEqualTo(own.size());
            account.policy().minBalance(account.currency()).ifPresent(floor ->
                    assertThat(balance.isLessThan(floor)).as("floor of %d", account.id()).isFalse());
        }
    }
}
