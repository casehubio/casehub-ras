# Epic 2: RAS Runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the RAS coordination layer — CloudEvent observation, ganglion dispatch, chain mode evaluation, and case triggering.

**Architecture:** Three-layer decomposition: RasEngine (CDI `@ObservesAsync` observer, routing) → SituationEvaluator (per-situation pipeline with striped locking) → CaseTrigger SPI (engine bridge). `SituationDefinitionRegistry` indexes definitions from CDI-discovered `SituationDefinitionProvider` beans and validates ganglion capabilities at startup. `DefaultRasTriggerPolicy` evaluates chain modes via exhaustive sealed-interface pattern matching.

**Tech Stack:** Java 21, Quarkus 3.32.2, Mutiny, CDI (quarkus-arc), quarkus-scheduler, cloudevents-core 3.x/4.x, casehub-engine-api 0.2-SNAPSHOT

## Global Constraints

- **Java version:** 21 (sealed interfaces, pattern matching, records)
- **Reactive returns:** All SPIs return `Uni<T>` per spi-reactive-blocking-io protocol
- **Tier compliance:** api/ is Tier 1 (pure Java + Mutiny provided + CDI annotations provided). runtime/ is Tier 3 (full Quarkus).
- **casehub-engine-api** dependency on runtime/ only — never on api/
- **Jandex:** All modules producing CDI beans require `jandex-maven-plugin`
- **Tenancy:** All SituationContext is tenancy-scoped — no cross-tenant situation accumulation
- **Issue:** All commits reference casehubio/casehub-ras#2
- **TDD:** Write failing test first, then implementation, then verify green
- **Build verification:** `mvn --batch-mode install` must pass after each task

---

### Task 1: API Module — Identity Model and Type Changes

API-layer changes that all other modules depend on. New types: `TimestampedDetection`, `CaseTrigger`. Modified types: `SituationContext` (add `correlationKey`, change detections to `List<TimestampedDetection>`), `SituationStore` (add `correlationKey` to `find`/`remove`), `ChainMode` (add `referencedGanglia()`), `Ganglion` (update `close()` signature). All existing api/ tests are updated to compile and pass with the new signatures.

**Files:**
- Create: `api/src/main/java/io/casehub/ras/api/TimestampedDetection.java`
- Create: `api/src/main/java/io/casehub/ras/api/CaseTrigger.java`
- Create: `api/src/test/java/io/casehub/ras/api/TimestampedDetectionTest.java`
- Modify: `api/src/main/java/io/casehub/ras/api/SituationContext.java`
- Modify: `api/src/main/java/io/casehub/ras/api/SituationStore.java`
- Modify: `api/src/main/java/io/casehub/ras/api/ChainMode.java`
- Modify: `api/src/main/java/io/casehub/ras/api/Ganglion.java`
- Modify: `api/src/test/java/io/casehub/ras/api/SituationContextTest.java`
- Modify: `api/src/test/java/io/casehub/ras/api/GanglionContractTest.java`
- Modify: `api/src/test/java/io/casehub/ras/api/ChainModeTest.java`

**Interfaces:**
- Consumes: existing `DetectionResult`, `DetectionSignal`, `SituationDefinition`, `CaseTriggerConfig`, `CloudEvent`
- Produces: `TimestampedDetection(DetectionResult result, Instant eventTime)`, `CaseTrigger.fire(CaseTriggerConfig, SituationContext) → Uni<UUID>`, `SituationContext.initial(String situationId, String correlationKey, String tenancyId, Instant eventTime)`, `SituationContext.withDetection(DetectionResult result, Instant eventTime) → SituationContext`, `SituationStore.find(String situationId, String correlationKey, String tenancyId)`, `SituationStore.remove(String situationId, String correlationKey, String tenancyId)`, `ChainMode.referencedGanglia() → Set<String>`, `Ganglion.close(String situationId, String correlationKey, String tenancyId) → Uni<Void>`

- [ ] **Step 1: Create TimestampedDetection with test**

Create `api/src/test/java/io/casehub/ras/api/TimestampedDetectionTest.java`:

```java
package io.casehub.ras.api;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class TimestampedDetectionTest {

    private static final Instant T1 = Instant.parse("2026-06-20T10:00:00Z");
    private static final DetectionResult RESULT = new DetectionResult(
            "g1", 0.8, DetectionSignal.DETECTED, Map.of("key", "val"));

    @Test
    void constructionSucceeds() {
        var td = new TimestampedDetection(RESULT, T1);
        assertThat(td.result()).isSameAs(RESULT);
        assertThat(td.eventTime()).isEqualTo(T1);
    }

    @Test
    void nullResultIsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new TimestampedDetection(null, T1))
                .withMessage("result");
    }

    @Test
    void nullEventTimeIsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new TimestampedDetection(RESULT, null))
                .withMessage("eventTime");
    }
}
```

- [ ] **Step 2: Run test — verify it fails**

Run: `mvn --batch-mode test -pl api -Dtest=TimestampedDetectionTest`
Expected: FAIL — `TimestampedDetection` class does not exist

- [ ] **Step 3: Implement TimestampedDetection**

Create `api/src/main/java/io/casehub/ras/api/TimestampedDetection.java`:

```java
package io.casehub.ras.api;

import java.time.Instant;
import java.util.Objects;

public record TimestampedDetection(DetectionResult result, Instant eventTime) {
    public TimestampedDetection {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(eventTime, "eventTime");
    }
}
```

- [ ] **Step 4: Run test — verify it passes**

Run: `mvn --batch-mode test -pl api -Dtest=TimestampedDetectionTest`
Expected: PASS (3 tests)

- [ ] **Step 5: Create CaseTrigger SPI**

Create `api/src/main/java/io/casehub/ras/api/CaseTrigger.java`:

```java
package io.casehub.ras.api;

import io.smallrye.mutiny.Uni;
import java.util.UUID;

public interface CaseTrigger {
    Uni<UUID> fire(CaseTriggerConfig triggerConfig, SituationContext context);
}
```

No dedicated test — it's an interface. Coverage comes from `MockCaseTrigger` (Task 2) and `DefaultCaseTrigger` (Task 6).

- [ ] **Step 6: Update SituationContext — add correlationKey, change detections type**

Replace `api/src/main/java/io/casehub/ras/api/SituationContext.java` with:

```java
package io.casehub.ras.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record SituationContext(
        String situationId,
        String correlationKey,
        String tenancyId,
        Instant firstSignal,
        Instant lastSignal,
        List<TimestampedDetection> detections
) {
    public SituationContext {
        Objects.requireNonNull(situationId, "situationId");
        Objects.requireNonNull(correlationKey, "correlationKey");
        Objects.requireNonNull(tenancyId, "tenancyId");
        Objects.requireNonNull(firstSignal, "firstSignal");
        Objects.requireNonNull(lastSignal, "lastSignal");
        detections = detections != null ? List.copyOf(detections) : List.of();
    }

    public static SituationContext initial(String situationId, String correlationKey,
                                           String tenancyId, Instant eventTime) {
        Objects.requireNonNull(eventTime, "eventTime");
        return new SituationContext(situationId, correlationKey, tenancyId,
                                   eventTime, eventTime, List.of());
    }

    public SituationContext withDetection(DetectionResult result, Instant eventTime) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(eventTime, "eventTime");
        var td = new TimestampedDetection(result, eventTime);
        var newDetections = new ArrayList<>(detections);
        newDetections.add(td);
        Instant newFirst = eventTime.isBefore(firstSignal) ? eventTime : firstSignal;
        Instant newLast = eventTime.isAfter(lastSignal) ? eventTime : lastSignal;
        return new SituationContext(situationId, correlationKey, tenancyId,
                                   newFirst, newLast, newDetections);
    }
}
```

- [ ] **Step 7: Update SituationStore — add correlationKey to find/remove**

Replace `api/src/main/java/io/casehub/ras/api/SituationStore.java` with:

```java
package io.casehub.ras.api;

import io.smallrye.mutiny.Uni;
import java.time.Instant;
import java.util.Optional;

public interface SituationStore {

    Uni<Optional<SituationContext>> find(String situationId, String correlationKey, String tenancyId);

    Uni<Void> save(SituationContext context);

    Uni<Void> remove(String situationId, String correlationKey, String tenancyId);

    Uni<Void> removeExpired(Instant cutoff);
}
```

- [ ] **Step 8: Update ChainMode — add referencedGanglia()**

Add the default method to `api/src/main/java/io/casehub/ras/api/ChainMode.java`. Insert after line 7 (`public sealed interface ChainMode {`):

```java
    default Set<String> referencedGanglia() {
        return switch (this) {
            case And a -> a.requiredGanglia();
            case Or o -> o.ganglia();
            case Threshold t -> t.ganglia();
            case Sequence s -> Set.copyOf(s.orderedGanglia());
            case Count c -> Set.of(c.ganglionId());
        };
    }
```

- [ ] **Step 9: Update Ganglion.close() — add correlationKey**

In `api/src/main/java/io/casehub/ras/api/Ganglion.java`, change the `close` default method from:

```java
    default Uni<Void> close(String situationId, String tenancyId) {
```

to:

```java
    default Uni<Void> close(String situationId, String correlationKey, String tenancyId) {
```

- [ ] **Step 10: Update SituationContextTest**

Replace `api/src/test/java/io/casehub/ras/api/SituationContextTest.java` with the version that uses the new 4-arg `initial()`, 6-arg constructor, and `List<TimestampedDetection>` assertions:

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
        var ctx = SituationContext.initial("sit-1", "machine-42", "tenant-a", T1);

        assertThat(ctx.situationId()).isEqualTo("sit-1");
        assertThat(ctx.correlationKey()).isEqualTo("machine-42");
        assertThat(ctx.tenancyId()).isEqualTo("tenant-a");
        assertThat(ctx.firstSignal()).isEqualTo(T1);
        assertThat(ctx.lastSignal()).isEqualTo(T1);
        assertThat(ctx.detections()).isEmpty();
    }

    @Test
    void withDetectionAppendsTimestampedAndUpdatesLastSignal() {
        var ctx = SituationContext.initial("sit-1", "machine-42", "tenant-a", T1)
                .withDetection(RESULT_A, T2);

        assertThat(ctx.detections()).hasSize(1);
        assertThat(ctx.detections().getFirst().result()).isEqualTo(RESULT_A);
        assertThat(ctx.detections().getFirst().eventTime()).isEqualTo(T2);
        assertThat(ctx.firstSignal()).isEqualTo(T1);
        assertThat(ctx.lastSignal()).isEqualTo(T2);
    }

    @Test
    void withDetectionHandlesOutOfOrderEarlierEvent() {
        var ctx = SituationContext.initial("sit-1", "machine-42", "tenant-a", T1)
                .withDetection(RESULT_A, T0);

        assertThat(ctx.firstSignal()).isEqualTo(T0);
        assertThat(ctx.lastSignal()).isEqualTo(T1);
    }

    @Test
    void withDetectionHandlesOutOfOrderLaterEvent() {
        var ctx = SituationContext.initial("sit-1", "machine-42", "tenant-a", T1)
                .withDetection(RESULT_A, T2)
                .withDetection(RESULT_B, T1);

        assertThat(ctx.firstSignal()).isEqualTo(T1);
        assertThat(ctx.lastSignal()).isEqualTo(T2);
        assertThat(ctx.detections()).hasSize(2);
        assertThat(ctx.detections().get(0).result()).isEqualTo(RESULT_A);
        assertThat(ctx.detections().get(1).result()).isEqualTo(RESULT_B);
    }

    @Test
    void withDetectionIsImmutable() {
        var original = SituationContext.initial("sit-1", "machine-42", "tenant-a", T1);
        var updated = original.withDetection(RESULT_A, T2);

        assertThat(original.detections()).isEmpty();
        assertThat(updated.detections()).hasSize(1);
    }

    @Test
    void nullSituationIdIsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> SituationContext.initial(null, "key", "tenant-a", T1))
                .withMessage("situationId");
    }

    @Test
    void nullCorrelationKeyIsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> SituationContext.initial("sit-1", null, "tenant-a", T1))
                .withMessage("correlationKey");
    }

    @Test
    void nullTenancyIdIsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> SituationContext.initial("sit-1", "key", null, T1))
                .withMessage("tenancyId");
    }

    @Test
    void detectionsAreDefensivelyCopied() {
        var td = new TimestampedDetection(RESULT_A, T1);
        var mutableDetections = new ArrayList<>(List.of(td));
        var ctx = new SituationContext("sit-1", "key", "tenant-a", T1, T1, mutableDetections);
        mutableDetections.add(new TimestampedDetection(RESULT_B, T2));
        assertThat(ctx.detections()).hasSize(1);
    }

    @Test
    void nullDetectionsNormalisedToEmptyList() {
        var ctx = new SituationContext("sit-1", "key", "tenant-a", T1, T1, null);
        assertThat(ctx.detections()).isNotNull().isEmpty();
    }
}
```

- [ ] **Step 11: Update GanglionContractTest — correlationKey on initial() and close()**

Replace `api/src/test/java/io/casehub/ras/api/GanglionContractTest.java`:

```java
package io.casehub.ras.api;

import io.cloudevents.CloudEvent;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class GanglionContractTest {

    private Ganglion minimalGanglion() {
        return new Ganglion() {
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
    }

    @Test
    void compactDefaultReturnsContextUnchanged() {
        Ganglion ganglion = minimalGanglion();
        var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a",
                java.time.Instant.parse("2026-06-20T10:00:00Z"));

        SituationContext compacted = ganglion.compact(ctx).await().indefinitely();
        assertThat(compacted).isSameAs(ctx);
    }

    @Test
    void closeDefaultReturnsCompletedUni() {
        Ganglion ganglion = minimalGanglion();
        Void result = ganglion.close("sit-1", "key-1", "tenant-a").await().indefinitely();
        assertThat(result).isNull();
    }
}
```

- [ ] **Step 12: Add referencedGanglia test to ChainModeTest**

Append to `api/src/test/java/io/casehub/ras/api/ChainModeTest.java`:

```java
    @Test
    void referencedGangliaForAnd() {
        ChainMode mode = new ChainMode.And(Set.of("g1", "g2"));
        assertThat(mode.referencedGanglia()).containsExactlyInAnyOrder("g1", "g2");
    }

    @Test
    void referencedGangliaForOr() {
        ChainMode mode = new ChainMode.Or(Set.of("g3"));
        assertThat(mode.referencedGanglia()).containsExactly("g3");
    }

    @Test
    void referencedGangliaForThreshold() {
        ChainMode mode = new ChainMode.Threshold(Set.of("g1", "g4"), 1.0);
        assertThat(mode.referencedGanglia()).containsExactlyInAnyOrder("g1", "g4");
    }

    @Test
    void referencedGangliaForSequence() {
        ChainMode mode = new ChainMode.Sequence(List.of("g1", "g2", "g3"));
        assertThat(mode.referencedGanglia()).containsExactlyInAnyOrder("g1", "g2", "g3");
    }

    @Test
    void referencedGangliaForCount() {
        ChainMode mode = new ChainMode.Count("g5", 3);
        assertThat(mode.referencedGanglia()).containsExactly("g5");
    }
```

- [ ] **Step 13: Run all api/ tests — verify green**

Run: `mvn --batch-mode test -pl api`
Expected: ALL PASS — TimestampedDetectionTest (3), SituationContextTest (10), GanglionContractTest (2), ChainModeTest (17), DetectionResultTest, DetectionSignalTest, SituationDefinitionTest, CaseTriggerConfigTest

- [ ] **Step 14: Commit**

```
git add api/
git commit -m "feat(casehub-ras#2): API changes — correlationKey identity, TimestampedDetection, CaseTrigger SPI

Add correlationKey to SituationContext (three-field identity), change detections
to List<TimestampedDetection>, add correlationKey to SituationStore.find/remove
and Ganglion.close, add ChainMode.referencedGanglia(), add CaseTrigger SPI.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Cross-Module Cascade — persistence-memory, ras-drools, testing

Propagate API changes to all dependent modules. `InMemorySituationStore` gains `correlationKey`. `DroolsSessionStore`, `InMemoryDroolsSessionStore`, and `DroolsGanglion` gain `correlationKey`. `MockCaseTrigger` added to testing/. All existing tests updated and green.

**Files:**
- Modify: `persistence-memory/src/main/java/io/casehub/ras/memory/InMemorySituationStore.java`
- Modify: `persistence-memory/src/test/java/io/casehub/ras/memory/InMemorySituationStoreTest.java`
- Modify: `ras-drools/src/main/java/io/casehub/ras/drools/DroolsSessionStore.java`
- Modify: `ras-drools/src/main/java/io/casehub/ras/drools/InMemoryDroolsSessionStore.java`
- Modify: `ras-drools/src/main/java/io/casehub/ras/drools/DroolsGanglion.java`
- Modify: `ras-drools/src/test/java/io/casehub/ras/drools/InMemoryDroolsSessionStoreTest.java`
- Modify: `ras-drools/src/test/java/io/casehub/ras/drools/DroolsGanglionTest.java`
- Create: `testing/src/main/java/io/casehub/ras/testing/MockCaseTrigger.java`
- Create: `testing/src/test/java/io/casehub/ras/testing/MockCaseTriggerTest.java`
- Modify: `testing/src/test/java/io/casehub/ras/testing/MockGanglionTest.java`

**Interfaces:**
- Consumes: all api/ changes from Task 1
- Produces: `MockCaseTrigger.fire(CaseTriggerConfig, SituationContext) → Uni<UUID>`, `MockCaseTrigger.firedCases() → List<FiredCase>`, `MockCaseTrigger.reset()`, `MockCaseTrigger.FiredCase(UUID caseId, CaseTriggerConfig triggerConfig, SituationContext context)`

- [ ] **Step 1: Update InMemorySituationStore — SituationKey gains correlationKey**

In `persistence-memory/src/main/java/io/casehub/ras/memory/InMemorySituationStore.java`:

Change `SituationKey` record to:
```java
    private record SituationKey(String situationId, String correlationKey, String tenancyId) {}
```

Update `find()`:
```java
    @Override
    public Uni<Optional<SituationContext>> find(String situationId, String correlationKey, String tenancyId) {
        return Uni.createFrom().item(Optional.ofNullable(store.get(new SituationKey(situationId, correlationKey, tenancyId))));
    }
```

Update `save()` — use `context.correlationKey()`:
```java
    @Override
    public Uni<Void> save(SituationContext context) {
        store.put(new SituationKey(context.situationId(), context.correlationKey(), context.tenancyId()), context);
        return Uni.createFrom().voidItem();
    }
```

Update `remove()`:
```java
    @Override
    public Uni<Void> remove(String situationId, String correlationKey, String tenancyId) {
        store.remove(new SituationKey(situationId, correlationKey, tenancyId));
        return Uni.createFrom().voidItem();
    }
```

- [ ] **Step 2: Update InMemorySituationStoreTest — add correlationKey to all calls**

In all `SituationContext.initial()` calls, add `"key-1"` as the second argument (after situationId, before tenancyId).

In all `store.find()` and `store.remove()` calls, add `"key-1"` as the second argument.

Add a new test for correlationKey isolation:

```java
    @Test
    void correlationKeyIsolation() {
        var ctx1 = SituationContext.initial("sit-1", "machine-1", "tenant-a", T1);
        var ctx2 = SituationContext.initial("sit-1", "machine-2", "tenant-a", T1);
        store.save(ctx1).await().indefinitely();
        store.save(ctx2).await().indefinitely();

        assertThat(store.find("sit-1", "machine-1", "tenant-a").await().indefinitely())
                .isPresent().get().extracting(SituationContext::correlationKey).isEqualTo("machine-1");
        assertThat(store.find("sit-1", "machine-2", "tenant-a").await().indefinitely())
                .isPresent().get().extracting(SituationContext::correlationKey).isEqualTo("machine-2");
    }
```

- [ ] **Step 3: Update DroolsSessionStore — add correlationKey**

Replace `ras-drools/src/main/java/io/casehub/ras/drools/DroolsSessionStore.java`:

```java
package io.casehub.ras.drools;

import org.kie.api.runtime.KieSession;
import java.util.Optional;

public interface DroolsSessionStore {

    Optional<KieSession> get(String ganglionId, String situationId, String correlationKey, String tenancyId);

    void put(String ganglionId, String situationId, String correlationKey, String tenancyId, KieSession session);

    void remove(String ganglionId, String situationId, String correlationKey, String tenancyId);
}
```

- [ ] **Step 4: Update InMemoryDroolsSessionStore — SessionKey gains correlationKey**

In `ras-drools/src/main/java/io/casehub/ras/drools/InMemoryDroolsSessionStore.java`:

Change `SessionKey` to:
```java
    private record SessionKey(String ganglionId, String situationId, String correlationKey, String tenancyId) {}
```

Update all three methods to pass `correlationKey` through to `SessionKey` constructor.

- [ ] **Step 5: Update DroolsGanglion — close() and detect() correlationKey**

In `ras-drools/src/main/java/io/casehub/ras/drools/DroolsGanglion.java`:

Update `detect()` — everywhere `sessionStore.get/put/remove` is called, add `context.correlationKey()` between `situationId` and `tenancyId`.

Specifically:
- Line 58-59: `sessionStore.get(config.ganglionId(), situationId, tenancyId)` → `sessionStore.get(config.ganglionId(), situationId, context.correlationKey(), tenancyId)`
- Line 85: `sessionStore.remove(config.ganglionId(), situationId, tenancyId)` → `sessionStore.remove(config.ganglionId(), situationId, context.correlationKey(), tenancyId)`
- Line 95: `sessionStore.put(config.ganglionId(), situationId, tenancyId, session)` → `sessionStore.put(config.ganglionId(), situationId, context.correlationKey(), tenancyId, session)`

Update `close()`:
```java
    @Override
    public Uni<Void> close(String situationId, String correlationKey, String tenancyId) {
        sessionStore.remove(config.ganglionId(), situationId, correlationKey, tenancyId);
        return Uni.createFrom().voidItem();
    }
```

- [ ] **Step 6: Update all ras-drools tests — correlationKey in all calls**

In `InMemoryDroolsSessionStoreTest.java`: add `"key-1"` as third argument to all `store.get/put/remove` calls.

In `DroolsGanglionTest.java`:
- Update `testContext()` helper: `SituationContext.initial("sit-1", "key-1", "tenant-a", ...)`
- Update all `sessionStore.get("test-ganglion", "sit-1", "tenant-a")` → `sessionStore.get("test-ganglion", "sit-1", "key-1", "tenant-a")`
- Update `ganglion.close("sit-1", "tenant-a")` → `ganglion.close("sit-1", "key-1", "tenant-a")`

- [ ] **Step 7: Create MockCaseTrigger**

Create `testing/src/main/java/io/casehub/ras/testing/MockCaseTrigger.java`:

```java
package io.casehub.ras.testing;

import io.casehub.ras.api.CaseTrigger;
import io.casehub.ras.api.CaseTriggerConfig;
import io.casehub.ras.api.SituationContext;
import io.smallrye.mutiny.Uni;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public class MockCaseTrigger implements CaseTrigger {

    private final List<FiredCase> firedCases = new CopyOnWriteArrayList<>();

    @Override
    public Uni<UUID> fire(CaseTriggerConfig triggerConfig, SituationContext context) {
        UUID caseId = UUID.randomUUID();
        firedCases.add(new FiredCase(caseId, triggerConfig, context));
        return Uni.createFrom().item(caseId);
    }

    public List<FiredCase> firedCases() { return List.copyOf(firedCases); }

    public void reset() { firedCases.clear(); }

    public record FiredCase(UUID caseId, CaseTriggerConfig triggerConfig, SituationContext context) {}
}
```

- [ ] **Step 8: Create MockCaseTriggerTest**

Create `testing/src/test/java/io/casehub/ras/testing/MockCaseTriggerTest.java`:

```java
package io.casehub.ras.testing;

import io.casehub.ras.api.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class MockCaseTriggerTest {

    private static final Instant T1 = Instant.parse("2026-06-25T10:00:00Z");

    @Test
    void fireRecordsCaseAndReturnsId() {
        var trigger = new MockCaseTrigger();
        var config = new CaseTriggerConfig("ns", "case-name", "1.0", Map.of());
        var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);

        var caseId = trigger.fire(config, ctx).await().indefinitely();

        assertThat(caseId).isNotNull();
        assertThat(trigger.firedCases()).hasSize(1);
        var fired = trigger.firedCases().getFirst();
        assertThat(fired.caseId()).isEqualTo(caseId);
        assertThat(fired.triggerConfig()).isEqualTo(config);
        assertThat(fired.context()).isEqualTo(ctx);
    }

    @Test
    void resetClearsFiredCases() {
        var trigger = new MockCaseTrigger();
        var config = new CaseTriggerConfig("ns", "name", "1.0", Map.of());
        var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);
        trigger.fire(config, ctx).await().indefinitely();

        trigger.reset();

        assertThat(trigger.firedCases()).isEmpty();
    }

    @Test
    void multipleFiresAccumulate() {
        var trigger = new MockCaseTrigger();
        var config = new CaseTriggerConfig("ns", "name", "1.0", Map.of());
        var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);
        trigger.fire(config, ctx).await().indefinitely();
        trigger.fire(config, ctx).await().indefinitely();

        assertThat(trigger.firedCases()).hasSize(2);
    }
}
```

- [ ] **Step 9: Update MockGanglionTest — correlationKey on initial()**

In `testing/src/test/java/io/casehub/ras/testing/MockGanglionTest.java`:
- Change `SituationContext.initial("sit-1", "tenant-a", ...)` → `SituationContext.initial("sit-1", "key-1", "tenant-a", ...)`

- [ ] **Step 10: Run full build — verify all modules green**

Run: `mvn --batch-mode install`
Expected: ALL PASS — api/, persistence-memory/, ras-drools/, testing/ all compile and tests pass.

- [ ] **Step 11: Commit**

```
git add persistence-memory/ ras-drools/ testing/
git commit -m "feat(casehub-ras#2): cross-module cascade — correlationKey propagation, MockCaseTrigger

Update InMemorySituationStore, DroolsSessionStore, InMemoryDroolsSessionStore,
DroolsGanglion for correlationKey. Add MockCaseTrigger to testing/.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Runtime Foundation — Provider SPI, Registry, CorrelationKeyExtractor

Create the runtime source tree and implement the definition provider SPI, correlation key extraction, and the startup registry that validates and indexes everything.

**Files:**
- Create: `runtime/src/main/java/io/casehub/ras/runtime/CorrelationKeyExtractor.java`
- Create: `runtime/src/main/java/io/casehub/ras/runtime/DefaultCorrelationKeyExtractor.java`
- Create: `runtime/src/main/java/io/casehub/ras/runtime/SituationRegistration.java`
- Create: `runtime/src/main/java/io/casehub/ras/runtime/SituationDefinitionProvider.java`
- Create: `runtime/src/main/java/io/casehub/ras/runtime/SituationDefinitionRegistry.java`
- Create: `runtime/src/test/java/io/casehub/ras/runtime/DefaultCorrelationKeyExtractorTest.java`
- Create: `runtime/src/test/java/io/casehub/ras/runtime/SituationRegistrationTest.java`
- Create: `runtime/src/test/java/io/casehub/ras/runtime/SituationDefinitionRegistryTest.java`
- Modify: `runtime/pom.xml` (add `quarkus-scheduler`, add `casehub-ras-memory` test dep, update description)

**Interfaces:**
- Consumes: `SituationDefinition`, `ChainMode.referencedGanglia()`, `Ganglion.ganglionId()`, `Ganglion.handledEventTypes()`, `CloudEvent.getSubject()`
- Produces: `CorrelationKeyExtractor.extract(CloudEvent) → String`, `DefaultCorrelationKeyExtractor.INSTANCE`, `SituationRegistration(SituationDefinition, CorrelationKeyExtractor)`, `SituationDefinitionProvider.registrations() → List<SituationRegistration>`, `SituationDefinitionRegistry.findByEventType(String) → List<SituationRegistration>`, `SituationDefinitionRegistry.ganglion(String) → Ganglion`

- [ ] **Step 1: Update runtime/pom.xml**

Add `quarkus-scheduler` dependency, add `casehub-ras-memory` test dependency, update `<description>`:

```xml
<description>RAS engine — observes CloudEvent CDI events, routes to registered Ganglion
    implementations via SituationDefinitionRegistry, evaluates chain modes via
    DefaultRasTriggerPolicy, triggers case creation via CaseTrigger SPI.</description>
```

Add to `<dependencies>`:
```xml
<dependency><groupId>io.quarkus</groupId><artifactId>quarkus-scheduler</artifactId></dependency>
<dependency><groupId>io.casehub</groupId><artifactId>casehub-ras-memory</artifactId><scope>test</scope></dependency>
```

- [ ] **Step 2: Create CorrelationKeyExtractor**

Create `runtime/src/main/java/io/casehub/ras/runtime/CorrelationKeyExtractor.java`:

```java
package io.casehub.ras.runtime;

import io.cloudevents.CloudEvent;

@FunctionalInterface
public interface CorrelationKeyExtractor {
    String extract(CloudEvent event);
}
```

- [ ] **Step 3: Create DefaultCorrelationKeyExtractor with test**

Create `runtime/src/test/java/io/casehub/ras/runtime/DefaultCorrelationKeyExtractorTest.java`:

```java
package io.casehub.ras.runtime;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import org.junit.jupiter.api.Test;
import java.net.URI;
import static org.assertj.core.api.Assertions.*;

class DefaultCorrelationKeyExtractorTest {

    @Test
    void returnsSubjectWhenPresent() {
        CloudEvent event = CloudEventBuilder.v1()
                .withId("1").withSource(URI.create("/test")).withType("t")
                .withSubject("machine-42")
                .build();

        assertThat(DefaultCorrelationKeyExtractor.INSTANCE.extract(event))
                .isEqualTo("machine-42");
    }

    @Test
    void returnsSingletonWhenSubjectNull() {
        CloudEvent event = CloudEventBuilder.v1()
                .withId("1").withSource(URI.create("/test")).withType("t")
                .build();

        assertThat(DefaultCorrelationKeyExtractor.INSTANCE.extract(event))
                .isEqualTo("_singleton");
    }
}
```

Create `runtime/src/main/java/io/casehub/ras/runtime/DefaultCorrelationKeyExtractor.java`:

```java
package io.casehub.ras.runtime;

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

- [ ] **Step 4: Create SituationRegistration with test**

Create `runtime/src/test/java/io/casehub/ras/runtime/SituationRegistrationTest.java`:

```java
package io.casehub.ras.runtime;

import io.casehub.ras.api.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class SituationRegistrationTest {

    private static final SituationDefinition DEF = new SituationDefinition(
            "sit-1", Set.of("test.event"), Duration.ofMinutes(5),
            new ChainMode.Or(Set.of("g1")),
            new CaseTriggerConfig("ns", "name", "1.0", Map.of()));

    @Test
    void convenienceConstructorUsesDefaultExtractor() {
        var reg = new SituationRegistration(DEF);
        assertThat(reg.correlationKeyExtractor())
                .isSameAs(DefaultCorrelationKeyExtractor.INSTANCE);
    }

    @Test
    void nullExtractorDefaultsToDefault() {
        var reg = new SituationRegistration(DEF, null);
        assertThat(reg.correlationKeyExtractor())
                .isSameAs(DefaultCorrelationKeyExtractor.INSTANCE);
    }

    @Test
    void customExtractorIsUsed() {
        CorrelationKeyExtractor custom = event -> "custom-key";
        var reg = new SituationRegistration(DEF, custom);
        assertThat(reg.correlationKeyExtractor()).isSameAs(custom);
    }

    @Test
    void nullDefinitionIsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new SituationRegistration(null))
                .withMessage("definition");
    }
}
```

Create `runtime/src/main/java/io/casehub/ras/runtime/SituationRegistration.java`:

```java
package io.casehub.ras.runtime;

import io.casehub.ras.api.SituationDefinition;
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

- [ ] **Step 5: Create SituationDefinitionProvider**

Create `runtime/src/main/java/io/casehub/ras/runtime/SituationDefinitionProvider.java`:

```java
package io.casehub.ras.runtime;

import java.util.List;

public interface SituationDefinitionProvider {
    List<SituationRegistration> registrations();
}
```

- [ ] **Step 6: Create SituationDefinitionRegistry with test**

Create `runtime/src/test/java/io/casehub/ras/runtime/SituationDefinitionRegistryTest.java`:

```java
package io.casehub.ras.runtime;

import io.casehub.ras.api.*;
import io.casehub.ras.testing.MockGanglion;
import io.casehub.ras.testing.FixedDetectionResult;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class SituationDefinitionRegistryTest {

    private MockGanglion ganglion(String id, String... eventTypes) {
        return new MockGanglion(id, Set.of(eventTypes),
                FixedDetectionResult.detected(id, 0.8));
    }

    private SituationDefinition definition(String sitId, Set<String> eventTypes, ChainMode mode) {
        return new SituationDefinition(sitId, eventTypes, Duration.ofMinutes(5), mode,
                new CaseTriggerConfig("ns", "case", "1.0", Map.of()));
    }

    @Test
    void findByEventTypeReturnsMatchingRegistrations() {
        var g1 = ganglion("g1", "temp.reading");
        var def = definition("sit-1", Set.of("temp.reading"), new ChainMode.Or(Set.of("g1")));
        var reg = new SituationRegistration(def);

        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(reg)), List.of(g1));

        assertThat(registry.findByEventType("temp.reading")).containsExactly(reg);
        assertThat(registry.findByEventType("unknown.type")).isEmpty();
    }

    @Test
    void ganglionLookupWorks() {
        var g1 = ganglion("g1", "temp.reading");
        var def = definition("sit-1", Set.of("temp.reading"), new ChainMode.Or(Set.of("g1")));

        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(new SituationRegistration(def))), List.of(g1));

        assertThat(registry.ganglion("g1")).isSameAs(g1);
    }

    @Test
    void duplicateSituationIdThrows() {
        var g1 = ganglion("g1", "temp.reading");
        var def1 = definition("sit-1", Set.of("temp.reading"), new ChainMode.Or(Set.of("g1")));
        var def2 = definition("sit-1", Set.of("temp.reading"), new ChainMode.Or(Set.of("g1")));

        assertThatIllegalStateException().isThrownBy(() ->
                new SituationDefinitionRegistry(
                        List.of(() -> List.of(new SituationRegistration(def1)),
                                () -> List.of(new SituationRegistration(def2))),
                        List.of(g1)))
                .withMessageContaining("sit-1");
    }

    @Test
    void missingGanglionThrows() {
        var def = definition("sit-1", Set.of("temp.reading"),
                new ChainMode.And(Set.of("g1", "g-missing")));
        var g1 = ganglion("g1", "temp.reading");

        assertThatIllegalStateException().isThrownBy(() ->
                new SituationDefinitionRegistry(
                        List.of(() -> List.of(new SituationRegistration(def))),
                        List.of(g1)))
                .withMessageContaining("g-missing");
    }

    @Test
    void ganglionEventTypeMismatchThrows() {
        var g1 = ganglion("g1", "vibration.reading");
        var def = definition("sit-1", Set.of("temp.reading"), new ChainMode.Or(Set.of("g1")));

        assertThatIllegalStateException().isThrownBy(() ->
                new SituationDefinitionRegistry(
                        List.of(() -> List.of(new SituationRegistration(def))),
                        List.of(g1)))
                .withMessageContaining("g1")
                .withMessageContaining("temp.reading");
    }

    @Test
    void multipleEventTypesRouteCorrectly() {
        var g1 = ganglion("g1", "temp.reading", "vibration.reading");
        var def = definition("sit-1", Set.of("temp.reading", "vibration.reading"),
                new ChainMode.Or(Set.of("g1")));
        var reg = new SituationRegistration(def);

        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(reg)), List.of(g1));

        assertThat(registry.findByEventType("temp.reading")).containsExactly(reg);
        assertThat(registry.findByEventType("vibration.reading")).containsExactly(reg);
    }
}
```

Create `runtime/src/main/java/io/casehub/ras/runtime/SituationDefinitionRegistry.java`:

```java
package io.casehub.ras.runtime;

import io.casehub.ras.api.Ganglion;
import io.casehub.ras.api.SituationDefinition;
import java.util.*;
import java.util.stream.Collectors;

public class SituationDefinitionRegistry {

    private final Map<String, List<SituationRegistration>> byEventType;
    private final Map<String, Ganglion> gangliaById;

    public SituationDefinitionRegistry(List<SituationDefinitionProvider> providers,
                                       List<Ganglion> ganglia) {
        this.gangliaById = ganglia.stream()
                .collect(Collectors.toMap(Ganglion::ganglionId, g -> g));

        List<SituationRegistration> allRegistrations = new ArrayList<>();
        Set<String> seenSituationIds = new HashSet<>();
        for (var provider : providers) {
            for (var reg : provider.registrations()) {
                String sitId = reg.definition().situationId();
                if (!seenSituationIds.add(sitId)) {
                    throw new IllegalStateException(
                            "Duplicate situationId '" + sitId + "' across providers");
                }
                validate(reg.definition());
                allRegistrations.add(reg);
            }
        }

        Map<String, List<SituationRegistration>> index = new HashMap<>();
        for (var reg : allRegistrations) {
            for (String eventType : reg.definition().eventTypes()) {
                index.computeIfAbsent(eventType, k -> new ArrayList<>()).add(reg);
            }
        }
        this.byEventType = Map.copyOf(
                index.entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, e -> List.copyOf(e.getValue()))));
    }

    public List<SituationRegistration> findByEventType(String eventType) {
        return byEventType.getOrDefault(eventType, List.of());
    }

    public Ganglion ganglion(String ganglionId) {
        Ganglion g = gangliaById.get(ganglionId);
        if (g == null) {
            throw new IllegalArgumentException("Unknown ganglionId: " + ganglionId);
        }
        return g;
    }

    private void validate(SituationDefinition def) {
        for (String ganglionId : def.chainMode().referencedGanglia()) {
            Ganglion g = gangliaById.get(ganglionId);
            if (g == null) {
                throw new IllegalStateException(
                        "Situation '" + def.situationId() + "' references unknown ganglion '" + ganglionId + "'");
            }
            Set<String> overlap = new HashSet<>(g.handledEventTypes());
            overlap.retainAll(def.eventTypes());
            if (overlap.isEmpty()) {
                throw new IllegalStateException(
                        "Ganglion '" + ganglionId + "' handles " + g.handledEventTypes()
                        + " but situation '" + def.situationId() + "' declares " + def.eventTypes()
                        + " — no overlap");
            }
        }
    }
}
```

- [ ] **Step 7: Run all runtime tests — verify green**

Run: `mvn --batch-mode test -pl runtime`
Expected: ALL PASS

- [ ] **Step 8: Commit**

```
git add runtime/
git commit -m "feat(casehub-ras#2): runtime foundation — CorrelationKeyExtractor, SituationDefinitionRegistry

SituationDefinitionProvider SPI, SituationRegistration record,
DefaultCorrelationKeyExtractor (subject or _singleton), startup registry
with situationId uniqueness, ganglion existence, and event type overlap validation.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: DefaultRasTriggerPolicy — Chain Mode Evaluation

Pure domain logic — exhaustive sealed-interface pattern match on all five ChainMode variants. Signal threshold: `isAtLeast(WEAK)`. Sequence sorts by `eventTime`. `@DefaultBean @ApplicationScoped`.

**Files:**
- Create: `runtime/src/main/java/io/casehub/ras/runtime/DefaultRasTriggerPolicy.java`
- Create: `runtime/src/test/java/io/casehub/ras/runtime/DefaultRasTriggerPolicyTest.java`

**Interfaces:**
- Consumes: `RasTriggerPolicy.evaluate(SituationContext, SituationDefinition) → Uni<TriggerDecision>`, `ChainMode` variants, `TimestampedDetection.result()`, `TimestampedDetection.eventTime()`, `DetectionResult.ganglionId()`, `DetectionResult.signal()`, `DetectionResult.confidence()`, `DetectionSignal.isAtLeast()`
- Produces: `DefaultRasTriggerPolicy` (CDI `@DefaultBean` implementing `RasTriggerPolicy`)

- [ ] **Step 1: Write tests for all five chain modes + edge cases**

Create `runtime/src/test/java/io/casehub/ras/runtime/DefaultRasTriggerPolicyTest.java`:

```java
package io.casehub.ras.runtime;

import io.casehub.ras.api.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class DefaultRasTriggerPolicyTest {

    private final DefaultRasTriggerPolicy policy = new DefaultRasTriggerPolicy();

    private static final Instant T1 = Instant.parse("2026-06-25T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-06-25T10:01:00Z");
    private static final Instant T3 = Instant.parse("2026-06-25T10:02:00Z");
    private static final CaseTriggerConfig TRIGGER = new CaseTriggerConfig("ns", "c", "1", Map.of());

    private SituationDefinition def(ChainMode mode) {
        return new SituationDefinition("sit", Set.of("e"), Duration.ofMinutes(10), mode, TRIGGER);
    }

    private SituationContext ctx(TimestampedDetection... detections) {
        var ctx = SituationContext.initial("sit", "key", "tenant", T1);
        for (var td : detections) {
            ctx = ctx.withDetection(td.result(), td.eventTime());
        }
        return ctx;
    }

    private TimestampedDetection td(String ganglionId, DetectionSignal signal, double confidence, Instant time) {
        return new TimestampedDetection(
                new DetectionResult(ganglionId, confidence, signal, Map.of()), time);
    }

    // --- AND ---

    @Test
    void andSatisfiedWhenAllGangliaFired() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1),
                    td("g2", DetectionSignal.WEAK, 0.5, T2)),
                def(new ChainMode.And(Set.of("g1", "g2")))
        ).await().indefinitely();
        assertThat(result).isEqualTo(TriggerDecision.CREATE_CASE);
    }

    @Test
    void andNotSatisfiedWhenGanglionMissing() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1)),
                def(new ChainMode.And(Set.of("g1", "g2")))
        ).await().indefinitely();
        assertThat(result).isEqualTo(TriggerDecision.CONTINUE_ACCUMULATING);
    }

    @Test
    void andIgnoresNoiseDetections() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1),
                    td("g2", DetectionSignal.NOISE, 0.0, T2)),
                def(new ChainMode.And(Set.of("g1", "g2")))
        ).await().indefinitely();
        assertThat(result).isEqualTo(TriggerDecision.CONTINUE_ACCUMULATING);
    }

    @Test
    void andIgnoresAntiDetections() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1),
                    td("g2", DetectionSignal.ANTI, 0.7, T2)),
                def(new ChainMode.And(Set.of("g1", "g2")))
        ).await().indefinitely();
        assertThat(result).isEqualTo(TriggerDecision.CONTINUE_ACCUMULATING);
    }

    // --- OR ---

    @Test
    void orSatisfiedWhenAnyGanglionFired() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1)),
                def(new ChainMode.Or(Set.of("g1", "g2")))
        ).await().indefinitely();
        assertThat(result).isEqualTo(TriggerDecision.CREATE_CASE);
    }

    @Test
    void orNotSatisfiedWithOnlyNoise() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.NOISE, 0.0, T1)),
                def(new ChainMode.Or(Set.of("g1", "g2")))
        ).await().indefinitely();
        assertThat(result).isEqualTo(TriggerDecision.CONTINUE_ACCUMULATING);
    }

    // --- THRESHOLD ---

    @Test
    void thresholdSatisfiedWhenConfidenceSumMet() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.5, T1),
                    td("g2", DetectionSignal.WEAK, 0.4, T2)),
                def(new ChainMode.Threshold(Set.of("g1", "g2"), 0.8))
        ).await().indefinitely();
        assertThat(result).isEqualTo(TriggerDecision.CREATE_CASE);
    }

    @Test
    void thresholdNotSatisfiedBelowMinConfidence() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.3, T1)),
                def(new ChainMode.Threshold(Set.of("g1", "g2"), 0.8))
        ).await().indefinitely();
        assertThat(result).isEqualTo(TriggerDecision.CONTINUE_ACCUMULATING);
    }

    @Test
    void thresholdExcludesNoiseFromSum() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.5, T1),
                    td("g2", DetectionSignal.NOISE, 0.5, T2)),
                def(new ChainMode.Threshold(Set.of("g1", "g2"), 0.8))
        ).await().indefinitely();
        assertThat(result).isEqualTo(TriggerDecision.CONTINUE_ACCUMULATING);
    }

    // --- SEQUENCE ---

    @Test
    void sequenceSatisfiedInOrder() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1),
                    td("g2", DetectionSignal.DETECTED, 0.8, T2)),
                def(new ChainMode.Sequence(List.of("g1", "g2")))
        ).await().indefinitely();
        assertThat(result).isEqualTo(TriggerDecision.CREATE_CASE);
    }

    @Test
    void sequenceFailsOutOfOrder() {
        var result = policy.evaluate(
                ctx(td("g2", DetectionSignal.DETECTED, 0.8, T1),
                    td("g1", DetectionSignal.DETECTED, 0.9, T2)),
                def(new ChainMode.Sequence(List.of("g1", "g2")))
        ).await().indefinitely();
        assertThat(result).isEqualTo(TriggerDecision.CONTINUE_ACCUMULATING);
    }

    @Test
    void sequenceSortsByEventTimeNotArrivalOrder() {
        // Arrival order: g2@T2, g1@T1. Event-time order: g1@T1, g2@T2 → sequence satisfied.
        var result = policy.evaluate(
                ctx(td("g2", DetectionSignal.DETECTED, 0.8, T2),
                    td("g1", DetectionSignal.DETECTED, 0.9, T1)),
                def(new ChainMode.Sequence(List.of("g1", "g2")))
        ).await().indefinitely();
        assertThat(result).isEqualTo(TriggerDecision.CREATE_CASE);
    }

    @Test
    void sequenceIncomplete() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1)),
                def(new ChainMode.Sequence(List.of("g1", "g2")))
        ).await().indefinitely();
        assertThat(result).isEqualTo(TriggerDecision.CONTINUE_ACCUMULATING);
    }

    // --- COUNT ---

    @Test
    void countSatisfiedWhenEnoughDetections() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1),
                    td("g1", DetectionSignal.WEAK, 0.5, T2),
                    td("g1", DetectionSignal.DETECTED, 0.8, T3)),
                def(new ChainMode.Count("g1", 3))
        ).await().indefinitely();
        assertThat(result).isEqualTo(TriggerDecision.CREATE_CASE);
    }

    @Test
    void countNotSatisfiedBelowRequired() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1),
                    td("g1", DetectionSignal.WEAK, 0.5, T2)),
                def(new ChainMode.Count("g1", 3))
        ).await().indefinitely();
        assertThat(result).isEqualTo(TriggerDecision.CONTINUE_ACCUMULATING);
    }

    @Test
    void countExcludesNoiseDetections() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1),
                    td("g1", DetectionSignal.NOISE, 0.0, T2),
                    td("g1", DetectionSignal.DETECTED, 0.8, T3)),
                def(new ChainMode.Count("g1", 3))
        ).await().indefinitely();
        assertThat(result).isEqualTo(TriggerDecision.CONTINUE_ACCUMULATING);
    }

    // --- EMPTY CONTEXT ---

    @Test
    void emptyContextNeverSatisfied() {
        var ctx = SituationContext.initial("sit", "key", "tenant", T1);
        var result = policy.evaluate(ctx, def(new ChainMode.Or(Set.of("g1"))))
                .await().indefinitely();
        assertThat(result).isEqualTo(TriggerDecision.CONTINUE_ACCUMULATING);
    }
}
```

- [ ] **Step 2: Run test — verify failure**

Run: `mvn --batch-mode test -pl runtime -Dtest=DefaultRasTriggerPolicyTest`
Expected: FAIL — class does not exist

- [ ] **Step 3: Implement DefaultRasTriggerPolicy**

Create `runtime/src/main/java/io/casehub/ras/runtime/DefaultRasTriggerPolicy.java`:

```java
package io.casehub.ras.runtime;

import io.casehub.ras.api.*;
import io.quarkus.arc.DefaultBean;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Comparator;
import java.util.List;

@DefaultBean
@ApplicationScoped
public class DefaultRasTriggerPolicy implements RasTriggerPolicy {

    @Override
    public Uni<TriggerDecision> evaluate(SituationContext context, SituationDefinition definition) {
        boolean satisfied = switch (definition.chainMode()) {
            case ChainMode.And and -> evaluateAnd(context, and);
            case ChainMode.Or or -> evaluateOr(context, or);
            case ChainMode.Threshold threshold -> evaluateThreshold(context, threshold);
            case ChainMode.Sequence sequence -> evaluateSequence(context, sequence);
            case ChainMode.Count count -> evaluateCount(context, count);
        };
        return Uni.createFrom().item(satisfied ? TriggerDecision.CREATE_CASE
                                               : TriggerDecision.CONTINUE_ACCUMULATING);
    }

    private boolean evaluateAnd(SituationContext ctx, ChainMode.And and) {
        for (String ganglionId : and.requiredGanglia()) {
            if (countQualifying(ctx, ganglionId) == 0) return false;
        }
        return true;
    }

    private boolean evaluateOr(SituationContext ctx, ChainMode.Or or) {
        for (String ganglionId : or.ganglia()) {
            if (countQualifying(ctx, ganglionId) > 0) return true;
        }
        return false;
    }

    private boolean evaluateThreshold(SituationContext ctx, ChainMode.Threshold threshold) {
        double sum = ctx.detections().stream()
                .filter(td -> threshold.ganglia().contains(td.result().ganglionId()))
                .filter(td -> td.result().signal().isAtLeast(DetectionSignal.WEAK))
                .mapToDouble(td -> td.result().confidence())
                .sum();
        return sum >= threshold.minConfidence();
    }

    private boolean evaluateSequence(SituationContext ctx, ChainMode.Sequence sequence) {
        List<String> ordered = sequence.orderedGanglia();
        List<TimestampedDetection> sorted = ctx.detections().stream()
                .filter(td -> ordered.contains(td.result().ganglionId()))
                .filter(td -> td.result().signal().isAtLeast(DetectionSignal.WEAK))
                .sorted(Comparator.comparing(TimestampedDetection::eventTime))
                .toList();

        int seqIndex = 0;
        for (var td : sorted) {
            if (td.result().ganglionId().equals(ordered.get(seqIndex))) {
                seqIndex++;
                if (seqIndex == ordered.size()) return true;
            }
        }
        return false;
    }

    private boolean evaluateCount(SituationContext ctx, ChainMode.Count count) {
        return countQualifying(ctx, count.ganglionId()) >= count.requiredCount();
    }

    private long countQualifying(SituationContext ctx, String ganglionId) {
        return ctx.detections().stream()
                .filter(td -> td.result().ganglionId().equals(ganglionId))
                .filter(td -> td.result().signal().isAtLeast(DetectionSignal.WEAK))
                .count();
    }
}
```

- [ ] **Step 4: Run test — verify green**

Run: `mvn --batch-mode test -pl runtime -Dtest=DefaultRasTriggerPolicyTest`
Expected: ALL PASS (16 tests)

- [ ] **Step 5: Commit**

```
git add runtime/src/main/java/io/casehub/ras/runtime/DefaultRasTriggerPolicy.java runtime/src/test/java/io/casehub/ras/runtime/DefaultRasTriggerPolicyTest.java
git commit -m "feat(casehub-ras#2): DefaultRasTriggerPolicy — exhaustive chain mode evaluation

@DefaultBean implementing RasTriggerPolicy. Pattern match on all five ChainMode
variants. Signal threshold isAtLeast(WEAK). Sequence sorts by eventTime.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: SituationEvaluator — Core Pipeline

The heart of the runtime. Per-situation processing with striped locking: find/create context, check window expiry, dispatch to ganglia, accumulate results, evaluate trigger, act on decision.

**Files:**
- Create: `runtime/src/main/java/io/casehub/ras/runtime/SituationEvaluator.java`
- Create: `runtime/src/test/java/io/casehub/ras/runtime/SituationEvaluatorTest.java`

**Interfaces:**
- Consumes: `SituationStore`, `RasTriggerPolicy`, `CaseTrigger`, `SituationDefinitionRegistry.ganglion()`, `Ganglion.detect()`, `Ganglion.close()`, `Ganglion.handledEventTypes()`, `SituationContext.initial()`, `SituationContext.withDetection()`, `ChainMode.referencedGanglia()`, `CloudEvent.getTime()`, `TimestampedDetection`
- Produces: `SituationEvaluator.evaluate(CloudEvent event, SituationDefinition definition, String correlationKey, String tenancyId)` — the method RasEngine calls per matched registration

- [ ] **Step 1: Write SituationEvaluatorTest — core scenarios**

Create `runtime/src/test/java/io/casehub/ras/runtime/SituationEvaluatorTest.java`:

```java
package io.casehub.ras.runtime;

import io.casehub.ras.api.*;
import io.casehub.ras.memory.InMemorySituationStore;
import io.casehub.ras.testing.FixedDetectionResult;
import io.casehub.ras.testing.MockCaseTrigger;
import io.casehub.ras.testing.MockGanglion;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class SituationEvaluatorTest {

    private InMemorySituationStore store;
    private MockCaseTrigger caseTrigger;
    private DefaultRasTriggerPolicy policy;
    private SituationEvaluator evaluator;

    private static final Instant T1 = Instant.parse("2026-06-25T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-06-25T10:01:00Z");
    private static final CaseTriggerConfig TRIGGER_CONFIG =
            new CaseTriggerConfig("ns", "case", "1.0", Map.of());

    @BeforeEach
    void setUp() {
        store = new InMemorySituationStore();
        caseTrigger = new MockCaseTrigger();
        policy = new DefaultRasTriggerPolicy();
    }

    private void buildEvaluator(List<Ganglion> ganglia, SituationDefinition def) {
        var reg = new SituationRegistration(def);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(reg)), ganglia);
        evaluator = new SituationEvaluator(store, policy, caseTrigger, registry);
    }

    private CloudEvent event(String type, Instant time) {
        return CloudEventBuilder.v1()
                .withId("evt-1")
                .withSource(URI.create("/test"))
                .withType(type)
                .withTime(OffsetDateTime.ofInstant(time, ZoneOffset.UTC))
                .build();
    }

    @Test
    void singleGanglionOrModeTriggersCase() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), new ChainMode.Or(Set.of("g1")), TRIGGER_CONFIG);
        buildEvaluator(List.of(ganglion), def);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

        assertThat(caseTrigger.firedCases()).hasSize(1);
        assertThat(store.find("sit-1", "key-1", "tenant-a").await().indefinitely()).isEmpty();
    }

    @Test
    void andModeAccumulatesUntilAllFired() {
        var g1 = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var g2 = new MockGanglion("g2", Set.of("vibration.reading"),
                FixedDetectionResult.detected("g2", 0.8));
        var def = new SituationDefinition("sit-1",
                Set.of("temp.reading", "vibration.reading"),
                Duration.ofMinutes(5), new ChainMode.And(Set.of("g1", "g2")), TRIGGER_CONFIG);
        buildEvaluator(List.of(g1, g2), def);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");
        assertThat(caseTrigger.firedCases()).isEmpty();
        assertThat(store.find("sit-1", "key-1", "tenant-a").await().indefinitely()).isPresent();

        evaluator.evaluate(event("vibration.reading", T2), def, "key-1", "tenant-a");
        assertThat(caseTrigger.firedCases()).hasSize(1);
        assertThat(store.find("sit-1", "key-1", "tenant-a").await().indefinitely()).isEmpty();
    }

    @Test
    void noiseDetectionDoesNotTrigger() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.noise("g1"));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), new ChainMode.Or(Set.of("g1")), TRIGGER_CONFIG);
        buildEvaluator(List.of(ganglion), def);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

        assertThat(caseTrigger.firedCases()).isEmpty();
        assertThat(store.find("sit-1", "key-1", "tenant-a").await().indefinitely()).isPresent();
    }

    @Test
    void windowExpiryResetsContext() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(1), new ChainMode.Count("g1", 2), TRIGGER_CONFIG);
        buildEvaluator(List.of(ganglion), def);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");
        assertThat(store.find("sit-1", "key-1", "tenant-a").await().indefinitely())
                .isPresent().get().satisfies(ctx ->
                        assertThat(ctx.detections()).hasSize(1));

        // T1 + 2 minutes > 1 minute window → expired → fresh context
        Instant expired = T1.plus(Duration.ofMinutes(2));
        evaluator.evaluate(event("temp.reading", expired), def, "key-1", "tenant-a");
        assertThat(store.find("sit-1", "key-1", "tenant-a").await().indefinitely())
                .isPresent().get().satisfies(ctx ->
                        assertThat(ctx.detections()).hasSize(1));
    }

    @Test
    void ganglionNotHandlingEventTypeIsNotDispatched() {
        var g1 = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var g2 = new MockGanglion("g2", Set.of("vibration.reading"),
                FixedDetectionResult.detected("g2", 0.8));
        var def = new SituationDefinition("sit-1",
                Set.of("temp.reading", "vibration.reading"),
                Duration.ofMinutes(5), new ChainMode.And(Set.of("g1", "g2")), TRIGGER_CONFIG);
        buildEvaluator(List.of(g1, g2), def);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

        assertThat(g1.callCount()).isEqualTo(1);
        assertThat(g2.callCount()).isEqualTo(0);
    }

    @Test
    void nullEventTimeFallsBackToProcessingTime() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), new ChainMode.Or(Set.of("g1")), TRIGGER_CONFIG);
        buildEvaluator(List.of(ganglion), def);

        CloudEvent noTime = CloudEventBuilder.v1()
                .withId("evt-1").withSource(URI.create("/test")).withType("temp.reading")
                .build();

        evaluator.evaluate(noTime, def, "key-1", "tenant-a");
        assertThat(caseTrigger.firedCases()).hasSize(1);
    }
}
```

- [ ] **Step 2: Run test — verify failure**

Run: `mvn --batch-mode test -pl runtime -Dtest=SituationEvaluatorTest`
Expected: FAIL — class does not exist

- [ ] **Step 3: Implement SituationEvaluator**

Create `runtime/src/main/java/io/casehub/ras/runtime/SituationEvaluator.java`:

```java
package io.casehub.ras.runtime;

import io.casehub.ras.api.*;
import io.cloudevents.CloudEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

@ApplicationScoped
public class SituationEvaluator {

    private static final Logger LOG = Logger.getLogger(SituationEvaluator.class.getName());

    private record SituationInstanceKey(String situationId, String correlationKey, String tenancyId) {}

    private final SituationStore store;
    private final RasTriggerPolicy triggerPolicy;
    private final CaseTrigger caseTrigger;
    private final SituationDefinitionRegistry registry;
    private final ConcurrentHashMap<SituationInstanceKey, Object> locks = new ConcurrentHashMap<>();

    @Inject
    public SituationEvaluator(SituationStore store, RasTriggerPolicy triggerPolicy,
                              CaseTrigger caseTrigger, SituationDefinitionRegistry registry) {
        this.store = store;
        this.triggerPolicy = triggerPolicy;
        this.caseTrigger = caseTrigger;
        this.registry = registry;
    }

    public void evaluate(CloudEvent event, SituationDefinition definition,
                         String correlationKey, String tenancyId) {
        String situationId = definition.situationId();
        var key = new SituationInstanceKey(situationId, correlationKey, tenancyId);
        Object lock = locks.computeIfAbsent(key, k -> new Object());

        synchronized (lock) {
            Instant eventTime = extractEventTime(event);

            SituationContext context = store.find(situationId, correlationKey, tenancyId)
                    .await().indefinitely()
                    .orElseGet(() -> SituationContext.initial(situationId, correlationKey,
                                                             tenancyId, eventTime));

            if (isExpired(context, definition)) {
                closeGanglia(definition, situationId, correlationKey, tenancyId);
                store.remove(situationId, correlationKey, tenancyId).await().indefinitely();
                context = SituationContext.initial(situationId, correlationKey, tenancyId, eventTime);
            }

            Set<String> gangliaForEvent = gangliaHandlingEventType(definition, event.getType());
            for (String ganglionId : gangliaForEvent) {
                Ganglion ganglion = registry.ganglion(ganglionId);
                DetectionResult result = ganglion.detect(event, context).await().indefinitely();
                context = context.withDetection(result, eventTime);
            }

            TriggerDecision decision = triggerPolicy.evaluate(context, definition)
                    .await().indefinitely();

            switch (decision) {
                case CREATE_CASE -> {
                    try {
                        caseTrigger.fire(definition.triggerConfig(), context).await().indefinitely();
                    } catch (RuntimeException ex) {
                        LOG.severe("CaseTrigger.fire() failed for situation '" + situationId
                                   + "': " + ex.getMessage());
                        store.save(context).await().indefinitely();
                        return;
                    }
                    closeGanglia(definition, situationId, correlationKey, tenancyId);
                    store.remove(situationId, correlationKey, tenancyId).await().indefinitely();
                    locks.remove(key);
                }
                case CONTINUE_ACCUMULATING -> store.save(context).await().indefinitely();
                case DISCARD -> {
                    closeGanglia(definition, situationId, correlationKey, tenancyId);
                    store.remove(situationId, correlationKey, tenancyId).await().indefinitely();
                    locks.remove(key);
                }
            }
        }
    }

    private Instant extractEventTime(CloudEvent event) {
        OffsetDateTime time = event.getTime();
        return time != null ? time.toInstant() : Instant.now();
    }

    private boolean isExpired(SituationContext context, SituationDefinition definition) {
        if (definition.correlationWindow() == null) return false;
        Instant cutoff = Instant.now().minus(definition.correlationWindow());
        return context.lastSignal().isBefore(cutoff);
    }

    private Set<String> gangliaHandlingEventType(SituationDefinition definition, String eventType) {
        Set<String> all = definition.chainMode().referencedGanglia();
        return all.stream()
                .filter(id -> registry.ganglion(id).handledEventTypes().contains(eventType))
                .collect(java.util.stream.Collectors.toSet());
    }

    private void closeGanglia(SituationDefinition definition,
                              String situationId, String correlationKey, String tenancyId) {
        for (String ganglionId : definition.chainMode().referencedGanglia()) {
            try {
                registry.ganglion(ganglionId).close(situationId, correlationKey, tenancyId)
                        .await().indefinitely();
            } catch (RuntimeException ex) {
                LOG.warning("Ganglion '" + ganglionId + "' close() failed: " + ex.getMessage());
            }
        }
    }
}
```

- [ ] **Step 4: Run test — verify green**

Run: `mvn --batch-mode test -pl runtime -Dtest=SituationEvaluatorTest`
Expected: ALL PASS (6 tests)

- [ ] **Step 5: Commit**

```
git add runtime/src/main/java/io/casehub/ras/runtime/SituationEvaluator.java runtime/src/test/java/io/casehub/ras/runtime/SituationEvaluatorTest.java
git commit -m "feat(casehub-ras#2): SituationEvaluator — core per-situation pipeline

Striped locking per situation instance, window expiry check, ganglion dispatch
filtered by handledEventTypes, TimestampedDetection accumulation, trigger
evaluation, case creation / save / discard.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: RasEngine, DefaultCaseTrigger, SituationExpiryJob, CLAUDE.md

The CDI entry point, engine bridge, scheduled cleanup, and project documentation updates. RasEngine is the `@ObservesAsync CloudEvent` observer. DefaultCaseTrigger resolves CaseHub beans. SituationExpiryJob runs on `@Scheduled`.

**Files:**
- Create: `runtime/src/main/java/io/casehub/ras/runtime/RasEngine.java`
- Create: `runtime/src/main/java/io/casehub/ras/runtime/DefaultCaseTrigger.java`
- Create: `runtime/src/main/java/io/casehub/ras/runtime/SituationExpiryJob.java`
- Create: `runtime/src/test/java/io/casehub/ras/runtime/RasEngineTest.java`
- Create: `runtime/src/test/java/io/casehub/ras/runtime/DefaultCaseTriggerTest.java`
- Create: `runtime/src/test/java/io/casehub/ras/runtime/SituationExpiryJobTest.java`
- Modify: `CLAUDE.md` (update runtime/ module description)

**Interfaces:**
- Consumes: `SituationDefinitionRegistry.findByEventType()`, `SituationEvaluator.evaluate()`, `SituationRegistration.correlationKeyExtractor()`, `CloudEvent.getExtension("tenancyid")`, `CloudEvent.getType()`, `CaseHub.getDefinition()`, `CaseHub.startCase(Object)`, `CaseDefinition.getNamespace/getName/getVersion()`, `SituationStore.removeExpired()`, `SituationDefinitionRegistry` (for maxWindow), `Instance<CaseHub>`, `Instance<SituationDefinitionProvider>`, `Instance<Ganglion>`
- Produces: `RasEngine.onCloudEvent(@ObservesAsync CloudEvent)`, `DefaultCaseTrigger.fire(CaseTriggerConfig, SituationContext) → Uni<UUID>`, `SituationExpiryJob.cleanup()` (scheduled)

- [ ] **Step 1: Create RasEngine with test**

Create `runtime/src/test/java/io/casehub/ras/runtime/RasEngineTest.java`:

```java
package io.casehub.ras.runtime;

import io.casehub.ras.api.*;
import io.casehub.ras.memory.InMemorySituationStore;
import io.casehub.ras.testing.FixedDetectionResult;
import io.casehub.ras.testing.MockCaseTrigger;
import io.casehub.ras.testing.MockGanglion;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class RasEngineTest {

    private static final Instant T1 = Instant.parse("2026-06-25T10:00:00Z");
    private static final CaseTriggerConfig TRIGGER =
            new CaseTriggerConfig("ns", "case", "1.0", Map.of());

    private CloudEvent event(String type, String tenancyId) {
        var builder = CloudEventBuilder.v1()
                .withId("evt-1").withSource(URI.create("/test")).withType(type)
                .withTime(OffsetDateTime.ofInstant(T1, ZoneOffset.UTC));
        if (tenancyId != null) {
            builder = builder.withExtension("tenancyid", tenancyId);
        }
        return builder.build();
    }

    @Test
    void routesEventToMatchingDefinition() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), new ChainMode.Or(Set.of("g1")), TRIGGER);
        var reg = new SituationRegistration(def);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(reg)), List.of(ganglion));
        var store = new InMemorySituationStore();
        var caseTrigger = new MockCaseTrigger();
        var evaluator = new SituationEvaluator(store, new DefaultRasTriggerPolicy(),
                caseTrigger, registry);
        var engine = new RasEngine(registry, evaluator);

        engine.onCloudEvent(event("temp.reading", "tenant-a"));

        assertThat(caseTrigger.firedCases()).hasSize(1);
    }

    @Test
    void skipsEventWithoutTenancyId() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), new ChainMode.Or(Set.of("g1")), TRIGGER);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(new SituationRegistration(def))), List.of(ganglion));
        var store = new InMemorySituationStore();
        var caseTrigger = new MockCaseTrigger();
        var evaluator = new SituationEvaluator(store, new DefaultRasTriggerPolicy(),
                caseTrigger, registry);
        var engine = new RasEngine(registry, evaluator);

        engine.onCloudEvent(event("temp.reading", null));

        assertThat(ganglion.callCount()).isEqualTo(0);
        assertThat(caseTrigger.firedCases()).isEmpty();
    }

    @Test
    void unmatchedEventTypeIsIgnored() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), new ChainMode.Or(Set.of("g1")), TRIGGER);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(new SituationRegistration(def))), List.of(ganglion));
        var store = new InMemorySituationStore();
        var caseTrigger = new MockCaseTrigger();
        var evaluator = new SituationEvaluator(store, new DefaultRasTriggerPolicy(),
                caseTrigger, registry);
        var engine = new RasEngine(registry, evaluator);

        engine.onCloudEvent(event("unknown.type", "tenant-a"));

        assertThat(ganglion.callCount()).isEqualTo(0);
    }
}
```

Create `runtime/src/main/java/io/casehub/ras/runtime/RasEngine.java`:

```java
package io.casehub.ras.runtime;

import io.cloudevents.CloudEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import java.util.List;
import java.util.logging.Logger;

@ApplicationScoped
public class RasEngine {

    private static final Logger LOG = Logger.getLogger(RasEngine.class.getName());

    private final SituationDefinitionRegistry registry;
    private final SituationEvaluator evaluator;

    @Inject
    public RasEngine(SituationDefinitionRegistry registry, SituationEvaluator evaluator) {
        this.registry = registry;
        this.evaluator = evaluator;
    }

    void onCloudEvent(@ObservesAsync CloudEvent event) {
        String tenancyId = extractTenancyId(event);
        if (tenancyId == null) {
            LOG.warning("CloudEvent without tenancyid extension — skipping: " + event.getType());
            return;
        }

        List<SituationRegistration> registrations = registry.findByEventType(event.getType());
        for (SituationRegistration reg : registrations) {
            try {
                String correlationKey = reg.correlationKeyExtractor().extract(event);
                evaluator.evaluate(event, reg.definition(), correlationKey, tenancyId);
            } catch (RuntimeException ex) {
                LOG.warning("Evaluation failed for situation '" + reg.definition().situationId()
                            + "': " + ex.getMessage());
            }
        }
    }

    private String extractTenancyId(CloudEvent event) {
        Object ext = event.getExtension("tenancyid");
        return ext != null ? ext.toString() : null;
    }
}
```

- [ ] **Step 2: Create DefaultCaseTrigger with test**

Create `runtime/src/test/java/io/casehub/ras/runtime/DefaultCaseTriggerTest.java`:

```java
package io.casehub.ras.runtime;

import io.casehub.ras.api.*;
import io.casehub.api.engine.CaseHub;
import io.casehub.api.model.CaseDefinition;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import static org.assertj.core.api.Assertions.*;

class DefaultCaseTriggerTest {

    private static final Instant T1 = Instant.parse("2026-06-25T10:00:00Z");

    private CaseHub stubCaseHub(String namespace, String name, String version) {
        return new CaseHub() {
            private final CaseDefinition def = new CaseDefinition(namespace, name, version);
            @Override
            public CaseDefinition getDefinition() { return def; }
            @Override
            public CompletionStage<UUID> startCase(Object inputData) {
                return CompletableFuture.completedFuture(UUID.randomUUID());
            }
        };
    }

    @Test
    void firesMatchingCaseHub() {
        var hub = stubCaseHub("ns", "case-name", "1.0");
        var trigger = new DefaultCaseTrigger(List.of(hub));
        var config = new CaseTriggerConfig("ns", "case-name", "1.0", Map.of());
        var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);

        var caseId = trigger.fire(config, ctx).await().indefinitely();

        assertThat(caseId).isNotNull();
    }

    @Test
    void throwsWhenNoCaseHubMatches() {
        var hub = stubCaseHub("ns", "other-case", "1.0");
        var trigger = new DefaultCaseTrigger(List.of(hub));
        var config = new CaseTriggerConfig("ns", "case-name", "1.0", Map.of());
        var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);

        assertThatIllegalStateException()
                .isThrownBy(() -> trigger.fire(config, ctx).await().indefinitely())
                .withMessageContaining("No CaseHub");
    }

    @Test
    void throwsWhenMultipleCaseHubsMatch() {
        var hub1 = stubCaseHub("ns", "case-name", "1.0");
        var hub2 = stubCaseHub("ns", "case-name", "1.0");
        var trigger = new DefaultCaseTrigger(List.of(hub1, hub2));
        var config = new CaseTriggerConfig("ns", "case-name", "1.0", Map.of());
        var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);

        assertThatIllegalStateException()
                .isThrownBy(() -> trigger.fire(config, ctx).await().indefinitely())
                .withMessageContaining("Multiple CaseHub");
    }
}
```

Create `runtime/src/main/java/io/casehub/ras/runtime/DefaultCaseTrigger.java`:

```java
package io.casehub.ras.runtime;

import io.casehub.api.engine.CaseHub;
import io.casehub.api.model.CaseDefinition;
import io.casehub.ras.api.CaseTrigger;
import io.casehub.ras.api.CaseTriggerConfig;
import io.casehub.ras.api.SituationContext;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.*;
import java.util.concurrent.CompletionStage;

@ApplicationScoped
public class DefaultCaseTrigger implements CaseTrigger {

    private final List<CaseHub> caseHubs;

    @Inject
    public DefaultCaseTrigger(Instance<CaseHub> caseHubs) {
        this.caseHubs = new ArrayList<>();
        caseHubs.forEach(this.caseHubs::add);
    }

    DefaultCaseTrigger(List<CaseHub> caseHubs) {
        this.caseHubs = List.copyOf(caseHubs);
    }

    @PostConstruct
    void warmUp() {
        for (CaseHub hub : caseHubs) {
            hub.getDefinition();
        }
    }

    @Override
    public Uni<UUID> fire(CaseTriggerConfig triggerConfig, SituationContext context) {
        CaseHub hub = findCaseHub(triggerConfig);
        Map<String, Object> inputData = buildInputData(triggerConfig, context);
        CompletionStage<UUID> cs = hub.startCase(inputData);
        return Uni.createFrom().completionStage(cs);
    }

    private CaseHub findCaseHub(CaseTriggerConfig config) {
        List<CaseHub> matches = caseHubs.stream()
                .filter(hub -> {
                    CaseDefinition def = hub.getDefinition();
                    return def.getNamespace().equals(config.caseNamespace())
                            && def.getName().equals(config.caseName())
                            && def.getVersion().equals(config.caseVersion());
                })
                .toList();
        if (matches.isEmpty()) {
            throw new IllegalStateException(
                    "No CaseHub found for (" + config.caseNamespace() + ", "
                    + config.caseName() + ", " + config.caseVersion() + ")");
        }
        if (matches.size() > 1) {
            throw new IllegalStateException(
                    "Multiple CaseHub beans match (" + config.caseNamespace() + ", "
                    + config.caseName() + ", " + config.caseVersion() + ")");
        }
        return matches.getFirst();
    }

    private Map<String, Object> buildInputData(CaseTriggerConfig config, SituationContext context) {
        Map<String, Object> data = new HashMap<>(config.baseCaseData());
        data.put("situationId", context.situationId());
        data.put("correlationKey", context.correlationKey());
        data.put("tenancyId", context.tenancyId());
        data.put("detections", context.detections());
        return data;
    }
}
```

- [ ] **Step 3: Create SituationExpiryJob with test**

Create `runtime/src/test/java/io/casehub/ras/runtime/SituationExpiryJobTest.java`:

```java
package io.casehub.ras.runtime;

import io.casehub.ras.api.*;
import io.casehub.ras.memory.InMemorySituationStore;
import io.casehub.ras.testing.FixedDetectionResult;
import io.casehub.ras.testing.MockGanglion;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class SituationExpiryJobTest {

    private static final Instant OLD = Instant.parse("2026-06-25T09:00:00Z");
    private static final Instant RECENT = Instant.parse("2026-06-25T10:55:00Z");

    @Test
    void removesExpiredSituations() {
        var store = new InMemorySituationStore();
        store.save(SituationContext.initial("sit-old", "k", "t", OLD)).await().indefinitely();
        store.save(SituationContext.initial("sit-new", "k", "t", RECENT)).await().indefinitely();

        var ganglion = new MockGanglion("g1", Set.of("e"),
                FixedDetectionResult.noise("g1"));
        var def = new SituationDefinition("sit-old", Set.of("e"), Duration.ofMinutes(5),
                new ChainMode.Or(Set.of("g1")),
                new CaseTriggerConfig("ns", "c", "1", Map.of()));
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(new SituationRegistration(def))), List.of(ganglion));
        var job = new SituationExpiryJob(store, registry);

        job.cleanup();

        assertThat(store.find("sit-old", "k", "t").await().indefinitely()).isEmpty();
        assertThat(store.find("sit-new", "k", "t").await().indefinitely()).isPresent();
    }

    @Test
    void noOpWhenAllDefinitionsPersistent() {
        var store = new InMemorySituationStore();
        store.save(SituationContext.initial("sit-1", "k", "t", OLD)).await().indefinitely();

        var ganglion = new MockGanglion("g1", Set.of("e"),
                FixedDetectionResult.noise("g1"));
        var def = new SituationDefinition("sit-1", Set.of("e"), null,
                new ChainMode.Or(Set.of("g1")),
                new CaseTriggerConfig("ns", "c", "1", Map.of()));
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(new SituationRegistration(def))), List.of(ganglion));
        var job = new SituationExpiryJob(store, registry);

        job.cleanup();

        assertThat(store.find("sit-1", "k", "t").await().indefinitely()).isPresent();
    }
}
```

Create `runtime/src/main/java/io/casehub/ras/runtime/SituationExpiryJob.java`:

```java
package io.casehub.ras.runtime;

import io.casehub.ras.api.SituationStore;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;

@ApplicationScoped
public class SituationExpiryJob {

    private final SituationStore store;
    private final SituationDefinitionRegistry registry;

    @Inject
    public SituationExpiryJob(SituationStore store, SituationDefinitionRegistry registry) {
        this.store = store;
        this.registry = registry;
    }

    @Scheduled(every = "PT5M")
    void cleanup() {
        Duration maxWindow = registry.maxCorrelationWindow();
        if (maxWindow == null) return;
        Instant cutoff = Instant.now().minus(maxWindow);
        store.removeExpired(cutoff).await().indefinitely();
    }
}
```

Add `maxCorrelationWindow()` to `SituationDefinitionRegistry`. In the constructor, compute and store the maximum:

```java
    private final Duration maxCorrelationWindow;
```

In the constructor, after building `byEventType`:
```java
        this.maxCorrelationWindow = allRegistrations.stream()
                .map(r -> r.definition().correlationWindow())
                .filter(java.util.Objects::nonNull)
                .max(java.util.Comparator.naturalOrder())
                .orElse(null);
```

Add public method:
```java
    public Duration maxCorrelationWindow() {
        return maxCorrelationWindow;
    }
```

- [ ] **Step 4: Update CLAUDE.md — runtime module description**

In `CLAUDE.md`, update the runtime/ row in the Module Structure table:

From: `RasEngine, CompositeEventCorrelator, SituationAccumulator, CaseTriggerService. Quarkus extension.`
To: `RasEngine, SituationEvaluator, DefaultRasTriggerPolicy, DefaultCaseTrigger, SituationExpiryJob. Quarkus extension.`

Also update the Ganglion SPI section to show the new `close()` signature with `correlationKey`.

Add the Epic 2 spec to the design specs list:
```
- Epic 2 Runtime: `docs/superpowers/specs/2026-06-25-epic2-ras-runtime-design.md`
```

- [ ] **Step 5: Run full build — verify everything green**

Run: `mvn --batch-mode install`
Expected: ALL PASS across all modules

- [ ] **Step 6: Commit**

```
git add runtime/ CLAUDE.md
git commit -m "feat(casehub-ras#2): RasEngine, DefaultCaseTrigger, SituationExpiryJob, CLAUDE.md

RasEngine @ObservesAsync CloudEvent with tenancyId guard and per-situation
isolation. DefaultCaseTrigger resolves CaseHub beans with zero/multi match
validation and @PostConstruct warm-up. SituationExpiryJob @Scheduled(PT5M).
CLAUDE.md updated with new runtime component names and Epic 2 spec reference.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```
