# Clustered Retry Logic Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Handle concurrent writes to the same situation instance across clustered JVMs via optimistic concurrency control with retry.

**Architecture:** Add `@Version` to `SituationEntity` and `storeVersion` to `SituationContext` for two-layer conflict detection. `JpaSituationStore.save()` performs an application-level version comparison (non-overlapping transactions) and catches Hibernate OLE/constraint violations (overlapping transactions), wrapping both in `SituationConflictException`. `SituationEvaluator.processEvent()` is restructured into two phases: detect once (Phase 1), retry read-modify-write on conflict (Phase 2).

**Tech Stack:** Java 21, Quarkus, Hibernate ORM, JPA, Flyway, Mutiny, AssertJ

## Global Constraints

- `SituationConflictException` lives in `api/` — both thrower and catcher depend on it
- `SituationContext.storeVersion` is `OptionalLong` — empty for new, populated by `find()`
- `@Version private Long version = 0L;` — Java default must match SQL `DEFAULT 0` per protocol `entity-not-null-java-default-matches-sql-default`
- `InMemorySituationStore` is unchanged — JVM-level `synchronized` locks prevent conflicts
- Detection is never retried — ganglia mutate internal state (`DroolsGanglion` KieSession, `NaiveBayesGanglion` posteriors)
- `Ganglion.compact()` must be a pure function of `SituationContext` — retried on each conflict retry
- Default max retries: 3, configurable via `ras.evaluator.max-conflict-retries`

---

### Task 1: API-level types — SituationConflictException + SituationContext.storeVersion

**Files:**
- Create: `api/src/main/java/io/casehub/ras/api/SituationConflictException.java`
- Modify: `api/src/main/java/io/casehub/ras/api/SituationContext.java`
- Modify: `api/src/main/java/io/casehub/ras/api/Ganglion.java` (Javadoc)
- Modify: `api/src/test/java/io/casehub/ras/api/SituationContextTest.java`

**Interfaces:**
- Consumes: nothing (foundation task)
- Produces:
  - `SituationConflictException(String message, Throwable cause)`
  - `SituationContext` record with new `OptionalLong storeVersion` component
  - `SituationContext.initial()` returns context with `OptionalLong.empty()` storeVersion
  - `SituationContext.withDetection()` preserves storeVersion through transformations

- [ ] **Step 1: Write failing tests for storeVersion**

Add to `SituationContextTest.java`:

```java
@Test
void initialCreatesContextWithEmptyStoreVersion() {
    var ctx = SituationContext.initial("sit-1", "key", "tenant-a", T1);
    assertThat(ctx.storeVersion()).isEmpty();
}

@Test
void withDetectionPreservesStoreVersion() {
    var ctx = new SituationContext("sit-1", "key", "tenant-a", T1, T1,
            List.of(), OptionalLong.of(5));
    var updated = ctx.withDetection(RESULT_A, T2);
    assertThat(updated.storeVersion()).hasValue(5);
}

@Test
void constructorWithNullStoreVersionIsRejected() {
    assertThatNullPointerException()
            .isThrownBy(() -> new SituationContext("sit-1", "key", "tenant-a",
                    T1, T1, List.of(), null))
            .withMessage("storeVersion");
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `/opt/homebrew/bin/mvn --batch-mode -pl api test -Dtest=SituationContextTest -DfailIfNoTests=false`
Expected: Compilation failure — `SituationContext` constructor doesn't accept `storeVersion` yet.

- [ ] **Step 3: Create SituationConflictException**

Create `api/src/main/java/io/casehub/ras/api/SituationConflictException.java`:

```java
package io.casehub.ras.api;

public class SituationConflictException extends RuntimeException {
    public SituationConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 4: Add storeVersion to SituationContext**

Modify `api/src/main/java/io/casehub/ras/api/SituationContext.java`:

```java
package io.casehub.ras.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

public record SituationContext(
        String situationId,
        String correlationKey,
        String tenancyId,
        Instant firstSignal,
        Instant lastSignal,
        List<TimestampedDetection> detections,
        OptionalLong storeVersion
) {
    public SituationContext {
        Objects.requireNonNull(situationId, "situationId");
        Objects.requireNonNull(correlationKey, "correlationKey");
        Objects.requireNonNull(tenancyId, "tenancyId");
        Objects.requireNonNull(firstSignal, "firstSignal");
        Objects.requireNonNull(lastSignal, "lastSignal");
        Objects.requireNonNull(storeVersion, "storeVersion");
        detections = detections != null ? List.copyOf(detections) : List.of();
    }

    public static SituationContext initial(String situationId, String correlationKey,
                                           String tenancyId, Instant eventTime) {
        Objects.requireNonNull(eventTime, "eventTime");
        return new SituationContext(situationId, correlationKey, tenancyId,
                                   eventTime, eventTime, List.of(), OptionalLong.empty());
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
                                   newFirst, newLast, newDetections, storeVersion);
    }
}
```

- [ ] **Step 5: Fix existing SituationContextTest callers**

Update the two direct constructor calls in `SituationContextTest.java` to pass `OptionalLong.empty()`:

```java
// detectionsAreDefensivelyCopied
var ctx = new SituationContext("sit-1", "key", "tenant-a", T1, T1,
        mutableDetections, OptionalLong.empty());

// nullDetectionsNormalisedToEmptyList
var ctx = new SituationContext("sit-1", "key", "tenant-a", T1, T1,
        null, OptionalLong.empty());
```

Add import `java.util.OptionalLong;` to the test file.

- [ ] **Step 6: Fix NaiveBayesGanglion.compact() caller**

In `runtime/src/main/java/io/casehub/ras/runtime/NaiveBayesGanglion.java` line 96, update the `new SituationContext(...)` call to preserve `storeVersion`:

```java
return Uni.createFrom().item(new SituationContext(
        context.situationId(), context.correlationKey(), context.tenancyId(),
        context.firstSignal(), context.lastSignal(), kept, context.storeVersion()));
```

- [ ] **Step 7: Fix SituationEvaluatorTest compact test caller**

In `runtime/src/test/java/io/casehub/ras/runtime/SituationEvaluatorTest.java` line 207, update the `new SituationContext(...)` call:

```java
return Uni.createFrom().item(new SituationContext(
        context.situationId(), context.correlationKey(), context.tenancyId(),
        context.firstSignal(), context.lastSignal(), List.of(latest),
        context.storeVersion()));
```

- [ ] **Step 8: Add Javadoc to Ganglion.detect()**

In `api/src/main/java/io/casehub/ras/api/Ganglion.java`, add Javadoc to `detect()`:

```java
/**
 * Detect a signal from the given event in the context of an accumulating situation.
 *
 * <p><b>Design invariant — DetectionResult portability:</b> The returned result may be
 * applied to a different {@code SituationContext} than the one passed to this method
 * (e.g. after a concurrent-modification retry). Implementations must not base detection
 * decisions on {@code context.detections()} or other accumulated state, as these may
 * differ between detection time and application time.
 */
Uni<DetectionResult> detect(CloudEvent event, SituationContext context);
```

- [ ] **Step 9: Run all api + runtime tests**

Run: `/opt/homebrew/bin/mvn --batch-mode -pl api,runtime test`
Expected: All tests pass (persistence-jpa and persistence-memory will fail compilation — that's expected, they're fixed in Task 2 and don't need fixing here since they aren't in the `-pl` list).

- [ ] **Step 10: Commit**

```bash
git add api/src/main/java/io/casehub/ras/api/SituationConflictException.java \
       api/src/main/java/io/casehub/ras/api/SituationContext.java \
       api/src/main/java/io/casehub/ras/api/Ganglion.java \
       api/src/test/java/io/casehub/ras/api/SituationContextTest.java \
       runtime/src/main/java/io/casehub/ras/runtime/NaiveBayesGanglion.java \
       runtime/src/test/java/io/casehub/ras/runtime/SituationEvaluatorTest.java
git commit -m "feat(casehub-ras#18): add SituationConflictException and storeVersion to SituationContext"
```

---

### Task 2: JPA persistence layer — @Version, migration, conflict detection

**Files:**
- Create: `persistence-jpa/src/main/resources/db/ras/migration/V2__add_version_column.sql`
- Modify: `persistence-jpa/src/main/java/io/casehub/ras/persistence/jpa/SituationEntity.java`
- Modify: `persistence-jpa/src/main/java/io/casehub/ras/persistence/jpa/SituationMapper.java`
- Modify: `persistence-jpa/src/main/java/io/casehub/ras/persistence/jpa/JpaSituationStore.java`
- Modify: `persistence-jpa/src/test/java/io/casehub/ras/persistence/jpa/JpaSituationStoreTest.java`

**Interfaces:**
- Consumes: `SituationConflictException`, `SituationContext.storeVersion()` from Task 1
- Produces:
  - `JpaSituationStore.save()` throws `SituationConflictException` on concurrent modification
  - `JpaSituationStore.find()` populates `storeVersion` from entity `@Version` value

- [ ] **Step 1: Write failing tests for conflict detection**

Add to `JpaSituationStoreTest.java`:

```java
@Test
void findPopulatesStoreVersion() {
    var ctx = SituationContext.initial("sit-v", "key-1", "tenant-a", T1);
    store.save(ctx).await().indefinitely();

    var found = store.find("sit-v", "key-1", "tenant-a").await().indefinitely();
    assertThat(found).isPresent();
    assertThat(found.get().storeVersion()).isPresent();
    assertThat(found.get().storeVersion().getAsLong()).isEqualTo(0L);
}

@Test
void saveIncrementsStoreVersion() {
    var ctx = SituationContext.initial("sit-v", "key-1", "tenant-a", T1);
    store.save(ctx).await().indefinitely();

    var found1 = store.find("sit-v", "key-1", "tenant-a").await().indefinitely().orElseThrow();
    store.save(found1).await().indefinitely();

    var found2 = store.find("sit-v", "key-1", "tenant-a").await().indefinitely().orElseThrow();
    assertThat(found2.storeVersion().getAsLong()).isEqualTo(1L);
}

@Test
void saveThrowsConflictOnStaleVersion() {
    var ctx = SituationContext.initial("sit-v", "key-1", "tenant-a", T1);
    store.save(ctx).await().indefinitely();

    var found = store.find("sit-v", "key-1", "tenant-a").await().indefinitely().orElseThrow();
    // Simulate concurrent write: save the found context (bumps version to 1)
    store.save(found).await().indefinitely();

    // Now try to save the original found context (storeVersion=0, but DB is at version=1)
    var detection = new DetectionResult("g1", 0.8, DetectionSignal.DETECTED, Map.of());
    var stale = found.withDetection(detection, T2);
    assertThatThrownBy(() -> store.save(stale).await().indefinitely())
            .isInstanceOf(SituationConflictException.class);
}

@Test
void saveThrowsConflictWhenEntityCreatedByAnotherWriter() {
    // Context has empty storeVersion (from initial()), but entity already exists in DB
    var ctx1 = SituationContext.initial("sit-race", "key-1", "tenant-a", T1);
    store.save(ctx1).await().indefinitely();

    // Another "JVM" also tries to save initial (storeVersion empty, entity exists)
    var ctx2 = SituationContext.initial("sit-race", "key-1", "tenant-a", T1);
    assertThatThrownBy(() -> store.save(ctx2).await().indefinitely())
            .isInstanceOf(SituationConflictException.class);
}

@Test
void saveThrowsConflictWhenEntityRemovedByAnotherWriter() {
    var ctx = SituationContext.initial("sit-gone", "key-1", "tenant-a", T1);
    store.save(ctx).await().indefinitely();
    var found = store.find("sit-gone", "key-1", "tenant-a").await().indefinitely().orElseThrow();

    // Another JVM removed the entity
    store.remove("sit-gone", "key-1", "tenant-a").await().indefinitely();

    // Save with storeVersion present but entity gone → conflict
    var detection = new DetectionResult("g1", 0.8, DetectionSignal.DETECTED, Map.of());
    var stale = found.withDetection(detection, T2);
    assertThatThrownBy(() -> store.save(stale).await().indefinitely())
            .isInstanceOf(SituationConflictException.class);
}
```

Add import: `import io.casehub.ras.api.SituationConflictException;`

- [ ] **Step 2: Run tests to verify they fail**

Run: `/opt/homebrew/bin/mvn --batch-mode -pl persistence-jpa test -Dtest=JpaSituationStoreTest -DfailIfNoTests=false`
Expected: Compilation or assertion failures — @Version not present, storeVersion not mapped.

- [ ] **Step 3: Create Flyway V2 migration**

Create `persistence-jpa/src/main/resources/db/ras/migration/V2__add_version_column.sql`:

```sql
ALTER TABLE ras_situation ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
```

- [ ] **Step 4: Add @Version to SituationEntity**

In `SituationEntity.java`, add after the `detections` field:

```java
@jakarta.persistence.Version
private Long version = 0L;
```

Add getter:

```java
public Long getVersion() { return version; }
```

- [ ] **Step 5: Update SituationMapper to map storeVersion**

In `SituationMapper.java`, update `toContext()`:

```java
SituationContext toContext(SituationEntity entity) {
    List<TimestampedDetection> detections = deserializeDetections(entity.getDetections());
    return new SituationContext(
            entity.getSituationId(),
            entity.getCorrelationKey(),
            entity.getTenancyId(),
            entity.getFirstSignal(),
            entity.getLastSignal(),
            detections,
            OptionalLong.of(entity.getVersion()));
}
```

Add import `java.util.OptionalLong;`.

- [ ] **Step 6: Implement two-layer conflict detection in JpaSituationStore.save()**

Replace the `save()` method in `JpaSituationStore.java`:

```java
@Override
@Transactional(TxType.REQUIRED)
public Uni<Void> save(SituationContext context) {
    SituationEntity existing = em.createQuery(
                    "SELECT s FROM SituationEntity s WHERE s.situationId = :sid " +
                    "AND s.correlationKey = :ck AND s.tenancyId = :tid",
                    SituationEntity.class)
            .setParameter("sid", context.situationId())
            .setParameter("ck", context.correlationKey())
            .setParameter("tid", context.tenancyId())
            .getResultStream().findFirst().orElse(null);

    // Layer 1: application-level storeVersion comparison
    if (existing != null && context.storeVersion().isEmpty()) {
        throw new SituationConflictException(
                "Entity exists but context has no storeVersion — concurrent insert",
                null);
    }
    if (existing == null && context.storeVersion().isPresent()) {
        throw new SituationConflictException(
                "Entity removed but context has storeVersion — concurrent delete",
                null);
    }
    if (existing != null && context.storeVersion().isPresent()
            && existing.getVersion() != context.storeVersion().getAsLong()) {
        throw new SituationConflictException(
                "storeVersion mismatch: context=" + context.storeVersion().getAsLong()
                + " entity=" + existing.getVersion(),
                null);
    }

    try {
        if (existing != null) {
            mapper.updateEntity(existing, context);
        } else {
            em.persist(mapper.toEntity(context));
        }
        em.flush();
    } catch (jakarta.persistence.OptimisticLockException e) {
        throw new SituationConflictException("Concurrent modification detected", e);
    } catch (jakarta.persistence.PersistenceException e) {
        if (isConstraintViolation(e)) {
            throw new SituationConflictException("Concurrent insert detected", e);
        }
        throw e;
    }
    return Uni.createFrom().voidItem();
}

private boolean isConstraintViolation(Throwable t) {
    while (t != null) {
        if (t instanceof org.hibernate.exception.ConstraintViolationException) {
            return true;
        }
        t = t.getCause();
    }
    return false;
}
```

Add import: `import io.casehub.ras.api.SituationConflictException;`

- [ ] **Step 7: Run JPA tests**

Run: `/opt/homebrew/bin/mvn --batch-mode -pl persistence-jpa test`
Expected: All tests pass including new conflict detection tests.

- [ ] **Step 8: Commit**

```bash
git add persistence-jpa/src/main/resources/db/ras/migration/V2__add_version_column.sql \
       persistence-jpa/src/main/java/io/casehub/ras/persistence/jpa/SituationEntity.java \
       persistence-jpa/src/main/java/io/casehub/ras/persistence/jpa/SituationMapper.java \
       persistence-jpa/src/main/java/io/casehub/ras/persistence/jpa/JpaSituationStore.java \
       persistence-jpa/src/test/java/io/casehub/ras/persistence/jpa/JpaSituationStoreTest.java
git commit -m "feat(casehub-ras#18): @Version on SituationEntity, two-layer conflict detection in JpaSituationStore"
```

---

### Task 3: SituationEvaluator — two-phase processEvent with retry

**Files:**
- Modify: `runtime/src/main/java/io/casehub/ras/runtime/SituationEvaluator.java`
- Modify: `runtime/src/test/java/io/casehub/ras/runtime/SituationEvaluatorTest.java`
- Modify: `persistence-memory/src/main/java/io/casehub/ras/persistence/memory/InMemorySituationStore.java`
- Modify: `persistence-memory/src/test/java/io/casehub/ras/persistence/memory/InMemorySituationStoreTest.java`

**Interfaces:**
- Consumes: `SituationConflictException`, `SituationContext.storeVersion()` from Task 1; conflict-throwing `save()` from Task 2
- Produces: Restructured `SituationEvaluator.processEvent()` with two-phase detect/retry; `@ConfigProperty ras.evaluator.max-conflict-retries`

- [ ] **Step 1: Fix InMemorySituationStore compilation**

The `InMemorySituationStore` doesn't construct `SituationContext` directly — it stores and retrieves them. No changes needed to the store itself. But `InMemorySituationStoreTest` may construct `SituationContext` directly — check and fix compilation.

Read `InMemorySituationStoreTest.java` and fix any direct `SituationContext` constructor calls by adding `OptionalLong.empty()` as the last argument.

- [ ] **Step 2: Write a ConflictSimulatingStore test decorator**

Add to `SituationEvaluatorTest.java`:

```java
private static class ConflictSimulatingStore implements SituationStore {
    private final SituationStore delegate;
    private int conflictsRemaining;

    ConflictSimulatingStore(SituationStore delegate, int conflictCount) {
        this.delegate = delegate;
        this.conflictsRemaining = conflictCount;
    }

    @Override
    public Uni<Optional<SituationContext>> find(String situationId, String correlationKey,
                                                 String tenancyId) {
        return delegate.find(situationId, correlationKey, tenancyId);
    }

    @Override
    public Uni<Void> save(SituationContext context) {
        if (conflictsRemaining > 0) {
            conflictsRemaining--;
            throw new SituationConflictException("Simulated conflict", null);
        }
        return delegate.save(context);
    }

    @Override
    public Uni<Void> remove(String situationId, String correlationKey, String tenancyId) {
        return delegate.remove(situationId, correlationKey, tenancyId);
    }

    @Override
    public Uni<Void> removeExpired(Instant cutoff) {
        return delegate.removeExpired(cutoff);
    }
}
```

Add import: `import io.casehub.ras.api.SituationConflictException;`

- [ ] **Step 3: Write failing tests for retry logic**

Add to `SituationEvaluatorTest.java`:

```java
@Test
void conflictOnSaveRetriesAndSucceeds() {
    var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
            FixedDetectionResult.detected("g1", 0.9));
    var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
            Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")), TRIGGER_CONFIG);

    var conflictStore = new ConflictSimulatingStore(store, 1);
    var reg = new SituationRegistration(def);
    var registry = new SituationDefinitionRegistry(
            List.of(() -> List.of(reg)), List.of(ganglion));
    evaluator = new SituationEvaluator(conflictStore, policy, caseTrigger, registry, 3);

    evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

    assertThat(caseTrigger.firedCases()).hasSize(1);
}

@Test
void allRetriesExhaustedLosesEvent() {
    var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
            FixedDetectionResult.detected("g1", 0.4));
    var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
            Duration.ofMinutes(5), null, new ChainMode.Count("g1", 3), TRIGGER_CONFIG);

    var alwaysConflict = new ConflictSimulatingStore(store, Integer.MAX_VALUE);
    var reg = new SituationRegistration(def);
    var registry = new SituationDefinitionRegistry(
            List.of(() -> List.of(reg)), List.of(ganglion));
    evaluator = new SituationEvaluator(alwaysConflict, policy, caseTrigger, registry, 3);

    evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

    // Event lost — nothing saved, no case triggered
    assertThat(caseTrigger.firedCases()).isEmpty();
    assertThat(store.find("sit-1", "key-1", "tenant-a").await().indefinitely()).isEmpty();
}

@Test
void detectionNotRecomputedOnRetry() {
    var detectCount = new AtomicInteger();
    var ganglion = new Ganglion() {
        @Override public String ganglionId() { return "g1"; }
        @Override public Set<String> handledEventTypes() { return Set.of("temp.reading"); }
        @Override public Uni<DetectionResult> detect(CloudEvent event, SituationContext context) {
            detectCount.incrementAndGet();
            return Uni.createFrom().item(FixedDetectionResult.detected("g1", 0.4));
        }
    };
    var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
            Duration.ofMinutes(5), null, new ChainMode.Count("g1", 3), TRIGGER_CONFIG);

    var conflictStore = new ConflictSimulatingStore(store, 2);
    var reg = new SituationRegistration(def);
    var registry = new SituationDefinitionRegistry(
            List.of(() -> List.of(reg)), List.of(ganglion));
    evaluator = new SituationEvaluator(conflictStore, policy, caseTrigger, registry, 3);

    evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

    assertThat(detectCount.get()).isEqualTo(1);
}

@Test
void compactionRerunOnRetry() {
    var compactCalls = new AtomicInteger();
    var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
            FixedDetectionResult.detected("g1", 0.4)) {
        @Override
        public Uni<SituationContext> compact(SituationContext context) {
            compactCalls.incrementAndGet();
            return Uni.createFrom().item(context);
        }
    };
    // null correlationWindow → persistent → compact invoked
    var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
            null, null, new ChainMode.Count("g1", 5), TRIGGER_CONFIG);

    var conflictStore = new ConflictSimulatingStore(store, 1);
    var reg = new SituationRegistration(def);
    var registry = new SituationDefinitionRegistry(
            List.of(() -> List.of(reg)), List.of(ganglion));
    evaluator = new SituationEvaluator(conflictStore, policy, caseTrigger, registry, 3);

    evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

    // compact called on first attempt (conflict) + second attempt (success) = 2
    assertThat(compactCalls.get()).isEqualTo(2);
}
```

- [ ] **Step 4: Run tests to verify they fail**

Run: `/opt/homebrew/bin/mvn --batch-mode -pl runtime test -Dtest=SituationEvaluatorTest -DfailIfNoTests=false`
Expected: Compilation failure — `SituationEvaluator` constructor doesn't accept `maxConflictRetries` yet.

- [ ] **Step 5: Restructure SituationEvaluator**

Replace `SituationEvaluator.java` with the two-phase implementation:

```java
package io.casehub.ras.runtime;

import io.casehub.ras.api.*;
import io.cloudevents.CloudEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class SituationEvaluator {

    private static final Logger LOG = Logger.getLogger(SituationEvaluator.class.getName());

    private record SituationInstanceKey(String situationId, String correlationKey,
                                         String tenancyId) {}

    private final SituationStore store;
    private final RasTriggerPolicy triggerPolicy;
    private final CaseTrigger caseTrigger;
    private final SituationDefinitionRegistry registry;
    private final int maxConflictRetries;
    private final ConcurrentHashMap<SituationInstanceKey, Object> locks =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SituationInstanceKey, EventReorderBuffer> buffers =
            new ConcurrentHashMap<>();

    @Inject
    public SituationEvaluator(SituationStore store, RasTriggerPolicy triggerPolicy,
                              CaseTrigger caseTrigger, SituationDefinitionRegistry registry,
                              @ConfigProperty(name = "ras.evaluator.max-conflict-retries",
                                              defaultValue = "3")
                              int maxConflictRetries) {
        this.store = store;
        this.triggerPolicy = triggerPolicy;
        this.caseTrigger = caseTrigger;
        this.registry = registry;
        this.maxConflictRetries = maxConflictRetries;
    }

    public void evaluate(CloudEvent event, SituationDefinition definition,
                         String correlationKey, String tenancyId) {
        String situationId = definition.situationId();
        var key = new SituationInstanceKey(situationId, correlationKey, tenancyId);
        Object lock = locks.computeIfAbsent(key, k -> new Object());

        synchronized (lock) {
            boolean terminated;
            if (definition.eventBufferDelay() != null && event.getTime() != null) {
                var buffer = buffers.computeIfAbsent(key,
                        k -> new EventReorderBuffer(definition.eventBufferDelay(), definition));
                List<CloudEvent> toProcess = buffer.submit(event, Instant.now());
                terminated = false;
                for (CloudEvent e : toProcess) {
                    terminated = processEvent(e, definition, correlationKey, tenancyId);
                    if (terminated) break;
                }
            } else {
                terminated = processEvent(event, definition, correlationKey, tenancyId);
            }
            if (terminated) {
                buffers.remove(key);
                locks.remove(key);
            }
        }
    }

    private boolean processEvent(CloudEvent event, SituationDefinition definition,
                                  String correlationKey, String tenancyId) {
        String situationId = definition.situationId();
        Instant eventTime = extractEventTime(event);

        // Phase 1: Detect (once, never retried)
        SituationContext initialContext = loadContext(situationId, correlationKey,
                                                      tenancyId, definition, eventTime);
        List<DetectionResult> detectionResults = runDetection(event, definition, initialContext);

        // Phase 2: Apply + persist (retried on conflict)
        for (int attempt = 0; attempt <= maxConflictRetries; attempt++) {
            SituationContext context;
            if (attempt == 0) {
                context = initialContext;
            } else {
                LOG.warning("Retry " + attempt + "/" + maxConflictRetries
                            + " for situation '" + situationId + "'");
                context = loadContext(situationId, correlationKey,
                                     tenancyId, definition, eventTime);
            }

            for (DetectionResult result : detectionResults) {
                context = context.withDetection(result, eventTime);
            }

            TriggerDecision decision = triggerPolicy.evaluate(context, definition)
                    .await().indefinitely();

            try {
                return executeDecision(decision, context, definition,
                                       situationId, correlationKey, tenancyId);
            } catch (SituationConflictException e) {
                if (attempt == maxConflictRetries) {
                    LOG.severe("All retries exhausted for situation '" + situationId
                               + "', event lost: " + event.getType());
                    return false;
                }
            }
        }
        return false;
    }

    private SituationContext loadContext(String situationId, String correlationKey,
                                         String tenancyId, SituationDefinition definition,
                                         Instant eventTime) {
        SituationContext context = store.find(situationId, correlationKey, tenancyId)
                .await().indefinitely()
                .orElseGet(() -> SituationContext.initial(situationId, correlationKey,
                                                          tenancyId, eventTime));
        if (isExpired(context, definition, eventTime)) {
            closeGanglia(definition, situationId, correlationKey, tenancyId);
            store.remove(situationId, correlationKey, tenancyId).await().indefinitely();
            context = SituationContext.initial(situationId, correlationKey, tenancyId, eventTime);
        }
        return context;
    }

    private List<DetectionResult> runDetection(CloudEvent event,
                                                SituationDefinition definition,
                                                SituationContext context) {
        Set<String> gangliaForEvent = gangliaHandlingEventType(definition, event.getType());
        List<DetectionResult> results = new ArrayList<>();
        for (String ganglionId : gangliaForEvent) {
            Ganglion ganglion = registry.ganglion(ganglionId);
            DetectionResult result = ganglion.detect(event, context).await().indefinitely();
            results.add(result);
        }
        return results;
    }

    private boolean executeDecision(TriggerDecision decision, SituationContext context,
                                     SituationDefinition definition,
                                     String situationId, String correlationKey,
                                     String tenancyId) {
        switch (decision) {
            case CREATE_CASE -> {
                try {
                    caseTrigger.fire(definition.triggerConfig(), context).await().indefinitely();
                } catch (RuntimeException ex) {
                    LOG.severe("CaseTrigger.fire() failed for situation '" + situationId
                               + "': " + ex.getMessage());
                    store.save(context).await().indefinitely();
                    return false;
                }
                closeGanglia(definition, situationId, correlationKey, tenancyId);
                store.remove(situationId, correlationKey, tenancyId).await().indefinitely();
                return true;
            }
            case CONTINUE_ACCUMULATING -> {
                if (definition.correlationWindow() == null) {
                    context = compactGanglia(definition, context);
                }
                store.save(context).await().indefinitely();
                return false;
            }
            case DISCARD -> {
                closeGanglia(definition, situationId, correlationKey, tenancyId);
                store.remove(situationId, correlationKey, tenancyId).await().indefinitely();
                return true;
            }
        }
        return false;
    }

    private Instant extractEventTime(CloudEvent event) {
        OffsetDateTime time = event.getTime();
        return time != null ? time.toInstant() : Instant.now();
    }

    private boolean isExpired(SituationContext context, SituationDefinition definition,
                              Instant eventTime) {
        if (definition.correlationWindow() == null) return false;
        Instant cutoff = eventTime.minus(definition.correlationWindow());
        return context.lastSignal().isBefore(cutoff);
    }

    private Set<String> gangliaHandlingEventType(SituationDefinition definition,
                                                  String eventType) {
        Set<String> all = definition.chainMode().referencedGanglia();
        return all.stream()
                .filter(id -> registry.ganglion(id).handledEventTypes().contains(eventType))
                .collect(Collectors.toSet());
    }

    private SituationContext compactGanglia(SituationDefinition definition,
                                            SituationContext context) {
        for (String ganglionId : definition.chainMode().referencedGanglia()) {
            try {
                context = registry.ganglion(ganglionId).compact(context).await().indefinitely();
            } catch (RuntimeException ex) {
                LOG.warning("Ganglion '" + ganglionId + "' compact() failed: "
                            + ex.getMessage());
            }
        }
        return context;
    }

    private void closeGanglia(SituationDefinition definition,
                              String situationId, String correlationKey, String tenancyId) {
        for (String ganglionId : definition.chainMode().referencedGanglia()) {
            try {
                registry.ganglion(ganglionId).close(situationId, correlationKey, tenancyId)
                        .await().indefinitely();
            } catch (RuntimeException ex) {
                LOG.warning("Ganglion '" + ganglionId + "' close() failed: "
                            + ex.getMessage());
            }
        }
    }

    void flushIdleBuffers(Instant now) {
        for (var entry : buffers.entrySet()) {
            var key = entry.getKey();
            var buffer = entry.getValue();
            Object lock = locks.computeIfAbsent(key, k -> new Object());
            synchronized (lock) {
                if (buffer.isIdle(now)) {
                    List<CloudEvent> events = buffer.drainAll();
                    boolean terminated = false;
                    for (CloudEvent e : events) {
                        terminated = processEvent(e, buffer.definition(),
                                     key.correlationKey(), key.tenancyId());
                        if (terminated) break;
                    }
                    if (terminated) {
                        buffers.remove(key);
                        locks.remove(key);
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 6: Update existing tests — buildEvaluator helper**

The existing `buildEvaluator()` helper and `@BeforeEach` in `SituationEvaluatorTest` need updating for the new constructor:

```java
private void buildEvaluator(List<Ganglion> ganglia, SituationDefinition def) {
    var reg = new SituationRegistration(def);
    var registry = new SituationDefinitionRegistry(
            List.of(() -> List.of(reg)), ganglia);
    evaluator = new SituationEvaluator(store, policy, caseTrigger, registry, 3);
}
```

- [ ] **Step 7: Run all runtime tests**

Run: `/opt/homebrew/bin/mvn --batch-mode -pl runtime test`
Expected: All tests pass — existing and new.

- [ ] **Step 8: Run full build**

Run: `/opt/homebrew/bin/mvn --batch-mode install`
Expected: All modules compile and all tests pass.

- [ ] **Step 9: Commit**

```bash
git add runtime/src/main/java/io/casehub/ras/runtime/SituationEvaluator.java \
       runtime/src/test/java/io/casehub/ras/runtime/SituationEvaluatorTest.java \
       persistence-memory/src/main/java/io/casehub/ras/persistence/memory/InMemorySituationStore.java \
       persistence-memory/src/test/java/io/casehub/ras/persistence/memory/InMemorySituationStoreTest.java
git commit -m "feat(casehub-ras#18): two-phase processEvent with conflict retry in SituationEvaluator"
```
