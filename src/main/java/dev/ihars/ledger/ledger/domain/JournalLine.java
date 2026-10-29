package dev.ihars.ledger.ledger.domain;

import dev.ihars.ledger.shared.money.InvalidAmountException;
import dev.ihars.ledger.shared.money.Money;
import java.util.Objects;

/** One debit or credit of a strictly positive amount to an account (internal id). */
public record JournalLine(long accountId, Direction direction, Money amount) {

    public JournalLine {
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(amount, "amount");
        if (!amount.isPositive()) {
            throw new InvalidAmountException(InvalidAmountException.Reason.NOT_POSITIVE,
                    "Journal line amounts must be greater than zero");
        }
    }

    JournalLine flipped() {
        return new JournalLine(accountId, direction.opposite(), amount);
    }
}
