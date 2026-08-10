# API Module SPI Promotion, Artifact Naming, and ChainMode Extensions — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use hortora:subagent-driven-development (recommended) or hortora:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Promote 4 SPI types from runtime to api, rename 2 persistence artifact IDs to match folder names, and add ChainMode.Streak and ChainMode.Rate variants to the sealed interface.

**Architecture:** Pure refactoring (SPI promotion + artifact rename) plus additive sealed interface extension (Streak, Rate). All changes are within casehub-ras; cross-repo follow-up is deferred. TDD: tests first for new ChainMode variants; move-then-verify for refactoring tasks.

**Tech Stack:** Java 21, Quarkus, Maven, JUnit 5, AssertJ, Mutiny

## Global Constraints

- All types in `io.casehub.ras.api` — flat package, no subpackages
- `casehub-ras-api` pom.xml gains zero new dependencies
- `ChainMode` is a sealed interface — all variants in the same file
- Detections are not guaranteed chronologically ordered — evaluation algorithms must sort by `eventTime`
- NOISE signals are invisible to Streak and Rate evaluation (neither reset nor count)
- Rate window must be full before triggering (fewer than `windowSize` scoreable signals → `false`)

---

### Task 1: SPI Promotion — Move 4 Types from runtime to api

**Files:**
- Create: `api/src/main/java/io/casehub/ras/api/CorrelationKeyExtractor.java`
- Create: `api/src/main/java/io/casehub/ras/api/DefaultCorrelationKeyExtractor.java`
- Create: `api/src/main/java/io/casehub/ras/api/SituationDefinitionProvider.java`
- Create: `api/src/main/java/io/casehub/ras/api/SituationRegistration.java`
- Delete: `runtime/src/main/java/io/casehub/ras/runtime/CorrelationKeyExtractor.java`
- Delete: `runtime/src/main/java/io/casehub/ras/runtime/DefaultCorrelationKeyExtractor.java`
- Delete: `runtime/src/main/java/io/casehub/ras/runtime/SituationDefinitionProvider.java`
- Delete: `runtime/src/main/java/io/casehub/ras/runtime/SituationRegistration.java`
- Move: `runtime/src/test/java/.../DefaultCorrelationKeyExtractorTest.java` → `api/src/test/java/.../DefaultCorrelationKeyExtractorTest.java`
- Move: `runtime/src/test/java/.../SituationRegistrationTest.java` → `api/src/test/java/.../SituationRegistrationTest.java`
- Modify: `runtime/src/main/java/io/casehub/ras/runtime/SituationDefinitionRegistry.java` — import update
- Modify: `runtime/src/main/java/io/casehub/ras/runtime/YamlSituationDefinitionProvider.java` — import update
- Modify: `runtime/src/main/java/io/casehub/ras/runtime/RasEngine.java` — import update
- Modify: `runtime/src/main/java/io/casehub/ras/runtime/SituationExpiryJob.java` — import update (if applicable)
- Modify: all runtime test files that reference these types — import updates

**Interfaces:**
- Consumes: nothing (first task)
- Produces: `CorrelationKeyExtractor`, `DefaultCorrelationKeyExtractor`, `SituationDefinitionProvider`, `SituationRegistration` in `io.casehub.ras.api` package

- [ ] **Step 1: Create the 4 source files in api — package change only**

Create each file in `api/src/main/java/io/casehub/ras/api/` with the same content as runtime, changing only the `package` declaration from `io.casehub.ras.runtime` to `io.casehub.ras.api`.

`CorrelationKeyExtractor.java`:
```java
package io.casehub.ras.api;

import io.cloudevents.CloudEvent;

@FunctionalInterface
public interface CorrelationKeyExtractor {
    String extract(CloudEvent event);
}
```

`DefaultCorrelationKeyExtractor.java`:
```java
package io.casehub.ras.api;

import io.cloudevents.CloudEvent;

public final class DefaultCorrelationKeyExtractor implements CorrelationKeyExtractor {

    public static final DefaultCorrelationKeyExtractor INSTANCE = new DefaultCorrelationKeyExtractor();
    static final String SINGLETON_KEY = "_singleton";

    private DefaultCorrelationKeyExtractor() {}

    @Override
    public String extract(CloudEvent event) {
        String subject = event.getSubject();
        return subject != null ? subject : SINGLETON_KEY;
    }
}
```

`SituationDefinitionProvider.java`:
```java
package io.casehub.ras.api;

import java.util.List;

public interface SituationDefinitionProvider {
    List<SituationRegistration> registrations();
}
```

`SituationRegistration.java`:
```java
package io.casehub.ras.api;

import java.util.Objects;

public record SituationRegistration(
        SituationDefinition definition,
        CorrelationKeyExtractor correlationKeyExtractor
) {
    public SituationRegistration {
        Objects.requireNonNull(definition, "definition");
        if (correlationKeyExtractor == null) {
            correlationKeyExtractor = DefaultCorrelationKeyExtractor.INSTANCE;
        }
    }

    public SituationRegistration(SituationDefinition definition) {
        this(definition, null);
    }
}
```

- [ ] **Step 2: Move test files from runtime to api — update package and imports**

Move `DefaultCorrelationKeyExtractorTest.java` and `SituationRegistrationTest.java` from `runtime/src/test/java/io/casehub/ras/runtime/` to `api/src/test/java/io/casehub/ras/api/`. Change `package` to `io.casehub.ras.api` and remove any `import io.casehub.ras.runtime.*` lines (types are now in the same package).

- [ ] **Step 3: Delete the 4 source files from runtime**

Delete:
- `runtime/src/main/java/io/casehub/ras/runtime/CorrelationKeyExtractor.java`
- `runtime/src/main/java/io/casehub/ras/runtime/DefaultCorrelationKeyExtractor.java`
- `runtime/src/main/java/io/casehub/ras/runtime/SituationDefinitionProvider.java`
- `runtime/src/main/java/io/casehub/ras/runtime/SituationRegistration.java`

Delete the old test files:
- `runtime/src/test/java/io/casehub/ras/runtime/DefaultCorrelationKeyExtractorTest.java`
- `runtime/src/test/java/io/casehub/ras/runtime/SituationRegistrationTest.java`

- [ ] **Step 4: Update all runtime imports**

In every `.java` file under `runtime/src/main/java/` and `runtime/src/test/java/` that imports any of the 4 moved types: replace `import io.casehub.ras.runtime.{Type}` with `import io.casehub.ras.api.{Type}`. Since runtime already has `import io.casehub.ras.api.*` in most files, many will just need the specific runtime imports removed.

Production files to check:
- `SituationDefinitionRegistry.java`
- `YamlSituationDefinitionProvider.java`
- `RasEngine.java`
- `SituationExpiryJob.java`

Test files to check (all in `runtime/src/test/java/.../runtime/`):
- `SituationDefinitionRegistryTest.java`
- `YamlSituationDefinitionProviderTest.java`
- `RasEngineTest.java`
- `SituationExpiryJobTest.java`
- `SituationEvaluatorTest.java`

- [ ] **Step 5: Build and verify**

Run: `mvn --batch-mode install -pl api,runtime -am`
Expected: BUILD SUCCESS — all api and runtime tests pass.

- [ ] **Step 6: Commit**

```
feat(casehub-ras#27): promote SPI types from runtime to api module

Move CorrelationKeyExtractor, DefaultCorrelationKeyExtractor,
SituationDefinitionProvider, SituationRegistration from
io.casehub.ras.runtime to io.casehub.ras.api.

Domain adapter modules can now depend on casehub-ras-api alone.
```

---

### Task 2: Artifact Naming — Rename persistence module artifacts

**Files:**
- Modify: `persistence-jpa/pom.xml` — `<artifactId>` line 11
- Modify: `persistence-memory/pom.xml` — `<artifactId>` line 11
- Modify: `pom.xml` (parent) — lines 74, 79 in `<dependencyManagement>`
- Modify: `runtime/pom.xml` — test dep reference to `casehub-ras-memory`

**Interfaces:**
- Consumes: nothing (independent of Task 1)
- Produces: artifacts `casehub-ras-persistence-jpa` and `casehub-ras-persistence-memory`

- [ ] **Step 1: Rename artifactId in persistence-jpa/pom.xml**

Change line 11 from `<artifactId>casehub-ras-jpa</artifactId>` to `<artifactId>casehub-ras-persistence-jpa</artifactId>`.

- [ ] **Step 2: Rename artifactId in persistence-memory/pom.xml**

Change line 11 from `<artifactId>casehub-ras-memory</artifactId>` to `<artifactId>casehub-ras-persistence-memory</artifactId>`.

- [ ] **Step 3: Update parent pom.xml dependencyManagement**

Change line 74: `<artifactId>casehub-ras-memory</artifactId>` → `<artifactId>casehub-ras-persistence-memory</artifactId>`
Change line 79: `<artifactId>casehub-ras-jpa</artifactId>` → `<artifactId>casehub-ras-persistence-jpa</artifactId>`

- [ ] **Step 4: Update runtime/pom.xml test dependency**

The `runtime/pom.xml` has a test-scope dep on `casehub-ras-memory`. Change it to `casehub-ras-persistence-memory`.

- [ ] **Step 5: Build and verify**

Run: `mvn --batch-mode install`
Expected: BUILD SUCCESS — all modules resolve with new artifact IDs.

- [ ] **Step 6: Commit**

```
chore(casehub-ras#24): rename persistence artifact IDs to match folder names

casehub-ras-jpa → casehub-ras-persistence-jpa
casehub-ras-memory → casehub-ras-persistence-memory
```

---

### Task 3: ChainMode.Streak — Record, Validation Tests, referencedGanglia

**Files:**
- Modify: `api/src/main/java/io/casehub/ras/api/ChainMode.java` — add `Streak` record
- Modify: `api/src/test/java/io/casehub/ras/api/ChainModeTest.java` — add Streak tests

**Interfaces:**
- Consumes: `ChainMode` sealed interface (api/)
- Produces: `ChainMode.Streak(String ganglionId, int requiredCount)` record

- [ ] **Step 1: Write failing tests for Streak record validation**

Add to `ChainModeTest.java`:

```java
@Test
void streakWithValidInput() {
    var streak = new ChainMode.Streak("g1", 3);
    assertThat(streak.ganglionId()).isEqualTo("g1");
    assertThat(streak.requiredCount()).isEqualTo(3);
}

@Test
void streakRejectsNullGanglionId() {
    assertThatNullPointerException()
            .isThrownBy(() -> new ChainMode.Streak(null, 3))
            .withMessage("ganglionId");
}

@Test
void streakRejectsZeroCount() {
    assertThatIllegalArgumentException()
            .isThrownBy(() -> new ChainMode.Streak("g1", 0))
            .withMessageContaining("0");
}

@Test
void streakRejectsNegativeCount() {
    assertThatIllegalArgumentException()
            .isThrownBy(() -> new ChainMode.Streak("g1", -1))
            .withMessageContaining("-1");
}

@Test
void referencedGangliaForStreak() {
    ChainMode mode = new ChainMode.Streak("g5", 3);
    assertThat(mode.referencedGanglia()).containsExactly("g5");
}
```

Also update `sealedInterfacePermitsAllVariants` to include Streak:
```java
ChainMode streak = new ChainMode.Streak("g1", 1);
assertThat(streak).isInstanceOf(ChainMode.class);
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl api -Dtest=ChainModeTest`
Expected: FAIL — `Streak` does not exist.

- [ ] **Step 3: Implement Streak record in ChainMode.java**

Add after the `Count` record (before the closing `}` of the interface):

```java
record Streak(String ganglionId, int requiredCount) implements ChainMode {
    public Streak {
        Objects.requireNonNull(ganglionId, "ganglionId");
        if (requiredCount < 1) {
            throw new IllegalArgumentException(
                    "requiredCount must be >= 1, got: " + requiredCount);
        }
    }
}
```

Add the `case Streak s -> Set.of(s.ganglionId());` branch to `referencedGanglia()`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn --batch-mode test -pl api -Dtest=ChainModeTest`
Expected: PASS

- [ ] **Step 5: Commit**

```
feat(casehub-ras#25): add ChainMode.Streak record with validation
```

---

### Task 4: ChainMode.Rate — Record, Validation Tests, referencedGanglia

**Files:**
- Modify: `api/src/main/java/io/casehub/ras/api/ChainMode.java` — add `Rate` record
- Modify: `api/src/test/java/io/casehub/ras/api/ChainModeTest.java` — add Rate tests

**Interfaces:**
- Consumes: `ChainMode` sealed interface (api/)
- Produces: `ChainMode.Rate(Set<String> ganglia, double minRate, int windowSize)` record

- [ ] **Step 1: Write failing tests for Rate record validation**

Add to `ChainModeTest.java`:

```java
@Test
void rateWithValidInput() {
    var rate = new ChainMode.Rate(Set.of("g1", "g2"), 0.6, 10);
    assertThat(rate.ganglia()).containsExactlyInAnyOrder("g1", "g2");
    assertThat(rate.minRate()).isEqualTo(0.6);
    assertThat(rate.windowSize()).isEqualTo(10);
}

@Test
void rateRejectsEmptyGanglia() {
    assertThatIllegalArgumentException()
            .isThrownBy(() -> new ChainMode.Rate(Set.of(), 0.5, 10))
            .withMessageContaining("must not be empty");
}

@Test
void rateRejectsNullGanglia() {
    assertThatIllegalArgumentException()
            .isThrownBy(() -> new ChainMode.Rate(null, 0.5, 10))
            .withMessageContaining("must not be empty");
}

@Test
void rateRejectsZeroMinRate() {
    assertThatIllegalArgumentException()
            .isThrownBy(() -> new ChainMode.Rate(Set.of("g1"), 0.0, 10))
            .withMessageContaining("0.0");
}

@Test
void rateRejectsNegativeMinRate() {
    assertThatIllegalArgumentException()
            .isThrownBy(() -> new ChainMode.Rate(Set.of("g1"), -0.5, 10))
            .withMessageContaining("-0.5");
}

@Test
void rateRejectsMinRateAboveOne() {
    assertThatIllegalArgumentException()
            .isThrownBy(() -> new ChainMode.Rate(Set.of("g1"), 1.1, 10))
            .withMessageContaining("1.1");
}

@Test
void rateAcceptsMinRateExactlyOne() {
    var rate = new ChainMode.Rate(Set.of("g1"), 1.0, 5);
    assertThat(rate.minRate()).isEqualTo(1.0);
}

@Test
void rateRejectsZeroWindowSize() {
    assertThatIllegalArgumentException()
            .isThrownBy(() -> new ChainMode.Rate(Set.of("g1"), 0.5, 0))
            .withMessageContaining("0");
}

@Test
void rateIsDefensivelyCopied() {
    var mutable = new java.util.HashSet<>(Set.of("g1"));
    var rate = new ChainMode.Rate(mutable, 0.5, 10);
    mutable.add("g2");
    assertThat(rate.ganglia()).containsExactly("g1");
}

@Test
void referencedGangliaForRate() {
    ChainMode mode = new ChainMode.Rate(Set.of("g1", "g4"), 0.5, 10);
    assertThat(mode.referencedGanglia()).containsExactlyInAnyOrder("g1", "g4");
}
```

Also update `sealedInterfacePermitsAllVariants` to include Rate:
```java
ChainMode rate = new ChainMode.Rate(Set.of("g1"), 0.5, 10);
assertThat(rate).isInstanceOf(ChainMode.class);
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl api -Dtest=ChainModeTest`
Expected: FAIL — `Rate` does not exist.

- [ ] **Step 3: Implement Rate record in ChainMode.java**

Add after the `Streak` record:

```java
record Rate(Set<String> ganglia, double minRate, int windowSize) implements ChainMode {
    public Rate {
        if (ganglia == null || ganglia.isEmpty()) {
            throw new IllegalArgumentException("ganglia must not be empty");
        }
        ganglia = Set.copyOf(ganglia);
        if (minRate <= 0.0 || minRate > 1.0) {
            throw new IllegalArgumentException(
                    "minRate must be in (0.0, 1.0], got: " + minRate);
        }
        if (windowSize < 1) {
            throw new IllegalArgumentException(
                    "windowSize must be >= 1, got: " + windowSize);
        }
    }
}
```

Add the `case Rate r -> r.ganglia();` branch to `referencedGanglia()`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn --batch-mode test -pl api -Dtest=ChainModeTest`
Expected: PASS

- [ ] **Step 5: Commit**

```
feat(casehub-ras#26): add ChainMode.Rate record with validation
```

---

### Task 5: DefaultRasTriggerPolicy — evaluateStreak and evaluateRate

**Files:**
- Modify: `runtime/src/main/java/io/casehub/ras/runtime/DefaultRasTriggerPolicy.java`
- Modify: `runtime/src/test/java/io/casehub/ras/runtime/DefaultRasTriggerPolicyTest.java`

**Interfaces:**
- Consumes: `ChainMode.Streak`, `ChainMode.Rate` (from Tasks 3–4), `SituationContext`, `TimestampedDetection`, `DetectionSignal`
- Produces: `evaluateStreak()` and `evaluateRate()` methods; updated `evaluate()` switch

- [ ] **Step 1: Write failing tests for evaluateStreak**

Add to `DefaultRasTriggerPolicyTest.java`. Use existing `T1`, `T2`, `T3` constants, `td()`, `ctx()`, `def()` helpers. Add `T4` and `T5`:

```java
private static final Instant T4 = Instant.parse("2026-06-25T10:03:00Z");
private static final Instant T5 = Instant.parse("2026-06-25T10:04:00Z");

// --- STREAK ---

@Test
void streakSatisfiedWithConsecutiveDetections() {
    var result = policy.evaluate(
            ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1),
                td("g1", DetectionSignal.DETECTED, 0.8, T2),
                td("g1", DetectionSignal.WEAK, 0.5, T3)),
            def(new ChainMode.Streak("g1", 3))
    ).await().indefinitely();
    assertThat(result).isEqualTo(TriggerDecision.CREATE_CASE);
}

@Test
void streakResetByAnti() {
    var result = policy.evaluate(
            ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1),
                td("g1", DetectionSignal.ANTI, 0.7, T2),
                td("g1", DetectionSignal.DETECTED, 0.8, T3)),
            def(new ChainMode.Streak("g1", 2))
    ).await().indefinitely();
    assertThat(result).isEqualTo(TriggerDecision.CONTINUE_ACCUMULATING);
}

@Test
void streakIgnoresNoise() {
    var result = policy.evaluate(
            ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1),
                td("g1", DetectionSignal.NOISE, 0.0, T2),
                td("g1", DetectionSignal.DETECTED, 0.8, T3)),
            def(new ChainMode.Streak("g1", 2))
    ).await().indefinitely();
    assertThat(result).isEqualTo(TriggerDecision.CREATE_CASE);
}

@Test
void streakIgnoresOtherGanglia() {
    var result = policy.evaluate(
            ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1),
                td("g2", DetectionSignal.ANTI, 0.7, T2),
                td("g1", DetectionSignal.DETECTED, 0.8, T3)),
            def(new ChainMode.Streak("g1", 2))
    ).await().indefinitely();
    assertThat(result).isEqualTo(TriggerDecision.CREATE_CASE);
}

@Test
void streakNotSatisfiedBelowRequired() {
    var result = policy.evaluate(
            ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1)),
            def(new ChainMode.Streak("g1", 2))
    ).await().indefinitely();
    assertThat(result).isEqualTo(TriggerDecision.CONTINUE_ACCUMULATING);
}

@Test
void streakSortsByEventTimeNotArrivalOrder() {
    // Arrival order: ANTI@T2, DETECTED@T1, DETECTED@T3
    // Event-time order: DETECTED@T1, ANTI@T2, DETECTED@T3 → streak=1, not 2
    var result = policy.evaluate(
            ctx(td("g1", DetectionSignal.ANTI, 0.7, T2),
                td("g1", DetectionSignal.DETECTED, 0.9, T1),
                td("g1", DetectionSignal.DETECTED, 0.8, T3)),
            def(new ChainMode.Streak("g1", 2))
    ).await().indefinitely();
    assertThat(result).isEqualTo(TriggerDecision.CONTINUE_ACCUMULATING);
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl runtime -Dtest=DefaultRasTriggerPolicyTest#streak*`
Expected: FAIL — compilation error, no `Streak` case in switch.

- [ ] **Step 3: Implement evaluateStreak and update switch**

Add to the switch in `evaluate()`:
```java
case ChainMode.Streak streak -> evaluateStreak(context, streak);
```

Add method:
```java
private boolean evaluateStreak(SituationContext ctx, ChainMode.Streak streak) {
    List<TimestampedDetection> filtered = ctx.detections().stream()
            .filter(td -> td.result().ganglionId().equals(streak.ganglionId()))
            .sorted(Comparator.comparing(TimestampedDetection::eventTime))
            .toList();

    int consecutive = 0;
    for (var td : filtered) {
        DetectionSignal signal = td.result().signal();
        if (signal == DetectionSignal.ANTI) {
            consecutive = 0;
        } else if (signal.isAtLeast(DetectionSignal.WEAK)) {
            consecutive++;
            if (consecutive >= streak.requiredCount()) return true;
        }
    }
    return false;
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn --batch-mode test -pl runtime -Dtest=DefaultRasTriggerPolicyTest`
Expected: PASS

- [ ] **Step 5: Write failing tests for evaluateRate**

Add to `DefaultRasTriggerPolicyTest.java`:

```java
// --- RATE ---

@Test
void rateSatisfiedWhenRatioMet() {
    var result = policy.evaluate(
            ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1),
                td("g1", DetectionSignal.DETECTED, 0.8, T2),
                td("g1", DetectionSignal.ANTI, 0.5, T3)),
            def(new ChainMode.Rate(Set.of("g1"), 0.6, 3))
    ).await().indefinitely();
    // 2 qualifying / 3 total = 0.67 >= 0.6
    assertThat(result).isEqualTo(TriggerDecision.CREATE_CASE);
}

@Test
void rateNotSatisfiedBelowMinRate() {
    var result = policy.evaluate(
            ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1),
                td("g1", DetectionSignal.ANTI, 0.5, T2),
                td("g1", DetectionSignal.ANTI, 0.5, T3)),
            def(new ChainMode.Rate(Set.of("g1"), 0.6, 3))
    ).await().indefinitely();
    // 1 qualifying / 3 total = 0.33 < 0.6
    assertThat(result).isEqualTo(TriggerDecision.CONTINUE_ACCUMULATING);
}

@Test
void rateNotSatisfiedWhenWindowNotFull() {
    var result = policy.evaluate(
            ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1),
                td("g1", DetectionSignal.DETECTED, 0.8, T2)),
            def(new ChainMode.Rate(Set.of("g1"), 0.5, 3))
    ).await().indefinitely();
    // Only 2 scoreable signals, window needs 3
    assertThat(result).isEqualTo(TriggerDecision.CONTINUE_ACCUMULATING);
}

@Test
void rateExcludesNoiseFromWindow() {
    var result = policy.evaluate(
            ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1),
                td("g1", DetectionSignal.NOISE, 0.0, T2),
                td("g1", DetectionSignal.DETECTED, 0.8, T3)),
            def(new ChainMode.Rate(Set.of("g1"), 0.5, 3))
    ).await().indefinitely();
    // Only 2 scoreable (NOISE excluded), window needs 3
    assertThat(result).isEqualTo(TriggerDecision.CONTINUE_ACCUMULATING);
}

@Test
void rateUsesLastWindowSizeSignals() {
    var result = policy.evaluate(
            ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1),
                td("g1", DetectionSignal.ANTI, 0.5, T2),
                td("g1", DetectionSignal.DETECTED, 0.8, T3),
                td("g1", DetectionSignal.DETECTED, 0.7, T4)),
            def(new ChainMode.Rate(Set.of("g1"), 0.6, 3))
    ).await().indefinitely();
    // Last 3 scoreable: ANTI@T2, DETECTED@T3, DETECTED@T4 → 2/3 = 0.67 >= 0.6
    assertThat(result).isEqualTo(TriggerDecision.CREATE_CASE);
}

@Test
void rateAcrossMultipleGanglia() {
    var result = policy.evaluate(
            ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1),
                td("g2", DetectionSignal.ANTI, 0.5, T2),
                td("g1", DetectionSignal.DETECTED, 0.8, T3)),
            def(new ChainMode.Rate(Set.of("g1", "g2"), 0.6, 3))
    ).await().indefinitely();
    // 2 qualifying / 3 total = 0.67 >= 0.6
    assertThat(result).isEqualTo(TriggerDecision.CREATE_CASE);
}

@Test
void rateIgnoresNonParticipatingGanglia() {
    var result = policy.evaluate(
            ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1),
                td("g3", DetectionSignal.ANTI, 0.5, T2),
                td("g1", DetectionSignal.DETECTED, 0.8, T3)),
            def(new ChainMode.Rate(Set.of("g1"), 0.5, 2))
    ).await().indefinitely();
    // g3 not in ganglia → ignored; 2 qualifying / 2 total = 1.0 >= 0.5
    assertThat(result).isEqualTo(TriggerDecision.CREATE_CASE);
}

@Test
void rateSortsByEventTimeNotArrivalOrder() {
    // Arrival order: DETECTED@T3, ANTI@T1, DETECTED@T2
    // Event-time order: ANTI@T1, DETECTED@T2, DETECTED@T3
    // Last 2: DETECTED@T2, DETECTED@T3 → 2/2 = 1.0 >= 0.5
    var result = policy.evaluate(
            ctx(td("g1", DetectionSignal.DETECTED, 0.8, T3),
                td("g1", DetectionSignal.ANTI, 0.5, T1),
                td("g1", DetectionSignal.DETECTED, 0.9, T2)),
            def(new ChainMode.Rate(Set.of("g1"), 0.5, 2))
    ).await().indefinitely();
    assertThat(result).isEqualTo(TriggerDecision.CREATE_CASE);
}
```

- [ ] **Step 6: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl runtime -Dtest=DefaultRasTriggerPolicyTest#rate*`
Expected: FAIL — compilation error, no `Rate` case in switch.

- [ ] **Step 7: Implement evaluateRate and update switch**

Add to the switch in `evaluate()`:
```java
case ChainMode.Rate rate -> evaluateRate(context, rate);
```

Add method:
```java
private boolean evaluateRate(SituationContext ctx, ChainMode.Rate rate) {
    List<TimestampedDetection> scoreable = ctx.detections().stream()
            .filter(td -> rate.ganglia().contains(td.result().ganglionId()))
            .filter(td -> td.result().signal().isAtLeast(DetectionSignal.ANTI))
            .sorted(Comparator.comparing(TimestampedDetection::eventTime))
            .toList();

    if (scoreable.size() < rate.windowSize()) {
        return false;
    }

    List<TimestampedDetection> window = scoreable.subList(
            scoreable.size() - rate.windowSize(), scoreable.size());

    long qualifying = window.stream()
            .filter(td -> td.result().signal().isAtLeast(DetectionSignal.WEAK))
            .count();

    return (double) qualifying / rate.windowSize() >= rate.minRate();
}
```

- [ ] **Step 8: Run all policy tests**

Run: `mvn --batch-mode test -pl runtime -Dtest=DefaultRasTriggerPolicyTest`
Expected: PASS — all existing + new tests pass.

- [ ] **Step 9: Commit**

```
feat(casehub-ras#25,#26): evaluateStreak and evaluateRate in DefaultRasTriggerPolicy
```

---

### Task 6: YAML Parsing — Streak and Rate in YamlSituationDefinitionProvider

**Files:**
- Modify: `runtime/src/main/java/io/casehub/ras/runtime/YamlSituationDefinitionProvider.java`
- Modify: `runtime/src/test/java/io/casehub/ras/runtime/YamlSituationDefinitionProviderTest.java`

**Interfaces:**
- Consumes: `ChainMode.Streak`, `ChainMode.Rate` (from Tasks 3–4)
- Produces: YAML parsing for `type: streak` and `type: rate`

- [ ] **Step 1: Write failing tests for YAML streak parsing**

Add to `YamlSituationDefinitionProviderTest.java`:

```java
@Test
void parsesStreakChainMode() {
    var regs = provider("""
            situations:
              - situationId: sit1
                eventTypes: [e1]
                chainMode:
                  type: streak
                  ganglionId: g1
                  requiredCount: 3
                triggerConfig:
                  caseNamespace: ns
                  caseName: c
                  caseVersion: "1"
            """).registrations();

    var streak = (ChainMode.Streak) regs.get(0).definition().chainMode();
    assertThat(streak.ganglionId()).isEqualTo("g1");
    assertThat(streak.requiredCount()).isEqualTo(3);
}

@Test
void parsesRateChainMode() {
    var regs = provider("""
            situations:
              - situationId: sit1
                eventTypes: [e1]
                chainMode:
                  type: rate
                  ganglia: [g1, g2]
                  minRate: 0.6
                  windowSize: 10
                triggerConfig:
                  caseNamespace: ns
                  caseName: c
                  caseVersion: "1"
            """).registrations();

    var rate = (ChainMode.Rate) regs.get(0).definition().chainMode();
    assertThat(rate.ganglia()).containsExactlyInAnyOrder("g1", "g2");
    assertThat(rate.minRate()).isEqualTo(0.6);
    assertThat(rate.windowSize()).isEqualTo(10);
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl runtime -Dtest=YamlSituationDefinitionProviderTest#parsesStreakChainMode,YamlSituationDefinitionProviderTest#parsesRateChainMode`
Expected: FAIL — "Unknown chainMode type 'streak'"

- [ ] **Step 3: Add streak and rate cases to parseChainMode**

In `YamlSituationDefinitionProvider.parseChainMode()`, add two cases to the switch before the `default`:

```java
case "streak" -> new ChainMode.Streak(
        requireString(map, "ganglionId"),
        requireNumber(map, "requiredCount", situationId).intValue());
case "rate" -> new ChainMode.Rate(
        new LinkedHashSet<>(requireList(map, "ganglia", situationId)),
        requireNumber(map, "minRate", situationId).doubleValue(),
        requireNumber(map, "windowSize", situationId).intValue());
```

- [ ] **Step 4: Run all YAML tests**

Run: `mvn --batch-mode test -pl runtime -Dtest=YamlSituationDefinitionProviderTest`
Expected: PASS

- [ ] **Step 5: Commit**

```
feat(casehub-ras#25,#26): YAML parsing for streak and rate chain modes
```

---

### Task 7: CLAUDE.md Updates and Full Build Verification

**Files:**
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: all prior tasks
- Produces: updated documentation, verified full build

- [ ] **Step 1: Update CLAUDE.md**

Update the **Module Structure** table:
- `persistence-memory/` row: artifact `casehub-ras-persistence-memory`
- `persistence-jpa/` row: artifact `casehub-ras-persistence-jpa`

Update the **Core SPIs (api/)** section to add entries for:
- `CorrelationKeyExtractor` — `@FunctionalInterface`, domain adapters implement for custom correlation key extraction
- `DefaultCorrelationKeyExtractor` — default: `CloudEvent.getSubject()` or `"_singleton"` when null
- `SituationDefinitionProvider` — SPI for registering situation definitions
- `SituationRegistration` — bundles `SituationDefinition` + `CorrelationKeyExtractor`

Update the **Routing Model** chain modes list to add:
- STREAK (named ganglion fires N times consecutively without ANTI reset)
- RATE (ratio of qualifying signals in a sliding window of scoreable signals)

Update the **Core Types (api/)** table to add:
- `CorrelationKeyExtractor` and `SituationRegistration` entries

- [ ] **Step 2: Close issue #23 as duplicate of #27**

Run: `gh issue close 23 --repo casehubio/casehub-ras --comment "Subsumed by #27 — all types promoted in issue-27-api-spi-and-chainmode branch."`

- [ ] **Step 3: Full build verification**

Run: `mvn --batch-mode install`
Expected: BUILD SUCCESS across all modules.

- [ ] **Step 4: Commit**

```
docs(casehub-ras#27): update CLAUDE.md — promoted SPIs, renamed artifacts, new chain modes
```
