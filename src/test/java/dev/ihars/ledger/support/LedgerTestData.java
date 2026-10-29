package dev.ihars.ledger.support;

import dev.ihars.ledger.accounts.Account;
import dev.ihars.ledger.accounts.AccountService;
import dev.ihars.ledger.accounts.OpenAccountCommand;
import dev.ihars.ledger.audit.Actor;
import dev.ihars.ledger.ledger.domain.JournalDraft;
import dev.ihars.ledger.ledger.domain.JournalType;
import dev.ihars.ledger.ledger.posting.PostedJournal;
import dev.ihars.ledger.ledger.posting.PostingContext;
import dev.ihars.ledger.ledger.posting.PostingEngine;
import dev.ihars.ledger.shared.money.Money;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Creates ledger data through the real services. Ledger tables are append-only and cannot be
 * cleaned up, so every test opens its own accounts and asserts only on those.
 */
public final class LedgerTestData {

    public static final Actor ACTOR = Actor.system("integration-test");
    public static final PostingContext CONTEXT = new PostingContext(ACTOR, null, null);
    public static final String BANK = "ASSET.BANK.OPERATING";

    private final AccountService accounts;
    private final PostingEngine engine;
    private final Clock clock;

    public LedgerTestData(AccountService accounts, PostingEngine engine, Clock clock) {
        this.accounts = accounts;
        this.engine = engine;
        this.clock = clock;
    }

    public Account wallet(String currency) {
        return open(OpenAccountCommand.wallet("Test wallet", currency));
    }

    public Account open(OpenAccountCommand command) {
        return accounts.open(command, ACTOR, null);
    }

    public Account system(String systemCode, String currency) {
        return accounts.findSystemAccount(systemCode, currency).orElseThrow();
    }

    public LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    /** Bank cash in, wallet liability up. */
    public PostedJournal deposit(Account wallet, String amount) {
        Account bank = system(BANK, wallet.currency().code());
        Money money = money(wallet, amount);
        return engine.post(JournalDraft.builder(JournalType.DEPOSIT, wallet.currency(), today())
                .debit(bank.id(), money)
                .credit(wallet.id(), money)
                .build(), CONTEXT);
    }

    /** Money moves from one wallet to another: debit the payer, credit the payee. */
    public PostedJournal transfer(Account from, Account to, String amount) {
        Money money = money(from, amount);
        return engine.post(JournalDraft.builder(JournalType.TRANSFER, from.currency(), today())
                .debit(from.id(), money)
                .credit(to.id(), money)
                .build(), CONTEXT);
    }

    public static Money money(Account account, String amount) {
        return Money.parsePositive(amount, account.currency());
    }
}
