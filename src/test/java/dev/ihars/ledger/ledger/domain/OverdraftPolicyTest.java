package dev.ihars.ledger.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ihars.ledger.ledger.domain.OverdraftPolicy.Kind;
import dev.ihars.ledger.shared.money.CurrencyMismatchException;
import dev.ihars.ledger.shared.money.CurrencyUnit;
import dev.ihars.ledger.shared.money.Money;
import org.junit.jupiter.api.Test;

class OverdraftPolicyTest {

    static final CurrencyUnit INR = new CurrencyUnit("INR", 2);

    @Test
    void floors() {
        assertThat(OverdraftPolicy.forbidden().minBalance(INR)).contains(Money.zero(INR));
        assertThat(OverdraftPolicy.limited(inr("500")).minBalance(INR)).contains(inr("500").negate());
        assertThat(OverdraftPolicy.unlimited().minBalance(INR)).isEmpty();
    }

    @Test
    void limitIsRequiredExactlyForLimited() {
        assertThatThrownBy(() -> new OverdraftPolicy(Kind.LIMITED, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OverdraftPolicy(Kind.FORBIDDEN, inr("1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OverdraftPolicy(Kind.LIMITED, Money.zero(INR)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OverdraftPolicy.limited(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void limitMustBeInTheAccountCurrency() {
        assertThatThrownBy(() -> OverdraftPolicy.limited(inr("1")).minBalance(new CurrencyUnit("USD", 2)))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    private static Money inr(String amount) {
        return Money.parsePositive(amount, INR);
    }
}
