package dev.ihars.ledger.shared.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class UlidTest {

    @Test
    void encodesTheSmallestAndLargestValues() {
        assertThat(Ulid.encode(0, new byte[10])).isEqualTo("00000000000000000000000000");
        byte[] ones = new byte[10];
        Arrays.fill(ones, (byte) 0xFF);
        assertThat(Ulid.encode((1L << 48) - 1, ones)).isEqualTo("7ZZZZZZZZZZZZZZZZZZZZZZZZZ");
    }

    @Test
    void encodesTimestampAndRandomnessMostSignificantFirst() {
        byte[] randomness = new byte[10];
        randomness[9] = 1;
        assertThat(Ulid.encode(1, randomness)).isEqualTo("00000000010000000000000001");
    }

    @Test
    void rejectsOutOfRangeInput() {
        assertThatThrownBy(() -> Ulid.encode(-1, new byte[10])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Ulid.encode(1L << 48, new byte[10])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Ulid.encode(0, new byte[9])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void publicIdsArePrefixedTimeOrderedAndMatchTheSchemaCheck() {
        Instant now = Instant.parse("2026-10-29T08:00:00Z");
        PublicIdGenerator early = new PublicIdGenerator(Clock.fixed(now, ZoneOffset.UTC));
        PublicIdGenerator late = new PublicIdGenerator(Clock.fixed(now.plusMillis(1), ZoneOffset.UTC));

        String first = early.next(PublicIdGenerator.Prefix.ACCOUNT);
        String second = late.next(PublicIdGenerator.Prefix.ACCOUNT);

        assertThat(first).matches("acct_[0-9A-HJKMNP-TV-Z]{26}");
        assertThat(early.next(PublicIdGenerator.Prefix.JOURNAL)).matches("jtx_[0-9A-HJKMNP-TV-Z]{26}");
        assertThat(first).isLessThan(second);
        assertThat(early.next(PublicIdGenerator.Prefix.ACCOUNT)).isNotEqualTo(first);
    }
}
