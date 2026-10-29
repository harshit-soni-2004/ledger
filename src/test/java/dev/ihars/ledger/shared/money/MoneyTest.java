package dev.ihars.ledger.shared.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ihars.ledger.shared.money.InvalidAmountException.Reason;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyTest {

    static final CurrencyUnit INR = new CurrencyUnit("INR", 2);
    static final CurrencyUnit USD = new CurrencyUnit("USD", 2);
    static final CurrencyUnit JPY = new CurrencyUnit("JPY", 0);
    static final CurrencyUnit KWD = new CurrencyUnit("KWD", 3);

    @Test
    void parsesValidAmountsAndNormalizesToCurrencyScale() {
        assertThat(Money.parsePositive("10.5", INR).toPlainString()).isEqualTo("10.50");
        assertThat(Money.parsePositive("10", INR).toPlainString()).isEqualTo("10.00");
        assertThat(Money.parsePositive("0.01", USD).toPlainString()).isEqualTo("0.01");
        assertThat(Money.parsePositive("100", JPY).toPlainString()).isEqualTo("100");
        assertThat(Money.parsePositive("1.234", KWD).toPlainString()).isEqualTo("1.234");
        assertThat(Money.parsePositive("999999999999999.99", INR).toPlainString()).isEqualTo("999999999999999.99");
    }

    @Test
    void trailingZerosBeyondTheCurrencyScaleLoseNothingAndAreAccepted() {
        assertThat(Money.parsePositive("10.500", INR)).isEqualTo(Money.parsePositive("10.5", INR));
        assertThat(Money.parsePositive("100.0", JPY).toPlainString()).isEqualTo("100");
    }

    @Test
    void rejectsExcessPrecisionInsteadOfRounding() {
        assertReason(() -> Money.parsePositive("10.505", INR), Reason.PRECISION_EXCEEDED);
        assertReason(() -> Money.parsePositive("0.001", USD), Reason.PRECISION_EXCEEDED);
        assertReason(() -> Money.parsePositive("100.5", JPY), Reason.PRECISION_EXCEEDED);
        assertReason(() -> Money.parsePositive("1.2345", KWD), Reason.PRECISION_EXCEEDED);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" 10", "10 ", "1e3", "1E3", "1,000", "1,000.00", "-5", "+5", ".5", "5.", "NaN",
            "Infinity", "007", "00.5", "0x10", "１０", "10..5", "1 000"})
    void rejectsMalformedInput(String text) {
        assertReason(() -> Money.parsePositive(text, INR), Reason.MALFORMED);
    }

    @Test
    void rejectsOversizedInputBeforeParsing() {
        assertReason(() -> Money.parsePositive("1" + "0".repeat(60), INR), Reason.MALFORMED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.0", "0.00", "0.000"})
    void rejectsZero(String text) {
        assertReason(() -> Money.parsePositive(text, INR), Reason.NOT_POSITIVE);
    }

    @Test
    void rejectsAmountsBeyondFifteenIntegerDigits() {
        assertReason(() -> Money.parsePositive("1000000000000000", INR), Reason.OUT_OF_RANGE);
        assertReason(() -> Money.of(new BigDecimal("-1000000000000000"), INR), Reason.OUT_OF_RANGE);
    }

    @Test
    void ofNormalizesDatabaseScaleAndAcceptsAnySign() {
        assertThat(Money.of(new BigDecimal("10.5000"), INR).toPlainString()).isEqualTo("10.50");
        assertThat(Money.of(new BigDecimal("-3.0000"), JPY).toPlainString()).isEqualTo("-3");
        assertThat(Money.of(new BigDecimal("1.2340"), KWD).toPlainString()).isEqualTo("1.234");
        assertReason(() -> Money.of(new BigDecimal("10.5010"), INR), Reason.PRECISION_EXCEEDED);
    }

    @Test
    void arithmeticIsExact() {
        Money a = Money.parsePositive("0.10", INR);
        Money b = Money.parsePositive("0.20", INR);

        assertThat(a.plus(b)).isEqualTo(Money.parsePositive("0.30", INR));
        assertThat(a.minus(b).toPlainString()).isEqualTo("-0.10");
        assertThat(a.negate().toPlainString()).isEqualTo("-0.10");
        assertThat(a.negate().negate()).isEqualTo(a);
    }

    @Test
    void signPredicates() {
        Money positive = Money.parsePositive("1", INR);

        assertThat(positive.isPositive()).isTrue();
        assertThat(positive.isNegative()).isFalse();
        assertThat(positive.isZero()).isFalse();
        assertThat(positive.negate().isNegative()).isTrue();
        assertThat(Money.zero(INR).isZero()).isTrue();
        assertThat(Money.zero(INR).isPositive()).isFalse();
    }

    @Test
    void comparesByValue() {
        Money one = Money.parsePositive("1.00", INR);
        Money two = Money.parsePositive("2", INR);

        assertThat(one.isLessThan(two)).isTrue();
        assertThat(two.isLessThan(one)).isFalse();
        assertThat(one.compareTo(Money.parsePositive("1", INR))).isZero();
    }

    @Test
    void currencyMismatch() {
        Money rupees = Money.parsePositive("1", INR);
        Money dollars = Money.parsePositive("1", USD);

        assertThatThrownBy(() -> rupees.plus(dollars)).isInstanceOf(CurrencyMismatchException.class)
                .hasMessageContaining("INR").hasMessageContaining("USD");
        assertThatThrownBy(() -> rupees.minus(dollars)).isInstanceOf(CurrencyMismatchException.class);
        assertThatThrownBy(() -> rupees.compareTo(dollars)).isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void equalityIgnoresInputScale() {
        Money a = Money.parsePositive("10.5", INR);
        Money b = Money.of(new BigDecimal("10.5000"), INR);

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(Money.parsePositive("10.5", new CurrencyUnit("EUR", 2)));
        assertThat(a.toString()).isEqualTo("10.50 INR");
    }

    @Test
    void currencyUnitValidatesCodeAndMinorUnits() {
        assertThat(new CurrencyUnit("KWD", 3).toString()).isEqualTo("KWD");
        assertThatThrownBy(() -> new CurrencyUnit("inr", 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CurrencyUnit("INRX", 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CurrencyUnit("INR", -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CurrencyUnit("INR", 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CurrencyUnit(null, 2)).isInstanceOf(NullPointerException.class);
    }

    private static void assertReason(Runnable action, Reason reason) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(InvalidAmountException.class, e -> assertThat(e.reason()).isEqualTo(reason));
    }
}
