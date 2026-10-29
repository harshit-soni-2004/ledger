package dev.ihars.ledger.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ihars.ledger.ledger.domain.JournalRejectedException.Code;
import dev.ihars.ledger.shared.money.CurrencyUnit;
import dev.ihars.ledger.shared.money.InvalidAmountException;
import dev.ihars.ledger.shared.money.Money;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class JournalDraftTest {

    static final CurrencyUnit INR = new CurrencyUnit("INR", 2);
    static final CurrencyUnit USD = new CurrencyUnit("USD", 2);
    static final LocalDate DAY = LocalDate.of(2026, 10, 29);

    @Test
    void balancedJournalIsBuiltWithItsTotal() {
        JournalDraft draft = transfer()
                .debit(1, inr("100.00"))
                .credit(2, inr("60.00"))
                .credit(3, inr("40.00"))
                .description("Rent split")
                .externalReference("UTR-2026/10:29.1")
                .build();

        assertThat(draft.type()).isEqualTo(JournalType.TRANSFER);
        assertThat(draft.currency()).isEqualTo(INR);
        assertThat(draft.effectiveDate()).isEqualTo(DAY);
        assertThat(draft.total()).isEqualTo(inr("100"));
        assertThat(draft.lines()).hasSize(3);
        assertThat(draft.description()).isEqualTo("Rent split");
        assertThat(draft.externalReference()).isEqualTo("UTR-2026/10:29.1");
        assertThat(draft.reason()).isNull();
        assertThat(draft.reversesJournalId()).isNull();
    }

    @Test
    void linesAreImmutable() {
        JournalDraft draft = transfer().debit(1, inr("1")).credit(2, inr("1")).build();

        assertThatThrownBy(() -> draft.lines().add(new JournalLine(3, Direction.DEBIT, inr("1"))))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void unbalancedRejected() {
        assertRejected(transfer().debit(1, inr("100.00")).credit(2, inr("99.99")), Code.UNBALANCED);
    }

    @Test
    void singleLineRejected() {
        assertRejected(transfer().debit(1, inr("1")), Code.TOO_FEW_LINES);
        assertRejected(transfer(), Code.TOO_FEW_LINES);
    }

    @Test
    void atMostOneHundredLines() {
        JournalDraft.Builder hundred = transfer().credit(1000, inr("99"));
        for (int account = 1; account <= 99; account++) {
            hundred.debit(account, inr("1"));
        }
        assertThat(hundred.build().lines()).hasSize(JournalDraft.MAX_LINES);

        JournalDraft.Builder hundredAndOne = transfer().credit(1000, inr("100"));
        for (int account = 1; account <= 100; account++) {
            hundredAndOne.debit(account, inr("1"));
        }
        assertRejected(hundredAndOne, Code.TOO_MANY_LINES);
    }

    @Test
    void mixedCurrencyRejected() {
        assertRejected(transfer().debit(1, inr("1")).credit(2, Money.parsePositive("1", USD)), Code.CURRENCY_MISMATCH);
    }

    @Test
    void sameAccountBothSidesRejected() {
        assertRejected(transfer().debit(1, inr("2")).credit(1, inr("1")).credit(2, inr("1")),
                Code.SAME_ACCOUNT_BOTH_SIDES);
    }

    @Test
    void sameAccountTwiceOnOneSideIsAllowed() {
        JournalDraft draft = transfer().debit(1, inr("1")).debit(1, inr("2")).credit(2, inr("3")).build();

        assertThat(draft.total()).isEqualTo(inr("3"));
    }

    @Test
    void nonPositiveLineAmountsRejected() {
        assertThatThrownBy(() -> new JournalLine(1, Direction.DEBIT, Money.zero(INR)))
                .isInstanceOfSatisfying(InvalidAmountException.class,
                        e -> assertThat(e.reason()).isEqualTo(InvalidAmountException.Reason.NOT_POSITIVE));
        assertThatThrownBy(() -> new JournalLine(1, Direction.CREDIT, inr("1").negate()))
                .isInstanceOf(InvalidAmountException.class);
    }

    @Test
    void totalBeyondTheSupportedRangeRejected() {
        Money max = inr("999999999999999.99");

        assertRejected(transfer().debit(1, max).debit(2, max).credit(3, max), Code.AMOUNT_OUT_OF_RANGE);
    }

    @Test
    void adjustmentsRequireAReason() {
        JournalDraft.Builder noReason = JournalDraft.builder(JournalType.ADJUSTMENT, INR, DAY)
                .debit(1, inr("15")).credit(2, inr("15"));
        assertRejected(noReason, Code.REASON_REQUIRED);
        assertRejected(noReason.reason("   "), Code.REASON_REQUIRED);

        JournalDraft withReason = noReason.reason("Bank fee found in reconciliation").build();
        assertThat(withReason.reason()).isEqualTo("Bank fee found in reconciliation");
    }

    @Test
    void textFieldsAreBounded() {
        assertRejected(balancedTransfer().description("d".repeat(256)), Code.INVALID_FIELD);
        assertThat(balancedTransfer().description("d".repeat(255)).build().description()).hasSize(255);
        assertRejected(balancedTransfer().reason("r".repeat(501)), Code.INVALID_FIELD);
        assertThat(balancedTransfer().reason("r".repeat(500)).build().reason()).hasSize(500);
    }

    @Test
    void externalReferenceMustBeSafeAscii() {
        assertRejected(balancedTransfer().externalReference("has space"), Code.INVALID_FIELD);
        assertRejected(balancedTransfer().externalReference("é"), Code.INVALID_FIELD);
        assertRejected(balancedTransfer().externalReference(""), Code.INVALID_FIELD);
        assertRejected(balancedTransfer().externalReference("x".repeat(65)), Code.INVALID_FIELD);
        assertThat(balancedTransfer().externalReference("x".repeat(64)).build().externalReference()).hasSize(64);
    }

    @Test
    void reversalJournalsCanOnlyBeCreatedByMirroring() {
        assertThatThrownBy(() -> JournalDraft.builder(JournalType.REVERSAL, INR, DAY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mirrorFlipsEveryLineAndLinksTheOriginal() {
        JournalDraft original = transfer().debit(1, inr("70")).debit(2, inr("30")).credit(3, inr("100"))
                .description("original").externalReference("ref-1").build();

        JournalDraft reversal = original.mirror(42L, "Customer dispute", DAY.plusDays(1));

        assertThat(reversal.type()).isEqualTo(JournalType.REVERSAL);
        assertThat(reversal.reversesJournalId()).isEqualTo(42L);
        assertThat(reversal.reason()).isEqualTo("Customer dispute");
        assertThat(reversal.effectiveDate()).isEqualTo(DAY.plusDays(1));
        assertThat(reversal.total()).isEqualTo(original.total());
        assertThat(reversal.description()).isNull();
        assertThat(reversal.externalReference()).isNull();
        assertThat(reversal.lines()).containsExactly(
                new JournalLine(1, Direction.CREDIT, inr("70")),
                new JournalLine(2, Direction.CREDIT, inr("30")),
                new JournalLine(3, Direction.DEBIT, inr("100")));
    }

    @Test
    void mirrorRequiresAReason() {
        assertThatThrownBy(() -> balancedTransfer().build().mirror(1L, null, DAY))
                .isInstanceOfSatisfying(JournalRejectedException.class,
                        e -> assertThat(e.code()).isEqualTo(Code.REASON_REQUIRED));
    }

    @Test
    void aReversalCannotBeReversed() {
        JournalDraft reversal = balancedTransfer().build().mirror(1L, "mistake", DAY);

        assertThatThrownBy(() -> reversal.mirror(2L, "undo", DAY))
                .isInstanceOfSatisfying(JournalRejectedException.class,
                        e -> assertThat(e.code()).isEqualTo(Code.CANNOT_REVERSE_REVERSAL));
    }

    @Test
    void explicitLinesAreAccepted() {
        JournalDraft draft = transfer()
                .line(new JournalLine(1, Direction.DEBIT, inr("5")))
                .line(new JournalLine(2, Direction.CREDIT, inr("5")))
                .build();

        assertThat(draft.total()).isEqualTo(inr("5"));
    }

    private static JournalDraft.Builder transfer() {
        return JournalDraft.builder(JournalType.TRANSFER, INR, DAY);
    }

    private static JournalDraft.Builder balancedTransfer() {
        return transfer().debit(1, inr("1")).credit(2, inr("1"));
    }

    private static Money inr(String amount) {
        return Money.parsePositive(amount, INR);
    }

    private static void assertRejected(JournalDraft.Builder builder, Code code) {
        assertThatThrownBy(builder::build)
                .isInstanceOfSatisfying(JournalRejectedException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
}
