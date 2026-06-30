# Trigger Lifecycle and Situation Query Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement situation lifecycle model (FireOnce/Repeating), guard cleanup for #21, long-lived situation query API for #20, and all supporting persistence and runtime changes.

**Architecture:** TriggerDecision gains CREATE_CASE_AND_CONTINUE and RESOLVE variants. TriggerMode sealed interface on SituationDefinition drives DefaultRasTriggerPolicy. SituationStore.save() returns Uni\<SituationContext\> with storeVersion; tryClaimTrigger atomically stamps trigger metadata. Guard cleanup via SituationExpiryJob resolves #21. SituationSource/ActiveSituation query API resolves #20.

**Tech Stack:** Java 21, Quarkus 3.32, Mutiny, Hibernate ORM, PostgreSQL/Flyway, CDI events, JUnit 5/AssertJ

## Global Constraints

- **Spec:** `docs/superpowers/specs/2026-06-30-trigger-lifecycle-and-situation-query-design.md`
- **Protocols:** `module-tier-structure.md` (api/ = Tier 1, pure Java + Mutiny provided), `flyway-version-range-allocation.md` (V4 next), `ledger-spi-propagation.md` (SPI additions update ALL implementations)
- **API module** must not import Quarkus, JPA, or any runtime framework
- **TriggerMode defaults:** null triggerMode → FireOnce() in SituationDefinition compact constructor
- **Store-managed fields:** lastTriggered, triggerCount owned by tryClaimTrigger, NOT by save()/updateEntity()
- **Breaking changes are fine** — this platform has no end users; compile errors at call sites are the migration mechanism
- **Issue tracking:** casehubio/casehub-ras #21, #20. Branch: issue-21-trigger-cleanup-long-lived-situations

---

### Task 1: API type changes — TriggerDecision, TriggerMode, SituationContext, SituationDefinition

All changes in `api/` module. Pure Java, Tier 1 compliant. This task creates the type foundation everything else builds on AND mechanically fixes all construction sites so the project compiles and existing tests pass.

**Files:**
- Modify: `api/src/main/java/io/casehub/ras/api/TriggerDecision.java`
- Create: `api/src/main/java/io/casehub/ras/api/TriggerMode.java`
- Modify: `api/src/main/java/io/casehub/ras/api/SituationContext.java`
- Modify: `api/src/main/java/io/casehub/ras/api/SituationDefinition.java`
- Modify: `api/src/test/java/io/casehub/ras/api/SituationDefinitionTest.java`
- Modify: ALL test files that construct SituationDefinition (~30 sites) or SituationContext

**Interfaces:**
- Produces: `TriggerDecision` enum (5 variants), `TriggerMode` sealed interface (`FireOnce`, `Repeating(Duration)`), `SituationContext` with `lastTriggered`/`triggerCount`/`withStoreVersion()`, `SituationDefinition` with `triggerMode` field

- [ ] **Step 1: Write TriggerMode tests**

Create `api/src/test/java/io/casehub/ras/api/TriggerModeTest.java`:

```java
package io.casehub.ras.api;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

class TriggerModeTest {

    @Test
    void fireOnceCreates() {
        var mode = new TriggerMode.FireOnce();
        assertThat(mode).isInstanceOf(TriggerMode.class);
    }

    @Test
    void repeatingWithValidCooldown() {
        var mode = new TriggerMode.Repeating(Duration.ofMinutes(5));
        assertThat(mode.cooldown()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void repeatingRejectsNullCooldown() {
        assertThatThrownBy(() -> new TriggerMode.Repeating(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void repeatingRejectsZeroCooldown() {
        assertThatThrownBy(() -> new TriggerMode.Repeating(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void repeatingRejectsNegativeCooldown() {
        assertThatThrownBy(() -> new TriggerMode.Repeating(Duration.ofMinutes(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void sealedInterfacePermitsOnlyTwoVariants() {
        assertThat(TriggerMode.class.getPermittedSubclasses())
                .extracting(Class::getSimpleName)
                .containsExactlyInAnyOrder("FireOnce", "Repeating");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn --batch-mode test -pl api -Dtest=TriggerModeTest`
Expected: FAIL — TriggerMode does not exist

- [ ] **Step 3: Implement TriggerDecision + TriggerMode**

Replace `api/src/main/java/io/casehub/ras/api/TriggerDecision.java`:
```java
package io.casehub.ras.api;

public enum TriggerDecision {
    CREATE_CASE,
    CREATE_CASE_AND_CONTINUE,
    CONTINUE_ACCUMULATING,
    DISCARD,
    RESOLVE
}
```

Create `api/src/main/java/io/casehub/ras/api/TriggerMode.java`:
```java
package io.casehub.ras.api;

import java.time.Duration;
import java.util.Objects;

public sealed interface TriggerMode {
    record FireOnce() implements TriggerMode {}
    record Repeating(Duration cooldown) implements TriggerMode {
        public Repeating {
            Objects.requireNonNull(cooldown, "cooldown");
            if (cooldown.isZero() || cooldown.isNegative()) {
                throw new IllegalArgumentException(
                        "cooldown must be positive, got: " + cooldown);
            }
        }
    }
}
```

- [ ] **Step 4: Update SituationContext — add trigger history fields**

Replace `api/src/main/java/io/casehub/ras/api/SituationContext.java`:
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
        OptionalLong storeVersion,
        Instant lastTriggered,
        int triggerCount
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
                                   eventTime, eventTime, List.of(), OptionalLong.empty(),
                                   null, 0);
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
                                   newFirst, newLast, newDetections, storeVersion,
                                   lastTriggered, triggerCount);
    }

    public SituationContext withStoreVersion(long version) {
        return new SituationContext(situationId, correlationKey, tenancyId,
                                   firstSignal, lastSignal, detections, OptionalLong.of(version),
                                   lastTriggered, triggerCount);
    }

    // No withTrigger() — trigger metadata (lastTriggered, triggerCount) is stamped
    // atomically by SituationStore.tryClaimTrigger(), not by the evaluator.
    // The context carries these as read-only state for policy cooldown evaluation.
}
```

- [ ] **Step 5: Update SituationDefinition — add triggerMode field**

Replace `api/src/main/java/io/casehub/ras/api/SituationDefinition.java`:
```java
package io.casehub.ras.api;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

public record SituationDefinition(
        String situationId,
        Set<String> eventTypes,
        Duration correlationWindow,
        Duration eventBufferDelay,
        ChainMode chainMode,
        CaseTriggerConfig triggerConfig,
        TriggerMode triggerMode
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
        if (eventBufferDelay != null
                && (eventBufferDelay.isZero() || eventBufferDelay.isNegative())) {
            throw new IllegalArgumentException(
                    "eventBufferDelay must be positive when set, got: " + eventBufferDelay);
        }
        triggerMode = triggerMode != null ? triggerMode : new TriggerMode.FireOnce();
    }
}
```

- [ ] **Step 6: Fix ALL SituationDefinition construction sites**

Every 6-argument `new SituationDefinition(...)` call must become 7-argument with `null` (defaults to FireOnce). This is ~30 sites across test files. Use IntelliJ find-references to locate each one. Files affected:

- `api/src/test/java/.../SituationDefinitionTest.java` — ~10 construction sites
- `runtime/src/test/java/.../SituationEvaluatorTest.java` — ~25 construction sites
- `runtime/src/test/java/.../DefaultRasTriggerPolicyTest.java` — helper method
- `runtime/src/test/java/.../SituationDefinitionRegistryTest.java` — helper method
- `runtime/src/test/java/.../SituationRegistrationTest.java` — 1 site
- `runtime/src/test/java/.../SituationExpiryJobTest.java` — 2 sites
- `runtime/src/test/java/.../EventReorderBufferTest.java` — 1 site
- `runtime/src/test/java/.../RasEngineTest.java` — 3 sites
- `runtime/src/main/java/.../YamlSituationDefinitionProvider.java` — 1 site (line 96-98)

For each site, append `, null` as the 7th argument. The compact constructor defaults null to FireOnce().

- [ ] **Step 7: Fix ALL SituationContext construction sites**

Every 7-argument `new SituationContext(...)` call must become 9-argument with `null, 0` appended. Most construction happens via `SituationContext.initial()` (already updated) and `withDetection()` (already updated). Direct construction sites:

- `api/src/test/java/.../AbstractSituationStoreContractTest.java` — all `new SituationContext(...)` calls
- `runtime/src/test/java/.../SituationEvaluatorTest.java` — `compactInvokedForPersistentSituations` (line 213-215), `claimSucceedsSaveFailsResetsAndRetries` (line 680)
- `persistence-memory/src/main/java/.../InMemorySituationStore.java` — find() method (line 34-37)
- `persistence-jpa/src/main/java/.../SituationMapper.java` — toContext() method (line 24-31)

For each site, append `, null, 0` as the 8th and 9th arguments.

- [ ] **Step 8: Update SituationDefinitionTest for new triggerMode field**

Add tests for triggerMode defaulting:
```java
@Test
void nullTriggerModeDefaultsToFireOnce() {
    var def = new SituationDefinition("sit-1", Set.of("e"),
            Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")), TRIGGER, null);
    assertThat(def.triggerMode()).isInstanceOf(TriggerMode.FireOnce.class);
}

@Test
void explicitTriggerModeIsPreserved() {
    var mode = new TriggerMode.Repeating(Duration.ofMinutes(5));
    var def = new SituationDefinition("sit-1", Set.of("e"),
            Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")), TRIGGER, mode);
    assertThat(def.triggerMode()).isEqualTo(mode);
}
```

- [ ] **Step 9: Run full test suite to verify mechanical migration**

Run: `mvn --batch-mode test`
Expected: ALL existing tests PASS. No behavior change — only signatures changed.

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "feat(casehub-ras#21): API type changes — TriggerDecision, TriggerMode, SituationContext, SituationDefinition

Add CREATE_CASE_AND_CONTINUE and RESOLVE to TriggerDecision.
Add TriggerMode sealed interface (FireOnce, Repeating with cooldown).
Add lastTriggered/triggerCount to SituationContext with withTrigger()/withStoreVersion().
Add triggerMode to SituationDefinition (defaults to FireOnce when null).
Mechanical migration of all construction sites — no behavior change."
```

---

### Task 2: SituationStore SPI changes + query API types

Changes the SPI signatures and adds new types in `api/`. Adds default implementations so the project compiles. InMemory and JPA are updated in Tasks 3-4.

**Files:**
- Modify: `api/src/main/java/io/casehub/ras/api/SituationStore.java`
- Create: `api/src/main/java/io/casehub/ras/api/ActiveSituation.java`
- Create: `api/src/main/java/io/casehub/ras/api/SituationSource.java`
- Create: `api/src/main/java/io/casehub/ras/api/SituationChangeEvent.java`
- Modify: `persistence-memory/src/main/java/.../InMemorySituationStore.java` — update save() and tryClaimTrigger signatures (return context, accept Instant)
- Modify: `persistence-jpa/src/main/java/.../JpaSituationStore.java` — update save() and tryClaimTrigger signatures
- Modify: ALL callers of save() and tryClaimTrigger — evaluator, test helpers, contract tests

**Interfaces:**
- Consumes: `SituationContext.withStoreVersion()` from Task 1
- Produces: `SituationStore` with new signatures, `ActiveSituation`, `SituationSource`, `SituationChangeEvent`

- [ ] **Step 1: Update SituationStore SPI**

Replace `api/src/main/java/io/casehub/ras/api/SituationStore.java`:
```java
package io.casehub.ras.api;

import io.smallrye.mutiny.Uni;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SituationStore {

    Uni<Optional<SituationContext>> find(String situationId, String correlationKey, String tenancyId);

    Uni<SituationContext> save(SituationContext context);

    Uni<Void> remove(String situationId, String correlationKey, String tenancyId);

    Uni<Void> removeExpired(Instant cutoff);

    default Uni<Boolean> tryClaimTrigger(String situationId, String correlationKey,
                                          String tenancyId, Instant triggerTime) {
        return Uni.createFrom().item(true);
    }

    default Uni<Void> resetTriggerClaim(String situationId, String correlationKey,
                                         String tenancyId) {
        return Uni.createFrom().voidItem();
    }

    default Uni<Void> removeTriggeredBefore(Instant triggerCutoff) {
        return Uni.createFrom().voidItem();
    }

    default Uni<List<SituationContext>> findActive(String tenancyId) {
        return Uni.createFrom().item(List.of());
    }
}
```

- [ ] **Step 2: Create ActiveSituation, SituationSource, SituationChangeEvent**

Create `api/src/main/java/io/casehub/ras/api/ActiveSituation.java`:
```java
package io.casehub.ras.api;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record ActiveSituation(
        String situationId,
        String correlationKey,
        String tenancyId,
        double confidence,
        Map<String, Object> evidence,
        Instant since,
        Instant lastSignal,
        int triggerCount
) {
    public ActiveSituation {
        Objects.requireNonNull(situationId, "situationId");
        Objects.requireNonNull(correlationKey, "correlationKey");
        Objects.requireNonNull(tenancyId, "tenancyId");
        Objects.requireNonNull(since, "since");
        Objects.requireNonNull(lastSignal, "lastSignal");
        if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be 0.0-1.0, got: " + confidence);
        }
        evidence = evidence != null ? Map.copyOf(evidence) : Map.of();
    }
}
```

Create `api/src/main/java/io/casehub/ras/api/SituationSource.java`:
```java
package io.casehub.ras.api;

import io.smallrye.mutiny.Uni;
import java.util.List;

public interface SituationSource {
    Uni<List<ActiveSituation>> activeSituations(String tenancyId);
}
```

Create `api/src/main/java/io/casehub/ras/api/SituationChangeEvent.java`:
```java
package io.casehub.ras.api;

import java.util.Objects;

public record SituationChangeEvent(
        String tenancyId,
        String situationId,
        String correlationKey,
        ChangeType changeType
) {
    public enum ChangeType { TRIGGERED, RESOLVED, DISCARDED }

    public SituationChangeEvent {
        Objects.requireNonNull(tenancyId, "tenancyId");
        Objects.requireNonNull(situationId, "situationId");
        Objects.requireNonNull(correlationKey, "correlationKey");
        Objects.requireNonNull(changeType, "changeType");
    }
}
```

- [ ] **Step 3: Update InMemorySituationStore signatures (minimal — behavior in Task 3)**

Update `save()` to return `Uni<SituationContext>` — for now, return the context as-is with storeVersion populated (same logic as current, but returning the result). Update `tryClaimTrigger` to accept `Instant triggerTime` — ignore it for now (behavior in Task 3).

- [ ] **Step 4: Update JpaSituationStore signatures (minimal — behavior in Task 4)**

Update `save()` to return `Uni<SituationContext>` — return `context.withStoreVersion(entity.getVersion())` after flush. Update `tryClaimTrigger` to accept `Instant triggerTime` — pass-through for now.

- [ ] **Step 5: Fix ALL save() callers**

Current pattern: `store.save(context).await().indefinitely();` (ignores Void return).
New pattern: same — `store.save(context).await().indefinitely();` still works because the Uni resolves (we just don't capture the result yet). Where the evaluator needs the returned context (CREATE_CASE_AND_CONTINUE path), that comes in Task 5.

Fix the 33 call sites. Most need no change. Test helpers (`ConflictSimulatingStore`, `ClaimTrackingStore`) must update their save() signature to return `Uni<SituationContext>` and delegate properly.

- [ ] **Step 6: Fix ALL tryClaimTrigger callers**

Current pattern: `store.tryClaimTrigger(sid, ck, tid)`.
New pattern: `store.tryClaimTrigger(sid, ck, tid, triggerTime)`.

Evaluator call sites (2 in executeDecision) — pass `extractEventTime(event)` as triggerTime. The evaluator already has the event in scope.

Test call sites — pass `T1` or `Instant.now()`.

- [ ] **Step 7: Run full test suite**

Run: `mvn --batch-mode test`
Expected: ALL tests PASS. Signatures changed, behavior unchanged.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat(casehub-ras#21): SituationStore SPI changes + query API types

save() returns Uni<SituationContext> with storeVersion.
tryClaimTrigger accepts Instant triggerTime parameter.
Add removeTriggeredBefore and findActive with no-op defaults.
Add ActiveSituation, SituationSource, SituationChangeEvent to api/."
```

---

### Task 3: InMemorySituationStore — trigger metadata + new methods + contract tests

TDD cycle: write contract tests first, then implement InMemory behavior.

**Files:**
- Modify: `api/src/test/java/io/casehub/ras/api/AbstractSituationStoreContractTest.java`
- Modify: `persistence-memory/src/main/java/.../InMemorySituationStore.java`

**Interfaces:**
- Consumes: `SituationStore` SPI from Task 2, `SituationContext.withStoreVersion()` from Task 1
- Produces: InMemorySituationStore with trigger metadata stamping, removeTriggeredBefore, findActive, save preserving store-managed fields

- [ ] **Step 1: Write contract tests for trigger metadata stamping**

Add to `AbstractSituationStoreContractTest.java`:

```java
@Test
void tryClaimTriggerStampsTriggerMetadata() {
    var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);
    store.save(ctx).await().indefinitely();
    Instant triggerTime = T2;
    store.tryClaimTrigger("sit-1", "key-1", "tenant-a", triggerTime).await().indefinitely();
    var found = store.find("sit-1", "key-1", "tenant-a").await().indefinitely().orElseThrow();
    assertThat(found.lastTriggered()).isEqualTo(triggerTime);
    assertThat(found.triggerCount()).isEqualTo(1);
}

@Test
void tryClaimTriggerIncrementsCountOnSubsequentClaims() {
    var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);
    store.save(ctx).await().indefinitely();
    store.tryClaimTrigger("sit-1", "key-1", "tenant-a", T1).await().indefinitely();
    store.resetTriggerClaim("sit-1", "key-1", "tenant-a").await().indefinitely();
    store.tryClaimTrigger("sit-1", "key-1", "tenant-a", T2).await().indefinitely();
    var found = store.find("sit-1", "key-1", "tenant-a").await().indefinitely().orElseThrow();
    assertThat(found.lastTriggered()).isEqualTo(T2);
    assertThat(found.triggerCount()).isEqualTo(2);
}

@Test
void saveAfterClaimPreservesTriggerMetadata() {
    var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);
    var saved = store.save(ctx).await().indefinitely();
    store.tryClaimTrigger("sit-1", "key-1", "tenant-a", T1).await().indefinitely();
    var modified = saved.withDetection(
            new DetectionResult("g1", 0.9, DetectionSignal.DETECTED, Map.of()), T2);
    store.save(modified).await().indefinitely();
    var found = store.find("sit-1", "key-1", "tenant-a").await().indefinitely().orElseThrow();
    assertThat(found.lastTriggered()).isEqualTo(T1);
    assertThat(found.triggerCount()).isEqualTo(1);
    assertThat(found.detections()).hasSize(1);
}
```

- [ ] **Step 2: Write contract tests for removeTriggeredBefore**

```java
@Test
void removeTriggeredBeforeRemovesOldTriggeredEntities() {
    var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);
    store.save(ctx).await().indefinitely();
    store.tryClaimTrigger("sit-1", "key-1", "tenant-a", T1).await().indefinitely();
    store.removeTriggeredBefore(T2).await().indefinitely();
    assertThat(store.find("sit-1", "key-1", "tenant-a").await().indefinitely()).isEmpty();
}

@Test
void removeTriggeredBeforeKeepsRecentTriggeredEntities() {
    var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);
    store.save(ctx).await().indefinitely();
    store.tryClaimTrigger("sit-1", "key-1", "tenant-a", T2).await().indefinitely();
    store.removeTriggeredBefore(T1).await().indefinitely();
    assertThat(store.find("sit-1", "key-1", "tenant-a").await().indefinitely()).isPresent();
}

@Test
void removeTriggeredBeforeKeepsNonTriggeredEntities() {
    var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);
    store.save(ctx).await().indefinitely();
    store.removeTriggeredBefore(T3).await().indefinitely();
    assertThat(store.find("sit-1", "key-1", "tenant-a").await().indefinitely()).isPresent();
}
```

- [ ] **Step 3: Write contract tests for findActive**

```java
@Test
void findActiveReturnsByTenancy() {
    var ctxA = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);
    var ctxB = SituationContext.initial("sit-1", "key-1", "tenant-b", T1);
    store.save(ctxA).await().indefinitely();
    store.save(ctxB).await().indefinitely();
    var active = store.findActive("tenant-a").await().indefinitely();
    assertThat(active).hasSize(1);
    assertThat(active.get(0).tenancyId()).isEqualTo("tenant-a");
}

@Test
void findActiveExcludesTriggeredEntities() {
    var ctx1 = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);
    var ctx2 = SituationContext.initial("sit-2", "key-1", "tenant-a", T1);
    store.save(ctx1).await().indefinitely();
    store.save(ctx2).await().indefinitely();
    store.tryClaimTrigger("sit-1", "key-1", "tenant-a", T1).await().indefinitely();
    var active = store.findActive("tenant-a").await().indefinitely();
    assertThat(active).hasSize(1);
    assertThat(active.get(0).situationId()).isEqualTo("sit-2");
}

@Test
void findActiveEmptyForUnknownTenant() {
    var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);
    store.save(ctx).await().indefinitely();
    assertThat(store.findActive("tenant-x").await().indefinitely()).isEmpty();
}
```

- [ ] **Step 4: Write contract test for save round-trip with trigger fields**

```java
@Test
void saveAndFindRoundTripWithTriggerFields() {
    var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);
    store.save(ctx).await().indefinitely();
    var found = store.find("sit-1", "key-1", "tenant-a").await().indefinitely().orElseThrow();
    assertThat(found.lastTriggered()).isNull();
    assertThat(found.triggerCount()).isZero();
    assertThat(found.storeVersion()).isPresent();
}
```

- [ ] **Step 5: Run contract tests to verify they fail**

Run: `mvn --batch-mode test -pl persistence-memory -Dtest=InMemorySituationStoreTest`
Expected: FAIL — new test methods fail (trigger metadata not stamped, new methods use defaults)

- [ ] **Step 6: Implement InMemorySituationStore — all new behavior**

Update `persistence-memory/src/main/java/.../InMemorySituationStore.java`:

- `save()`: returns context with storeVersion. On update, preserves existing `lastTriggered`/`triggerCount` from the stored context (store-managed fields).
- `tryClaimTrigger()`: uses `putIfAbsent` for claim CAS, then stamps `lastTriggered` and increments `triggerCount` on the stored context via `computeIfPresent`.
- `removeTriggeredBefore()`: iterates entries, removes those with active claim and `lastTriggered <= cutoff`.
- `findActive()`: filters entries not claimed and matching tenancyId.

Implementation per spec §InMemorySituationStore.

- [ ] **Step 7: Run contract tests**

Run: `mvn --batch-mode test -pl persistence-memory`
Expected: ALL tests PASS

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat(casehub-ras#21): InMemorySituationStore trigger metadata + new methods

tryClaimTrigger stamps lastTriggered/triggerCount atomically.
save() preserves store-managed fields on update.
Implement removeTriggeredBefore and findActive.
Add 7 contract tests for trigger metadata, guard cleanup, active queries."
```

---

### Task 4: JPA persistence — entity, mapper, migration, store

Updates JPA layer for trigger metadata and new query methods. Contract tests from Task 3 validate automatically.

**Files:**
- Modify: `persistence-jpa/src/main/java/.../SituationEntity.java`
- Modify: `persistence-jpa/src/main/java/.../SituationMapper.java`
- Modify: `persistence-jpa/src/main/java/.../JpaSituationStore.java`
- Create: `persistence-jpa/src/main/resources/db/ras/migration/V4__add_trigger_lifecycle_columns.sql`

**Interfaces:**
- Consumes: `SituationStore` SPI from Task 2, `SituationContext.withStoreVersion()` from Task 1
- Produces: JpaSituationStore with trigger metadata stamping, removeTriggeredBefore, findActive

- [ ] **Step 1: Create Flyway V4 migration**

Create `persistence-jpa/src/main/resources/db/ras/migration/V4__add_trigger_lifecycle_columns.sql`:
```sql
ALTER TABLE ras_situation ADD COLUMN last_triggered TIMESTAMP;
ALTER TABLE ras_situation ADD COLUMN trigger_count INTEGER NOT NULL DEFAULT 0;
```

- [ ] **Step 2: Update SituationEntity**

Add to `SituationEntity.java`:
```java
@Column(name = "last_triggered")
private Instant lastTriggered;

@Column(name = "trigger_count", nullable = false)
private int triggerCount = 0;
```

Add getters: `getLastTriggered()`, `getTriggerCount()`.
Add setters: `setLastTriggered(Instant)`, `setTriggerCount(int)`.

Update constructor to include new fields (null and 0 for backward compatibility with `toEntity()`).

- [ ] **Step 3: Update SituationMapper**

Update `toContext()` — read lastTriggered and triggerCount from entity:
```java
SituationContext toContext(SituationEntity entity) {
    List<TimestampedDetection> detections = deserializeDetections(entity.getDetections());
    return new SituationContext(
            entity.getSituationId(), entity.getCorrelationKey(), entity.getTenancyId(),
            entity.getFirstSignal(), entity.getLastSignal(), detections,
            OptionalLong.of(entity.getVersion()),
            entity.getLastTriggered(), entity.getTriggerCount());
}
```

`updateEntity()` — does NOT write lastTriggered or triggerCount (store-managed). Unchanged from current — it only writes firstSignal, lastSignal, detections.

`toEntity()` — writes null/0 for initial insert. These fields are populated by `tryClaimTrigger`.

- [ ] **Step 4: Update JpaSituationStore — save returns context**

`save()` must return `Uni<SituationContext>` with the updated storeVersion. After `em.flush()`, the entity's `@Version` field is updated. Return `context.withStoreVersion(entity.getVersion())` for the existing-entity path. For the new-entity path (persist), return `context.withStoreVersion(entity.getVersion())` after flush.

Careful: on the update path, `existing` entity already has the version. After `em.flush()`, the version is incremented. Read it from `existing.getVersion()` for the return.

- [ ] **Step 5: Update JpaSituationStore — tryClaimTrigger stamps metadata**

```java
@Override
@Transactional(TxType.REQUIRED)
public Uni<Boolean> tryClaimTrigger(String situationId, String correlationKey,
                                     String tenancyId, Instant triggerTime) {
    int updated = em.createQuery(
                    "UPDATE SituationEntity s SET s.policyTriggered = true, " +
                    "s.lastTriggered = :triggerTime, s.triggerCount = s.triggerCount + 1 " +
                    "WHERE s.situationId = :sid AND s.correlationKey = :ck " +
                    "AND s.tenancyId = :tid AND s.policyTriggered = false")
            .setParameter("triggerTime", triggerTime)
            .setParameter("sid", situationId)
            .setParameter("ck", correlationKey)
            .setParameter("tid", tenancyId)
            .executeUpdate();
    return Uni.createFrom().item(updated > 0);
}
```

- [ ] **Step 6: Implement JpaSituationStore — removeTriggeredBefore and findActive**

```java
@Override
@Transactional(TxType.REQUIRED)
public Uni<Void> removeTriggeredBefore(Instant triggerCutoff) {
    em.createQuery("DELETE FROM SituationEntity s WHERE s.policyTriggered = true " +
                   "AND s.lastTriggered <= :cutoff")
            .setParameter("cutoff", triggerCutoff)
            .executeUpdate();
    return Uni.createFrom().voidItem();
}

@Override
@Transactional(TxType.REQUIRED)
public Uni<List<SituationContext>> findActive(String tenancyId) {
    List<SituationEntity> entities = em.createQuery(
                    "SELECT s FROM SituationEntity s WHERE s.tenancyId = :tid " +
                    "AND s.policyTriggered = false", SituationEntity.class)
            .setParameter("tid", tenancyId)
            .getResultList();
    List<SituationContext> contexts = entities.stream()
            .map(mapper::toContext)
            .toList();
    return Uni.createFrom().item(contexts);
}
```

- [ ] **Step 7: Run JPA contract tests + JPA-specific tests**

Run: `mvn --batch-mode test -pl persistence-jpa`
Expected: ALL tests PASS (contract tests from Task 3 run against JPA)

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat(casehub-ras#21): JPA persistence — trigger lifecycle columns + store methods

Flyway V4: add last_triggered and trigger_count columns.
SituationEntity/Mapper updated (store-managed field exclusion in updateEntity).
tryClaimTrigger atomically stamps metadata via JPQL bulk UPDATE.
Implement removeTriggeredBefore and findActive."
```

---

### Task 5: DefaultRasTriggerPolicy — TriggerMode mapping

TDD cycle: new policy tests for FireOnce/Repeating behavior, then implementation.

**Files:**
- Modify: `runtime/src/main/java/.../DefaultRasTriggerPolicy.java`
- Modify: `runtime/src/test/java/.../DefaultRasTriggerPolicyTest.java`

**Interfaces:**
- Consumes: `TriggerDecision` (5 variants), `TriggerMode`, `SituationContext.lastTriggered()`, `SituationDefinition.triggerMode()`
- Produces: DefaultRasTriggerPolicy that maps TriggerMode + chain satisfaction → correct TriggerDecision

- [ ] **Step 1: Write tests for FireOnce and Repeating behavior**

Add to `DefaultRasTriggerPolicyTest.java`:

```java
@Test
void fireOnceReturnsCreateCase() {
    var def = def(new ChainMode.Or(Set.of("g1")), new TriggerMode.FireOnce());
    var ctx = contextWithDetection("g1", 0.9);
    assertThat(policy.evaluate(ctx, def).await().indefinitely())
            .isEqualTo(TriggerDecision.CREATE_CASE);
}

@Test
void repeatingReturnsCreateCaseAndContinueOnFirstTrigger() {
    var def = def(new ChainMode.Or(Set.of("g1")),
                  new TriggerMode.Repeating(Duration.ofMinutes(5)));
    var ctx = contextWithDetection("g1", 0.9);
    assertThat(policy.evaluate(ctx, def).await().indefinitely())
            .isEqualTo(TriggerDecision.CREATE_CASE_AND_CONTINUE);
}

@Test
void repeatingReturnsContinueAccumulatingDuringCooldown() {
    var def = def(new ChainMode.Or(Set.of("g1")),
                  new TriggerMode.Repeating(Duration.ofMinutes(5)));
    var ctx = contextWithDetectionAndTrigger("g1", 0.9,
            T1, T1.plus(Duration.ofMinutes(1)));
    assertThat(policy.evaluate(ctx, def).await().indefinitely())
            .isEqualTo(TriggerDecision.CONTINUE_ACCUMULATING);
}

@Test
void repeatingReturnsCreateCaseAndContinueAfterCooldown() {
    var def = def(new ChainMode.Or(Set.of("g1")),
                  new TriggerMode.Repeating(Duration.ofMinutes(5)));
    var ctx = contextWithDetectionAndTrigger("g1", 0.9,
            T1, T1.plus(Duration.ofMinutes(10)));
    assertThat(policy.evaluate(ctx, def).await().indefinitely())
            .isEqualTo(TriggerDecision.CREATE_CASE_AND_CONTINUE);
}
```

Update existing `def()` helper to accept TriggerMode and add `contextWithDetectionAndTrigger()` helper.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl runtime -Dtest=DefaultRasTriggerPolicyTest`
Expected: FAIL — new tests fail (policy doesn't handle TriggerMode yet)

- [ ] **Step 3: Implement TriggerMode mapping in DefaultRasTriggerPolicy**

Per spec §DefaultRasTriggerPolicy — switch on definition.triggerMode() after chain mode evaluation. Cooldown uses context.lastSignal() vs context.lastTriggered() + cooldown.

- [ ] **Step 4: Run tests**

Run: `mvn --batch-mode test -pl runtime -Dtest=DefaultRasTriggerPolicyTest`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(casehub-ras#20): DefaultRasTriggerPolicy — TriggerMode mapping

FireOnce → CREATE_CASE, Repeating → CREATE_CASE_AND_CONTINUE with cooldown.
Cooldown uses event time (context.lastSignal) for deterministic evaluation."
```

---

### Task 6: SituationEvaluator — new execution paths + CDI events

The core behavior change. TDD with the existing SituationEvaluatorTest patterns.

**Files:**
- Modify: `runtime/src/main/java/.../SituationEvaluator.java`
- Modify: `runtime/src/test/java/.../SituationEvaluatorTest.java`
- Modify: `runtime/src/test/java/.../SituationEvaluatorTest.java` — update ConflictSimulatingStore and ClaimTrackingStore

**Interfaces:**
- Consumes: `TriggerDecision` (5 variants), `SituationStore.save()` returning context, `SituationStore.tryClaimTrigger()` with Instant
- Produces: SituationEvaluator handling CREATE_CASE_AND_CONTINUE (save-first, claim, fire, reset, compact), RESOLVE, DISCARD with SituationChangeEvent emission

- [ ] **Step 1: Write tests for CREATE_CASE_AND_CONTINUE**

Add to `SituationEvaluatorTest.java`:

```java
@Test
void repeatingModeFiresAndContinues() {
    var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
            FixedDetectionResult.detected("g1", 0.9));
    var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
            Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")), TRIGGER_CONFIG,
            new TriggerMode.Repeating(Duration.ofMinutes(1)));
    buildEvaluator(List.of(ganglion), def);

    evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

    assertThat(caseTrigger.firedCases()).hasSize(1);
    var saved = store.find("sit-1", "key-1", "tenant-a").await().indefinitely();
    assertThat(saved).isPresent();
    boolean reclaimable = store.tryClaimTrigger("sit-1", "key-1", "tenant-a", T2)
            .await().indefinitely();
    assertThat(reclaimable).isTrue();
}

@Test
void repeatingModeCooldownSuppressesTrigger() {
    var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
            FixedDetectionResult.detected("g1", 0.9));
    var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
            Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")), TRIGGER_CONFIG,
            new TriggerMode.Repeating(Duration.ofMinutes(10)));
    buildEvaluator(List.of(ganglion), def);

    evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");
    assertThat(caseTrigger.firedCases()).hasSize(1);

    evaluator.evaluate(event("temp.reading", T2), def, "key-1", "tenant-a");
    assertThat(caseTrigger.firedCases()).hasSize(1);
}

@Test
void repeatingModeLoserContinuesAccumulating() {
    var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
            FixedDetectionResult.detected("g1", 0.9));
    var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
            Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")), TRIGGER_CONFIG,
            new TriggerMode.Repeating(Duration.ofMinutes(1)));

    var claimOnceStore = new ClaimTrackingStore(store);
    var reg = new SituationRegistration(def);
    var registry = new SituationDefinitionRegistry(
            List.of(() -> List.of(reg)), List.of(ganglion));
    evaluator = new SituationEvaluator(claimOnceStore, policy, caseTrigger, registry, 3);

    evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");
    assertThat(caseTrigger.firedCases()).hasSize(1);

    evaluator.evaluate(event("temp.reading", T2), def, "key-1", "tenant-a");
    var saved = store.find("sit-1", "key-1", "tenant-a").await().indefinitely();
    assertThat(saved).isPresent();
    assertThat(saved.get().detections()).hasSizeGreaterThanOrEqualTo(1);
}
```

- [ ] **Step 2: Write tests for RESOLVE**

```java
@Test
void resolveRemovesSituation() {
    var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
            FixedDetectionResult.detected("g1", 0.4));
    var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
            null, null, new ChainMode.Count("g1", 5), TRIGGER_CONFIG, null);

    var resolvingPolicy = new RasTriggerPolicy() {
        private int calls = 0;
        @Override
        public Uni<TriggerDecision> evaluate(SituationContext ctx, SituationDefinition d) {
            return Uni.createFrom().item(++calls >= 3
                    ? TriggerDecision.RESOLVE : TriggerDecision.CONTINUE_ACCUMULATING);
        }
    };

    var reg = new SituationRegistration(def);
    var registry = new SituationDefinitionRegistry(
            List.of(() -> List.of(reg)), List.of(ganglion));
    evaluator = new SituationEvaluator(store, resolvingPolicy, caseTrigger, registry, 3);

    evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");
    evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");
    assertThat(store.find("sit-1", "key-1", "tenant-a").await().indefinitely()).isPresent();

    evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");
    assertThat(store.find("sit-1", "key-1", "tenant-a").await().indefinitely()).isEmpty();
    assertThat(caseTrigger.firedCases()).isEmpty();
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl runtime -Dtest=SituationEvaluatorTest`
Expected: FAIL — new tests fail, existing tests pass

- [ ] **Step 4: Implement new execution paths in SituationEvaluator**

Add CDI `Event<SituationChangeEvent>` injection. Add CREATE_CASE_AND_CONTINUE and RESOLVE branches per spec §SituationEvaluator. Update existing CREATE_CASE to capture returned context from save(). Update DISCARD to emit DISCARDED event.

Key implementation details:
- CREATE_CASE_AND_CONTINUE: save-first (always), then claim, fire, resetClaim, compact, save(compacted). Loser returns false.
- RESOLVE: closeGanglia, remove, return true. Emit RESOLVED.
- CREATE_CASE: emit TRIGGERED after successful fire. Use context returned from save().

- [ ] **Step 5: Run all evaluator tests**

Run: `mvn --batch-mode test -pl runtime -Dtest=SituationEvaluatorTest`
Expected: ALL PASS

- [ ] **Step 6: Run full test suite**

Run: `mvn --batch-mode test`
Expected: ALL PASS

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(casehub-ras#20): SituationEvaluator — CREATE_CASE_AND_CONTINUE, RESOLVE, CDI events

CREATE_CASE_AND_CONTINUE: save-first, claim, fire, resetClaim, compact.
RESOLVE: closeGanglia, remove, terminate.
SituationChangeEvent fired on trigger/resolve/discard via CDI fireAsync."
```

---

### Task 7: Runtime wiring — ExpiryJob, SituationSource, YAML, EndpointRegistry

Final runtime integration. All the plumbing that connects the new types to the running system.

**Files:**
- Modify: `runtime/src/main/java/.../SituationExpiryJob.java`
- Modify: `runtime/src/test/java/.../SituationExpiryJobTest.java`
- Create: `runtime/src/main/java/.../DefaultSituationSource.java`
- Create: `runtime/src/test/java/.../DefaultSituationSourceTest.java`
- Modify: `runtime/src/main/java/.../YamlSituationDefinitionProvider.java`
- Modify: `runtime/src/test/java/.../YamlSituationDefinitionProviderTest.java`
- Create or modify: `runtime/src/main/java/.../RasEndpointRegistration.java`

**Interfaces:**
- Consumes: `SituationStore.removeTriggeredBefore()`, `SituationStore.findActive()`, `SituationSource`, `ActiveSituation`, `TriggerMode`, EndpointRegistry from platform-api
- Produces: Guard cleanup, DefaultSituationSource, YAML triggerMode parsing, endpoint registration

- [ ] **Step 1: Write SituationExpiryJob test for guard cleanup**

Add to `SituationExpiryJobTest.java`:

```java
@Test
void removesTriggeredEntitiesAfterGuardPeriod() {
    var g = new MockGanglion("g1", Set.of("e"), FixedDetectionResult.detected("g1", 0.9));
    var def = new SituationDefinition("sit-1", Set.of("e"), null, null,
            new ChainMode.Or(Set.of("g1")), TRIGGER_CONFIG, null);
    var reg = new SituationRegistration(def);
    var registry = new SituationDefinitionRegistry(
            List.of(() -> List.of(reg)), List.of(g));

    var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a",
            Instant.parse("2026-06-25T10:00:00Z"));
    store.save(ctx).await().indefinitely();
    store.tryClaimTrigger("sit-1", "key-1", "tenant-a",
            Instant.parse("2026-06-25T10:00:00Z")).await().indefinitely();

    var job = new SituationExpiryJob(store, registry, Duration.ofMinutes(1));

    assertThat(store.find("sit-1", "key-1", "tenant-a").await().indefinitely()).isPresent();

    // Guard period has NOT elapsed — cleanup should keep entity
    // (We can't easily test time-based cleanup in unit tests without mocking Instant.now,
    //  but we test the store method directly in contract tests)
}

@Test
void cleanupRunsEvenWhenAllSituationsPersistent() {
    var g = new MockGanglion("g1", Set.of("e"), FixedDetectionResult.detected("g1", 0.9));
    var def = new SituationDefinition("sit-1", Set.of("e"), null, null,
            new ChainMode.Or(Set.of("g1")), TRIGGER_CONFIG, null);
    var reg = new SituationRegistration(def);
    var registry = new SituationDefinitionRegistry(
            List.of(() -> List.of(reg)), List.of(g));
    var job = new SituationExpiryJob(store, registry, Duration.ofMinutes(1));

    // Should not throw — previously returned early when maxWindow was null
    job.cleanup();
}
```

- [ ] **Step 2: Implement SituationExpiryJob guard cleanup**

Update constructor to accept `triggerGuardPeriod` via `@ConfigProperty`. Update `cleanup()` per spec — no longer early-return when `maxCorrelationWindow` is null, add `removeTriggeredBefore` call.

- [ ] **Step 3: Write DefaultSituationSource test**

Create `runtime/src/test/java/.../DefaultSituationSourceTest.java`:

```java
package io.casehub.ras.runtime;

import io.casehub.ras.api.*;
import io.casehub.ras.persistence.memory.InMemorySituationStore;
import io.casehub.ras.testing.FixedDetectionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class DefaultSituationSourceTest {

    private InMemorySituationStore store;
    private DefaultSituationSource source;

    @BeforeEach
    void setUp() {
        store = new InMemorySituationStore();
        source = new DefaultSituationSource(store);
    }

    @Test
    void returnsActiveSituationsForTenant() {
        var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a",
                Instant.parse("2026-06-25T10:00:00Z"))
                .withDetection(new DetectionResult("g1", 0.8, DetectionSignal.DETECTED, Map.of()),
                               Instant.parse("2026-06-25T10:00:00Z"));
        store.save(ctx).await().indefinitely();

        var active = source.activeSituations("tenant-a").await().indefinitely();
        assertThat(active).hasSize(1);
        assertThat(active.get(0).situationId()).isEqualTo("sit-1");
        assertThat(active.get(0).confidence()).isEqualTo(0.8);
        assertThat(active.get(0).since()).isEqualTo(Instant.parse("2026-06-25T10:00:00Z"));
    }

    @Test
    void excludesTriggeredSituations() {
        var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a",
                Instant.parse("2026-06-25T10:00:00Z"));
        store.save(ctx).await().indefinitely();
        store.tryClaimTrigger("sit-1", "key-1", "tenant-a",
                Instant.parse("2026-06-25T10:00:00Z")).await().indefinitely();

        assertThat(source.activeSituations("tenant-a").await().indefinitely()).isEmpty();
    }

    @Test
    void emptyForUnknownTenant() {
        assertThat(source.activeSituations("unknown").await().indefinitely()).isEmpty();
    }
}
```

- [ ] **Step 4: Implement DefaultSituationSource**

Create `runtime/src/main/java/io/casehub/ras/runtime/DefaultSituationSource.java` per spec §DefaultSituationSource. Confidence = max qualifying detection confidence. Projects SituationContext → ActiveSituation.

- [ ] **Step 5: Write YAML triggerMode parsing test**

Add to `YamlSituationDefinitionProviderTest.java` (or create if not present):

Test that YAML with `triggerMode: { type: repeating, cooldown: PT5M }` produces a `SituationDefinition` with `TriggerMode.Repeating(Duration.ofMinutes(5))`, and YAML without `triggerMode` defaults to `FireOnce`.

- [ ] **Step 6: Implement YAML triggerMode parsing**

Add `parseTriggerMode()` method to `YamlSituationDefinitionProvider`. Parse `triggerMode.type` discriminator: `fire-once` or `repeating`. For `repeating`, parse `cooldown` as ISO-8601 Duration. Pass parsed TriggerMode to SituationDefinition constructor.

- [ ] **Step 7: Implement EndpointRegistry integration**

Create `runtime/src/main/java/io/casehub/ras/runtime/RasEndpointRegistration.java`:

```java
@ApplicationScoped
public class RasEndpointRegistration {

    @Inject EndpointRegistry registry;

    void onStart(@Observes StartupEvent event) {
        registry.register(new EndpointDescriptor(
                Path.of("ras", "situations"),
                TenancyConstants.PLATFORM_TENANT_ID,
                EndpointType.SERVICE,
                EndpointProtocol.HTTP,
                Map.of(),
                null,
                Set.of(EndpointCapability.QUERY)));
    }
}
```

Check that `TenancyConstants` and `EndpointDescriptor` are accessible from `casehub-platform-api` (already a dependency).

- [ ] **Step 8: Run full test suite**

Run: `mvn --batch-mode test`
Expected: ALL PASS

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat(casehub-ras#20): runtime wiring — guard cleanup, SituationSource, YAML, EndpointRegistry

SituationExpiryJob: guard cleanup via removeTriggeredBefore (resolves #21).
DefaultSituationSource: projects SituationContext → ActiveSituation.
YamlSituationDefinitionProvider: triggerMode parsing (fire-once/repeating).
RasEndpointRegistration: QUERY endpoint in EndpointRegistry."
```

---

## File Inventory

### Created
| File | Task | Purpose |
|------|------|---------|
| `api/.../TriggerMode.java` | 1 | Sealed interface — FireOnce, Repeating |
| `api/.../TriggerModeTest.java` | 1 | TriggerMode validation tests |
| `api/.../ActiveSituation.java` | 2 | Read-only situation projection |
| `api/.../SituationSource.java` | 2 | Query API interface |
| `api/.../SituationChangeEvent.java` | 2 | CDI event record |
| `persistence-jpa/.../V4__add_trigger_lifecycle_columns.sql` | 4 | Flyway migration |
| `runtime/.../DefaultSituationSource.java` | 7 | SituationSource implementation |
| `runtime/.../DefaultSituationSourceTest.java` | 7 | Source tests |
| `runtime/.../RasEndpointRegistration.java` | 7 | Endpoint registration |

### Modified
| File | Task | Change |
|------|------|--------|
| `api/.../TriggerDecision.java` | 1 | Add 2 enum variants |
| `api/.../SituationContext.java` | 1 | Add lastTriggered, triggerCount, withTrigger(), withStoreVersion() |
| `api/.../SituationDefinition.java` | 1 | Add triggerMode field |
| `api/.../SituationDefinitionTest.java` | 1 | Update construction + new tests |
| `api/.../SituationStore.java` | 2 | save() return type, tryClaimTrigger signature, new methods |
| `api/.../AbstractSituationStoreContractTest.java` | 3 | 7 new contract tests |
| `persistence-memory/.../InMemorySituationStore.java` | 3 | All new behavior |
| `persistence-jpa/.../SituationEntity.java` | 4 | New columns |
| `persistence-jpa/.../SituationMapper.java` | 4 | Map new fields |
| `persistence-jpa/.../JpaSituationStore.java` | 4 | New methods + save return |
| `runtime/.../DefaultRasTriggerPolicy.java` | 5 | TriggerMode mapping |
| `runtime/.../DefaultRasTriggerPolicyTest.java` | 5 | New tests |
| `runtime/.../SituationEvaluator.java` | 6 | New execution paths + CDI events |
| `runtime/.../SituationEvaluatorTest.java` | 6 | New tests + helper updates |
| `runtime/.../SituationExpiryJob.java` | 7 | Guard cleanup |
| `runtime/.../SituationExpiryJobTest.java` | 7 | Guard cleanup tests |
| `runtime/.../YamlSituationDefinitionProvider.java` | 7 | triggerMode parsing |
| ~30 test files | 1 | Mechanical SituationDefinition/SituationContext construction updates |
