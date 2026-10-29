package dev.ihars.ledger.idempotency;

/** The successful result of a guarded operation: what is stored and replayed. */
public record Outcome(int status, Object body, String resourceId) {

    public Outcome {
        if (status < 200 || status > 299) {
            throw new IllegalArgumentException("Only successful outcomes are stored: " + status);
        }
    }

    public static Outcome created(Object body, String resourceId) {
        return new Outcome(201, body, resourceId);
    }

    public static Outcome ok(Object body, String resourceId) {
        return new Outcome(200, body, resourceId);
    }
}
