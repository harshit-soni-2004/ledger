package dev.ihars.ledger.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ihars.ledger.support.SeededRandomExtension;
import dev.ihars.ledger.shared.money.CurrencyUnit;
import dev.ihars.ledger.shared.money.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** Seeded randomized checks of journal rules; see {@link SeededRandomExtension} to replay a seed. */
@ExtendWith(SeededRandomExtension.class)
class JournalPropertiesTest {

    static final int TRIES = 1_000;
    static final LocalDate TODAY = LocalDate.of(2026, 10, 29);
    static final List<CurrencyUnit> CURRENCIES = List.of(
            new CurrencyUnit("INR", 2), new CurrencyUnit("JPY", 0), new CurrencyUnit("KWD", 3));

    @Test
    void everyValidDraftBalances(RandomGenerator random) {
        for (int i = 0; i < TRIES; i++) {
            CurrencyUnit currency = CURRENCIES.get(random.nextInt(CURRENCIES.size()));
            JournalDraft draft = RandomJournals.balanced(random, currency, List.of(1L, 2L, 3L, 4L, 5L, 6L), 6,
                    10_000_000, TODAY);

            Money debits = sum(draft, Direction.DEBIT);
            Money credits = sum(draft, Direction.CREDIT);
            assertThat(debits).isEqualTo(credits).isEqualTo(draft.total());
            assertThat(draft.lines()).allSatisfy(line -> assertThat(line.amount().isPositive()).isTrue());
        }
    }

    @Test
    void applyingADraftAndItsMirrorRestoresEveryBalance(RandomGenerator random) {
        for (int i = 0; i < TRIES; i++) {
            CurrencyUnit currency = CURRENCIES.get(random.nextInt(CURRENCIES.size()));
            InMemoryLedger ledger = new InMemoryLedger();
            List<Long> ids = java.util.stream.IntStream.range(0, 5)
                    .mapToObj(n -> ledger.open(currency, AccountType.values()[random.nextInt(AccountType.values().length)],
                            OverdraftPolicy.unlimited()).id())
                    .toList();
            JournalDraft draft = RandomJournals.balanced(random, currency, ids, 5, 1_000_000, TODAY);
            Map<Long, Money> before = balances(ledger, ids);

            assertThat(ledger.post(draft, TODAY)).isEmpty();
            JournalDraft mirror = draft.mirror(1L, "reversal", TODAY);
            assertThat(mirror.total()).isEqualTo(draft.total());
            assertThat(ledger.post(mirror, TODAY)).isEmpty();

            assertThat(balances(ledger, ids)).isEqualTo(before);
        }
    }

    private static Money sum(JournalDraft draft, Direction direction) {
        return draft.lines().stream()
                .filter(line -> line.direction() == direction)
                .map(JournalLine::amount)
                .reduce(Money.zero(draft.currency()), Money::plus);
    }

    private static Map<Long, Money> balances(InMemoryLedger ledger, List<Long> ids) {
        return ids.stream().collect(Collectors.toMap(Function.identity(), ledger::balance));
    }
}
