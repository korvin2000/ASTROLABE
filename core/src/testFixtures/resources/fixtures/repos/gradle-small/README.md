# Fixture `gradle-small`

One Gradle/Kotlin module with two test classes in two packages and passing
tests. Materialized by `FixtureRepos.materialize(Fixture.GradleSmall)`.

## Runners (cwd = repository root)

| Purpose | Command |
|---|---|
| Canonical | `gradle test --offline --no-daemon -q --console=plain` |
| JUnit XML | the same run; reports land in `build/test-results/test/` |

The fixture deliberately carries **no wrapper**. `Runners.gradle()` finds a
distribution in this order: `GRADLE_HOME/bin`, `gradle` on `PATH`, then the
newest `…/wrapper/dists/gradle-*/**/bin/gradle` under `GRADLE_USER_HOME`
(default `~/.gradle`) — which the outer build's own wrapper has already
unpacked, locally and on CI. Tests skip visibly when none of the three exists.

`--offline` is safe because every coordinate here — Kotlin 2.4.20 and its
Gradle plugin marker, JUnit BOM/Jupiter 6.1.3, `junit-platform-launcher` — is
pinned to the version the outer build already resolved into the shared Gradle
cache. Bumping the outer versions without bumping these breaks the fixture;
that is intended, a version bump is a deliberate boundary (D-02).

The JDK 26 toolchain resolves from the JVM the tests hand it (`JAVA_HOME` set
to the test worker's own `java.home`), so nothing is downloaded.

## What it is for

- P1.3 atlas/sniff: `settings.gradle.kts` + `build.gradle.kts` are the manifest
  pair a JVM project is sniffed from.
- P1.6.6 shaping parsers: `build/test-results/test/TEST-*.xml` is the JUnit XML
  the Gradle/Maven shaper reads. `smoke()` exists in `pay.RouterTest` **and**
  `pay.handlers.HandlerTest` — same method name, two packages, separated only
  by `classname` (D-27, IX-13) — and `normalizesCurrency` is a
  `@ParameterizedTest` whose XML rows are named `[1] "EUR"`, `[2] "Usd"`,
  `[3] " gbp "`: Gradle's XML keeps the parameterization but drops the method
  name, so a parser that ignores `classname` and ordinal cannot tell these
  apart, which is the ambiguity D-50 says must read as `inconclusive`.
