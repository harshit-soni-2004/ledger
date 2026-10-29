package dev.ihars.ledger.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ihars.ledger.ledger.domain.JournalRejectedException.Code;
import dev.ihars.ledger.ledger.domain.PostingPlan.BalanceChange;
import dev.ihars.ledger.ledger.domain.PostingPlan.PlannedEntry;
import dev.ihars.ledger.shared.money.CurrencyUnit;
import dev.ihars.ledger.shared.money.Money;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PostingCalculatorTest {

    static final CurrencyUnit INR = new CurrencyUnit("INR", 2);
    static final CurrencyUnit USD = new CurrencyUnit("USD", 2);
    static final LocalDate TODAY = LocalDate.of(2026, 10, 29);

    static final long CASH = 1;
    static final long WALLET_A = 2;
    static final long WALLET_B = 3;

    @Test
    void depositIncreasesBothSidesInTheirNormalOrientation() {
        Map<Long, AccountSnapshot> accounts = Map.of(
                CASH, snapshot(CASH, Direction.DEBIT, "0", null, 0),
                WALLET_A, snapshot(WALLET_A, Direction.CREDIT, "0", "0", 0));
        JournalDraft deposit = JournalDraft.builder(JournalType.DEPOSIT, INR, TODAY)
                .debit(CASH, inr("1000")).credit(WALLET_A, inr("1000")).build();

        PostingPlan plan = PostingCalculator.plan(deposit, accounts, TODAY);

        assertThat(plan.draft()).isSameAs(deposit);
        assertThat(plan.entries()).containsExactly(
                new PlannedEntry(1, CASH, Direction.DEBIT, inr("1000"), 1, inr("1000")),
                new PlannedEntry(2, WALLET_A, Direction.CREDIT, inr("1000"), 1, inr("1000")));
        assertThat(plan.balanceChanges()).containsExactly(
                new BalanceChange(CASH, Money.zero(INR), inr("1000"), 0, 1),
                new BalanceChange(WALLET_A, Money.zero(INR), inr("1000"), 0, 1));
    }

    @Test
    void sequencesContinueAndBalanceChangesAreInAscendingAccountOrder() {
        Map<Long, AccountSnapshot> accounts = Map.of(
                WALLET_A, snapshot(WALLET_A, Direction.CREDIT, "500", "0", 7),
                WALLET_B, snapshot(WALLET_B, Direction.CREDIT, "10", "0", 3));
        JournalDraft transfer = JournalDraft.builder(JournalType.TRANSFER, INR, TODAY)
                .credit(WALLET_B, inr("100")).credit(WALLET_B, inr("200")).debit(WALLET_A, inr("300")).build();

        PostingPlan plan = PostingCalculator.plan(transfer, accounts, TODAY);

        assertThat(plan.entries()).extracting(PlannedEntry::accountSeq).containsExactly(4L, 5L, 8L);
        assertThat(plan.entries()).extracting(PlannedEntry::balanceAfter)
                .containsExactly(inr("110"), inr("310"), inr("200"));
        assertThat(plan.balanceChanges()).extracting(BalanceChange::accountId).containsExactly(WALLET_A, WALLET_B);
        assertThat(plan.balanceChanges().get(0)).isEqualTo(new BalanceChange(WALLET_A, inr("500"), inr("200"), 7, 8));
    }

    @Test
    void forbiddenOverdraftRejectsGoingBelowZeroButAllowsExactlyZero() {
        Map<Long, AccountSnapshot> accounts = Map.of(
                WALLET_A, snapshot(WALLET_A, Direction.CREDIT, "100", "0", 1),
                WALLET_B, snapshot(WALLET_B, Direction.CREDIT, "0", "0", 0));

        assertRejected(transfer(WALLET_A, WALLET_B, "100.01"), accounts, Code.INSUFFICIENT_FUNDS);
        assertThat(PostingCalculator.plan(transfer(WALLET_A, WALLET_B, "100"), accounts, TODAY)
                .balanceChanges().get(0).newBalance()).isEqualTo(Money.zero(INR));
    }

    @Test
    void limitedOverdraftAllowsDownToTheNegativeLimit() {
        Map<Long, AccountSnapshot> accounts = Map.of(
                WALLET_A, snapshot(WALLET_A, Direction.CREDIT, "0", "-50", 0),
                WALLET_B, snapshot(WALLET_B, Direction.CREDIT, "0", "0", 0));

        assertThat(PostingCalculator.plan(transfer(WALLET_A, WALLET_B, "50"), accounts, TODAY)
                .balanceChanges().get(0).newBalance()).isEqualTo(inr("50").negate());
        assertRejected(transfer(WALLET_A, WALLET_B, "50.01"), accounts, Code.INSUFFICIENT_FUNDS);
    }

    @Test
    void unlimitedAccountsMayGoNegative() {
        Map<Long, AccountSnapshot> accounts = Map.of(
                CASH, snapshot(CASH, Direction.DEBIT, "0", null, 0),
                WALLET_A, snapshot(WALLET_A, Direction.CREDIT, "0", "0", 0));
        JournalDraft payout = JournalDraft.builder(JournalType.PAYOUT, INR, TODAY)
                .debit(WALLET_A, inr("1")).credit(CASH, inr("1")).build();

        assertRejected(payout, accounts, Code.INSUFFICIENT_FUNDS);

        Map<Long, AccountSnapshot> funded = Map.of(
                CASH, snapshot(CASH, Direction.DEBIT, "0", null, 0),
                WALLET_A, snapshot(WALLET_A, Direction.CREDIT, "1", "0", 1));
        assertThat(PostingCalculator.plan(payout, funded, TODAY).balanceChanges().get(0).newBalance())
                .isEqualTo(inr("1").negate());
    }

    @Test
    void creditsToAnAccountBelowItsFloorAreStillAccepted() {
        Map<Long, AccountSnapshot> accounts = Map.of(
                WALLET_A, snapshot(WALLET_A, Direction.CREDIT, "-10", "0", 1),
                CASH, snapshot(CASH, Direction.DEBIT, "0", null, 0));
        JournalDraft deposit = JournalDraft.builder(JournalType.DEPOSIT, INR, TODAY)
                .debit(CASH, inr("5")).credit(WALLET_A, inr("5")).build();

        assertThat(PostingCalculator.plan(deposit, accounts, TODAY).balanceChanges())
                .anySatisfy(change -> assertThat(change.newBalance()).isEqualTo(inr("5").negate()));
    }

    @Test
    void unknownAccountRejected() {
        Map<Long, AccountSnapshot> accounts = Map.of(WALLET_A, snapshot(WALLET_A, Direction.CREDIT, "100", "0", 1));

        assertRejected(transfer(WALLET_A, WALLET_B, "1"), accounts, Code.ACCOUNT_NOT_FOUND);
    }

    @Test
    void accountInAnotherCurrencyRejected() {
        Map<Long, AccountSnapshot> accounts = Map.of(
                WALLET_A, snapshot(WALLET_A, Direction.CREDIT, "100", "0", 1),
                WALLET_B, new AccountSnapshot(WALLET_B, USD, Direction.CREDIT, AccountStatus.ACTIVE,
                        Money.zero(USD), Money.zero(USD), 0));

        assertRejected(transfer(WALLET_A, WALLET_B, "1"), accounts, Code.CURRENCY_MISMATCH);
    }

    @Test
    void inactiveAccountsRejected() {
        for (AccountStatus status : new AccountStatus[] {AccountStatus.FROZEN, AccountStatus.CLOSED}) {
            Map<Long, AccountSnapshot> accounts = Map.of(
                    WALLET_A, snapshot(WALLET_A, Direction.CREDIT, "100", "0", 1),
                    WALLET_B, new AccountSnapshot(WALLET_B, INR, Direction.CREDIT, status, Money.zero(INR),
                            Money.zero(INR), 0));

            assertRejected(transfer(WALLET_A, WALLET_B, "1"), accounts, Code.ACCOUNT_NOT_ACTIVE);
        }
    }

    @Test
    void futureEffectiveDatesRejected() {
        Map<Long, AccountSnapshot> accounts = Map.of(
                WALLET_A, snapshot(WALLET_A, Direction.CREDIT, "100", "0", 1),
                WALLET_B, snapshot(WALLET_B, Direction.CREDIT, "0", "0", 0));
        JournalDraft tomorrow = JournalDraft.builder(JournalType.TRANSFER, INR, TODAY.plusDays(1))
                .debit(WALLET_A, inr("1")).credit(WALLET_B, inr("1")).build();

        assertRejected(tomorrow, accounts, Code.EFFECTIVE_DATE_IN_FUTURE);
        assertThat(PostingCalculator.plan(tomorrow, accounts, TODAY.plusDays(1)).entries()).hasSize(2);
    }

    @Test
    void balancesBeyondTheSupportedRangeRejected() {
        Map<Long, AccountSnapshot> accounts = Map.of(
                CASH, snapshot(CASH, Direction.DEBIT, "999999999999999.99", null, 1),
                WALLET_A, snapshot(WALLET_A, Direction.CREDIT, "0", "0", 0));
        JournalDraft deposit = JournalDraft.builder(JournalType.DEPOSIT, INR, TODAY)
                .debit(CASH, inr("0.01")).credit(WALLET_A, inr("0.01")).build();

        assertRejected(deposit, accounts, Code.AMOUNT_OUT_OF_RANGE);
    }

    @Test
    void snapshotsMustBeInTheAccountCurrency() {
        assertThatThrownBy(() -> new AccountSnapshot(1, INR, Direction.DEBIT, AccountStatus.ACTIVE,
                Money.zero(USD), null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AccountSnapshot(1, INR, Direction.DEBIT, AccountStatus.ACTIVE,
                Money.zero(INR), Money.zero(USD), 0)).isInstanceOf(IllegalArgumentException.class);
    }

    private static JournalDraft transfer(long from, long to, String amount) {
        return JournalDraft.builder(JournalType.TRANSFER, INR, TODAY)
                .debit(from, inr(amount)).credit(to, inr(amount)).build();
    }

    private static AccountSnapshot snapshot(long id, Direction normal, String balance, String minBalance, long seq) {
        return new AccountSnapshot(id, INR, normal, AccountStatus.ACTIVE, signed(balance),
                minBalance == null ? null : signed(minBalance), seq);
    }

    private static Money signed(String amount) {
        return Money.of(new java.math.BigDecimal(amount), INR);
    }

    private static Money inr(String amount) {
        return Money.parsePositive(amount, INR);
    }

    private static void assertRejected(JournalDraft draft, Map<Long, AccountSnapshot> accounts, Code code) {
        assertThatThrownBy(() -> PostingCalculator.plan(draft, accounts, TODAY))
                .isInstanceOfSatisfying(JournalRejectedException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
}
