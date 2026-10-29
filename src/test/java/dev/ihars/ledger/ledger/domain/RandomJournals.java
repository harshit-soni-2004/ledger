package dev.ihars.ledger.ledger.domain;

import dev.ihars.ledger.shared.money.CurrencyUnit;
import dev.ihars.ledger.shared.money.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import java.util.random.RandomGenerator;

/** Generates valid, balanced journal drafts from a seeded generator. */
public final class RandomJournals {

    private RandomJournals() {}

    /**
     * A balanced journal over 2..{@code maxAccounts} distinct accounts drawn from
     * {@code accountIds}, split into a debit side and a disjoint credit side.
     */
    public static JournalDraft balanced(RandomGenerator random, CurrencyUnit currency, List<Long> accountIds,
            int maxAccounts, long maxTotalMinorUnits, LocalDate effectiveDate) {
        List<Long> shuffled = new ArrayList<>(accountIds);
        Collections.shuffle(shuffled, asRandom(random));
        int accounts = 2 + random.nextInt(Math.min(maxAccounts, shuffled.size()) - 1);
        int debitCount = 1 + random.nextInt(accounts - 1);
        List<Long> debitAccounts = shuffled.subList(0, debitCount);
        List<Long> creditAccounts = shuffled.subList(debitCount, accounts);

        long totalMinor = Math.max(accounts, 1 + random.nextLong(maxTotalMinorUnits));
        List<Long> debitParts = split(random, totalMinor, debitAccounts.size());
        List<Long> creditParts = split(random, totalMinor, creditAccounts.size());

        JournalType type = JournalType.values()[random.nextInt(JournalType.values().length)];
        // Reversals are built with mirror; openings need fresh accounts (PostingEngine.postOpening).
        if (type == JournalType.REVERSAL || type == JournalType.OPENING) {
            type = JournalType.TRANSFER;
        }
        JournalDraft.Builder builder = JournalDraft.builder(type, currency, effectiveDate);
        if (type.requiresReason()) {
            builder.reason("generated");
        }
        for (int i = 0; i < debitAccounts.size(); i++) {
            builder.debit(debitAccounts.get(i), minor(debitParts.get(i), currency));
        }
        for (int i = 0; i < creditAccounts.size(); i++) {
            builder.credit(creditAccounts.get(i), minor(creditParts.get(i), currency));
        }
        return builder.build();
    }

    /** Splits {@code total} minor units into {@code parts} strictly positive parts. */
    static List<Long> split(RandomGenerator random, long total, int parts) {
        TreeSet<Long> cuts = new TreeSet<>();
        while (cuts.size() < parts - 1) {
            cuts.add(1 + random.nextLong(total - 1));
        }
        List<Long> result = new ArrayList<>(parts);
        long previous = 0;
        for (long cut : cuts) {
            result.add(cut - previous);
            previous = cut;
        }
        result.add(total - previous);
        return result;
    }

    static Money minor(long minorUnits, CurrencyUnit currency) {
        return Money.of(BigDecimal.valueOf(minorUnits, currency.minorUnits()), currency);
    }

    private static java.util.Random asRandom(RandomGenerator random) {
        return new java.util.Random(random.nextLong());
    }
}
