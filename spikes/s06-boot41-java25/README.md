# S-06: Spring Boot 4.1 and Java 25 compatibility

Verifies that the libraries chosen in `docs/03-architecture/tech-stack-and-versions.md` (DOC-11) work together on
Spring Boot 4.1.1 and Java 25, and pins down the Spring Batch 6 behaviour the ETL design relies on (DR-53 point 0).

## Run

Requires JDK 25 and Docker. With OrbStack, Testcontainers needs:

```bash
export DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
./gradlew test                                    # Postgres 17.11
./gradlew test -PpgImage=postgres:18.1 --tests '*BatchSemanticsTest'
./gradlew spotlessCheck checkstyleMain spotbugsMain jacocoTestReport
./gradlew jibDockerBuild                          # linux/arm64 by default; -PjibPlatforms=linux/amd64
```

## What the tests show

`BatchSemanticsTest` runs the same job on both chunk step implementations of Spring Batch 6.0.5:

| Scenario | `ChunkOrientedStep` (new) | `FaultTolerantStepBuilder` (legacy, deprecated) |
| --- | --- | --- |
| Write error at item 37 of 500 | 499 rows, **dead letter lost**, write skip count 0 | 499 rows, 1 dead letter written in the chunk transaction |
| Crash (Error) while scanning item 300, then restart | **298 of 499 rows**: unscanned items are skipped silently | 499 rows |
| Non-skippable error at item 700 of 1,200, restart | Resumes at item 701 | Resumes at item 701 |
| Transient write error | not tested | Retried, 500 rows, no dead letter |
| `JobOperator.recover` on a STARTED execution | FAILED, VERSION bumped, stale owner fenced | Same |

The assertions for the new step document the observed behaviour, so the suite turns red if a later Spring Batch
version fixes it. Decision: DR-80 (use the legacy builder).

`LibraryCompatibilityTest` and `PlainLibrariesTest` cover ShedLock, Spring Cloud AWS S3 against SeaweedFS 4.47,
a Kafka batch listener with `ContainerPausingBackOffHandler` against Kafka 4.3.1, springdoc, Resilience4j,
OpenTelemetry tracing, virtual threads, JSON Schema 2020-12 on Jackson 3, canonical JSON, UUIDv7/v5, ULID,
Bucket4j, MockWebServer and ArchUnit on Java 25 bytecode.
