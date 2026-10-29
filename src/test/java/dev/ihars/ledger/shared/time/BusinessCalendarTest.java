package dev.ihars.ledger.shared.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class BusinessCalendarTest {

    @Test
    void theBusinessDayChangesAtMidnightInKolkataNotUtc() {
        assertThat(today("2026-10-29T18:29:59.999999Z")).isEqualTo(LocalDate.of(2026, 10, 29));
        assertThat(today("2026-10-29T18:30:00Z")).isEqualTo(LocalDate.of(2026, 10, 30));
        // 01:30 IST on the 30th while UTC is still on the 29th.
        assertThat(today("2026-10-29T20:00:00Z")).isEqualTo(LocalDate.of(2026, 10, 30));
    }

    @Test
    void theZoneIsConfigurable() {
        BusinessCalendar utc = new BusinessCalendar(Clock.fixed(Instant.parse("2026-10-29T20:00:00Z"), ZoneOffset.UTC),
                "UTC");

        assertThat(utc.today()).isEqualTo(LocalDate.of(2026, 10, 29));
    }

    @Test
    void anInvalidZoneFailsAtStartup() {
        assertThatThrownBy(() -> new BusinessCalendar(Clock.systemUTC(), "Mars/Olympus"))
                .isInstanceOf(DateTimeException.class);
    }

    private static LocalDate today(String instant) {
        return new BusinessCalendar(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC), "Asia/Kolkata").today();
    }
}
