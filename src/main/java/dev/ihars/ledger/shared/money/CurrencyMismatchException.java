package dev.ihars.ledger.shared.money;

/** Arithmetic or comparison between amounts in different currencies. */
public class CurrencyMismatchException extends RuntimeException {

    public CurrencyMismatchException(CurrencyUnit expected, CurrencyUnit actual) {
        super("Currency mismatch: expected " + expected + " but was " + actual);
    }
}
