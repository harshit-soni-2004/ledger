package dev.ihars.ledger.accounts;

import static dev.ihars.ledger.support.LedgerTestData.ACTOR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ihars.ledger.accounts.AccountRejectedException.Code;
import dev.ihars.ledger.ledger.domain.AccountStatus;
import dev.ihars.ledger.ledger.domain.AccountType;
import dev.ihars.ledger.ledger.domain.Direction;
import dev.ihars.ledger.ledger.domain.OverdraftPolicy;
import dev.ihars.ledger.ledger.query.AccountBalance;
import dev.ihars.ledger.shared.money.Money;
import dev.ihars.ledger.support.LedgerIntegrationTest;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class AccountServiceIT extends LedgerIntegrationTest {

    @Test
    void openingAWalletCreatesTheAccountAndAZeroBalanceWithAZeroFloor() {
        Account wallet = accounts.open(OpenAccountCommand.wallet("  Alice  ", "INR"), ACTOR, "req-open-1");

        assertThat(wallet.publicId()).matches("acct_[0-9A-HJKMNP-TV-Z]{26}");
        assertThat(wallet.name()).isEqualTo("Alice");
        assertThat(wallet.normalBalance()).isEqualTo(Direction.CREDIT);
        assertThat(wallet.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(accounts.findByPublicId(wallet.publicId())).contains(wallet);
        assertThat(accounts.findById(wallet.id())).contains(wallet);

        AccountBalance balance = queries.balance(wallet.id()).orElseThrow();
        assertThat(balance.ledgerBalance()).isEqualTo(Money.zero(wallet.currency()));
        assertThat(balance.minBalance()).isEqualTo(Money.zero(wallet.currency()));
        assertThat(balance.lastEntrySeq()).isZero();
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_id = ? AND request_id = 'req-open-1'",
                wallet.publicId())).isEqualTo(1);
    }

    @Test
    void theNormalBalanceFollowsTheTypeAndTheFloorFollowsThePolicy() {
        Account asset = data.open(new OpenAccountCommand("Receivable", AccountType.ASSET, "KWD",
                OverdraftPolicy.Kind.LIMITED, "12.345"));
        Account equity = data.open(new OpenAccountCommand("Capital", AccountType.EQUITY, "JPY",
                OverdraftPolicy.Kind.UNLIMITED, null));

        assertThat(asset.normalBalance()).isEqualTo(Direction.DEBIT);
        assertThat(asset.overdraftPolicy().limit().amount()).isEqualByComparingTo("12.345");
        assertThat(queries.balance(asset.id()).orElseThrow().minBalance().amount())
                .isEqualByComparingTo(new BigDecimal("-12.345"));
        assertThat(equity.normalBalance()).isEqualTo(Direction.CREDIT);
        assertThat(queries.balance(equity.id()).orElseThrow().minBalance()).isNull();
    }

    @Test
    void invalidRequestsAreRejectedWithoutWritingAnything() {
        long before = count("SELECT COUNT(*) FROM accounts");

        assertRejected(Code.INVALID_NAME, OpenAccountCommand.wallet("   ", "INR"));
        assertRejected(Code.INVALID_NAME, OpenAccountCommand.wallet("x".repeat(129), "INR"));
        assertRejected(Code.INVALID_NAME, OpenAccountCommand.wallet("tab\tname", "INR"));
        assertRejected(Code.UNSUPPORTED_CURRENCY, OpenAccountCommand.wallet("Wallet", "GBP"));
        assertRejected(Code.UNSUPPORTED_CURRENCY, OpenAccountCommand.wallet("Wallet", "inr"));
        assertRejected(Code.UNSUPPORTED_CURRENCY, OpenAccountCommand.wallet("Wallet", "₹₹₹"));
        assertThatThrownBy(() -> accounts.open(OpenAccountCommand.wallet("Wallet", "INR"), ACTOR, "req id"))
                .isInstanceOf(IllegalArgumentException.class);
        assertRejected(Code.INVALID_TYPE, new OpenAccountCommand("Wallet", null, "INR",
                OverdraftPolicy.Kind.FORBIDDEN, null));
        assertRejected(Code.INVALID_OVERDRAFT, new OpenAccountCommand("Wallet", AccountType.LIABILITY, "INR",
                null, null));
        assertRejected(Code.INVALID_OVERDRAFT, new OpenAccountCommand("Wallet", AccountType.LIABILITY, "INR",
                OverdraftPolicy.Kind.LIMITED, null));
        assertRejected(Code.INVALID_OVERDRAFT, new OpenAccountCommand("Wallet", AccountType.LIABILITY, "KWD",
                OverdraftPolicy.Kind.LIMITED, "1.2345"));
        assertRejected(Code.INVALID_OVERDRAFT, new OpenAccountCommand("Wallet", AccountType.LIABILITY, "INR",
                OverdraftPolicy.Kind.FORBIDDEN, "10.00"));

        assertThat(count("SELECT COUNT(*) FROM accounts")).isEqualTo(before);
    }

    @Test
    void systemAccountsAreSeededPerCurrency() {
        Account suspense = accounts.findSystemAccount("LIABILITY.SUSPENSE", "KWD").orElseThrow();

        assertThat(suspense.systemCode()).isEqualTo("LIABILITY.SUSPENSE");
        assertThat(suspense.currency().minorUnits()).isEqualTo(3);
        assertThat(suspense.overdraftPolicy().kind()).isEqualTo(OverdraftPolicy.Kind.UNLIMITED);
        assertThat(accounts.findSystemAccount("LIABILITY.SUSPENSE", "GBP")).isEmpty();
    }

    private void assertRejected(Code code, OpenAccountCommand command) {
        assertThatThrownBy(() -> accounts.open(command, ACTOR, null))
                .isInstanceOfSatisfying(AccountRejectedException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
}
