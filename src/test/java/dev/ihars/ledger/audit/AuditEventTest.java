package dev.ihars.ledger.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AuditEventTest {

    private static final Actor ACTOR = Actor.system("test");

    @Test
    void acceptsMissingAndPrintableAsciiTraceIds() {
        assertThat(new AuditEvent(ACTOR, "X", "T", "e", null, null, null).payload()).isEmpty();
        assertThat(new AuditEvent(ACTOR, "X", "T", "e", "req-1", "a".repeat(64), Map.of("k", "v")).payload())
                .containsEntry("k", "v");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "has space", "ünïcode", "tab\t"})
    void rejectsTraceIdsThatDoNotFitTheAsciiColumns(String id) {
        assertThatThrownBy(() -> new AuditEvent(ACTOR, "X", "T", "e", id, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditEvent(ACTOR, "X", "T", "e", null, id, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOverlongTraceIdsAndActorIds() {
        assertThatThrownBy(() -> new AuditEvent(ACTOR, "X", "T", "e", "a".repeat(65), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Actor.system("a".repeat(65))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Actor.system(" ")).isInstanceOf(IllegalArgumentException.class);
    }
}
