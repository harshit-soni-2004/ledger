package dev.ihars.ledger.ledger.posting;

import static dev.ihars.ledger.support.LedgerTestData.CONTEXT;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ihars.ledger.accounts.Account;
import dev.ihars.ledger.accounts.OpenAccountCommand;
import dev.ihars.ledger.ledger.domain.AccountType;
import dev.ihars.ledger.ledger.domain.InMemoryLedger;
import dev.ihars.ledger.ledger.domain.JournalDraft;
import dev.ihars.ledger.ledger.domain.JournalLine;
import dev.ihars.ledger.ledger.domain.JournalRejectedException;
import dev.ihars.ledger.ledger.domain.OverdraftPolicy;
import dev.ihars.ledger.ledger.domain.RandomJournals;
import dev.ihars.ledger.ledger.query.AccountBalance;
import dev.ihars.ledger.ledger.query.LedgerEntryView;
import dev.ihars.ledger.ledger.query.LedgerQueries;
import dev.ihars.ledger.shared.money.CurrencyUnit;
import dev.ihars.ledger.support.LedgerIntegrationTest;
import dev.ihars.ledger.support.SeededRandomExtension;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Posts seeded random journals to MySQL and to the in-memory reference model and checks that
 * both accept and reject the same journals and end with identical balances, sequences and
 * running balances. Reproduce a failure with the seed printed in its message.
 */
@ExtendWith(SeededRandomExtension.class)
class RandomizedLedgerSimulationIT extends LedgerIntegrationTest {

    private static final int STEPS = 200;

    private record Pair(Account db, InMemoryLedger.Account model) {}

    @Test
    void databaseAndReferenceModelAgreeOnEveryPosting(RandomGenerator random) {
        InMemoryLedger model = new InMemoryLedger();
        Map<String, List<Pair>> byCurrency = new HashMap<>();
        Map<Long, Account> dbByModelId = new HashMap<>();
        for (String currency : List.of("INR", "JPY", "KWD")) {
            List<Pair> pairs = List.of(
                    open(model, new OpenAccountCommand("Sim cash", AccountType.ASSET, currency,
                            OverdraftPolicy.Kind.UNLIMITED, null)),
                    open(model, new OpenAccountCommand("Sim revenue", AccountType.REVENUE, currency,
                            OverdraftPolicy.Kind.UNLIMITED, null)),
                    open(model, OpenAccountCommand.wallet("Sim wallet 1", currency)),
                    open(model, OpenAccountCommand.wallet("Sim wallet 2", currency)),
                    open(model, OpenAccountCommand.wallet("Sim wallet 3", currency)),
                    open(model, new OpenAccountCommand("Sim credit", AccountType.LIABILITY, currency,
                            OverdraftPolicy.Kind.LIMITED, "100")),
                    open(model, new OpenAccountCommand("Sim expense", AccountType.EXPENSE, currency,
                            OverdraftPolicy.Kind.FORBIDDEN, null)));
            byCurrency.put(currency, pairs);
            pairs.forEach(p -> dbByModelId.put(p.model().id(), p.db()));
        }
        LocalDate today = data.today();
        int accepted = 0;

        for (int step = 0; step < STEPS; step++) {
            List<Pair> pairs = byCurrency.get(List.of("INR", "JPY", "KWD").get(random.nextInt(3)));
            CurrencyUnit currency = pairs.getFirst().db().currency();
            JournalDraft modelDraft = RandomJournals.balanced(random, currency,
                    pairs.stream().map(p -> p.model().id()).toList(), 4,
                    200 * BigInteger.TEN.pow(currency.minorUnits()).longValueExact(),
                    today.minusDays(random.nextInt(30)));

            Optional<JournalRejectedException> expected = model.post(modelDraft, today);
            Optional<JournalRejectedException> actual = post(translate(modelDraft, dbByModelId));

            assertThat(actual.map(JournalRejectedException::code)).as("step %d", step)
                    .isEqualTo(expected.map(JournalRejectedException::code));
            if (expected.isEmpty()) {
                accepted++;
            }
            for (Pair pair : pairs) {
                AccountBalance balance = queries.balance(pair.db().id()).orElseThrow();
                assertThat(balance.ledgerBalance()).as("step %d balance", step)
                        .isEqualTo(model.balance(pair.model().id()));
                assertThat(balance.lastEntrySeq()).as("step %d seq", step)
                        .isEqualTo(model.lastSeq(pair.model().id()));
            }
        }

        assertThat(accepted).as("accepted postings").isGreaterThan(STEPS / 4);
        for (List<Pair> pairs : byCurrency.values()) {
            for (Pair pair : pairs) {
                List<String> modelEntries = model.entries().stream()
                        .filter(e -> e.accountId() == pair.model().id())
                        .map(e -> e.accountSeq() + ":" + e.direction() + ":" + e.amount() + ":" + e.balanceAfter())
                        .toList();
                List<String> dbEntries = queries.entries(pair.db().id(), 0, LedgerQueries.MAX_PAGE_SIZE).stream()
                        .map(RandomizedLedgerSimulationIT::describe)
                        .toList();
                assertThat(dbEntries).isEqualTo(modelEntries);
            }
        }
        assertThat(integrity.check().violations()).isEmpty();
    }

    private Pair open(InMemoryLedger model, OpenAccountCommand command) {
        Account db = data.open(command);
        return new Pair(db, model.open(db.currency(), db.type(), db.overdraftPolicy()));
    }

    private static JournalDraft translate(JournalDraft draft, Map<Long, Account> dbByModelId) {
        JournalDraft.Builder builder = JournalDraft.builder(draft.type(), draft.currency(), draft.effectiveDate());
        for (JournalLine line : draft.lines()) {
            builder.line(new JournalLine(dbByModelId.get(line.accountId()).id(), line.direction(), line.amount()));
        }
        if (draft.reason() != null) {
            builder.reason(draft.reason());
        }
        return builder.build();
    }

    private Optional<JournalRejectedException> post(JournalDraft draft) {
        try {
            engine.post(draft, CONTEXT);
            return Optional.empty();
        } catch (JournalRejectedException e) {
            return Optional.of(e);
        }
    }

    private static String describe(LedgerEntryView e) {
        return e.accountSeq() + ":" + e.direction() + ":" + e.amount() + ":" + e.balanceAfter();
    }
}
