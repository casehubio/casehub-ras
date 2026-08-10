# DroolsGanglion Result Collection Strategy & Test Gaps — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add configurable N→1 result collection to DroolsGanglion (#8) and close three test gaps (#10).

**Architecture:** `ResultCollectionStrategy` enum on `DroolsGanglionConfig` controls how multiple DRL rule emissions reduce to one `DetectionResult`. `DetectionSignal` gains strength ordering (api/ change). `ResultCollectorChannel` accumulates a list; strategy applied after `fireAllRules()`.

**Tech Stack:** Java 21, JUnit 5, AssertJ, Drools 10.1.0 (kie-api), Mutiny

## Global Constraints

- Build: `mvn --batch-mode install` must pass after each task
- Never use plain `buildAll()` — always `buildAll(ExecutableModelProject.class)`
- `List.copyOf()` / `Map.copyOf()` for defensive copies (not `Collections.unmodifiableList()`)
- Every commit references #8 or #10

---

### Task 1: DetectionSignal strength ordering (api/)

**Files:**
- Modify: `api/src/main/java/io/casehub/ras/api/DetectionSignal.java`
- Modify: `api/src/test/java/io/casehub/ras/api/DetectionResultTest.java`

**Interfaces:**
- Produces: `DetectionSignal.isAtLeast(DetectionSignal threshold)` — used by Task 2 (ACCUMULATE) and future `RasTriggerPolicy` implementations

- [ ] **Step 1: Write tests for DetectionSignal ordering and isAtLeast()**

Create `api/src/test/java/io/casehub/ras/api/DetectionSignalTest.java`:

```java
package io.casehub.ras.api;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DetectionSignalTest {

    @Test
    void declarationOrderIsAscendingStrength() {
        assertThat(DetectionSignal.NOISE.ordinal())
                .isLessThan(DetectionSignal.ANTI.ordinal());
        assertThat(DetectionSignal.ANTI.ordinal())
                .isLessThan(DetectionSignal.WEAK.ordinal());
        assertThat(DetectionSignal.WEAK.ordinal())
                .isLessThan(DetectionSignal.DETECTED.ordinal());
    }

    @Test
    void isAtLeastReturnsTrueForSameOrStronger() {
        assertThat(DetectionSignal.DETECTED.isAtLeast(DetectionSignal.DETECTED)).isTrue();
        assertThat(DetectionSignal.DETECTED.isAtLeast(DetectionSignal.WEAK)).isTrue();
        assertThat(DetectionSignal.DETECTED.isAtLeast(DetectionSignal.ANTI)).isTrue();
        assertThat(DetectionSignal.DETECTED.isAtLeast(DetectionSignal.NOISE)).isTrue();
    }

    @Test
    void isAtLeastReturnsFalseForStrongerThreshold() {
        assertThat(DetectionSignal.NOISE.isAtLeast(DetectionSignal.ANTI)).isFalse();
        assertThat(DetectionSignal.NOISE.isAtLeast(DetectionSignal.WEAK)).isFalse();
        assertThat(DetectionSignal.ANTI.isAtLeast(DetectionSignal.WEAK)).isFalse();
        assertThat(DetectionSignal.WEAK.isAtLeast(DetectionSignal.DETECTED)).isFalse();
    }

    @Test
    void valuesArrayIsInAscendingStrengthOrder() {
        DetectionSignal[] values = DetectionSignal.values();
        assertThat(values).containsExactly(
                DetectionSignal.NOISE,
                DetectionSignal.ANTI,
                DetectionSignal.WEAK,
                DetectionSignal.DETECTED);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl api -Dtest=DetectionSignalTest`
Expected: compilation failure — `isAtLeast` does not exist, ordinal ordering wrong

- [ ] **Step 3: Implement DetectionSignal changes**

Replace `api/src/main/java/io/casehub/ras/api/DetectionSignal.java` with:

```java
package io.casehub.ras.api;

public enum DetectionSignal {
    NOISE,
    ANTI,
    WEAK,
    DETECTED;

    public boolean isAtLeast(DetectionSignal threshold) {
        return this.ordinal() >= threshold.ordinal();
    }
}
```

- [ ] **Step 4: Update DetectionResultTest signal references**

Existing `DetectionResultTest` creates `DetectionResult` instances with signal constants. Check that all references use the enum name (not ordinal) — they do. No changes needed unless compilation fails.

- [ ] **Step 5: Run all api/ tests**

Run: `mvn --batch-mode test -pl api`
Expected: all pass (DetectionSignalTest + existing tests)

- [ ] **Step 6: Commit**

```
feat(casehub-ras#8): DetectionSignal strength ordering with isAtLeast()

Reorder enum constants to NOISE, ANTI, WEAK, DETECTED (ascending strength).
Add isAtLeast(DetectionSignal) for readable threshold comparisons in trigger
policies and collection strategies.
```

---

### Task 2: ResultCollectionStrategy enum and resolve() (ras-drools/)

**Files:**
- Create: `ras-drools/src/main/java/io/casehub/ras/drools/ResultCollectionStrategy.java`
- Create: `ras-drools/src/test/java/io/casehub/ras/drools/ResultCollectionStrategyTest.java`

**Interfaces:**
- Consumes: `DetectionSignal` ordinal ordering from Task 1
- Produces: `ResultCollectionStrategy.resolve(List<DetectionResult>, String)` — used by Tasks 3 and 4

- [ ] **Step 1: Write tests for all four strategies**

Create `ras-drools/src/test/java/io/casehub/ras/drools/ResultCollectionStrategyTest.java`:

```java
package io.casehub.ras.drools;

import io.casehub.ras.api.DetectionResult;
import io.casehub.ras.api.DetectionSignal;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class ResultCollectionStrategyTest {

    private DetectionResult result(DetectionSignal signal, double confidence,
            Map<String, Object> evidence) {
        return new DetectionResult("test-g", confidence, signal, evidence);
    }

    private DetectionResult result(DetectionSignal signal, double confidence) {
        return result(signal, confidence, Map.of());
    }

    // --- Empty list ---

    @Test
    void allStrategiesReturnNoiseForEmptyList() {
        for (var strategy : ResultCollectionStrategy.values()) {
            DetectionResult r = strategy.resolve(List.of(), "g1");
            assertThat(r.ganglionId()).isEqualTo("g1");
            assertThat(r.signal()).isEqualTo(DetectionSignal.NOISE);
            assertThat(r.confidence()).isEqualTo(0.0);
            assertThat(r.evidence()).isEmpty();
        }
    }

    // --- Single result ---

    @Test
    void selectStrategiesReturnSingleResultUnchanged() {
        var single = result(DetectionSignal.DETECTED, 0.8);
        assertThat(ResultCollectionStrategy.HIGHEST_CONFIDENCE
                .resolve(List.of(single), "g1")).isSameAs(single);
        assertThat(ResultCollectionStrategy.FIRST_MATCH
                .resolve(List.of(single), "g1")).isSameAs(single);
        assertThat(ResultCollectionStrategy.LAST_WINS
                .resolve(List.of(single), "g1")).isSameAs(single);
    }

    // --- HIGHEST_CONFIDENCE ---

    @Test
    void highestConfidencePicksMaxConfidence() {
        var low = result(DetectionSignal.DETECTED, 0.3);
        var high = result(DetectionSignal.WEAK, 0.9);
        var mid = result(DetectionSignal.DETECTED, 0.6);
        DetectionResult r = ResultCollectionStrategy.HIGHEST_CONFIDENCE
                .resolve(List.of(low, high, mid), "g1");
        assertThat(r).isSameAs(high);
    }

    @Test
    void highestConfidenceTiesPickFirstEncountered() {
        var first = result(DetectionSignal.DETECTED, 0.7);
        var second = result(DetectionSignal.WEAK, 0.7);
        DetectionResult r = ResultCollectionStrategy.HIGHEST_CONFIDENCE
                .resolve(List.of(first, second), "g1");
        assertThat(r).isSameAs(first);
    }

    // --- FIRST_MATCH ---

    @Test
    void firstMatchPicksFirstResult() {
        var first = result(DetectionSignal.WEAK, 0.3);
        var second = result(DetectionSignal.DETECTED, 0.9);
        DetectionResult r = ResultCollectionStrategy.FIRST_MATCH
                .resolve(List.of(first, second), "g1");
        assertThat(r).isSameAs(first);
    }

    // --- LAST_WINS ---

    @Test
    void lastWinsPicksLastResult() {
        var first = result(DetectionSignal.DETECTED, 0.9);
        var second = result(DetectionSignal.WEAK, 0.3);
        DetectionResult r = ResultCollectionStrategy.LAST_WINS
                .resolve(List.of(first, second), "g1");
        assertThat(r).isSameAs(second);
    }

    // --- ACCUMULATE ---

    @Test
    void accumulatePicksStrongestSignal() {
        var weak = result(DetectionSignal.WEAK, 0.3);
        var anti = result(DetectionSignal.ANTI, 0.5);
        DetectionResult r = ResultCollectionStrategy.ACCUMULATE
                .resolve(List.of(weak, anti), "g1");
        assertThat(r.signal()).isEqualTo(DetectionSignal.WEAK);
    }

    @Test
    void accumulatePicksMaxConfidenceRegardlessOfSignal() {
        var detected = result(DetectionSignal.DETECTED, 0.3);
        var weak = result(DetectionSignal.WEAK, 0.9);
        DetectionResult r = ResultCollectionStrategy.ACCUMULATE
                .resolve(List.of(detected, weak), "g1");
        assertThat(r.signal()).isEqualTo(DetectionSignal.DETECTED);
        assertThat(r.confidence()).isEqualTo(0.9);
    }

    @Test
    void accumulateMergesEvidence() {
        var r1 = result(DetectionSignal.DETECTED, 0.8, Map.of("key1", "val1"));
        var r2 = result(DetectionSignal.DETECTED, 0.6, Map.of("key2", "val2"));
        DetectionResult r = ResultCollectionStrategy.ACCUMULATE
                .resolve(List.of(r1, r2), "g1");
        assertThat(r.evidence()).containsEntry("key1", "val1")
                .containsEntry("key2", "val2");
    }

    @Test
    void accumulateEvidenceCollisionLastWins() {
        var r1 = result(DetectionSignal.DETECTED, 0.5, Map.of("k", "first"));
        var r2 = result(DetectionSignal.DETECTED, 0.5, Map.of("k", "second"));
        DetectionResult r = ResultCollectionStrategy.ACCUMULATE
                .resolve(List.of(r1, r2), "g1");
        assertThat(r.evidence()).containsEntry("k", "second");
    }

    @Test
    void accumulateUsesProvidedGanglionId() {
        var r1 = result(DetectionSignal.DETECTED, 0.8);
        DetectionResult r = ResultCollectionStrategy.ACCUMULATE
                .resolve(List.of(r1), "override-id");
        assertThat(r.ganglionId()).isEqualTo("override-id");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl ras-drools -Dtest=ResultCollectionStrategyTest`
Expected: compilation failure — `ResultCollectionStrategy` does not exist

- [ ] **Step 3: Implement ResultCollectionStrategy**

Create `ras-drools/src/main/java/io/casehub/ras/drools/ResultCollectionStrategy.java`:

```java
package io.casehub.ras.drools;

import io.casehub.ras.api.DetectionResult;
import io.casehub.ras.api.DetectionSignal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public enum ResultCollectionStrategy {

    HIGHEST_CONFIDENCE {
        @Override
        public DetectionResult resolve(List<DetectionResult> results, String ganglionId) {
            if (results.isEmpty()) return noiseResult(ganglionId);
            DetectionResult best = results.getFirst();
            for (int i = 1; i < results.size(); i++) {
                if (results.get(i).confidence() > best.confidence()) {
                    best = results.get(i);
                }
            }
            return best;
        }
    },

    FIRST_MATCH {
        @Override
        public DetectionResult resolve(List<DetectionResult> results, String ganglionId) {
            if (results.isEmpty()) return noiseResult(ganglionId);
            return results.getFirst();
        }
    },

    LAST_WINS {
        @Override
        public DetectionResult resolve(List<DetectionResult> results, String ganglionId) {
            if (results.isEmpty()) return noiseResult(ganglionId);
            return results.getLast();
        }
    },

    ACCUMULATE {
        @Override
        public DetectionResult resolve(List<DetectionResult> results, String ganglionId) {
            if (results.isEmpty()) return noiseResult(ganglionId);
            DetectionSignal strongestSignal = DetectionSignal.NOISE;
            double maxConfidence = 0.0;
            Map<String, Object> mergedEvidence = new HashMap<>();
            for (var r : results) {
                if (r.signal().ordinal() > strongestSignal.ordinal()) {
                    strongestSignal = r.signal();
                }
                if (r.confidence() > maxConfidence) {
                    maxConfidence = r.confidence();
                }
                mergedEvidence.putAll(r.evidence());
            }
            return new DetectionResult(ganglionId, maxConfidence, strongestSignal, mergedEvidence);
        }
    };

    public abstract DetectionResult resolve(List<DetectionResult> results, String ganglionId);

    static DetectionResult noiseResult(String ganglionId) {
        return new DetectionResult(ganglionId, 0.0, DetectionSignal.NOISE, Map.of());
    }
}
```

- [ ] **Step 4: Run tests**

Run: `mvn --batch-mode test -pl ras-drools -Dtest=ResultCollectionStrategyTest`
Expected: all pass

- [ ] **Step 5: Commit**

```
feat(casehub-ras#8): ResultCollectionStrategy enum with resolve()

Four strategies: HIGHEST_CONFIDENCE, FIRST_MATCH, LAST_WINS, ACCUMULATE.
resolve() always returns a valid DetectionResult (NOISE for empty list).
ACCUMULATE merges strongest signal, max confidence, union of evidence.
```

---

### Task 3: DroolsGanglionConfig + ResultCollectorChannel + DroolsGanglion wiring

**Files:**
- Modify: `ras-drools/src/main/java/io/casehub/ras/drools/DroolsGanglionConfig.java`
- Modify: `ras-drools/src/main/java/io/casehub/ras/drools/ResultCollectorChannel.java`
- Modify: `ras-drools/src/main/java/io/casehub/ras/drools/DroolsGanglion.java`
- Modify: `ras-drools/src/test/java/io/casehub/ras/drools/DroolsGanglionConfigTest.java`
- Modify: `ras-drools/src/test/java/io/casehub/ras/drools/DroolsGanglionTest.java`

**Interfaces:**
- Consumes: `ResultCollectionStrategy.resolve()` from Task 2
- Produces: Updated `DroolsGanglionConfig` with `resultCollectionStrategy` field, updated `DroolsGanglion.detect()` using strategy-based resolution

- [ ] **Step 1: Write config test for new field and convenience constructor**

Add to `DroolsGanglionConfigTest.java`:

```java
@Test
void canonicalConstructorRequiresResultCollectionStrategy() {
    assertThatThrownBy(() -> new DroolsGanglionConfig(
            "g", Set.of("e"), SessionMode.LONG_LIVED, ClockMode.PSEUDO,
            List.of("r.drl"), List.of(), null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("resultCollectionStrategy");
}

@Test
void convenienceConstructorDefaultsToHighestConfidence() {
    var config = new DroolsGanglionConfig(
            "g", Set.of("e"), SessionMode.LONG_LIVED, ClockMode.PSEUDO,
            List.of("r.drl"), List.of());
    assertThat(config.resultCollectionStrategy())
            .isEqualTo(ResultCollectionStrategy.HIGHEST_CONFIDENCE);
}

@Test
void canonicalConstructorAcceptsExplicitStrategy() {
    var config = new DroolsGanglionConfig(
            "g", Set.of("e"), SessionMode.LONG_LIVED, ClockMode.PSEUDO,
            List.of("r.drl"), List.of(), ResultCollectionStrategy.ACCUMULATE);
    assertThat(config.resultCollectionStrategy())
            .isEqualTo(ResultCollectionStrategy.ACCUMULATE);
}
```

- [ ] **Step 2: Write DroolsGanglion integration test for multi-rule strategy**

Add a new DRL file `ras-drools/src/test/resources/io/casehub/ras/drools/test-multi-rule.drl`:

```drl
package io.casehub.ras.drools;

import io.cloudevents.CloudEvent;
import io.casehub.ras.api.DetectionResult;
import io.casehub.ras.api.DetectionSignal;
import java.util.Map;

rule "Low confidence"
when
    $ce : CloudEvent(type == "test.event")
then
    channels["results"].send(new DetectionResult(
        "test-ganglion", 0.3, DetectionSignal.WEAK, Map.of("rule", "low")));
end

rule "High confidence"
when
    $ce : CloudEvent(type == "test.event")
then
    channels["results"].send(new DetectionResult(
        "test-ganglion", 0.9, DetectionSignal.DETECTED, Map.of("rule", "high")));
end
```

Add to `DroolsGanglionTest.java`:

```java
@Test
void highestConfidenceStrategyPicksHigherConfidence() {
    var config = new DroolsGanglionConfig(
            "test-ganglion", Set.of("test.event"),
            SessionMode.EPHEMERAL, ClockMode.PSEUDO,
            List.of("io/casehub/ras/drools/test-multi-rule.drl"), List.of(),
            ResultCollectionStrategy.HIGHEST_CONFIDENCE);
    var ganglion = new DroolsGanglion(config, sessionStore, List.of());
    var event = testEvent("test.event", Instant.parse("2026-06-21T10:00:00Z"));
    DetectionResult result = ganglion.detect(event, testContext())
            .await().indefinitely();
    assertThat(result.confidence()).isEqualTo(0.9);
    assertThat(result.signal()).isEqualTo(DetectionSignal.DETECTED);
}

@Test
void accumulateStrategyMergesResults() {
    var config = new DroolsGanglionConfig(
            "test-ganglion", Set.of("test.event"),
            SessionMode.EPHEMERAL, ClockMode.PSEUDO,
            List.of("io/casehub/ras/drools/test-multi-rule.drl"), List.of(),
            ResultCollectionStrategy.ACCUMULATE);
    var ganglion = new DroolsGanglion(config, sessionStore, List.of());
    var event = testEvent("test.event", Instant.parse("2026-06-21T10:00:00Z"));
    DetectionResult result = ganglion.detect(event, testContext())
            .await().indefinitely();
    assertThat(result.signal()).isEqualTo(DetectionSignal.DETECTED);
    assertThat(result.confidence()).isEqualTo(0.9);
    assertThat(result.evidence()).containsKeys("rule");
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl ras-drools -Dtest=DroolsGanglionConfigTest,DroolsGanglionTest`
Expected: compilation failures — new constructor parameter, new field

- [ ] **Step 4: Implement DroolsGanglionConfig change**

Replace `ras-drools/src/main/java/io/casehub/ras/drools/DroolsGanglionConfig.java`:

```java
package io.casehub.ras.drools;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public record DroolsGanglionConfig(
        String ganglionId,
        Set<String> handledEventTypes,
        SessionMode sessionMode,
        ClockMode clockMode,
        List<String> classpathRules,
        List<String> programmaticRules,
        ResultCollectionStrategy resultCollectionStrategy
) {
    public DroolsGanglionConfig {
        Objects.requireNonNull(ganglionId, "ganglionId");
        Objects.requireNonNull(sessionMode, "sessionMode");
        Objects.requireNonNull(clockMode, "clockMode");
        Objects.requireNonNull(resultCollectionStrategy, "resultCollectionStrategy");
        if (handledEventTypes == null || handledEventTypes.isEmpty()) {
            throw new IllegalArgumentException("handledEventTypes must not be empty");
        }
        handledEventTypes = Set.copyOf(handledEventTypes);
        if (classpathRules == null) classpathRules = List.of();
        if (programmaticRules == null) programmaticRules = List.of();
        classpathRules = List.copyOf(classpathRules);
        programmaticRules = List.copyOf(programmaticRules);
        if (classpathRules.isEmpty() && programmaticRules.isEmpty()) {
            throw new IllegalArgumentException("At least one rule source required");
        }
    }

    public DroolsGanglionConfig(String ganglionId, Set<String> handledEventTypes,
            SessionMode sessionMode, ClockMode clockMode,
            List<String> classpathRules, List<String> programmaticRules) {
        this(ganglionId, handledEventTypes, sessionMode, clockMode,
             classpathRules, programmaticRules, ResultCollectionStrategy.HIGHEST_CONFIDENCE);
    }
}
```

- [ ] **Step 5: Implement ResultCollectorChannel change**

Replace `ras-drools/src/main/java/io/casehub/ras/drools/ResultCollectorChannel.java`:

```java
package io.casehub.ras.drools;

import io.casehub.ras.api.DetectionResult;
import org.kie.api.runtime.Channel;
import java.util.ArrayList;
import java.util.List;

class ResultCollectorChannel implements Channel {

    private final List<DetectionResult> results = new ArrayList<>();

    @Override
    public void send(Object object) {
        if (object instanceof DetectionResult dr) {
            results.add(dr);
        }
    }

    List<DetectionResult> results() { return List.copyOf(results); }
}
```

- [ ] **Step 6: Implement DroolsGanglion.detect() change**

In `DroolsGanglion.java`, replace lines 90–93 (the result collection block after `fireAllRules()`):

Old:
```java
DetectionResult result = collector.getResult();
if (result == null) {
    result = new DetectionResult(config.ganglionId(), 0.0, DetectionSignal.NOISE, Map.of());
}
```

New:
```java
DetectionResult result = config.resultCollectionStrategy()
        .resolve(collector.results(), config.ganglionId());
```

Also remove the unused `DetectionSignal` import if it was only used for the NOISE fallback. (Check — it is also imported via the wildcard `io.casehub.ras.api.*`, so no import change needed.)

- [ ] **Step 7: Run all ras-drools tests**

Run: `mvn --batch-mode test -pl ras-drools`
Expected: all pass (existing tests use convenience constructor → HIGHEST_CONFIDENCE default, single-rule DRLs produce identical results under any strategy)

- [ ] **Step 8: Run full build**

Run: `mvn --batch-mode install`
Expected: all modules pass

- [ ] **Step 9: Commit**

```
feat(casehub-ras#8): DroolsGanglion configurable result collection strategy

DroolsGanglionConfig gains resultCollectionStrategy field (defaults to
HIGHEST_CONFIDENCE via convenience constructor). ResultCollectorChannel
accumulates a list; strategy resolves after fireAllRules(). Null-to-NOISE
fallback in detect() replaced by resolve() which always returns a valid result.
```

---

### Task 4: Test gaps (#10)

**Files:**
- Modify: `ras-drools/src/test/java/io/casehub/ras/drools/DroolsGanglionTest.java`
- Modify: `ras-drools/src/test/java/io/casehub/ras/drools/InMemoryDroolsSessionStoreTest.java`

**Interfaces:**
- Consumes: existing `DroolsGanglion`, `InMemoryDroolsSessionStore`

- [ ] **Step 1: Write close() ephemeral test**

Add to `DroolsGanglionTest.java`:

```java
@Test
void closeOnEphemeralGanglionIsNoOp() {
    var ganglion = ganglionWithClasspathRule();
    var event = testEvent("test.event", Instant.parse("2026-06-21T10:00:00Z"));
    ganglion.detect(event, testContext()).await().indefinitely();
    assertThat(sessionStore.get("test-ganglion", "sit-1", "tenant-a")).isEmpty();
    ganglion.close("sit-1", "tenant-a").await().indefinitely();
    assertThat(sessionStore.get("test-ganglion", "sit-1", "tenant-a")).isEmpty();
}
```

- [ ] **Step 2: Write null-time sequence test**

Add to `DroolsGanglionTest.java`:

```java
@Test
void nullEventTimeDoesNotAdvanceClock() {
    var config = new DroolsGanglionConfig(
            "test-ganglion", Set.of("test.event"),
            SessionMode.LONG_LIVED, ClockMode.PSEUDO,
            List.of("io/casehub/ras/drools/test-threshold.drl"), List.of());
    var ganglion = new DroolsGanglion(config, sessionStore, List.of());
    var ctx = testContext();

    var event1 = testEvent("test.event", Instant.parse("2026-06-21T10:00:00Z"));
    DetectionResult r1 = ganglion.detect(event1, ctx).await().indefinitely();
    assertThat(r1.signal()).isEqualTo(DetectionSignal.DETECTED);

    var nullTimeEvent = CloudEventBuilder.v1()
            .withId("evt-null")
            .withSource(URI.create("/test"))
            .withType("test.event")
            .build();
    DetectionResult r2 = ganglion.detect(nullTimeEvent, ctx).await().indefinitely();
    assertThat(r2.signal()).isEqualTo(DetectionSignal.DETECTED);

    var event3 = testEvent("test.event", Instant.parse("2026-06-21T10:01:00Z"));
    DetectionResult r3 = ganglion.detect(event3, ctx).await().indefinitely();
    assertThat(r3.signal()).isEqualTo(DetectionSignal.DETECTED);
}
```

- [ ] **Step 3: Fix InMemoryDroolsSessionStoreTest buildAll() consistency**

In `InMemoryDroolsSessionStoreTest.java`, change line 22:

Old:
```java
kieBuilder.buildAll();
```

New:
```java
kieBuilder.buildAll(ExecutableModelProject.class);
```

Add the import:
```java
import org.drools.model.codegen.ExecutableModelProject;
```

- [ ] **Step 4: Run all ras-drools tests**

Run: `mvn --batch-mode test -pl ras-drools`
Expected: all pass

- [ ] **Step 5: Run full build**

Run: `mvn --batch-mode install`
Expected: all modules pass

- [ ] **Step 6: Commit**

```
test(casehub-ras#10): close() ephemeral, null-time sequence, buildAll consistency

Three test gaps from Epic 4 review:
- close() on ephemeral ganglion is a no-op (session already disposed)
- Null event time in real→null→real sequence: clock stays put, detection works
- InMemoryDroolsSessionStoreTest uses ExecutableModelProject.class consistently
```
