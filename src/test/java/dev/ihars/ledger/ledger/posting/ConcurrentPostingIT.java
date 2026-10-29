package dev.ihars.ledger.ledger.posting;

import static dev.ihars.ledger.support.LedgerTestData.money;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ihars.ledger.accounts.Account;
import dev.ihars.ledger.ledger.domain.JournalRejectedException;
import dev.ihars.ledger.ledger.query.AccountBalance;
import dev.ihars.ledger.ledger.query.LedgerEntryView;
import dev.ihars.ledger.shared.money.Money;
import dev.ihars.ledger.support.LedgerIntegrationTest;
import dev.ihars.ledger.support.SeededRandomExtension;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Postings that touch the same accounts serialize on the balance row locks (ADR-0005): no
 * lost updates, no sequence gaps, no balance below its floor, and no deadlocks thanks to the
 * ascending lock order.
 */
@ExtendWith(SeededRandomExtension.class)
class ConcurrentPostingIT extends LedgerIntegrationTest {

    private static final int THREADS = 8;

    @Test
    void concurrentDepositsIntoOneAccountLoseNothing() throws Exception {
        Account wallet = data.wallet("INR");
        int perThread = 25;

        runConcurrently(thread -> () -> {
            for (int i = 0; i < perThread; i++) {
                data.deposit(wallet, "1.00");
            }
            return null;
        });

        AccountBalance balance = queries.balance(wallet.id()).orElseThrow();
        assertThat(balance.ledgerBalance()).isEqualTo(money(wallet, String.valueOf(THREADS * perThread)));
        assertThat(balance.lastEntrySeq()).isEqualTo(THREADS * perThread);
        assertGaplessChain(wallet);
        assertThat(integrity.check().violations()).isEmpty();
    }

    @Test
    void concurrentTransfersInBothDirectionsConserveMoneyAndRespectFloors(RandomGenerator random) throws Exception {
        List<Account> wallets = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Account wallet = data.wallet("INR");
            data.deposit(wallet, "100.00");
            wallets.add(wallet);
        }
        long[] seeds = random.longs(THREADS).toArray();
        AtomicInteger posted = new AtomicInteger();
        AtomicInteger insufficientFunds = new AtomicInteger();
        int perThread = 30;

        runConcurrently(thread -> () -> {
            SplittableRandom threadRandom = new SplittableRandom(seeds[thread]);
            for (int i = 0; i < perThread; i++) {
                int from = threadRandom.nextInt(wallets.size());
                int to = (from + 1 + threadRandom.nextInt(wallets.size() - 1)) % wallets.size();
                String amount = "%d.%02d".formatted(1 + threadRandom.nextInt(60), threadRandom.nextInt(100));
                try {
                    data.transfer(wallets.get(from), wallets.get(to), amount);
                    posted.incrementAndGet();
                } catch (JournalRejectedException e) {
                    assertThat(e.code()).isEqualTo(JournalRejectedException.Code.INSUFFICIENT_FUNDS);
                    insufficientFunds.incrementAndGet();
                }
            }
            return null;
        });

        assertThat(posted.get() + insufficientFunds.get()).isEqualTo(THREADS * perThread);
        assertThat(posted.get()).isPositive();
        Money total = Money.zero(wallets.getFirst().currency());
        long entries = 0;
        for (Account wallet : wallets) {
            AccountBalance balance = queries.balance(wallet.id()).orElseThrow();
            assertThat(balance.ledgerBalance().isNegative()).as("balance of %s", wallet.publicId()).isFalse();
            total = total.plus(balance.ledgerBalance());
            entries += balance.lastEntrySeq();
            assertGaplessChain(wallet);
        }
        assertThat(total).isEqualTo(money(wallets.getFirst(), "500.00"));
        assertThat(entries).isEqualTo(wallets.size() + 2L * posted.get());
        assertThat(integrity.check().violations()).isEmpty();
    }

    private void assertGaplessChain(Account account) {
        List<LedgerEntryView> entries = queries.entries(account.id(), 0, 500);
        Money running = Money.zero(account.currency());
        for (int i = 0; i < entries.size(); i++) {
            LedgerEntryView entry = entries.get(i);
            assertThat(entry.accountSeq()).isEqualTo(i + 1);
            running = entry.direction() == account.normalBalance() ? running.plus(entry.amount())
                    : running.minus(entry.amount());
            assertThat(entry.balanceAfter()).isEqualTo(running);
        }
    }

    private interface Task {
        Callable<Void> forThread(int thread);
    }

    private static void runConcurrently(Task task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Void>> futures = new ArrayList<>();
            ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
            for (int t = 0; t < THREADS; t++) {
                Callable<Void> body = task.forThread(t);
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        return body.call();
                    } catch (Throwable e) {
                        failures.add(e);
                        throw e;
                    }
                }));
            }
            start.countDown();
            for (Future<Void> future : futures) {
                try {
                    future.get(5, TimeUnit.MINUTES);
                } catch (java.util.concurrent.ExecutionException ignored) {
                    // collected in failures
                }
            }
            assertThat(failures).isEmpty();
        } finally {
            executor.shutdownNow();
        }
    }
}
