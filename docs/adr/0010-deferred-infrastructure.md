# ADR-0010: Defer Kafka, Redis, Kubernetes, and elaborate monitoring

- Status: Accepted
- Date: 2026-10-29

## Decision

Until the core ledger and reconciliation workflows are correct and tested, the project
uses only: the application, MySQL, Docker Compose, GitHub Actions, and Actuator metrics.

| Deferred | Re-entry criterion |
|---|---|
| Kafka | A real downstream consumer exists; then via a transactional outbox table written in the posting transaction. Never publish before commit. |
| Redis | A measured read hot-spot that MySQL cannot serve. Never for idempotency or locks on the money path. |
| Kubernetes | Never needed for this portfolio scope; Compose is enough. |
| Prometheus + Grafana | Optional Compose profile in Phase 11 with one dashboard. |
| OpenTelemetry tracing | After Phase 11, if demonstrating tracing is a goal. |

## Consequences

- Fewer moving parts → faster feedback, fewer flaky tests.
- Interview narrative: "I added infrastructure only when a requirement demanded it."
