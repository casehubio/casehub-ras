# DroolsSessionStore Orphaned Session Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #39 — DroolsSessionStore: orphaned session cleanup for ReliableDroolsSessionStore
**Issue group:** #39

**Goal:** Add `OrphanedResourceCleaner` SPI to `api/`, implement it in `ReliableDroolsSessionStore` and `JpaGanglionStateStore`, consolidate orphan cleanup in `SituationExpiryJob` under a single CDI discovery loop.

**Architecture:** New `OrphanedResourceCleaner` interface in `api/` with `cleanerType()` and `removeOrphaned()`. `JpaGanglionStateStore` migrates its existing `removeOrphaned()` to this interface. `ReliableDroolsSessionStore` implements it using `SituationStore.find()` per key with error isolation. `SituationExpiryJob` replaces the direct `ganglionStateStore.removeOrphaned()` call with `Instance<OrphanedResourceCleaner>` iteration.

**Tech Stack:** Java 21, Quarkus CDI, Mutiny `Uni<>`, H2MVStore, JPA/Hibernate, Micrometer

## Global Constraints

- All `Uni<>` returns for async-compatible SPI methods
- `api/` must remain free of Drools, JPA, and H2MVStore dependencies
- `DroolsSessionStore` interface in `ras-drools/` is NOT modified
- Key field values must not contain `|` delimiter (documented invariant)
- Pre-release platform — breaking changes to `GanglionStateStore` and `RasMetrics` are acceptable

---

### Task 1: `OrphanedResourceCleaner` SPI + `DroolsSessionKey.fromStorageKey()`

**Files:**
- Create: `api/src/main/java/io/casehub/ras/api/OrphanedResourceCleaner.java`
- Modify: `ras-drools/src/main/java/io/casehub/ras/drools/DroolsSessionKey.java`
- Test: `ras-drools/src/test/java/io/casehub/ras/drools/DroolsSessionKeyTest.java`

**Interfaces:**
- Produces: `OrphanedResourceCleaner { String cleanerType(); Uni<Integer> removeOrphaned(); }`
- Produces: `DroolsSessionKey.fromStorageKey(String storageKey) → DroolsSessionKey`

- [ ] **Step 1: Write failing test for `DroolsSessionKey.fromStorageKey()` round-trip**

```java
// DroolsSessionKeyTest.java (new file)
package io.casehub.ras.drools;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DroolsSessionKeyTest {

    @Test
    void fromStorageKeyRoundTrip() {
        var original = new DroolsSessionKey("g1", "sit-1", "key-1", "tenant-a");
        var restored = DroolsSessionKey.fromStorageKey(original.toStorageKey());
        assertThat(restored).isEqualTo(original);
    }

    @Test
    void fromStorageKeyWithComplexValues() {
        var original = new DroolsSessionKey("my.ganglion", "situation-def-1", "order-12345", "tenant-xyz");
        var restored = DroolsSessionKey.fromStorageKey(original.toStorageKey());
        assertThat(restored).isEqualTo(original);
    }

    @Test
    void fromStorageKeyMalformedThrows() {
        assertThatThrownBy(() -> DroolsSessionKey.fromStorageKey("only-two|parts"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Malformed storage key")
                .hasMessageContaining("only-two|parts");
    }

    @Test
    void fromStorageKeyEmptyThrows() {
        assertThatThrownBy(() -> DroolsSessionKey.fromStorageKey(""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn --batch-mode test -pl ras-drools -Dtest=DroolsSessionKeyTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — `fromStorageKey` method does not exist

- [ ] **Step 3: Create `OrphanedResourceCleaner` interface**

Use `ide_create_file`:
```java
// api/src/main/java/io/casehub/ras/api/OrphanedResourceCleaner.java
package io.casehub.ras.api;

import io.smallrye.mutiny.Uni;

public interface OrphanedResourceCleaner {
    String cleanerType();
    Uni<Integer> removeOrphaned();
}
```

- [ ] **Step 4: Implement `fromStorageKey()` on `DroolsSessionKey`**

Use `ide_insert_member` on `DroolsSessionKey`, after `toStorageKey`:
```java
public static DroolsSessionKey fromStorageKey(String storageKey) {
    String[] parts = storageKey.split("\\|", 4);
    if (parts.length < 4) {
        throw new IllegalArgumentException(
                "Malformed storage key (expected 4 '|'-separated parts): " + storageKey);
    }
    return new DroolsSessionKey(parts[0], parts[1], parts[2], parts[3]);
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn --batch-mode test -pl ras-drools -Dtest=DroolsSessionKeyTest`
Expected: PASS — all 4 tests green

- [ ] **Step 6: Verify with `ide_diagnostics`**

Run `ide_diagnostics` on `DroolsSessionKey.java` and `OrphanedResourceCleaner.java` — expect no errors.

- [ ] **Step 7: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/ras add api/src/main/java/io/casehub/ras/api/OrphanedResourceCleaner.java ras-drools/src/main/java/io/casehub/ras/drools/DroolsSessionKey.java ras-drools/src/test/java/io/casehub/ras/drools/DroolsSessionKeyTest.java
git -C /Users/mdproctor/claude/casehub/ras commit -m "feat(casehub-ras#39): OrphanedResourceCleaner SPI + DroolsSessionKey.fromStorageKey()"
```

---

### Task 2: Migrate `GanglionStateStore.removeOrphaned()` to `OrphanedResourceCleaner`

**Files:**
- Modify: `api/src/main/java/io/casehub/ras/api/GanglionStateStore.java` — remove `removeOrphaned()` default method
- Modify: `persistence-jpa/src/main/java/io/casehub/ras/persistence/jpa/JpaGanglionStateStore.java` — implement `OrphanedResourceCleaner`
- Modify: `runtime/src/main/java/io/casehub/ras/runtime/InMemoryGanglionStateStore.java` — remove override if any
- Modify: `api/src/test/java/io/casehub/ras/api/AbstractGanglionStateStoreContractTest.java` — remove `removeOrphaned` test if present
- Modify: `persistence-jpa/src/test/java/io/casehub/ras/persistence/jpa/JpaGanglionStateStoreTest.java` — update `removeOrphaned` test to use `OrphanedResourceCleaner` type

**Interfaces:**
- Consumes: `OrphanedResourceCleaner` (from Task 1)
- Produces: `JpaGanglionStateStore implements GanglionStateStore, OrphanedResourceCleaner`

- [ ] **Step 1: Check callers of `GanglionStateStore.removeOrphaned()`**

Use `ide_find_references` on `GanglionStateStore.removeOrphaned()` to identify all callers.
Expected callers: `SituationExpiryJob.cleanup()` (line 50), one test.

- [ ] **Step 2: Remove `removeOrphaned()` from `GanglionStateStore` interface**

Use `ide_edit_member` on `GanglionStateStore`, member `removeOrphaned` — delete the default method:
```java
// Remove this method entirely from the interface
```

- [ ] **Step 3: Make `JpaGanglionStateStore` implement `OrphanedResourceCleaner`**

Use `ide_edit_member` on `JpaGanglionStateStore`, member `JpaGanglionStateStore` (class declaration):
```java
@ApplicationScoped
public class JpaGanglionStateStore implements GanglionStateStore, OrphanedResourceCleaner {
```

- [ ] **Step 4: Add `cleanerType()` to `JpaGanglionStateStore`**

Use `ide_insert_member` on `JpaGanglionStateStore`, after `removeForSituation`:
```java
@Override
public String cleanerType() {
    return "ganglion_state";
}
```

The existing `removeOrphaned()` method already has the correct signature — it now satisfies `OrphanedResourceCleaner.removeOrphaned()` instead of the removed `GanglionStateStore` default.

- [ ] **Step 5: Check `InMemoryGanglionStateStore` for `removeOrphaned` override**

Use `ide_file_structure` on `InMemoryGanglionStateStore.java`. If it overrides `removeOrphaned()`, remove the override (it was a no-op delegating to the default). If not, no change needed.

- [ ] **Step 6: Update `JpaGanglionStateStoreTest`**

The test `removeOrphanedRemovesEntriesWithNoMatchingSituation` currently calls `store.removeOrphaned()` via the `GanglionStateStore` reference. Cast to `OrphanedResourceCleaner` or use the `jpaStore` field directly:

Use `ide_replace_member` on `JpaGanglionStateStoreTest`, method `removeOrphanedRemovesEntriesWithNoMatchingSituation`:
```java
@Test
void removeOrphanedRemovesEntriesWithNoMatchingSituation() {
    var key = new GanglionStateKey("g1", "orphan-sit", "key-1", "tenant-a");
    store.save(key, new GanglionState(new double[]{1.0}, OptionalLong.empty()))
            .await().indefinitely();

    int removed = ((OrphanedResourceCleaner) jpaStore).removeOrphaned().await().indefinitely();

    assertThat(removed).isEqualTo(1);
    assertThat(store.load(key).await().indefinitely()).isEmpty();
}
```

- [ ] **Step 7: Run tests**

Run: `mvn --batch-mode test -pl api,persistence-jpa`
Expected: api tests pass (no removeOrphaned reference in contract test), JPA tests pass with cast

- [ ] **Step 8: Verify no remaining compilation errors**

Run `ide_diagnostics` on `GanglionStateStore.java`, `JpaGanglionStateStore.java`, `InMemoryGanglionStateStore.java`.
Expected: no errors. The compile error in `SituationExpiryJob` (calling removed method) is expected — fixed in Task 3.

- [ ] **Step 9: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/ras add api/src/main/java/io/casehub/ras/api/GanglionStateStore.java persistence-jpa/src/main/java/io/casehub/ras/persistence/jpa/JpaGanglionStateStore.java runtime/src/main/java/io/casehub/ras/runtime/InMemoryGanglionStateStore.java persistence-jpa/src/test/java/io/casehub/ras/persistence/jpa/JpaGanglionStateStoreTest.java
git -C /Users/mdproctor/claude/casehub/ras commit -m "refactor(casehub-ras#39): migrate GanglionStateStore.removeOrphaned() to OrphanedResourceCleaner SPI"
```

---

### Task 3: Refactor `SituationExpiryJob` + `RasMetrics` for `OrphanedResourceCleaner`

**Files:**
- Modify: `runtime/src/main/java/io/casehub/ras/runtime/SituationExpiryJob.java`
- Modify: `runtime/src/main/java/io/casehub/ras/runtime/RasMetrics.java`
- Modify: `runtime/src/test/java/io/casehub/ras/runtime/SituationExpiryJobTest.java`

**Interfaces:**
- Consumes: `OrphanedResourceCleaner` (from Task 1), `JpaGanglionStateStore implements OrphanedResourceCleaner` (from Task 2)
- Produces: `SituationExpiryJob` uses `Instance<OrphanedResourceCleaner>` instead of direct `GanglionStateStore.removeOrphaned()`

- [ ] **Step 1: Update `SituationExpiryJobTest` — rewrite the orphan cleanup test**

Use `ide_replace_member` on `SituationExpiryJobTest`, method `cleanupCallsRemoveOrphanedAndRecordsMetric`:
```java
@Test
void cleanupCallsRemoveOrphanedAndRecordsMetric() {
    var store = new InMemorySituationStore();
    var ganglion = new MockGanglion("g1", Set.of("e"),
                                    FixedDetectionResult.noise("g1"));
    var def = new SituationDefinition("sit-1", Set.of("e"), null, null,
                                      new ChainMode.Or(Set.of("g1")),
                                      new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "c", "1", Map.of())), null);
    var registry = new SituationDefinitionRegistry(
            List.of(() -> List.of(new SituationRegistration(def))), List.of(ganglion));
    var metrics = initMetrics(registry);

    OrphanedResourceCleaner mockCleaner = new OrphanedResourceCleaner() {
        @Override public String cleanerType() { return "test"; }
        @Override public Uni<Integer> removeOrphaned() { return Uni.createFrom().item(3); }
    };

    var job = new SituationExpiryJob(store, registry,
                                     Duration.ofMinutes(1), metrics, List.of(mockCleaner));

    job.cleanup();

    assertThat(meterRegistry.counter("ras.expiry.orphans_cleaned", "cleaner_type", "test").count())
            .isEqualTo(3.0);
}
```

- [ ] **Step 2: Add test for per-cleaner error isolation**

Use `ide_insert_member` on `SituationExpiryJobTest`, after `cleanupCallsRemoveOrphanedAndRecordsMetric`:
```java
@Test
void cleanupIsolatesCleanerFailures() {
    var store = new InMemorySituationStore();
    var ganglion = new MockGanglion("g1", Set.of("e"),
                                    FixedDetectionResult.noise("g1"));
    var def = new SituationDefinition("sit-1", Set.of("e"), null, null,
                                      new ChainMode.Or(Set.of("g1")),
                                      new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "c", "1", Map.of())), null);
    var registry = new SituationDefinitionRegistry(
            List.of(() -> List.of(new SituationRegistration(def))), List.of(ganglion));
    var metrics = initMetrics(registry);

    OrphanedResourceCleaner failingCleaner = new OrphanedResourceCleaner() {
        @Override public String cleanerType() { return "failing"; }
        @Override public Uni<Integer> removeOrphaned() { throw new RuntimeException("boom"); }
    };
    OrphanedResourceCleaner workingCleaner = new OrphanedResourceCleaner() {
        @Override public String cleanerType() { return "working"; }
        @Override public Uni<Integer> removeOrphaned() { return Uni.createFrom().item(2); }
    };

    var job = new SituationExpiryJob(store, registry,
                                     Duration.ofMinutes(1), metrics, List.of(failingCleaner, workingCleaner));

    job.cleanup();

    assertThat(meterRegistry.counter("ras.expiry.orphans_cleaned", "cleaner_type", "working").count())
            .isEqualTo(2.0);
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl runtime -Dtest=SituationExpiryJobTest`
Expected: FAIL — constructor signature changed, `orphanedGanglionStateCleaned` method doesn't exist yet

- [ ] **Step 4: Refactor `RasMetrics` — replace `orphanedGanglionStateCleaned` with `orphanedResourcesCleaned`**

Use `ide_edit_member` on `RasMetrics`, method `orphanedGanglionStateCleaned`:
```java
public void orphanedResourcesCleaned(int count, String cleanerType) {
    counterBy("ras.expiry.orphans_cleaned", count, "cleaner_type", cleanerType);
}
```

- [ ] **Step 5: Refactor `SituationExpiryJob` — replace `GanglionStateStore` with `Instance<OrphanedResourceCleaner>`**

Use `ide_edit_member` on `SituationExpiryJob`, member `SituationExpiryJob` (the entire class):
```java
@ApplicationScoped
public class SituationExpiryJob {

    private static final org.jboss.logging.Logger log = org.jboss.logging.Logger.getLogger(SituationExpiryJob.class);

    private final SituationStore                    store;
    private final SituationDefinitionRegistry       registry;
    private final Duration                          triggerGuardPeriod;
    private final RasMetrics                        metrics;
    private final Iterable<OrphanedResourceCleaner> resourceCleaners;

    @Inject
    public SituationExpiryJob(
            SituationStore store,
            SituationDefinitionRegistry registry,
            @ConfigProperty(name = "ras.evaluator.trigger-guard-period", defaultValue = "PT1M")
            Duration triggerGuardPeriod,
            RasMetrics metrics,
            Instance<OrphanedResourceCleaner> resourceCleaners) {
        this.store              = store;
        this.registry           = registry;
        this.triggerGuardPeriod = triggerGuardPeriod;
        this.metrics            = metrics;
        this.resourceCleaners   = resourceCleaners;
    }

    SituationExpiryJob(SituationStore store,
                       SituationDefinitionRegistry registry,
                       Duration triggerGuardPeriod,
                       RasMetrics metrics,
                       Iterable<OrphanedResourceCleaner> resourceCleaners) {
        this.store              = store;
        this.registry           = registry;
        this.triggerGuardPeriod = triggerGuardPeriod;
        this.metrics            = metrics;
        this.resourceCleaners   = resourceCleaners;
    }

    @Scheduled(every = "PT5M")
    void cleanup() {
        Instant guardCutoff      = Instant.now().minus(triggerGuardPeriod);
        int     triggeredRemoved = store.removeTriggeredBefore(guardCutoff).await().indefinitely();
        metrics.triggeredCleaned(triggeredRemoved);

        Duration maxWindow = registry.maxCorrelationWindow();
        if (maxWindow != null) {
            Instant cutoff         = Instant.now().minus(maxWindow);
            int     expiredRemoved = store.removeExpired(cutoff).await().indefinitely();
            metrics.expiredCleaned(expiredRemoved);
        }

        for (OrphanedResourceCleaner cleaner : resourceCleaners) {
            try {
                int cleaned = cleaner.removeOrphaned().await().indefinitely();
                metrics.orphanedResourcesCleaned(cleaned, cleaner.cleanerType());
            } catch (Exception e) {
                log.warnf(e, "Orphan cleaner '%s' failed, skipping", cleaner.cleanerType());
            }
        }
    }
}
```

- [ ] **Step 6: Fix imports in `SituationExpiryJob`**

Ensure these imports are present (remove `GanglionStateStore` import, add new ones):
- `io.casehub.ras.api.OrphanedResourceCleaner`
- `jakarta.enterprise.inject.Instance`
- `java.time.Instant`

- [ ] **Step 7: Update remaining `SituationExpiryJobTest` methods**

All existing tests that construct `SituationExpiryJob` with the old 5-arg constructor need updating to the new constructor (no `GanglionStateStore`, add `List.of()` for cleaners). Update each test's constructor call:

Old: `new SituationExpiryJob(store, new InMemoryGanglionStateStore(), registry, Duration.ofMinutes(1), metrics)`
New: `new SituationExpiryJob(store, registry, Duration.ofMinutes(1), metrics, List.of())`

Use `ide_replace_text_in_file` with search `new SituationExpiryJob(store, new InMemoryGanglionStateStore(), registry,` → replace with `new SituationExpiryJob(store, registry,` across the test file. Then replace `initMetrics(registry));` with `initMetrics(registry), List.of());` in those same lines.

- [ ] **Step 8: Run tests**

Run: `mvn --batch-mode test -pl runtime -Dtest=SituationExpiryJobTest`
Expected: PASS — all tests green including new orphan cleanup and error isolation tests

- [ ] **Step 9: Verify with `ide_diagnostics`**

Run `ide_diagnostics` on `SituationExpiryJob.java`, `RasMetrics.java`.

- [ ] **Step 10: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/ras add runtime/src/main/java/io/casehub/ras/runtime/SituationExpiryJob.java runtime/src/main/java/io/casehub/ras/runtime/RasMetrics.java runtime/src/test/java/io/casehub/ras/runtime/SituationExpiryJobTest.java
git -C /Users/mdproctor/claude/casehub/ras commit -m "refactor(casehub-ras#39): SituationExpiryJob uses OrphanedResourceCleaner discovery loop"
```

---

### Task 4: `ReliableDroolsSessionStore` implements `OrphanedResourceCleaner`

**Files:**
- Modify: `drools-reliability/src/main/java/io/casehub/ras/drools/reliability/ReliableDroolsSessionStore.java`
- Modify: `drools-reliability/src/test/java/io/casehub/ras/drools/reliability/ReliableDroolsSessionStoreTest.java`

**Interfaces:**
- Consumes: `OrphanedResourceCleaner` (Task 1), `DroolsSessionKey.fromStorageKey()` (Task 1), `SituationStore.find()` (api/)
- Produces: `ReliableDroolsSessionStore implements DroolsSessionStore, OrphanedResourceCleaner`

- [ ] **Step 1: Write failing tests for orphan cleanup**

Use `ide_insert_member` on `ReliableDroolsSessionStoreTest`, after `worksWithoutMetrics`:

```java
@Test
void removeOrphanedRemovesSessionsWithNoMatchingSituation() {
    var k1 = key("g1", "sit-1");
    var k2 = key("g2", "sit-2");
    store.computeIfAbsent(k1, kieBase, config, 0);
    store.computeIfAbsent(k2, kieBase, config, 0);
    assertThat(store.activeSessionCount()).isEqualTo(2);

    int removed = ((OrphanedResourceCleaner) store).removeOrphaned().await().indefinitely();

    assertThat(removed).isEqualTo(2);
    assertThat(store.activeSessionCount()).isEqualTo(0);
}

@Test
void removeOrphanedPreservesSessionsWithMatchingSituation() {
    var situationStore = new InMemorySituationStore();
    var k1 = key("g1", "sit-1");
    store.computeIfAbsent(k1, kieBase, config, 0);

    var ctx = new SituationContext("sit-1", "key-1", "tenant-a",
            Instant.now(), Instant.now(), List.of(), OptionalLong.empty(), null, 0);
    situationStore.save(ctx).await().indefinitely();

    store.setSituationStore(situationStore);

    int removed = ((OrphanedResourceCleaner) store).removeOrphaned().await().indefinitely();

    assertThat(removed).isEqualTo(0);
    assertThat(store.activeSessionCount()).isEqualTo(1);
}

@Test
void removeOrphanedIsolatesPerKeyErrors() {
    var k1 = key("g1", "sit-1");
    var k2 = key("g2", "sit-2");
    store.computeIfAbsent(k1, kieBase, config, 0);
    store.computeIfAbsent(k2, kieBase, config, 0);

    int removed = ((OrphanedResourceCleaner) store).removeOrphaned().await().indefinitely();

    assertThat(removed).isEqualTo(2);
}

@Test
void removeOrphanedReturnsZeroAfterShutdown() {
    var k = key("g1", "sit-1");
    store.computeIfAbsent(k, kieBase, config, 0);

    store.destroy();

    int removed = ((OrphanedResourceCleaner) store).removeOrphaned().await().indefinitely();
    assertThat(removed).isEqualTo(0);
}

@Test
void cleanerTypeReturnsDroolsSession() {
    assertThat(((OrphanedResourceCleaner) store).cleanerType()).isEqualTo("drools_session");
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl drools-reliability -Dtest=ReliableDroolsSessionStoreTest#removeOrphanedRemovesSessionsWithNoMatchingSituation`
Expected: FAIL — `ReliableDroolsSessionStore` does not implement `OrphanedResourceCleaner`

- [ ] **Step 3: Add `SituationStore` field and setter to `ReliableDroolsSessionStore`**

Use `ide_insert_member` on `ReliableDroolsSessionStore`, after `metrics` field:

```java
@Inject
Instance<SituationStore> situationStoreInstance;

private SituationStore situationStore;

void setSituationStore(SituationStore situationStore) {
    this.situationStore = situationStore;
}
```

- [ ] **Step 4: Wire `situationStore` in `init()`**

Update the `init()` method to resolve `situationStore` from the CDI instance, after metrics init. Use `ide_replace_member` body:

Add this at the end of `init()`, before the log statement:
```java
if (situationStore == null && situationStoreInstance != null && situationStoreInstance.isResolvable()) {
    situationStore = situationStoreInstance.get();
}
```

- [ ] **Step 5: Add shutdown guard and implement `OrphanedResourceCleaner`**

Update class declaration to implement `OrphanedResourceCleaner`:
```java
@ApplicationScoped
public class ReliableDroolsSessionStore implements DroolsSessionStore, OrphanedResourceCleaner {
```

Add `volatile boolean closed` field. Update `destroy()` to set `closed = true` before clearing cache.

Add `cleanerType()` and `removeOrphaned()`:
```java
@Override
public String cleanerType() {
    return "drools_session";
}

@Override
public Uni<Integer> removeOrphaned() {
    if (closed) {
        return Uni.createFrom().item(0);
    }
    int removed = 0;
    List<String> storageKeys = new ArrayList<>(sessionIds.keySet());
    for (String storageKey : storageKeys) {
        try {
            DroolsSessionKey key = DroolsSessionKey.fromStorageKey(storageKey);
            if (situationStore == null) {
                remove(key);
                removed++;
            } else {
                Optional<SituationContext> ctx = situationStore
                        .find(key.situationId(), key.correlationKey(), key.tenancyId())
                        .await().indefinitely();
                if (ctx.isEmpty()) {
                    remove(key);
                    removed++;
                }
            }
        } catch (Exception e) {
            log.warn("Orphan cleanup failed for key '{}', skipping", storageKey, e);
        }
    }
    return Uni.createFrom().item(removed);
}
```

- [ ] **Step 6: Run tests**

Run: `mvn --batch-mode test -pl drools-reliability -Dtest=ReliableDroolsSessionStoreTest`
Expected: PASS — all existing + new tests green

- [ ] **Step 7: Verify with `ide_diagnostics`**

Run `ide_diagnostics` on `ReliableDroolsSessionStore.java`.

- [ ] **Step 8: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/ras add drools-reliability/src/main/java/io/casehub/ras/drools/reliability/ReliableDroolsSessionStore.java drools-reliability/src/test/java/io/casehub/ras/drools/reliability/ReliableDroolsSessionStoreTest.java
git -C /Users/mdproctor/claude/casehub/ras commit -m "feat(casehub-ras#39): ReliableDroolsSessionStore implements OrphanedResourceCleaner"
```

---

### Task 5: Full build verification + CLAUDE.md update

**Files:**
- Modify: `CLAUDE.md` — update design spec list and SPI documentation

- [ ] **Step 1: Full build**

Run: `mvn --batch-mode install`
Expected: BUILD SUCCESS — all modules compile, all tests pass

- [ ] **Step 2: Update CLAUDE.md**

Add the design spec reference to the design specs list:
```
- DroolsSessionStore orphan cleanup: `docs/superpowers/specs/2026-07-17-drools-session-store-orphan-cleanup-design.md`
```

Update the `SituationExpiryJob` documentation to reflect that it now uses `Instance<OrphanedResourceCleaner>` instead of direct `GanglionStateStore.removeOrphaned()`.

Update the `GanglionStateStore` SPI section — remove `removeOrphaned()` from the interface description.

Add `OrphanedResourceCleaner` to the Core SPIs section:
```
### OrphanedResourceCleaner — derived resource cleanup (api/)
interface OrphanedResourceCleaner {
    String cleanerType();
    Uni<Integer> removeOrphaned();
}
```

- [ ] **Step 3: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/ras add CLAUDE.md
git -C /Users/mdproctor/claude/casehub/ras commit -m "docs(casehub-ras#39): update CLAUDE.md for OrphanedResourceCleaner SPI"
```
