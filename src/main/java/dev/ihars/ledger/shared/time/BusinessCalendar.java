package dev.ihars.ledger.shared.time;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Business dates (effective dates, as-of dates) are calendar dates in one configured zone,
 * {@code Asia/Kolkata} by default. Instants stay in UTC. The zone is fixed per deployment:
 * changing it reinterprets every stored date.
 */
@Component
public class BusinessCalendar {

    private final Clock clock;
    private final ZoneId zone;

    public BusinessCalendar(Clock clock, @Value("${ledger.business-zone:Asia/Kolkata}") String zone) {
        this.clock = clock;
        this.zone = ZoneId.of(zone);
    }

    public ZoneId zone() {
        return zone;
    }

    public LocalDate today() {
        return dateOf(clock.instant());
    }

    public LocalDate dateOf(Instant instant) {
        return LocalDate.ofInstant(instant, zone);
    }
}
