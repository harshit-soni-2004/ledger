package dev.ihars.ledger.idempotency;

/**
 * The committed result of a guarded operation: what is stored and replayed. Either a success
 * (2xx) or a business rejection (422) whose record was committed on purpose, so that one key
 * stays one attempt (docs/14 Q3).
 */
public record Outcome(int status, Object body, String resourceId) {

    public Outcome {
        if ((status < 200 || status > 299) && status != 422) {
            throw new IllegalArgumentException("Only successes and committed rejections are stored: " + status);
        }
    }

    public static Outcome created(Object body, String resourceId) {
        return new Outcome(201, body, resourceId);
    }

    public static Outcome ok(Object body, String resourceId) {
        return new Outcome(200, body, resourceId);
    }

    /** A committed business rejection; {@code problem} is a Problem Details body. */
    public static Outcome rejected(Object problem, String resourceId) {
        return new Outcome(422, problem, resourceId);
    }
}
