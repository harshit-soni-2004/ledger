# ADR-0002: Technology stack and Spring Boot version

- Status: Accepted (versions re-verified at the start of Phase 1)
- Date: 2026-10-29

## Context

The requested stack was Java 21, Spring Boot 3.4.x, Maven, MySQL 8.4, Flyway, JUnit 5,
Mockito, Testcontainers, and OpenAPI. Versions must be currently supported and mutually
compatible.

## Verification performed (2026-10-29)

| Item | Finding | Source |
|---|---|---|
| Spring Boot 3.4 | OSS support **ended** 2025-12-31 | endoflife.date/spring-boot |
| Spring Boot 3.5 | OSS support **ended** 2026-06-30 (latest 3.5.16) | endoflife.date/spring-boot |
| Spring Boot 4.0 | OSS support ends 2026-12-31 (latest 4.0.9) | endoflife.date/spring-boot |
| Spring Boot 4.1 | Released 2026-06-30; OSS support until **2027-07-31**; latest **4.1.2**; Java 17–26 | endoflife.date, spring.io |
| Boot 4.1 managed Flyway | 12.4.x | Spring Boot 4.1 release notes |
| Flyway + MySQL 8.4 | Supported since Flyway 11.13.3 (requires `flyway-mysql` module) | Redgate support forum, flyway/flyway#4168 |
| Boot 4.1 managed MySQL Connector/J | 9.7.0 | Spring Boot 4.1 release notes |
| Testcontainers | 2.0.x; MySQL module coordinates `org.testcontainers:testcontainers-mysql` | java.testcontainers.org |
| springdoc-openapi | 3.2.0 targets Boot 4.1.x (3.0.x targets Boot 4.0) | springdoc releases |
| Local toolchain | JDK 21.0.5, Maven 3.9.9, Docker 29.2.1, Git 2.52 | local inspection |

## Decision

| Concern | Choice |
|---|---|
| Language | Java 21 (LTS) |
| Framework | **Spring Boot 4.1.x** (Spring Framework 7, Jakarta EE 11, Jackson 3) |
| Build | Maven 3.9+, Maven Wrapper committed |
| Database | MySQL 8.4 LTS (`mysql:8.4` image) |
| Migrations | Flyway (Boot-managed 12.x) + `flyway-mysql`; Boot 4 requires the Flyway starter module for auto-configuration |
| Persistence | Spring JDBC `JdbcClient` ([ADR-0003](0003-jdbc-for-persistence.md)) |
| Validation | Jakarta Bean Validation (Hibernate Validator) |
| API docs | springdoc-openapi 3.2.x |
| CSV | Apache Commons CSV |
| Tests | JUnit 5, AssertJ, Mockito, Testcontainers 2 (MySQL), ArchUnit, jqwik |
| Load tests | k6 (Phase 11) |
| Observability | Actuator, Micrometer + Prometheus registry, Boot built-in structured JSON logging |

## Why not 3.5.x (which the sibling invoice project uses)?

It is out of OSS support. Starting a new portfolio project on an unsupported line is a
poor signal. Boot 4 changes (Jackson 3 package names, modular starters, Testcontainers 2
coordinates) are a small, one-time learning cost.

## Phase 1 verification gate

Before the scaffolding commit:

1. Generate the project from Spring Initializr with Boot 4.1.x to get exact starter names.
2. Run `mvn dependency:tree` and confirm Flyway ≥ 11.13.3, Testcontainers 2.x, springdoc 3.2.x.
3. Start the app against `mysql:8.4` and confirm Flyway logs **no** "MySQL 8.4 is newer than this version of Flyway" warning.
4. If any check fails, record the deviation in this ADR before proceeding.

## Consequences

- Supported until mid-2027; a minor upgrade (4.2) will be needed for longer support.
- Some blog posts/examples target Boot 3; Jackson and Testcontainers imports differ.
