package dev.ihars.ledger.ledger.domain;

import dev.ihars.ledger.shared.money.CurrencyMismatchException;
import dev.ihars.ledger.shared.money.CurrencyUnit;
import dev.ihars.ledger.shared.money.Money;
import java.util.Objects;
import java.util.Optional;

/**
 * How far below zero an account's balance (in its normal orientation) may go.
 *
 * <ul>
 *   <li>{@code FORBIDDEN}: floor 0 (customer wallets)
 *   <li>{@code LIMITED}: floor {@code -limit}
 *   <li>{@code UNLIMITED}: no floor (system accounts)
 * </ul>
 */
public record OverdraftPolicy(Kind kind, Money limit) {

    public enum Kind {
        FORBIDDEN,
        LIMITED,
        UNLIMITED
    }

    public OverdraftPolicy {
        Objects.requireNonNull(kind, "kind");
        if ((kind == Kind.LIMITED) != (limit != null)) {
            throw new IllegalArgumentException("A limit is required for LIMITED and not allowed otherwise");
        }
        if (limit != null && !limit.isPositive()) {
            throw new IllegalArgumentException("Overdraft limit must be positive");
        }
    }

    public static OverdraftPolicy forbidden() {
        return new OverdraftPolicy(Kind.FORBIDDEN, null);
    }

    public static OverdraftPolicy unlimited() {
        return new OverdraftPolicy(Kind.UNLIMITED, null);
    }

    public static OverdraftPolicy limited(Money limit) {
        return new OverdraftPolicy(Kind.LIMITED, Objects.requireNonNull(limit, "limit"));
    }

    /** Lowest allowed balance in the given currency; empty when there is no floor. */
    public Optional<Money> minBalance(CurrencyUnit currency) {
        return switch (kind) {
            case FORBIDDEN -> Optional.of(Money.zero(currency));
            case LIMITED -> {
                if (!limit.currency().equals(currency)) {
                    throw new CurrencyMismatchException(currency, limit.currency());
                }
                yield Optional.of(limit.negate());
            }
            case UNLIMITED -> Optional.empty();
        };
    }
}
