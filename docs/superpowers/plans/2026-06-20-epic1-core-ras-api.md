# Epic 1: Core RAS API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the core API types, SPIs, in-memory persistence, and test fixtures for `casehub-ras-api`.

**Architecture:** Pure Java records and interfaces in `api/`, `@ApplicationScoped @Alternative` in-memory store in `persistence-memory/`, test fixtures in `testing/`. All types are immutable with compact-constructor validation. SPIs return `Uni<T>` for reactive compatibility. Definition-driven routing (Model B) — ganglia evaluate, they do not route.

**Tech Stack:** Java 21 records, sealed interfaces, Mutiny `Uni<T>`, CloudEvents SDK (`io.cloudevents.CloudEvent`), CDI annotations, JUnit 5, AssertJ.

## Global Constraints

- Java 21, `maven.compiler.release=21`
- All records use compact constructors with `Objects.requireNonNull` for required fields
- Collections in records are defensively copied (`List.copyOf`, `Set.copyOf`, `Map.copyOf`)
- Null collections normalised to empty (`evidence != null ? Map.copyOf(evidence) : Map.of()`)
- Package: `io.casehub.ras.api` (api), `io.casehub.ras.memory` (persistence-memory), `io.casehub.ras.testing` (testing)
- Every module with CDI beans includes `jandex-maven-plugin`
- `casehub-engine-api` is NOT on `api/` classpath — only on `runtime/`
- Commit messages: `feat(casehub-ras#1): <description>`

---

### Task 1: Core enums and DetectionResult record

**Files:**
- Create: `api/src/main/java/io/casehub/ras/api/DetectionSignal.java`
- Create: `api/src/main/java/io/casehub/ras/api/DetectionResult.java`
- Test: `api/src/test/java/io/casehub/ras/api/DetectionResultTest.java`

**Interfaces:**
- Consumes: nothing
- Produces: `DetectionSignal` enum (DETECTED, WEAK, NOISE, ANTI), `DetectionResult` record (`String ganglionId`, `double confidence`, `DetectionSignal signal`, `Map<String, Object> evidence`)

- [ ] **Step 1: Write failing tests for DetectionResult validation**

```java
package io.casehub.ras.api;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class DetectionResultTest {

    @Test
    void validResultIsCreated() {
        var result = new DetectionResult("temp-ganglion", 0.85, DetectionSignal.DETECTED,
                Map.of("threshold", 95.0));

        assertThat(result.ganglionId()).isEqualTo("temp-ganglion");
        assertThat(result.confidence()).isEqualTo(0.85);
        assertThat(result.signal()).isEqualTo(DetectionSignal.DETECTED);
        assertThat(result.evidence()).containsEntry("threshold", 95.0);
    }

    @Test
    void nullGanglionIdIsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new DetectionResult(null, 0.5, DetectionSignal.DETECTED, Map.of()))
                .withMessage("ganglionId");
    }

    @Test
    void nullSignalIsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new DetectionResult("g1", 0.5, null, Map.of()))
                .withMessage("signal");
    }

    @Test
    void confidenceBelowZeroIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DetectionResult("g1", -0.1, DetectionSignal.DETECTED, Map.of()))
                .withMessageContaining("-0.1");
    }

    @Test
    void confidenceAboveOneIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DetectionResult("g1", 1.01, DetectionSignal.DETECTED, Map.of()))
                .withMessageContaining("1.01");
    }

    @Test
    void confidenceBoundariesAreAccepted() {
        assertThatNoException().isThrownBy(
                () -> new DetectionResult("g1", 0.0, DetectionSignal.NOISE, Map.of()));
        assertThatNoException().isThrownBy(
                () -> new DetectionResult("g1", 1.0, DetectionSignal.DETECTED, Map.of()));
    }

    @Test
    void nullEvidenceNormalisedToEmptyMap() {
        var result = new DetectionResult("g1", 0.5, DetectionSignal.WEAK, null);
        assertThat(result.evidence()).isNotNull().isEmpty();
    }

    @Test
    void evidenceIsDefensivelyCopied() {
        var mutable = new java.util.HashMap<String, Object>();
        mutable.put("key", "value");
        var result = new DetectionResult("g1", 0.5, DetectionSignal.DETECTED, mutable);
        mutable.put("extra", "should-not-appear");
        assertThat(result.evidence()).doesNotContainKey("extra");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl api -Dtest=DetectionResultTest`
Expected: compilation failure — classes do not exist yet.

- [ ] **Step 3: Implement DetectionSignal and DetectionResult**

`api/src/main/java/io/casehub/ras/api/DetectionSignal.java`:
```java
package io.casehub.ras.api;

public enum DetectionSignal {
    DETECTED,
    WEAK,
    NOISE,
    ANTI
}
```

`api/src/main/java/io/casehub/ras/api/DetectionResult.java`:
```java
package io.casehub.ras.api;

import java.util.Map;
import java.util.Objects;

public record DetectionResult(
        String ganglionId,
        double confidence,
        DetectionSignal signal,
        Map<String, Object> evidence
) {
    public DetectionResult {
        Objects.requireNonNull(ganglionId, "ganglionId");
        Objects.requireNonNull(signal, "signal");
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be 0.0–1.0, got: " + confidence);
        }
        evidence = evidence != null ? Map.copyOf(evidence) : Map.of();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn --batch-mode test -pl api -Dtest=DetectionResultTest`
Expected: all 7 tests PASS.

- [ ] **Step 5: Commit**

```
git add api/src/
git commit -m "feat(casehub-ras#1): DetectionSignal enum and DetectionResult record"
```

---

### Task 2: SituationContext record

**Files:**
- Create: `api/src/main/java/io/casehub/ras/api/SituationContext.java`
- Test: `api/src/test/java/io/casehub/ras/api/SituationContextTest.java`

**Interfaces:**
- Consumes: `DetectionResult` from Task 1
- Produces: `SituationContext` record (`String situationId`, `String tenancyId`, `Instant firstSignal`, `Instant lastSignal`, `List<DetectionResult> detections`), static `initial(String, String, Instant)`, instance `withDetection(DetectionResult, Instant)`

- [ ] **Step 1: Write failing tests**

```java
package io.casehub.ras.api;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class SituationContextTest {

    private static final Instant T1 = Instant.parse("2026-06-20T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-06-20T10:05:00Z");
    private static final Instant T0 = Instant.parse("2026-06-20T09:55:00Z");

    private static final DetectionResult RESULT_A = new DetectionResult(
            "temp-ganglion", 0.8, DetectionSignal.DETECTED, Map.of("temp", 95.0));
    private static final DetectionResult RESULT_B = new DetectionResult(
            "vibration-ganglion", 0.6, DetectionSignal.WEAK, Map.of("freq", 120));

    @Test
    void initialCreatesContextWithEmptyDetections() {
        var ctx = SituationContext.initial("sit-1", "tenant-a", T1);

        assertThat(ctx.situationId()).isEqualTo("sit-1");
        assertThat(ctx.tenancyId()).isEqualTo("tenant-a");
        assertThat(ctx.firstSignal()).isEqualTo(T1);
        assertThat(ctx.lastSignal()).isEqualTo(T1);
        assertThat(ctx.detections()).isEmpty();
    }

    @Test
    void withDetectionAppendsAndUpdatesLastSignal() {
        var ctx = SituationContext.initial("sit-1", "tenant-a", T1)
                .withDetection(RESULT_A, T2);

        assertThat(ctx.detections()).containsExactly(RESULT_A);
        assertThat(ctx.firstSignal()).isEqualTo(T1);
        assertThat(ctx.lastSignal()).isEqualTo(T2);
    }

    @Test
    void withDetectionHandlesOutOfOrderEarlierEvent() {
        var ctx = SituationContext.initial("sit-1", "tenant-a", T1)
                .withDetection(RESULT_A, T0);

        assertThat(ctx.firstSignal()).isEqualTo(T0);
        assertThat(ctx.lastSignal()).isEqualTo(T1);
    }

    @Test
    void withDetectionHandlesOutOfOrderLaterEvent() {
        var ctx = SituationContext.initial("sit-1", "tenant-a", T1)
                .withDetection(RESULT_A, T2)
                .withDetection(RESULT_B, T1);

        assertThat(ctx.firstSignal()).isEqualTo(T1);
        assertThat(ctx.lastSignal()).isEqualTo(T2);
        assertThat(ctx.detections()).containsExactly(RESULT_A, RESULT_B);
    }

    @Test
    void withDetectionIsImmutable() {
        var original = SituationContext.initial("sit-1", "tenant-a", T1);
        var updated = original.withDetection(RESULT_A, T2);

        assertThat(original.detections()).isEmpty();
        assertThat(updated.detections()).hasSize(1);
    }

    @Test
    void nullSituationIdIsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> SituationContext.initial(null, "tenant-a", T1))
                .withMessage("situationId");
    }

    @Test
    void nullTenancyIdIsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> SituationContext.initial("sit-1", null, T1))
                .withMessage("tenancyId");
    }

    @Test
    void detectionsAreDefensivelyCopied() {
        var mutableDetections = new ArrayList<>(List.of(RESULT_A));
        var ctx = new SituationContext("sit-1", "tenant-a", T1, T1, mutableDetections);
        mutableDetections.add(RESULT_B);
        assertThat(ctx.detections()).hasSize(1);
    }

    @Test
    void nullDetectionsNormalisedToEmptyList() {
        var ctx = new SituationContext("sit-1", "tenant-a", T1, T1, null);
        assertThat(ctx.detections()).isNotNull().isEmpty();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl api -Dtest=SituationContextTest`
Expected: compilation failure.

- [ ] **Step 3: Implement SituationContext**

`api/src/main/java/io/casehub/ras/api/SituationContext.java`:
```java
package io.casehub.ras.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record SituationContext(
        String situationId,
        String tenancyId,
        Instant firstSignal,
        Instant lastSignal,
        List<DetectionResult> detections
) {
    public SituationContext {
        Objects.requireNonNull(situationId, "situationId");
        Objects.requireNonNull(tenancyId, "tenancyId");
        Objects.requireNonNull(firstSignal, "firstSignal");
        Objects.requireNonNull(lastSignal, "lastSignal");
        detections = detections != null ? List.copyOf(detections) : List.of();
    }

    public static SituationContext initial(String situationId, String tenancyId, Instant eventTime) {
        return new SituationContext(situationId, tenancyId, eventTime, eventTime, List.of());
    }

    public SituationContext withDetection(DetectionResult result, Instant eventTime) {
        var newDetections = new ArrayList<>(detections);
        newDetections.add(result);
        Instant newFirst = eventTime.isBefore(firstSignal) ? eventTime : firstSignal;
        Instant newLast = eventTime.isAfter(lastSignal) ? eventTime : lastSignal;
        return new SituationContext(situationId, tenancyId, newFirst, newLast, newDetections);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn --batch-mode test -pl api -Dtest=SituationContextTest`
Expected: all 9 tests PASS.

- [ ] **Step 5: Commit**

```
git add api/src/
git commit -m "feat(casehub-ras#1): SituationContext record with out-of-order event handling"
```

---

### Task 3: ChainMode sealed interface, CaseTriggerConfig, and SituationDefinition

**Files:**
- Create: `api/src/main/java/io/casehub/ras/api/ChainMode.java`
- Create: `api/src/main/java/io/casehub/ras/api/CaseTriggerConfig.java`
- Create: `api/src/main/java/io/casehub/ras/api/SituationDefinition.java`
- Test: `api/src/test/java/io/casehub/ras/api/ChainModeTest.java`
- Test: `api/src/test/java/io/casehub/ras/api/CaseTriggerConfigTest.java`
- Test: `api/src/test/java/io/casehub/ras/api/SituationDefinitionTest.java`

**Interfaces:**
- Consumes: `CaseTriggerConfig`, `ChainMode` (within this task)
- Produces: `ChainMode` sealed interface (And, Or, Threshold, Sequence, Count), `CaseTriggerConfig` record, `SituationDefinition` record

- [ ] **Step 1: Write failing tests for ChainMode**

```java
package io.casehub.ras.api;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class ChainModeTest {

    @Test
    void andWithValidGanglia() {
        var and = new ChainMode.And(Set.of("g1", "g2"));
        assertThat(and.requiredGanglia()).containsExactlyInAnyOrder("g1", "g2");
    }

    @Test
    void andRejectsEmptySet() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChainMode.And(Set.of()))
                .withMessageContaining("must not be empty");
    }

    @Test
    void andRejectsNull() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChainMode.And(null))
                .withMessageContaining("must not be empty");
    }

    @Test
    void orRejectsEmptySet() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChainMode.Or(Set.of()))
                .withMessageContaining("must not be empty");
    }

    @Test
    void thresholdRejectsZeroConfidence() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChainMode.Threshold(Set.of("g1"), 0.0))
                .withMessageContaining("0.0");
    }

    @Test
    void thresholdRejectsNegativeConfidence() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChainMode.Threshold(Set.of("g1"), -1.0))
                .withMessageContaining("-1.0");
    }

    @Test
    void thresholdAcceptsValuesAboveOne() {
        var threshold = new ChainMode.Threshold(Set.of("g1", "g2"), 2.5);
        assertThat(threshold.minConfidence()).isEqualTo(2.5);
    }

    @Test
    void thresholdRejectsEmptyGanglia() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChainMode.Threshold(Set.of(), 0.5))
                .withMessageContaining("must not be empty");
    }

    @Test
    void sequenceRejectsEmptyList() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChainMode.Sequence(List.of()))
                .withMessageContaining("must not be empty");
    }

    @Test
    void sequencePreservesOrder() {
        var seq = new ChainMode.Sequence(List.of("g1", "g2", "g3"));
        assertThat(seq.orderedGanglia()).containsExactly("g1", "g2", "g3");
    }

    @Test
    void countRejectsZero() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChainMode.Count("g1", 0))
                .withMessageContaining("0");
    }

    @Test
    void countRejectsNullGanglionId() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ChainMode.Count(null, 3))
                .withMessage("ganglionId");
    }

    @Test
    void andIsDefensivelyCopied() {
        var mutable = new java.util.HashSet<>(Set.of("g1"));
        var and = new ChainMode.And(mutable);
        mutable.add("g2");
        assertThat(and.requiredGanglia()).containsExactly("g1");
    }

    @Test
    void sealedInterfacePermitsAllVariants() {
        ChainMode and = new ChainMode.And(Set.of("g1"));
        ChainMode or = new ChainMode.Or(Set.of("g1"));
        ChainMode threshold = new ChainMode.Threshold(Set.of("g1"), 0.5);
        ChainMode sequence = new ChainMode.Sequence(List.of("g1"));
        ChainMode count = new ChainMode.Count("g1", 1);

        assertThat(and).isInstanceOf(ChainMode.class);
        assertThat(or).isInstanceOf(ChainMode.class);
        assertThat(threshold).isInstanceOf(ChainMode.class);
        assertThat(sequence).isInstanceOf(ChainMode.class);
        assertThat(count).isInstanceOf(ChainMode.class);
    }
}
```

- [ ] **Step 2: Write failing tests for CaseTriggerConfig**

```java
package io.casehub.ras.api;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class CaseTriggerConfigTest {

    @Test
    void validConfigIsCreated() {
        var config = new CaseTriggerConfig("io.casehub", "maintenance", "1.0",
                Map.of("priority", "HIGH"));

        assertThat(config.caseNamespace()).isEqualTo("io.casehub");
        assertThat(config.caseName()).isEqualTo("maintenance");
        assertThat(config.caseVersion()).isEqualTo("1.0");
        assertThat(config.baseCaseData()).containsEntry("priority", "HIGH");
    }

    @Test
    void nullNamespaceIsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new CaseTriggerConfig(null, "name", "1.0", Map.of()))
                .withMessage("caseNamespace");
    }

    @Test
    void nullNameIsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new CaseTriggerConfig("ns", null, "1.0", Map.of()))
                .withMessage("caseName");
    }

    @Test
    void nullVersionIsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new CaseTriggerConfig("ns", "name", null, Map.of()))
                .withMessage("caseVersion");
    }

    @Test
    void nullBaseCaseDataNormalisedToEmptyMap() {
        var config = new CaseTriggerConfig("ns", "name", "1.0", null);
        assertThat(config.baseCaseData()).isNotNull().isEmpty();
    }
}
```

- [ ] **Step 3: Write failing tests for SituationDefinition**

```java
package io.casehub.ras.api;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class SituationDefinitionTest {

    private static final CaseTriggerConfig TRIGGER = new CaseTriggerConfig(
            "io.casehub", "maintenance", "1.0", Map.of());
    private static final ChainMode CHAIN = new ChainMode.Or(Set.of("g1"));

    @Test
    void validDefinitionIsCreated() {
        var def = new SituationDefinition("equipment-failure",
                Set.of("iot.temperature"), Duration.ofMinutes(10), CHAIN, TRIGGER);

        assertThat(def.situationId()).isEqualTo("equipment-failure");
        assertThat(def.eventTypes()).containsExactly("iot.temperature");
        assertThat(def.correlationWindow()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void nullCorrelationWindowMeansPersistent() {
        var def = new SituationDefinition("persistent-sit",
                Set.of("iot.temperature"), null, CHAIN, TRIGGER);
        assertThat(def.correlationWindow()).isNull();
    }

    @Test
    void emptyEventTypesRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SituationDefinition("sit-1",
                        Set.of(), null, CHAIN, TRIGGER))
                .withMessageContaining("must not be empty");
    }

    @Test
    void nullEventTypesRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SituationDefinition("sit-1",
                        null, null, CHAIN, TRIGGER))
                .withMessageContaining("must not be empty");
    }

    @Test
    void zeroCorrelationWindowRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SituationDefinition("sit-1",
                        Set.of("type"), Duration.ZERO, CHAIN, TRIGGER))
                .withMessageContaining("positive");
    }

    @Test
    void negativeCorrelationWindowRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SituationDefinition("sit-1",
                        Set.of("type"), Duration.ofMinutes(-5), CHAIN, TRIGGER))
                .withMessageContaining("positive");
    }

    @Test
    void nullChainModeRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new SituationDefinition("sit-1",
                        Set.of("type"), null, null, TRIGGER))
                .withMessage("chainMode");
    }

    @Test
    void nullTriggerConfigRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new SituationDefinition("sit-1",
                        Set.of("type"), null, CHAIN, null))
                .withMessage("triggerConfig");
    }
}
```

- [ ] **Step 4: Run all tests to verify they fail**

Run: `mvn --batch-mode test -pl api -Dtest="ChainModeTest,CaseTriggerConfigTest,SituationDefinitionTest"`
Expected: compilation failure.

- [ ] **Step 5: Implement ChainMode, CaseTriggerConfig, SituationDefinition**

`api/src/main/java/io/casehub/ras/api/ChainMode.java`:
```java
package io.casehub.ras.api;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public sealed interface ChainMode {

    record And(Set<String> requiredGanglia) implements ChainMode {
        public And {
            if (requiredGanglia == null || requiredGanglia.isEmpty()) {
                throw new IllegalArgumentException("requiredGanglia must not be empty");
            }
            requiredGanglia = Set.copyOf(requiredGanglia);
        }
    }

    record Or(Set<String> ganglia) implements ChainMode {
        public Or {
            if (ganglia == null || ganglia.isEmpty()) {
                throw new IllegalArgumentException("ganglia must not be empty");
            }
            ganglia = Set.copyOf(ganglia);
        }
    }

    record Threshold(Set<String> ganglia, double minConfidence) implements ChainMode {
        public Threshold {
            if (ganglia == null || ganglia.isEmpty()) {
                throw new IllegalArgumentException("ganglia must not be empty");
            }
            ganglia = Set.copyOf(ganglia);
            if (minConfidence <= 0.0) {
                throw new IllegalArgumentException(
                        "minConfidence must be > 0.0, got: " + minConfidence);
            }
        }
    }

    record Sequence(List<String> orderedGanglia) implements ChainMode {
        public Sequence {
            if (orderedGanglia == null || orderedGanglia.isEmpty()) {
                throw new IllegalArgumentException("orderedGanglia must not be empty");
            }
            orderedGanglia = List.copyOf(orderedGanglia);
        }
    }

    record Count(String ganglionId, int requiredCount) implements ChainMode {
        public Count {
            Objects.requireNonNull(ganglionId, "ganglionId");
            if (requiredCount < 1) {
                throw new IllegalArgumentException(
                        "requiredCount must be >= 1, got: " + requiredCount);
            }
        }
    }
}
```

`api/src/main/java/io/casehub/ras/api/CaseTriggerConfig.java`:
```java
package io.casehub.ras.api;

import java.util.Map;
import java.util.Objects;

public record CaseTriggerConfig(
        String caseNamespace,
        String caseName,
        String caseVersion,
        Map<String, Object> baseCaseData
) {
    public CaseTriggerConfig {
        Objects.requireNonNull(caseNamespace, "caseNamespace");
        Objects.requireNonNull(caseName, "caseName");
        Objects.requireNonNull(caseVersion, "caseVersion");
        baseCaseData = baseCaseData != null ? Map.copyOf(baseCaseData) : Map.of();
    }
}
```

`api/src/main/java/io/casehub/ras/api/SituationDefinition.java`:
```java
package io.casehub.ras.api;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

public record SituationDefinition(
        String situationId,
        Set<String> eventTypes,
        Duration correlationWindow,
        ChainMode chainMode,
        CaseTriggerConfig triggerConfig
) {
    public SituationDefinition {
        Objects.requireNonNull(situationId, "situationId");
        Objects.requireNonNull(chainMode, "chainMode");
        Objects.requireNonNull(triggerConfig, "triggerConfig");
        if (eventTypes == null || eventTypes.isEmpty()) {
            throw new IllegalArgumentException("eventTypes must not be empty");
        }
        eventTypes = Set.copyOf(eventTypes);
        if (correlationWindow != null
                && (correlationWindow.isZero() || correlationWindow.isNegative())) {
            throw new IllegalArgumentException(
                    "correlationWindow must be positive when set, got: " + correlationWindow);
        }
    }
}
```

- [ ] **Step 6: Run all tests to verify they pass**

Run: `mvn --batch-mode test -pl api`
Expected: all tests in Task 1–3 PASS.

- [ ] **Step 7: Commit**

```
git add api/src/
git commit -m "feat(casehub-ras#1): ChainMode sealed interface, CaseTriggerConfig, SituationDefinition"
```

---

### Task 4: Ganglion SPI, RasTriggerPolicy SPI, TriggerDecision, and SituationStore SPI

**Files:**
- Create: `api/src/main/java/io/casehub/ras/api/Ganglion.java`
- Create: `api/src/main/java/io/casehub/ras/api/TriggerDecision.java`
- Create: `api/src/main/java/io/casehub/ras/api/RasTriggerPolicy.java`
- Create: `api/src/main/java/io/casehub/ras/api/SituationStore.java`
- Test: `api/src/test/java/io/casehub/ras/api/GanglionContractTest.java`

**Interfaces:**
- Consumes: `DetectionResult`, `SituationContext`, `SituationDefinition`, `CloudEvent` (from `casehub-platform-api`)
- Produces: `Ganglion` interface, `TriggerDecision` enum, `RasTriggerPolicy` interface, `SituationStore` interface

- [ ] **Step 1: Write Ganglion default-method contract test**

Per spi-default-method-contract-test protocol — anonymous implementation verifying `compact()` compiles without override. Compiler error = RED state.

```java
package io.casehub.ras.api;

import io.cloudevents.CloudEvent;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class GanglionContractTest {

    @Test
    void compactDefaultReturnsContextUnchanged() {
        Ganglion ganglion = new Ganglion() {
            @Override
            public String ganglionId() { return "test-ganglion"; }

            @Override
            public Set<String> handledEventTypes() { return Set.of("test.event"); }

            @Override
            public Uni<DetectionResult> detect(CloudEvent event, SituationContext context) {
                return Uni.createFrom().item(
                        new DetectionResult("test-ganglion", 0.5, DetectionSignal.DETECTED, null));
            }
        };

        var ctx = SituationContext.initial("sit-1", "tenant-a",
                java.time.Instant.parse("2026-06-20T10:00:00Z"));

        SituationContext compacted = ganglion.compact(ctx).await().indefinitely();
        assertThat(compacted).isSameAs(ctx);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn --batch-mode test -pl api -Dtest=GanglionContractTest`
Expected: compilation failure — `Ganglion` does not exist.

- [ ] **Step 3: Implement all SPIs**

`api/src/main/java/io/casehub/ras/api/Ganglion.java`:
```java
package io.casehub.ras.api;

import io.cloudevents.CloudEvent;
import io.smallrye.mutiny.Uni;
import java.util.Set;

public interface Ganglion {

    String ganglionId();

    Set<String> handledEventTypes();

    Uni<DetectionResult> detect(CloudEvent event, SituationContext context);

    default Uni<SituationContext> compact(SituationContext context) {
        return Uni.createFrom().item(context);
    }
}
```

`api/src/main/java/io/casehub/ras/api/TriggerDecision.java`:
```java
package io.casehub.ras.api;

public enum TriggerDecision {
    CREATE_CASE,
    CONTINUE_ACCUMULATING,
    DISCARD
}
```

`api/src/main/java/io/casehub/ras/api/RasTriggerPolicy.java`:
```java
package io.casehub.ras.api;

import io.smallrye.mutiny.Uni;

public interface RasTriggerPolicy {
    Uni<TriggerDecision> evaluate(SituationContext context, SituationDefinition definition);
}
```

`api/src/main/java/io/casehub/ras/api/SituationStore.java`:
```java
package io.casehub.ras.api;

import io.smallrye.mutiny.Uni;
import java.time.Instant;
import java.util.Optional;

public interface SituationStore {

    Uni<Optional<SituationContext>> find(String situationId, String tenancyId);

    Uni<Void> save(SituationContext context);

    Uni<Void> remove(String situationId, String tenancyId);

    Uni<Void> removeExpired(Instant cutoff);
}
```

- [ ] **Step 4: Run all api/ tests**

Run: `mvn --batch-mode test -pl api`
Expected: all tests PASS (Tasks 1–4).

- [ ] **Step 5: Commit**

```
git add api/src/
git commit -m "feat(casehub-ras#1): Ganglion, RasTriggerPolicy, SituationStore SPIs and TriggerDecision"
```

---

### Task 5: persistence-memory module — InMemorySituationStore

**Files:**
- Create: `persistence-memory/pom.xml`
- Create: `persistence-memory/src/main/java/io/casehub/ras/memory/InMemorySituationStore.java`
- Test: `persistence-memory/src/test/java/io/casehub/ras/memory/InMemorySituationStoreTest.java`
- Modify: `pom.xml` (parent) — add `<module>persistence-memory</module>` and dependency management entry

**Interfaces:**
- Consumes: `SituationStore` SPI, `SituationContext` from Task 2/4
- Produces: `InMemorySituationStore` — `@ApplicationScoped @Alternative @Priority(1)` CDI bean

- [ ] **Step 1: Create persistence-memory/pom.xml and add to parent**

`persistence-memory/pom.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>io.casehub</groupId>
        <artifactId>casehub-ras-parent</artifactId>
        <version>0.2-SNAPSHOT</version>
    </parent>
    <artifactId>casehub-ras-memory</artifactId>
    <name>CaseHub RAS :: Persistence Memory</name>
    <description>In-memory SituationStore — ConcurrentHashMap-backed, zero-config default.</description>
    <dependencies>
        <dependency>
            <groupId>io.casehub</groupId>
            <artifactId>casehub-ras-api</artifactId>
        </dependency>
        <dependency>
            <groupId>io.smallrye.reactive</groupId>
            <artifactId>mutiny</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>jakarta.inject</groupId>
            <artifactId>jakarta.inject-api</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>jakarta.enterprise</groupId>
            <artifactId>jakarta.enterprise.cdi-api</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>jakarta.annotation</groupId>
            <artifactId>jakarta.annotation-api</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <groupId>io.smallrye</groupId>
                <artifactId>jandex-maven-plugin</artifactId>
                <version>${jandex-maven-plugin.version}</version>
                <executions><execution><id>make-index</id><goals><goal>jandex</goal></goals></execution></executions>
            </plugin>
        </plugins>
    </build>
</project>
```

Add to parent `pom.xml` `<modules>`:
```xml
<module>persistence-memory</module>
```

Add to parent `pom.xml` `<dependencyManagement>`:
```xml
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-ras-memory</artifactId>
    <version>${project.version}</version>
</dependency>
```

- [ ] **Step 2: Write failing tests for InMemorySituationStore**

```java
package io.casehub.ras.memory;

import io.casehub.ras.api.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class InMemorySituationStoreTest {

    private InMemorySituationStore store;

    private static final Instant T1 = Instant.parse("2026-06-20T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-06-20T10:05:00Z");
    private static final Instant T3 = Instant.parse("2026-06-20T10:10:00Z");

    @BeforeEach
    void setUp() {
        store = new InMemorySituationStore();
    }

    @Test
    void findReturnsEmptyWhenNotPresent() {
        var result = store.find("unknown", "tenant-a").await().indefinitely();
        assertThat(result).isEmpty();
    }

    @Test
    void saveAndFindRoundTrip() {
        var ctx = SituationContext.initial("sit-1", "tenant-a", T1);
        store.save(ctx).await().indefinitely();

        var found = store.find("sit-1", "tenant-a").await().indefinitely();
        assertThat(found).isPresent().contains(ctx);
    }

    @Test
    void saveIsUpsert() {
        var ctx1 = SituationContext.initial("sit-1", "tenant-a", T1);
        store.save(ctx1).await().indefinitely();

        var detection = new DetectionResult("g1", 0.8, DetectionSignal.DETECTED, Map.of());
        var ctx2 = ctx1.withDetection(detection, T2);
        store.save(ctx2).await().indefinitely();

        var found = store.find("sit-1", "tenant-a").await().indefinitely();
        assertThat(found).isPresent();
        assertThat(found.get().detections()).hasSize(1);
    }

    @Test
    void tenantIsolation() {
        var ctxA = SituationContext.initial("sit-1", "tenant-a", T1);
        var ctxB = SituationContext.initial("sit-1", "tenant-b", T1);
        store.save(ctxA).await().indefinitely();
        store.save(ctxB).await().indefinitely();

        assertThat(store.find("sit-1", "tenant-a").await().indefinitely())
                .isPresent().get().extracting(SituationContext::tenancyId).isEqualTo("tenant-a");
        assertThat(store.find("sit-1", "tenant-b").await().indefinitely())
                .isPresent().get().extracting(SituationContext::tenancyId).isEqualTo("tenant-b");
    }

    @Test
    void removeDeletesEntry() {
        var ctx = SituationContext.initial("sit-1", "tenant-a", T1);
        store.save(ctx).await().indefinitely();
        store.remove("sit-1", "tenant-a").await().indefinitely();

        assertThat(store.find("sit-1", "tenant-a").await().indefinitely()).isEmpty();
    }

    @Test
    void removeNonExistentIsNoOp() {
        assertThatNoException().isThrownBy(
                () -> store.remove("nonexistent", "tenant-a").await().indefinitely());
    }

    @Test
    void removeExpiredEvictsOldEntries() {
        var old = SituationContext.initial("old-sit", "tenant-a", T1);
        var recent = SituationContext.initial("recent-sit", "tenant-a", T3);
        store.save(old).await().indefinitely();
        store.save(recent).await().indefinitely();

        store.removeExpired(T2).await().indefinitely();

        assertThat(store.find("old-sit", "tenant-a").await().indefinitely()).isEmpty();
        assertThat(store.find("recent-sit", "tenant-a").await().indefinitely()).isPresent();
    }

    @Test
    void removeExpiredIsCrossTenant() {
        var ctxA = SituationContext.initial("sit-1", "tenant-a", T1);
        var ctxB = SituationContext.initial("sit-2", "tenant-b", T1);
        store.save(ctxA).await().indefinitely();
        store.save(ctxB).await().indefinitely();

        store.removeExpired(T2).await().indefinitely();

        assertThat(store.find("sit-1", "tenant-a").await().indefinitely()).isEmpty();
        assertThat(store.find("sit-2", "tenant-b").await().indefinitely()).isEmpty();
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl persistence-memory`
Expected: compilation failure.

- [ ] **Step 4: Implement InMemorySituationStore**

`persistence-memory/src/main/java/io/casehub/ras/memory/InMemorySituationStore.java`:
```java
package io.casehub.ras.memory;

import io.casehub.ras.api.SituationContext;
import io.casehub.ras.api.SituationStore;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
@Alternative
@Priority(1)
public class InMemorySituationStore implements SituationStore {

    private record SituationKey(String situationId, String tenancyId) {}

    private final ConcurrentHashMap<SituationKey, SituationContext> store = new ConcurrentHashMap<>();

    @Override
    public Uni<Optional<SituationContext>> find(String situationId, String tenancyId) {
        return Uni.createFrom().item(Optional.ofNullable(store.get(new SituationKey(situationId, tenancyId))));
    }

    @Override
    public Uni<Void> save(SituationContext context) {
        store.put(new SituationKey(context.situationId(), context.tenancyId()), context);
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> remove(String situationId, String tenancyId) {
        store.remove(new SituationKey(situationId, tenancyId));
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> removeExpired(Instant cutoff) {
        store.entrySet().removeIf(entry -> !entry.getValue().lastSignal().isAfter(cutoff));
        return Uni.createFrom().voidItem();
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn --batch-mode test -pl persistence-memory`
Expected: all 8 tests PASS.

- [ ] **Step 6: Commit**

```
git add persistence-memory/ pom.xml
git commit -m "feat(casehub-ras#1): persistence-memory module with InMemorySituationStore"
```

---

### Task 6: Testing module fixtures, pom cleanup, and full build

**Files:**
- Create: `testing/src/main/java/io/casehub/ras/testing/MockGanglion.java`
- Create: `testing/src/main/java/io/casehub/ras/testing/FixedDetectionResult.java`
- Test: `testing/src/test/java/io/casehub/ras/testing/MockGanglionTest.java`
- Modify: `testing/pom.xml` — update description, add mutiny dep
- Modify: `api/pom.xml` — already done (engine-api removed)

**Interfaces:**
- Consumes: `Ganglion`, `DetectionResult`, `DetectionSignal`, `SituationContext`, `CloudEvent`
- Produces: `MockGanglion` (configurable test ganglion), `FixedDetectionResult` (factory methods)

- [ ] **Step 1: Update testing/pom.xml**

Replace the description and add mutiny dependency:

```xml
<description>Test fixtures — MockGanglion, FixedDetectionResult.
    Test scope only — never compile or runtime dep.</description>
```

Add mutiny as a compile dependency (testing module is itself always test-scoped):
```xml
<dependency>
    <groupId>io.smallrye.reactive</groupId>
    <artifactId>mutiny</artifactId>
</dependency>
```

- [ ] **Step 2: Write failing test for MockGanglion**

```java
package io.casehub.ras.testing;

import io.casehub.ras.api.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class MockGanglionTest {

    @Test
    void returnsConfiguredResult() {
        var expected = new DetectionResult("mock", 0.9, DetectionSignal.DETECTED, Map.of());
        var ganglion = new MockGanglion("mock", Set.of("test.event"), expected);

        var ctx = SituationContext.initial("sit-1", "tenant-a",
                Instant.parse("2026-06-20T10:00:00Z"));

        var result = ganglion.detect(null, ctx).await().indefinitely();
        assertThat(result).isEqualTo(expected);
    }

    @Test
    void recordsCallCount() {
        var expected = new DetectionResult("mock", 0.5, DetectionSignal.WEAK, Map.of());
        var ganglion = new MockGanglion("mock", Set.of("test.event"), expected);

        var ctx = SituationContext.initial("sit-1", "tenant-a",
                Instant.parse("2026-06-20T10:00:00Z"));

        ganglion.detect(null, ctx).await().indefinitely();
        ganglion.detect(null, ctx).await().indefinitely();

        assertThat(ganglion.callCount()).isEqualTo(2);
    }

    @Test
    void exposesGanglionIdAndHandledTypes() {
        var ganglion = new MockGanglion("temp-mock", Set.of("iot.temperature", "iot.pressure"),
                FixedDetectionResult.noise("temp-mock"));

        assertThat(ganglion.ganglionId()).isEqualTo("temp-mock");
        assertThat(ganglion.handledEventTypes()).containsExactlyInAnyOrder(
                "iot.temperature", "iot.pressure");
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn --batch-mode test -pl testing -Dtest=MockGanglionTest`
Expected: compilation failure.

- [ ] **Step 4: Implement MockGanglion and FixedDetectionResult**

`testing/src/main/java/io/casehub/ras/testing/FixedDetectionResult.java`:
```java
package io.casehub.ras.testing;

import io.casehub.ras.api.DetectionResult;
import io.casehub.ras.api.DetectionSignal;
import java.util.Map;

public final class FixedDetectionResult {

    private FixedDetectionResult() {}

    public static DetectionResult detected(String ganglionId, double confidence,
            Map<String, Object> evidence) {
        return new DetectionResult(ganglionId, confidence, DetectionSignal.DETECTED, evidence);
    }

    public static DetectionResult detected(String ganglionId, double confidence) {
        return detected(ganglionId, confidence, Map.of());
    }

    public static DetectionResult weak(String ganglionId, double confidence) {
        return new DetectionResult(ganglionId, confidence, DetectionSignal.WEAK, Map.of());
    }

    public static DetectionResult noise(String ganglionId) {
        return new DetectionResult(ganglionId, 0.0, DetectionSignal.NOISE, Map.of());
    }

    public static DetectionResult anti(String ganglionId, double confidence) {
        return new DetectionResult(ganglionId, confidence, DetectionSignal.ANTI, Map.of());
    }
}
```

`testing/src/main/java/io/casehub/ras/testing/MockGanglion.java`:
```java
package io.casehub.ras.testing;

import io.casehub.ras.api.*;
import io.cloudevents.CloudEvent;
import io.smallrye.mutiny.Uni;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class MockGanglion implements Ganglion {

    private final String ganglionId;
    private final Set<String> handledEventTypes;
    private final DetectionResult fixedResult;
    private final AtomicInteger calls = new AtomicInteger();

    public MockGanglion(String ganglionId, Set<String> handledEventTypes,
            DetectionResult fixedResult) {
        this.ganglionId = ganglionId;
        this.handledEventTypes = Set.copyOf(handledEventTypes);
        this.fixedResult = fixedResult;
    }

    @Override
    public String ganglionId() { return ganglionId; }

    @Override
    public Set<String> handledEventTypes() { return handledEventTypes; }

    @Override
    public Uni<DetectionResult> detect(CloudEvent event, SituationContext context) {
        calls.incrementAndGet();
        return Uni.createFrom().item(fixedResult);
    }

    public int callCount() { return calls.get(); }

    public void reset() { calls.set(0); }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn --batch-mode test -pl testing`
Expected: all 3 tests PASS.

- [ ] **Step 6: Full build — all modules**

Run: `mvn --batch-mode install`
Expected: BUILD SUCCESS across all modules (api, persistence-memory, testing, runtime, ras-drools, ras-llm — the latter three have no source yet and will produce empty JARs).

- [ ] **Step 7: Commit**

```
git add testing/ pom.xml api/pom.xml
git commit -m "feat(casehub-ras#1): MockGanglion and FixedDetectionResult test fixtures, pom cleanup"
```

---

## Self-Review

**Spec coverage:**
- §4.1 DetectionSignal → Task 1 ✓
- §4.2 DetectionResult → Task 1 ✓
- §4.3 SituationContext (initial, withDetection, out-of-order) → Task 2 ✓
- §5.1 ChainMode (all 5 variants, validation) → Task 3 ✓
- §5.3 CaseTriggerConfig → Task 3 ✓
- §5.4 SituationDefinition (eventTypes, correlationWindow validation) → Task 3 ✓
- §6 Ganglion SPI (detect, compact default) → Task 4 ✓
- §7 TriggerDecision, RasTriggerPolicy → Task 4 ✓
- §8 SituationStore SPI → Task 4 ✓
- §9.2 persistence-memory module → Task 5 ✓
- §9.3 testing module (MockGanglion, FixedDetectionResult) → Task 6 ✓
- §9.4 parent pom changes → Task 5 + Task 6 ✓
- engine-api removal from api/pom.xml → already done, verified in Task 6 build ✓

**Placeholder scan:** No TBD, TODO, or "add appropriate" language. All code is complete.

**Type consistency:** `DetectionResult` → used consistently (no situationId). `SituationContext.initial()` → used consistently (not `empty()`). `Ganglion.compact()` → returns `Uni<SituationContext>`. `correlationWindow` → `@Nullable Duration` (no Optional). All ChainMode variants use non-empty validation.
