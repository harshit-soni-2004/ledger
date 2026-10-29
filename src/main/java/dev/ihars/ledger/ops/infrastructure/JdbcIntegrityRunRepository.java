package dev.ihars.ledger.ops.infrastructure;

import dev.ihars.ledger.ledger.integrity.IntegrityReport.Check;
import dev.ihars.ledger.ledger.integrity.IntegrityReport.Violation;
import dev.ihars.ledger.ops.IntegrityCheckRun;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcIntegrityRunRepository {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public JdbcIntegrityRunRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insert(IntegrityCheckRun run) {
        List<Map<String, String>> violations = run.violations().stream()
                .map(v -> Map.of("check", v.check().name(), "subject", v.subject()))
                .toList();
        jdbc.sql("""
                INSERT INTO integrity_check_runs (public_id, trigger_type, triggered_by, started_at, finished_at,
                                                  violation_count, violations)
                VALUES (:publicId, :trigger, :triggeredBy, :startedAt, :finishedAt, :count, :violations)
                """)
                .param("publicId", run.publicId())
                .param("trigger", run.trigger().name())
                .param("triggeredBy", run.triggeredBy())
                .param("startedAt", utc(run.startedAt()))
                .param("finishedAt", utc(run.finishedAt()))
                .param("count", run.violationCount())
                .param("violations", json.writeValueAsString(violations))
                .update();
    }

    public Optional<IntegrityCheckRun> latest() {
        return jdbc.sql("""
                SELECT public_id, trigger_type, triggered_by, started_at, finished_at, violation_count, violations
                  FROM integrity_check_runs
                 ORDER BY finished_at DESC, id DESC
                 LIMIT 1
                """)
                .query((rs, n) -> run(rs))
                .optional();
    }

    private IntegrityCheckRun run(ResultSet rs) throws SQLException {
        List<Violation> violations = new ArrayList<>();
        for (JsonNode node : json.readTree(rs.getString("violations"))) {
            violations.add(new Violation(Check.valueOf(node.get("check").asString()), node.get("subject").asString()));
        }
        return new IntegrityCheckRun(rs.getString("public_id"),
                IntegrityCheckRun.Trigger.valueOf(rs.getString("trigger_type")), rs.getString("triggered_by"),
                instant(rs, "started_at"), instant(rs, "finished_at"), rs.getInt("violation_count"), violations);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDateTime.class).toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
