package dev.ihars.ledger.ledger.domain;

import dev.ihars.ledger.ledger.domain.JournalRejectedException.Code;
import dev.ihars.ledger.shared.money.CurrencyUnit;
import dev.ihars.ledger.shared.money.InvalidAmountException;
import dev.ihars.ledger.shared.money.Money;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A journal that satisfies every rule that can be checked without account state
 * (docs/02-accounting-model.md, rules 1-5). An instance cannot exist unbalanced; account
 * rules (currency, status, floors) are checked by {@link PostingCalculator} against locked
 * balances.
 */
public final class JournalDraft {

    /** {@code ledger_entries.line_no} is a SMALLINT; this keeps journals reviewable. */
    public static final int MAX_LINES = 100;
    public static final int MAX_DESCRIPTION_LENGTH = 255;
    public static final int MAX_REASON_LENGTH = 500;

    private static final Pattern EXTERNAL_REFERENCE = Pattern.compile("[A-Za-z0-9._:/-]{1,64}");

    private final JournalType type;
    private final CurrencyUnit currency;
    private final List<JournalLine> lines;
    private final LocalDate effectiveDate;
    private final String description;
    private final String reason;
    private final String externalReference;
    private final Long reversesJournalId;
    private final Money total;

    private JournalDraft(JournalType type, CurrencyUnit currency, List<JournalLine> lines, LocalDate effectiveDate,
            String description, String reason, String externalReference, Long reversesJournalId) {
        this.type = Objects.requireNonNull(type, "type");
        this.currency = Objects.requireNonNull(currency, "currency");
        this.effectiveDate = Objects.requireNonNull(effectiveDate, "effectiveDate");
        this.lines = List.copyOf(lines);
        this.description = description;
        this.reason = reason;
        this.externalReference = externalReference;
        this.reversesJournalId = reversesJournalId;
        this.total = validate();
    }

    public static Builder builder(JournalType type, CurrencyUnit currency, LocalDate effectiveDate) {
        if (type == JournalType.REVERSAL) {
            throw new IllegalArgumentException("Reversal journals are created with JournalDraft.mirror");
        }
        return new Builder(type, currency, effectiveDate);
    }

    /**
     * The reversal of this journal: every line with its direction flipped, same accounts and
     * amounts. Applying a journal and its mirror leaves every balance unchanged.
     */
    public JournalDraft mirror(long originalJournalId, String reason, LocalDate effectiveDate) {
        if (type == JournalType.REVERSAL) {
            throw new JournalRejectedException(Code.CANNOT_REVERSE_REVERSAL, "A reversal cannot be reversed");
        }
        List<JournalLine> flipped = lines.stream().map(JournalLine::flipped).toList();
        return new JournalDraft(JournalType.REVERSAL, currency, flipped, effectiveDate,
                null, reason, null, originalJournalId);
    }

    private Money validate() {
        if (lines.size() < 2) {
            throw reject(Code.TOO_FEW_LINES, "A journal needs at least two lines");
        }
        if (lines.size() > MAX_LINES) {
            throw reject(Code.TOO_MANY_LINES, "A journal may have at most " + MAX_LINES + " lines");
        }
        Money debits = Money.zero(currency);
        Money credits = Money.zero(currency);
        Set<Long> debited = new HashSet<>();
        Set<Long> credited = new HashSet<>();
        try {
            for (JournalLine line : lines) {
                if (!line.amount().currency().equals(currency)) {
                    throw reject(Code.CURRENCY_MISMATCH,
                            "Line currency %s differs from journal currency %s".formatted(line.amount().currency(), currency));
                }
                if (line.direction() == Direction.DEBIT) {
                    debits = debits.plus(line.amount());
                    debited.add(line.accountId());
                } else {
                    credits = credits.plus(line.amount());
                    credited.add(line.accountId());
                }
            }
        } catch (InvalidAmountException e) {
            throw reject(Code.AMOUNT_OUT_OF_RANGE, "Journal total exceeds the supported range");
        }
        if (debits.compareTo(credits) != 0) {
            throw reject(Code.UNBALANCED, "Debits %s do not equal credits %s".formatted(debits, credits));
        }
        debited.retainAll(credited);
        if (!debited.isEmpty()) {
            throw reject(Code.SAME_ACCOUNT_BOTH_SIDES, "An account is both debited and credited: " + debited);
        }
        if (type.requiresReason() && (reason == null || reason.isBlank())) {
            throw reject(Code.REASON_REQUIRED, type + " journals require a reason");
        }
        requireLength("description", description, MAX_DESCRIPTION_LENGTH);
        requireLength("reason", reason, MAX_REASON_LENGTH);
        if (externalReference != null && !EXTERNAL_REFERENCE.matcher(externalReference).matches()) {
            throw reject(Code.INVALID_FIELD, "externalReference must be 1-64 characters of [A-Za-z0-9._:/-]");
        }
        return debits;
    }

    private static void requireLength(String field, String value, int max) {
        if (value != null && value.length() > max) {
            throw reject(Code.INVALID_FIELD, "%s exceeds %d characters".formatted(field, max));
        }
    }

    private static JournalRejectedException reject(Code code, String message) {
        return new JournalRejectedException(code, message);
    }

    public JournalType type() {
        return type;
    }

    public CurrencyUnit currency() {
        return currency;
    }

    public List<JournalLine> lines() {
        return lines;
    }

    public LocalDate effectiveDate() {
        return effectiveDate;
    }

    public String description() {
        return description;
    }

    public String reason() {
        return reason;
    }

    public String externalReference() {
        return externalReference;
    }

    public Long reversesJournalId() {
        return reversesJournalId;
    }

    /** Sum of the debit lines, which equals the sum of the credit lines. */
    public Money total() {
        return total;
    }

    public static final class Builder {

        private final JournalType type;
        private final CurrencyUnit currency;
        private final LocalDate effectiveDate;
        private final List<JournalLine> lines = new ArrayList<>();
        private String description;
        private String reason;
        private String externalReference;

        private Builder(JournalType type, CurrencyUnit currency, LocalDate effectiveDate) {
            this.type = type;
            this.currency = currency;
            this.effectiveDate = effectiveDate;
        }

        public Builder debit(long accountId, Money amount) {
            lines.add(new JournalLine(accountId, Direction.DEBIT, amount));
            return this;
        }

        public Builder credit(long accountId, Money amount) {
            lines.add(new JournalLine(accountId, Direction.CREDIT, amount));
            return this;
        }

        public Builder line(JournalLine line) {
            lines.add(Objects.requireNonNull(line, "line"));
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public Builder externalReference(String externalReference) {
            this.externalReference = externalReference;
            return this;
        }

        public JournalDraft build() {
            return new JournalDraft(type, currency, lines, effectiveDate, description, reason, externalReference, null);
        }
    }
}
