package dev.ihars.ledger.shared.money;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * An ISO 4217 currency and its number of minor units (decimal places). The supported set and
 * their minor units come from the {@code currencies} table; this type only guarantees the
 * values are well formed.
 */
public record CurrencyUnit(String code, int minorUnits) {

    /** Highest scale the database stores ({@code DECIMAL(19,4)}). */
    public static final int MAX_MINOR_UNITS = 4;

    private static final Pattern CODE = Pattern.compile("[A-Z]{3}");

    public CurrencyUnit {
        Objects.requireNonNull(code, "code");
        if (!CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("Currency code must be three uppercase letters: " + code);
        }
        if (minorUnits < 0 || minorUnits > MAX_MINOR_UNITS) {
            throw new IllegalArgumentException("Minor units must be between 0 and " + MAX_MINOR_UNITS + ": " + minorUnits);
        }
    }

    @Override
    public String toString() {
        return code;
    }
}
