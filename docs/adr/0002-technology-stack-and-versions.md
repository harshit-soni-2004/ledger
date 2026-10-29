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
| Tests | JUnit 6 (Jupiter), AssertJ, Mockito, Testcontainers 2 (MySQL), ArchUnit; property tests as seeded randomized JUnit tests (see below) |
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

## Phase 1 gate result (2026-10-29)

All checks passed; no deviations.

| Check | Result |
|---|---|
| Initializr (Boot 4.1.2) starter names | `spring-boot-starter-webmvc`, `-validation`, `-jdbc`, `-flyway` (+ `flyway-mysql`), `-actuator`; test starters per technology (`spring-boot-starter-webmvc-test`, `-jdbc-test`, `-flyway-test`, `-actuator-test`, `-validation-test`); `spring-boot-testcontainers`, `testcontainers-junit-jupiter`, `testcontainers-mysql` |
| Resolved by `mvn dependency:tree` | Spring Boot 4.1.2, Spring Framework 7.0.10, Flyway 12.4.0, MySQL Connector/J 9.7.0, HikariCP 7.0.2, Jackson 3.1.7, Testcontainers 2.0.5, JUnit Jupiter 6.0.3 |
| Pinned (not Boot-managed) | springdoc-openapi 3.2.0 (its parent is spring-boot-starter-parent 4.1.2), ArchUnit 1.5.1, JaCoCo 0.8.16 |
| Maven Wrapper | 3.3.4 (`only-script`), Maven 3.9.9, `distributionSha256Sum` checked against Maven Central's published SHA-512 |
| Flyway on MySQL 8.4 (`mysql:8.4` image, Testcontainers and Compose) | Detected "MySQL 8.4"; migrations applied; no "newer than this version of Flyway" warning |
| springdoc compatibility | `/v3/api-docs` served under the `local` profile (`OpenApiDocsIT`) |
| CI action versions | `actions/checkout@v7`, `actions/setup-java@v6`, `actions/upload-artifact@v7` (latest releases v7.0.1, v6.1.0, v7.0.2 on 2026-10-29) |

Notes:

- springdoc pulls Jackson 2 (`com.fasterxml.jackson`) transitively for swagger-core; the
  application itself uses Boot's Jackson 3 (`tools.jackson`).
- Mockito is attached as a `-javaagent` in Surefire and Failsafe (resolved through
  `dependency:properties`). Spring Boot's test support initialises Mockito's inline mock maker,
  and JDK 21 warns about dynamic agent loading, which later JDKs disable by default (JEP 451).

### Property-based testing: jqwik removed (Phase 1 review)

jqwik was declared in Phase 1 but no test used it. It was removed for these reasons:

1. **Unused.** No Phase 1 test needs it, yet its engine ran on every Surefire and Failsafe
   execution and wrote a `.jqwik-database` file to the project root.
2. **Platform mismatch.** jqwik 1.10.x is built on JUnit Platform 1.x, while this project
   runs JUnit 6 (Boot-managed 6.0.3). The jqwik 1.10.1 release notes say a Platform 6 build
   may never be released.
3. **Maintainer terms.** From 1.10, jqwik ships what its release notes call an "Anti-AI Usage
   Clause", and its engine prints a notice addressed to AI agents. This project is developed
   with an AI coding assistant, so depending on jqwik goes against the maintainer's stated
   wishes. The printed text was treated as library output, not as an instruction; it is
   documented here only as a fact about the library.

Replacement (proposed for Phase 2, Q12): `*PropertiesTest` classes written as plain JUnit 6
tests that generate inputs with a seeded `java.util.random.RandomGenerator`. Each run logs
its seed, and a failing seed can be pinned to reproduce the run. Shrinking is lost; inputs
are kept small and boundary cases are enumerated explicitly to compensate.

## Consequences

- Supported until mid-2027; a minor upgrade (4.2) will be needed for longer support.
- Some blog posts/examples target Boot 3; Jackson and Testcontainers imports differ.
