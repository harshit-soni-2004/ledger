package dev.ihars.ledger.ledger.posting;

import static dev.ihars.ledger.support.LedgerTestData.ACTOR;
import static dev.ihars.ledger.support.LedgerTestData.BANK;
import static dev.ihars.ledger.support.LedgerTestData.CONTEXT;
import static dev.ihars.ledger.support.LedgerTestData.money;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import dev.ihars.ledger.accounts.Account;
import dev.ihars.ledger.accounts.OpenAccountCommand;
import dev.ihars.ledger.ledger.domain.AccountType;
import dev.ihars.ledger.ledger.domain.Direction;
import dev.ihars.ledger.ledger.domain.JournalDraft;
import dev.ihars.ledger.ledger.domain.JournalRejectedException;
import dev.ihars.ledger.ledger.domain.JournalRejectedException.Code;
import dev.ihars.ledger.ledger.domain.JournalType;
import dev.ihars.ledger.ledger.domain.OverdraftPolicy;
import dev.ihars.ledger.ledger.query.AccountBalance;
import dev.ihars.ledger.ledger.query.JournalView;
import dev.ihars.ledger.ledger.query.LedgerEntryView;
import dev.ihars.ledger.shared.money.Money;
import dev.ihars.ledger.support.LedgerIntegrationTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class PostingEngineIT extends LedgerIntegrationTest {

    @Test
    void postingWritesHeaderEntriesBalancesAndOneAuditEvent() {
        Account wallet = data.wallet("INR");
        Account bank = data.system(BANK, "INR");
        AccountBalance bankBefore = queries.balance(bank.id()).orElseThrow();
        Money amount = money(wallet, "100.50");

        PostedJournal posted = engine.post(JournalDraft.builder(JournalType.DEPOSIT, wallet.currency(), data.today())
                .debit(bank.id(), amount)
                .credit(wallet.id(), amount)
                .description("Initial deposit")
                .externalReference("bank:tx-0001")
                .build(), new PostingContext(ACTOR, "req-posting-1", "corr-posting-1"));

        JournalView journal = queries.journal(posted.publicId()).orElseThrow();
        assertThat(journal.publicId()).matches("jtx_[0-9A-HJKMNP-TV-Z]{26}");
        assertThat(journal.type()).isEqualTo(JournalType.DEPOSIT);
        assertThat(journal.total()).isEqualTo(amount);
        assertThat(journal.postedBy()).isEqualTo(ACTOR.id());
        assertThat(journal.requestId()).isEqualTo("req-posting-1");
        assertThat(journal.correlationId()).isEqualTo("corr-posting-1");
        assertThat(journal.description()).isEqualTo("Initial deposit");
        assertThat(journal.externalReference()).isEqualTo("bank:tx-0001");
        assertThat(journal.effectiveDate()).isEqualTo(data.today());
        assertThat(journal.postedAt()).isEqualTo(posted.postedAt());

        Map<String, Object> header = privilegedJdbc()
                .sql("SELECT total_debit, total_credit FROM journal_transactions WHERE id = ?")
                .param(posted.id())
                .query()
                .singleRow();
        assertThat((BigDecimal) header.get("total_debit")).isEqualByComparingTo("100.50");
        assertThat((BigDecimal) header.get("total_credit")).isEqualByComparingTo("100.50");

        assertThat(journal.entries()).extracting(LedgerEntryView::lineNo, LedgerEntryView::accountId,
                        LedgerEntryView::direction, LedgerEntryView::amount)
                .containsExactly(
                        tuple(1, bank.id(), Direction.DEBIT, amount),
                        tuple(2, wallet.id(), Direction.CREDIT, amount));
        LedgerEntryView bankEntry = journal.entries().get(0);
        LedgerEntryView walletEntry = journal.entries().get(1);
        assertThat(bankEntry.accountSeq()).isEqualTo(bankBefore.lastEntrySeq() + 1);
        assertThat(bankEntry.balanceAfter()).isEqualTo(bankBefore.ledgerBalance().plus(amount));
        assertThat(walletEntry.accountSeq()).isEqualTo(1);
        assertThat(walletEntry.balanceAfter()).isEqualTo(amount);

        assertThat(queries.balance(wallet.id()).orElseThrow().ledgerBalance()).isEqualTo(amount);
        assertThat(queries.balance(bank.id()).orElseThrow().ledgerBalance())
                .isEqualTo(bankBefore.ledgerBalance().plus(amount));

        List<Map<String, Object>> audit = privilegedJdbc()
                .sql("""
                        SELECT actor_type, actor_id, action, entity_type, request_id, correlation_id,
                               payload->>'$.total' AS total, payload->>'$.currency' AS currency
                          FROM audit_events WHERE entity_id = ?
                        """)
                .param(posted.publicId())
                .query()
                .listOfRows();
        assertThat(audit).hasSize(1);
        assertThat(audit.getFirst()).containsEntry("actor_type", "SYSTEM")
                .containsEntry("actor_id", ACTOR.id())
                .containsEntry("action", PostingEngine.JOURNAL_POSTED)
                .containsEntry("entity_type", "JOURNAL")
                .containsEntry("request_id", "req-posting-1")
                .containsEntry("correlation_id", "corr-posting-1")
                .containsEntry("total", "100.50")
                .containsEntry("currency", "INR");
    }

    @Test
    void sequencesAndRunningBalancesArePerAccountAndGapless() {
        Account alice = data.wallet("INR");
        Account bob = data.wallet("INR");

        data.deposit(alice, "100.00");
        data.transfer(alice, bob, "30.00");
        data.transfer(alice, bob, "20.25");

        assertThat(queries.entries(alice.id(), 0, 10))
                .extracting(e -> e.accountSeq() + ":" + e.direction() + ":" + e.balanceAfter().toPlainString())
                .containsExactly("1:CREDIT:100.00", "2:DEBIT:70.00", "3:DEBIT:49.75");
        assertThat(queries.entries(bob.id(), 0, 10))
                .extracting(e -> e.accountSeq() + ":" + e.direction() + ":" + e.balanceAfter().toPlainString())
                .containsExactly("1:CREDIT:30.00", "2:CREDIT:50.25");

        AccountBalance aliceBalance = queries.balance(alice.id()).orElseThrow();
        assertThat(aliceBalance.ledgerBalance()).isEqualTo(money(alice, "49.75"));
        assertThat(aliceBalance.lastEntrySeq()).isEqualTo(3);
        assertThat(queries.balance(bob.id()).orElseThrow().lastEntrySeq()).isEqualTo(2);
    }

    @Test
    void amountsKeepTheirCurrencyScale() {
        Account dinar = data.wallet("KWD");
        Account yen = data.wallet("JPY");

        data.deposit(dinar, "1.234");
        data.deposit(yen, "500");

        assertThat(queries.balance(dinar.id()).orElseThrow().ledgerBalance().toPlainString()).isEqualTo("1.234");
        assertThat(queries.balance(yen.id()).orElseThrow().ledgerBalance().toPlainString()).isEqualTo("500");
        BigDecimal stored = privilegedJdbc()
                .sql("SELECT amount FROM ledger_entries WHERE account_id = ?")
                .param(dinar.id())
                .query(BigDecimal.class)
                .single();
        assertThat(stored).isEqualByComparingTo("1.234");
    }

    @Test
    void limitedOverdraftAllowsANegativeBalanceDownToTheLimit() {
        Account credit = data.open(new OpenAccountCommand("Credit line", AccountType.LIABILITY, "INR",
                OverdraftPolicy.Kind.LIMITED, "50.00"));
        Account payee = data.wallet("INR");

        data.transfer(credit, payee, "50.00");

        AccountBalance balance = queries.balance(credit.id()).orElseThrow();
        assertThat(balance.ledgerBalance()).isEqualTo(money(credit, "50.00").negate());
        assertThat(balance.available()).contains(Money.zero(credit.currency()));
        assertRejectedWithoutTrace(Code.INSUFFICIENT_FUNDS, () -> data.transfer(credit, payee, "0.01"));
    }

    @Test
    void rejectedJournalsLeaveNoTrace() {
        Account empty = data.wallet("INR");
        Account other = data.wallet("INR");
        Account dollars = data.wallet("USD");
        data.deposit(other, "10.00");
        Money ten = money(other, "10.00");

        assertRejectedWithoutTrace(Code.INSUFFICIENT_FUNDS, () -> data.transfer(empty, other, "0.01"));
        assertRejectedWithoutTrace(Code.CURRENCY_MISMATCH, () -> engine.post(
                JournalDraft.builder(JournalType.TRANSFER, other.currency(), data.today())
                        .debit(other.id(), ten).credit(dollars.id(), ten).build(), CONTEXT));
        assertRejectedWithoutTrace(Code.ACCOUNT_NOT_FOUND, () -> engine.post(
                JournalDraft.builder(JournalType.TRANSFER, other.currency(), data.today())
                        .debit(other.id(), ten).credit(Long.MAX_VALUE, ten).build(), CONTEXT));
        assertRejectedWithoutTrace(Code.EFFECTIVE_DATE_IN_FUTURE, () -> engine.post(
                JournalDraft.builder(JournalType.TRANSFER, other.currency(), data.today().plusDays(1))
                        .debit(other.id(), ten).credit(empty.id(), ten).build(), CONTEXT));

        PostedJournal posted = data.transfer(other, empty, "1.00");
        JournalDraft original = JournalDraft.builder(JournalType.TRANSFER, other.currency(), data.today())
                .debit(other.id(), money(other, "1.00")).credit(empty.id(), money(other, "1.00")).build();
        assertRejectedWithoutTrace(Code.REVERSAL_NOT_SUPPORTED,
                () -> engine.post(original.mirror(posted.id(), "customer request", data.today()), CONTEXT));

        privilegedJdbc().sql("UPDATE accounts SET status = 'FROZEN' WHERE id = ?").param(empty.id()).update();
        assertRejectedWithoutTrace(Code.ACCOUNT_NOT_ACTIVE, () -> data.transfer(other, empty, "1.00"));

        assertThat(queries.balance(other.id()).orElseThrow().ledgerBalance()).isEqualTo(money(other, "9.00"));
        assertThat(queries.balance(empty.id()).orElseThrow().ledgerBalance()).isEqualTo(money(other, "1.00"));
    }

    private void assertRejectedWithoutTrace(Code code, Supplier<PostedJournal> posting) {
        long journals = count("SELECT COUNT(*) FROM journal_transactions");
        long entries = count("SELECT COUNT(*) FROM ledger_entries");
        long auditEvents = count("SELECT COUNT(*) FROM audit_events");
        String balances = balancesFingerprint();

        assertThatThrownBy(posting::get)
                .isInstanceOfSatisfying(JournalRejectedException.class, e -> assertThat(e.code()).isEqualTo(code));

        assertThat(count("SELECT COUNT(*) FROM journal_transactions")).isEqualTo(journals);
        assertThat(count("SELECT COUNT(*) FROM ledger_entries")).isEqualTo(entries);
        assertThat(count("SELECT COUNT(*) FROM audit_events")).isEqualTo(auditEvents);
        assertThat(balancesFingerprint()).isEqualTo(balances);
    }

    private static String balancesFingerprint() {
        return privilegedJdbc()
                .sql("SELECT COALESCE(SUM(CRC32(CONCAT(account_id, ':', balance, ':', last_entry_seq))), 0) "
                        + "FROM account_balances")
                .query(String.class)
                .single();
    }
}
