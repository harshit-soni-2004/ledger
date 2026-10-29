package dev.ihars.ledger.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ihars.ledger.shared.money.CurrencyUnit;
import dev.ihars.ledger.shared.money.Money;
import org.junit.jupiter.api.Test;

class NormalBalanceTest {

    static final Money TEN = Money.parsePositive("10", new CurrencyUnit("INR", 2));

    @Test
    void normalBalancesFollowTheAccountingEquation() {
        assertThat(AccountType.ASSET.normalBalance()).isEqualTo(Direction.DEBIT);
        assertThat(AccountType.EXPENSE.normalBalance()).isEqualTo(Direction.DEBIT);
        assertThat(AccountType.LIABILITY.normalBalance()).isEqualTo(Direction.CREDIT);
        assertThat(AccountType.EQUITY.normalBalance()).isEqualTo(Direction.CREDIT);
        assertThat(AccountType.REVENUE.normalBalance()).isEqualTo(Direction.CREDIT);
    }

    @Test
    void entriesOnTheNormalSideIncreaseTheBalanceAndOthersDecreaseIt() {
        for (AccountType type : AccountType.values()) {
            Direction normal = type.normalBalance();

            assertThat(PostingCalculator.signedEffect(normal, normal, TEN)).as("%s on normal side", type).isEqualTo(TEN);
            assertThat(PostingCalculator.signedEffect(normal, normal.opposite(), TEN))
                    .as("%s on opposite side", type).isEqualTo(TEN.negate());
        }
    }

    @Test
    void directionsAreOpposites() {
        assertThat(Direction.DEBIT.opposite()).isEqualTo(Direction.CREDIT);
        assertThat(Direction.CREDIT.opposite()).isEqualTo(Direction.DEBIT);
    }

    @Test
    void onlyReversalsAdjustmentsAndOpeningsRequireAReason() {
        for (JournalType type : JournalType.values()) {
            assertThat(type.requiresReason()).as(type.name())
                    .isEqualTo(type == JournalType.REVERSAL || type == JournalType.ADJUSTMENT
                            || type == JournalType.OPENING);
        }
    }
}
