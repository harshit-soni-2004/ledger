package dev.ihars.ledger.ledger.posting;

import static dev.ihars.ledger.support.LedgerTestData.CONTEXT;
import static dev.ihars.ledger.support.LedgerTestData.money;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ihars.ledger.accounts.Account;
import dev.ihars.ledger.ledger.domain.JournalDraft;
import dev.ihars.ledger.ledger.domain.JournalRejectedException;
import dev.ihars.ledger.ledger.domain.JournalType;
import dev.ihars.ledger.support.LedgerIntegrationTest;
import dev.ihars.ledger.support.MutableClock;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/** The future-date check uses the business date in Asia/Kolkata, not the UTC date. */
@Import(MutableClock.Configuration.class)
class BusinessDatePostingIT extends LedgerIntegrationTest {

    private static final LocalDate OCT_30 = LocalDate.of(2026, 10, 30);

    @Autowired
    MutableClock mutableClock;

    @AfterEach
    void resetClock() {
        mutableClock.reset();
    }

    @Test
    void justAfterMidnightInKolkataTodaysDateIsAcceptedThoughUtcIsStillYesterday() {
        Account wallet = data.wallet("INR");
        mutableClock.set(Instant.parse("2026-10-29T20:00:00Z"));

        PostedJournal posted = deposit(wallet, OCT_30);

        assertThat(posted.effectiveDate()).isEqualTo(OCT_30);
        assertThat(posted.postedAt()).isEqualTo(Instant.parse("2026-10-29T20:00:00Z"));
        assertThatThrownBy(() -> deposit(wallet, OCT_30.plusDays(1)))
                .isInstanceOfSatisfying(JournalRejectedException.class,
                        e -> assertThat(e.code()).isEqualTo(JournalRejectedException.Code.EFFECTIVE_DATE_IN_FUTURE));
    }

    @Test
    void oneMicrosecondBeforeMidnightInKolkataTomorrowIsStillInTheFuture() {
        Account wallet = data.wallet("INR");
        mutableClock.set(Instant.parse("2026-10-29T18:29:59.999999Z"));

        assertThatThrownBy(() -> deposit(wallet, OCT_30))
                .isInstanceOfSatisfying(JournalRejectedException.class,
                        e -> assertThat(e.code()).isEqualTo(JournalRejectedException.Code.EFFECTIVE_DATE_IN_FUTURE));

        mutableClock.set(Instant.parse("2026-10-29T18:30:00Z"));
        assertThat(deposit(wallet, OCT_30).effectiveDate()).isEqualTo(OCT_30);
    }

    private PostedJournal deposit(Account wallet, LocalDate effectiveDate) {
        Account bank = data.system("ASSET.BANK.OPERATING", "INR");
        return engine.post(JournalDraft.builder(JournalType.DEPOSIT, wallet.currency(), effectiveDate)
                .debit(bank.id(), money(wallet, "1.00"))
                .credit(wallet.id(), money(wallet, "1.00"))
                .build(), CONTEXT);
    }
}
