package dev.ihars.ledger.shared.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ihars.ledger.support.SeededRandomExtension;
import java.math.BigDecimal;
import java.util.List;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** Seeded randomized checks of {@link Money}; see {@link SeededRandomExtension} to replay a seed. */
@ExtendWith(SeededRandomExtension.class)
class MoneyPropertiesTest {

    static final int TRIES = 1_000;
    static final List<CurrencyUnit> CURRENCIES = List.of(
            new CurrencyUnit("INR", 2), new CurrencyUnit("USD", 2), new CurrencyUnit("EUR", 2),
            new CurrencyUnit("JPY", 0), new CurrencyUnit("KWD", 3));

    @Test
    void parsingTheCanonicalFormRoundTrips(RandomGenerator random) {
        for (int i = 0; i < TRIES; i++) {
            Money money = randomPositive(random, randomCurrency(random));

            assertThat(Money.parsePositive(money.toPlainString(), money.currency())).isEqualTo(money);
            assertThat(money.amount().scale()).isEqualTo(money.currency().minorUnits());
        }
    }

    @Test
    void additionIsExactCommutativeAndInvertible(RandomGenerator random) {
        for (int i = 0; i < TRIES; i++) {
            CurrencyUnit currency = randomCurrency(random);
            Money a = randomPositive(random, currency);
            Money b = randomPositive(random, currency);

            assertThat(a.plus(b)).isEqualTo(b.plus(a));
            assertThat(a.plus(b).minus(b)).isEqualTo(a);
            assertThat(a.plus(b).amount()).isEqualByComparingTo(a.amount().add(b.amount()));
            assertThat(a.minus(a).isZero()).isTrue();
        }
    }

    @Test
    void anyDigitBeyondTheCurrencyScaleIsRejectedNotRounded(RandomGenerator random) {
        for (int i = 0; i < TRIES; i++) {
            CurrencyUnit currency = randomCurrency(random);
            String exact = randomPositive(random, currency).toPlainString();
            String extraDigit = (currency.minorUnits() == 0 ? exact + "." : exact) + (1 + random.nextInt(9));

            assertThatThrownBy(() -> Money.parsePositive(extraDigit, currency))
                    .isInstanceOfSatisfying(InvalidAmountException.class,
                            e -> assertThat(e.reason()).isEqualTo(InvalidAmountException.Reason.PRECISION_EXCEEDED));
        }
    }

    static CurrencyUnit randomCurrency(RandomGenerator random) {
        return CURRENCIES.get(random.nextInt(CURRENCIES.size()));
    }

    /** Positive amounts from one minor unit up to about 10^12, biased toward small values. */
    static Money randomPositive(RandomGenerator random, CurrencyUnit currency) {
        long bound = random.nextBoolean() ? 1_000 : 1_000_000_000_000L;
        long minorUnits = 1 + random.nextLong(bound);
        return Money.of(BigDecimal.valueOf(minorUnits, currency.minorUnits()), currency);
    }
}
