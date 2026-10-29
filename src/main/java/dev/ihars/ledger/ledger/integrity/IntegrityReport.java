package dev.ihars.ledger.ledger.integrity;

import java.util.List;

/** The result of an integrity check; clean means every invariant holds. */
public record IntegrityReport(List<Violation> violations) {

    public enum Check {
        /** Header totals differ from the entries, or a journal has fewer than two entries. */
        JOURNAL_UNBALANCED,
        /** Debits differ from credits for a currency, over entries or over balances. */
        TRIAL_BALANCE,
        /** An account has no balance row. */
        MISSING_BALANCE_ROW,
        /** A projected balance or last sequence differs from the entries. */
        PROJECTION_DRIFT,
        /** An account's entry sequence is not 1..n. */
        SEQUENCE_GAP,
        /** An entry's balance_after is not the previous one plus its signed amount. */
        BALANCE_CHAIN_BROKEN,
        /** A stored amount has more decimals than its currency allows. */
        SCALE_VIOLATION,
        /** min_balance does not match the account's overdraft policy. */
        FLOOR_MISMATCH,
        /** An entry's effective_date differs from its journal's. */
        ENTRY_EFFECTIVE_DATE_MISMATCH,
        /** An entry's posted_at differs from its journal's. */
        ENTRY_POSTED_AT_MISMATCH
    }

    /** @param subject the offending row, e.g. a journal or account public id or a currency */
    public record Violation(Check check, String subject) {}

    public IntegrityReport {
        violations = List.copyOf(violations);
    }

    public boolean isClean() {
        return violations.isEmpty();
    }
}
