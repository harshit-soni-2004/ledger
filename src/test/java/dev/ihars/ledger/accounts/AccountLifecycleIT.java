package dev.ihars.ledger.accounts;

import static dev.ihars.ledger.support.LedgerTestData.ACTOR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ihars.ledger.accounts.AccountRejectedException.Code;
import dev.ihars.ledger.ledger.domain.AccountStatus;
import dev.ihars.ledger.ledger.domain.JournalRejectedException;
import dev.ihars.ledger.support.LedgerIntegrationTest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class AccountLifecycleIT extends LedgerIntegrationTest {

    @Autowired
    private AccountLifecycleService lifecycle;

    @Autowired
    private PlatformTransactionManager transactions;

    private ExecutorService pool;

    @BeforeEach
    void startPool() {
        pool = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void stopPool() {
        pool.shutdownNow();
    }

    @Test
    void freezeBlocksPostingsAndUnfreezeRestoresThem() {
        Account wallet = data.wallet("INR");
        data.deposit(wallet, "10.00");

        assertThat(lifecycle.freeze(wallet.publicId(), "KYC review", ACTOR, "req-1").status())
                .isEqualTo(AccountStatus.FROZEN);
        assertThatThrownBy(() -> data.deposit(wallet, "1.00"))
                .isInstanceOfSatisfying(JournalRejectedException.class,
                        e -> assertThat(e.code()).isEqualTo(JournalRejectedException.Code.ACCOUNT_NOT_ACTIVE));

        assertThat(lifecycle.unfreeze(wallet.publicId(), "Review passed", ACTOR, "req-2").status())
                .isEqualTo(AccountStatus.ACTIVE);
        data.deposit(wallet, "1.00");

        assertThat(accounts.findById(wallet.id()).orElseThrow().status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_id = ? AND action IN (?, ?)",
                wallet.publicId(), AccountLifecycleService.ACCOUNT_FROZEN, AccountLifecycleService.ACCOUNT_UNFROZEN))
                .isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_id = ? AND action = ? "
                + "AND JSON_UNQUOTE(JSON_EXTRACT(payload, '$.reason')) = 'KYC review'",
                wallet.publicId(), AccountLifecycleService.ACCOUNT_FROZEN)).isEqualTo(1);
    }

    @Test
    void onlyZeroBalanceAccountsCloseAndClosedIsTerminal() {
        Account funded = data.wallet("INR");
        data.deposit(funded, "5.00");
        assertRejected(Code.BALANCE_NOT_ZERO, () -> lifecycle.close(funded.publicId(), "Customer left", ACTOR, null));
        assertThat(accounts.findById(funded.id()).orElseThrow().status()).isEqualTo(AccountStatus.ACTIVE);

        Account empty = data.wallet("INR");
        lifecycle.freeze(empty.publicId(), "Dormant", ACTOR, null);
        assertThat(lifecycle.close(empty.publicId(), "Customer left", ACTOR, null).status())
                .isEqualTo(AccountStatus.CLOSED);

        assertRejected(Code.STATUS_CONFLICT, () -> lifecycle.unfreeze(empty.publicId(), "Oops", ACTOR, null));
        assertRejected(Code.STATUS_CONFLICT, () -> lifecycle.freeze(empty.publicId(), "Oops", ACTOR, null));
        assertRejected(Code.STATUS_CONFLICT, () -> lifecycle.close(empty.publicId(), "Again", ACTOR, null));
        assertThatThrownBy(() -> data.deposit(empty, "1.00")).isInstanceOf(JournalRejectedException.class);
    }

    @Test
    void invalidTransitionsAreRejectedWithoutSideEffects() {
        Account wallet = data.wallet("INR");
        assertRejected(Code.STATUS_CONFLICT, () -> lifecycle.unfreeze(wallet.publicId(), "Not frozen", ACTOR, null));
        assertRejected(Code.REASON_REQUIRED, () -> lifecycle.freeze(wallet.publicId(), "  ", ACTOR, null));
        assertRejected(Code.REASON_REQUIRED, () -> lifecycle.freeze(wallet.publicId(), null, ACTOR, null));
        assertRejected(Code.REASON_REQUIRED, () -> lifecycle.freeze(wallet.publicId(), "x".repeat(256), ACTOR, null));
        assertRejected(Code.ACCOUNT_NOT_FOUND, () -> lifecycle.freeze("acct_00000000000000000000000000", "x", ACTOR, null));
        Account suspense = data.system("LIABILITY.SUSPENSE", "INR");
        assertRejected(Code.SYSTEM_ACCOUNT, () -> lifecycle.freeze(suspense.publicId(), "No", ACTOR, null));

        assertThat(accounts.findById(wallet.id()).orElseThrow().status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_id = ? AND action LIKE 'ACCOUNT\\_%' "
                + "AND action <> 'ACCOUNT_OPENED'", wallet.publicId())).isZero();
    }

    @Test
    void aPostingWaitingOnTheLockSeesAFreezeThatCommittedMeanwhile() throws Exception {
        Account wallet = data.wallet("INR");
        CountDownLatch frozen = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);

        Future<?> freezer = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            lifecycle.freeze(wallet.publicId(), "Fraud alert", ACTOR, null);
            frozen.countDown();
            await(commit);
        }));
        assertThat(frozen.await(10, TimeUnit.SECONDS)).isTrue();
        Future<?> deposit = pool.submit(() -> data.deposit(wallet, "1.00"));
        TimeUnit.MILLISECONDS.sleep(500);
        assertThat(deposit.isDone()).as("the posting waits for the balance lock").isFalse();
        commit.countDown();
        freezer.get(10, TimeUnit.SECONDS);

        assertThatThrownBy(() -> deposit.get(10, TimeUnit.SECONDS))
                .cause()
                .isInstanceOfSatisfying(JournalRejectedException.class,
                        e -> assertThat(e.code()).isEqualTo(JournalRejectedException.Code.ACCOUNT_NOT_ACTIVE));
        assertThat(queries.balance(wallet.id()).orElseThrow().lastEntrySeq()).isZero();
    }

    @Test
    void aCloseWaitingOnTheLockSeesTheBalanceOfAPostingThatCommittedMeanwhile() throws Exception {
        Account wallet = data.wallet("INR");
        CountDownLatch posted = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);

        Future<?> depositor = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            data.deposit(wallet, "3.00");
            posted.countDown();
            await(commit);
        }));
        assertThat(posted.await(10, TimeUnit.SECONDS)).isTrue();
        Future<Account> close = pool.submit(() -> lifecycle.close(wallet.publicId(), "Customer left", ACTOR, null));
        TimeUnit.MILLISECONDS.sleep(500);
        assertThat(close.isDone()).as("the close waits for the balance lock").isFalse();
        commit.countDown();
        depositor.get(10, TimeUnit.SECONDS);

        assertThatThrownBy(() -> close.get(10, TimeUnit.SECONDS))
                .cause()
                .isInstanceOfSatisfying(AccountRejectedException.class,
                        e -> assertThat(e.code()).isEqualTo(Code.BALANCE_NOT_ZERO));
        assertThat(accounts.findById(wallet.id()).orElseThrow().status()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void aFailureAfterTheStatusChangeRollsItBack() {
        Account wallet = data.wallet("INR");

        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            lifecycle.freeze(wallet.publicId(), "Will roll back", ACTOR, null);
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(accounts.findById(wallet.id()).orElseThrow().status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_id = ? AND action = ?",
                wallet.publicId(), AccountLifecycleService.ACCOUNT_FROZEN)).isZero();
    }

    private static void assertRejected(Code code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(AccountRejectedException.class,
                e -> assertThat(e.code()).isEqualTo(code));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
