package dev.ihars.ledger.shared.money;

import dev.ihars.ledger.shared.money.InvalidAmountException.Reason;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * An exact amount in one currency (ADR-0004).
 *
 * <p>The amount is always normalized to the currency's minor units. Normalization never
 * rounds: a value that would need rounding is rejected. Because every instance has the same
 * scale for a given currency, {@link #equals} is value equality ({@code 10.5} and
 * {@code 10.50} INR are equal).
 *
 * <p>Amounts may be negative (balances can be); user input goes through
 * {@link #parsePositive}, which accepts only positive plain decimals.
 */
public record Money(BigDecimal amount, CurrencyUnit currency) implements Comparable<Money> {

    /** Integer digits that fit {@code DECIMAL(19,4)}. */
    public static final int MAX_INTEGER_DIGITS = 15;

    private static final int MAX_INPUT_LENGTH = MAX_INTEGER_DIGITS + 1 + 32;
    private static final Pattern PLAIN_DECIMAL = Pattern.compile("(0|[1-9][0-9]*)(\\.[0-9]+)?");

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        try {
            amount = amount.setScale(currency.minorUnits(), RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new InvalidAmountException(Reason.PRECISION_EXCEEDED,
                    "%s allows %d decimal places: %s".formatted(currency, currency.minorUnits(), amount.toPlainString()));
        }
        if (amount.precision() - amount.scale() > MAX_INTEGER_DIGITS) {
            throw new InvalidAmountException(Reason.OUT_OF_RANGE,
                    "Amount exceeds %d integer digits: %s".formatted(MAX_INTEGER_DIGITS, amount.toPlainString()));
        }
    }

    public static Money of(BigDecimal amount, CurrencyUnit currency) {
        return new Money(amount, currency);
    }

    public static Money zero(CurrencyUnit currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    /**
     * Parses untrusted input such as {@code "10.50"}. Accepts only a positive plain decimal:
     * no sign, exponent, grouping separator, whitespace or leading zeros. Trailing zeros are
     * accepted because they lose nothing ({@code "10.500"} INR is {@code 10.50}).
     */
    public static Money parsePositive(String text, CurrencyUnit currency) {
        Objects.requireNonNull(currency, "currency");
        if (text == null || text.length() > MAX_INPUT_LENGTH || !PLAIN_DECIMAL.matcher(text).matches()) {
            throw new InvalidAmountException(Reason.MALFORMED, "Amount must be a plain positive decimal string");
        }
        Money money = new Money(new BigDecimal(text), currency);
        if (!money.isPositive()) {
            throw new InvalidAmountException(Reason.NOT_POSITIVE, "Amount must be greater than zero");
        }
        return money;
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public Money negate() {
        return new Money(amount.negate(), currency);
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isLessThan(Money other) {
        return compareTo(other) < 0;
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    /** Plain decimal at the currency scale, e.g. {@code "1500.00"}; the API and log format. */
    public String toPlainString() {
        return amount.toPlainString();
    }

    @Override
    public String toString() {
        return toPlainString() + " " + currency;
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }
}
