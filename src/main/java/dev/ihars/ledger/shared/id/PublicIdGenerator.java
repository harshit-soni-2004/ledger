package dev.ihars.ledger.shared.id;

import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.stereotype.Component;

/**
 * Generates prefixed ULIDs such as {@code acct_01M62F9N009N9WZP7P2AM8H53S}: not enumerable,
 * sortable by creation time, readable in logs. Internal numeric ids never leave the service.
 */
@Component
public class PublicIdGenerator {

    public enum Prefix {
        ACCOUNT("acct"),
        JOURNAL("jtx"),
        CLIENT("cli"),
        API_KEY("key"),
        INTEGRITY_CHECK("ichk"),
        TRANSFER("trf");

        private final String value;

        Prefix(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public PublicIdGenerator(Clock clock) {
        this.clock = clock;
    }

    public String next(Prefix prefix) {
        byte[] randomness = new byte[Ulid.RANDOM_BYTES];
        random.nextBytes(randomness);
        return prefix.value() + "_" + Ulid.encode(clock.millis(), randomness);
    }
}
