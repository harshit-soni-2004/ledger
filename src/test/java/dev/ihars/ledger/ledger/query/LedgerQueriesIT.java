package dev.ihars.ledger.ledger.query;

import static dev.ihars.ledger.support.LedgerTestData.BANK;
import static dev.ihars.ledger.support.LedgerTestData.CONTEXT;
import static dev.ihars.ledger.support.LedgerTestData.money;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ihars.ledger.accounts.Account;
import dev.ihars.ledger.accounts.OpenAccountCommand;
import dev.ihars.ledger.ledger.domain.AccountType;
import dev.ihars.ledger.ledger.domain.JournalDraft;
import dev.ihars.ledger.ledger.domain.JournalType;
import dev.ihars.ledger.ledger.domain.OverdraftPolicy;
import dev.ihars.ledger.shared.money.Money;
import dev.ihars.ledger.support.LedgerIntegrationTest;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class LedgerQueriesIT extends LedgerIntegrationTest {

    @Test
    void balanceAsOfADateFollowsEffectiveDatesWhileBalanceAfterFollowsPostingOrder() {
        Account wallet = data.wallet("INR");
        LocalDate today = data.today();
        data.deposit(wallet, "100.00");
        depositOn(wallet, "40.00", today.minusDays(10));

        assertThat(queries.balanceAsOf(wallet.id(), today.minusDays(11))).contains(Money.zero(wallet.currency()));
        assertThat(queries.balanceAsOf(wallet.id(), today.minusDays(10))).contains(money(wallet, "40.00"));
        assertThat(queries.balanceAsOf(wallet.id(), today.minusDays(1))).contains(money(wallet, "40.00"));
        assertThat(queries.balanceAsOf(wallet.id(), today)).contains(money(wallet, "140.00"));
        // The backdated entry was posted second, so its balance_after includes the first deposit.
        assertThat(queries.entries(wallet.id(), 0, 10))
                .extracting(e -> e.effectiveDate() + ":" + e.balanceAfter().toPlainString())
                .containsExactly(today + ":100.00", today.minusDays(10) + ":140.00");
        assertThat(queries.balance(wallet.id()).orElseThrow().ledgerBalance()).isEqualTo(money(wallet, "140.00"));
    }

    @Test
    void balanceAsOfIsSignedByNormalBalance() {
        Account wallet = data.wallet("INR");
        Account payee = data.wallet("INR");
        data.deposit(wallet, "10.00");
        data.transfer(wallet, payee, "3.50");

        assertThat(queries.balanceAsOf(wallet.id(), data.today())).contains(money(wallet, "6.50"));
        assertThat(queries.balanceAsOf(payee.id(), data.today())).contains(money(wallet, "3.50"));
    }

    @Test
    void availableIsWhatTheFloorStillAllows() {
        Account wallet = data.wallet("INR");
        Account credit = data.open(new OpenAccountCommand("Credit line", AccountType.LIABILITY, "INR",
                OverdraftPolicy.Kind.LIMITED, "25.00"));
        data.deposit(wallet, "10.00");
        data.deposit(credit, "5.00");

        assertThat(queries.balance(wallet.id()).orElseThrow().available()).contains(money(wallet, "10.00"));
        assertThat(queries.balance(credit.id()).orElseThrow().available()).contains(money(wallet, "30.00"));
        assertThat(queries.balance(data.system(BANK, "INR").id()).orElseThrow().available()).isEmpty();
    }

    @Test
    void entriesArePagedBySequence() {
        Account wallet = data.wallet("JPY");
        for (int i = 1; i <= 5; i++) {
            data.deposit(wallet, String.valueOf(i * 100));
        }

        assertThat(queries.entries(wallet.id(), 0, 2)).extracting(LedgerEntryView::accountSeq).containsExactly(1L, 2L);
        assertThat(queries.entries(wallet.id(), 2, 2)).extracting(LedgerEntryView::accountSeq).containsExactly(3L, 4L);
        assertThat(queries.entries(wallet.id(), 4, 2)).extracting(LedgerEntryView::accountSeq).containsExactly(5L);
        assertThat(queries.entries(wallet.id(), 5, 2)).isEmpty();
        assertThatThrownBy(() -> queries.entries(wallet.id(), 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> queries.entries(wallet.id(), 0, LedgerQueries.MAX_PAGE_SIZE + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownAccountsAndJournalsAreEmpty() {
        assertThat(queries.balance(Long.MAX_VALUE)).isEmpty();
        assertThat(queries.balanceAsOf(Long.MAX_VALUE, data.today())).isEmpty();
        assertThat(queries.entries(Long.MAX_VALUE, 0, 10)).isEmpty();
        assertThat(queries.journal("jtx_00000000000000000000000000")).isEmpty();
    }

    private void depositOn(Account wallet, String amount, LocalDate effectiveDate) {
        Money money = money(wallet, amount);
        engine.post(JournalDraft.builder(JournalType.DEPOSIT, wallet.currency(), effectiveDate)
                .debit(data.system(BANK, "INR").id(), money)
                .credit(wallet.id(), money)
                .build(), CONTEXT);
    }
}
