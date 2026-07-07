# Service Lifecycle RAS Integration — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #6 — Design: service lifecycle management pattern
**Issue group:** #6

**Goal:** Extend casehub-ras to support signaling existing cases via enriched CDI events,
dynamic situation registration, and a generalized trigger output model.

**Architecture:** Replace mandatory `CaseTriggerConfig` with a `TriggerAction` sealed
interface (`CreateCase` | `NotifyOnly`). Enrich `SituationChangeEvent` with `SituationContext`.
Add `register()`/`deregister()` to `SituationDefinitionRegistry` using copy-on-write
`RegistrySnapshot`. Add `SituationStore.removeAllForSituation()` for persistent situation
cleanup.

**Tech Stack:** Java 21, Quarkus, Mutiny, CDI, JPA/Hibernate

## Global Constraints

- All types in `api/` — no runtime dependencies in the API module
- `TriggerAction` is a sealed interface in `io.casehub.ras.api`
- `TriggerDecision` values rename: `CREATE_CASE` → `TRIGGER`, `CREATE_CASE_AND_CONTINUE` → `TRIGGER_AND_CONTINUE`
- `SituationChangeEvent` compact constructor validates all 5 fields with `Objects.requireNonNull`
- `RegistrySnapshot` is an immutable record — one volatile swap for atomicity
- `NotifyOnly` evaluator path awaits CDI event delivery; `CreateCase` event remains fire-and-forget
- Cross-repo consumers (desiredstate/ras-adapter, ops/deployment) are tracked separately — not in this plan

---

### Task 1: TriggerAction sealed interface

**Files:**
- Create: `api/src/main/java/io/casehub/ras/api/TriggerAction.java`
- Test: `api/src/test/java/io/casehub/ras/api/TriggerActionTest.java`

**Interfaces:**
- Consumes: `CaseTriggerConfig` (existing)
- Produces: `TriggerAction` sealed interface with `CreateCase(CaseTriggerConfig)` and `NotifyOnly()` — used by Task 2 (SituationDefinition), Task 5 (SituationEvaluator), Task 7 (YAML)

- [ ] **Step 1: Write tests for TriggerAction**

```java
package io.casehub.ras.api;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class TriggerActionTest {

    @Test
    void createCase_wraps_config() {
        var config = new CaseTriggerConfig("ns", "case", "1.0", Map.of());
        var action = new TriggerAction.CreateCase(config);
        assertThat(action.config()).isSameAs(config);
    }

    @Test
    void createCase_rejects_null_config() {
        assertThatNullPointerException()
                .isThrownBy(() -> new TriggerAction.CreateCase(null))
                .withMessage("config");
    }

    @Test
    void notifyOnly_is_a_triggerAction() {
        TriggerAction action = new TriggerAction.NotifyOnly();
        assertThat(action).isInstanceOf(TriggerAction.class);
    }

    @Test
    void sealed_permits_exhaustive_switch() {
        TriggerAction action = new TriggerAction.NotifyOnly();
        String result = switch (action) {
            case TriggerAction.CreateCase c -> "create:" + c.config().caseName();
            case TriggerAction.NotifyOnly n -> "notify";
        };
        assertThat(result).isEqualTo("notify");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn --batch-mode test -pl api -Dtest=TriggerActionTest`
Expected: FAIL — `TriggerAction` not found

- [ ] **Step 3: Implement TriggerAction**

```java
package io.casehub.ras.api;

import java.util.Objects;

public sealed interface TriggerAction {
    record CreateCase(CaseTriggerConfig config) implements TriggerAction {
        public CreateCase {
            Objects.requireNonNull(config, "config");
        }
    }
    record NotifyOnly() implements TriggerAction {}
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn --batch-mode test -pl api -Dtest=TriggerActionTest`
Expected: PASS

- [ ] **Step 5: Commit**

```
feat(casehub-ras#6): add TriggerAction sealed interface
```

---

### Task 2: TriggerDecision rename + SituationDefinition migration

**Files:**
- Modify: `api/src/main/java/io/casehub/ras/api/TriggerDecision.java`
- Modify: `api/src/main/java/io/casehub/ras/api/SituationDefinition.java`
- Modify: `runtime/src/main/java/io/casehub/ras/runtime/DefaultRasTriggerPolicy.java`
- Modify: `runtime/src/main/java/io/casehub/ras/runtime/SituationEvaluator.java` (switch labels only)
- Modify: `runtime/src/main/java/io/casehub/ras/runtime/YamlSituationDefinitionProvider.java` (constructor call)
- Modify: All test files that reference `CREATE_CASE`, `CREATE_CASE_AND_CONTINUE`, or `SituationDefinition` constructors

**Interfaces:**
- Consumes: `TriggerAction` from Task 1
- Produces: Renamed `TriggerDecision.TRIGGER` / `TRIGGER_AND_CONTINUE`, `SituationDefinition` with `triggerAction` field

This is a mechanical migration. Every `CaseTriggerConfig` passed to `SituationDefinition` becomes `new TriggerAction.CreateCase(config)`. Every `TriggerDecision.CREATE_CASE` becomes `TriggerDecision.TRIGGER`.

- [ ] **Step 1: Rename TriggerDecision enum values**

In `api/src/main/java/io/casehub/ras/api/TriggerDecision.java`:
```java
public enum TriggerDecision {
    TRIGGER,
    TRIGGER_AND_CONTINUE,
    CONTINUE_ACCUMULATING,
    DISCARD,
    RESOLVE
}
```

- [ ] **Step 2: Change SituationDefinition field**

In `api/src/main/java/io/casehub/ras/api/SituationDefinition.java`, replace `CaseTriggerConfig triggerConfig` with `TriggerAction triggerAction`:
```java
public record SituationDefinition(
    String situationId,
    Set<String> eventTypes,
    Duration correlationWindow,
    Duration eventBufferDelay,
    ChainMode chainMode,
    TriggerAction triggerAction,
    TriggerMode triggerMode
) {
    public SituationDefinition {
        Objects.requireNonNull(situationId, "situationId");
        Objects.requireNonNull(chainMode, "chainMode");
        Objects.requireNonNull(triggerAction, "triggerAction");
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

- [ ] **Step 3: Update DefaultRasTriggerPolicy**

Replace all `TriggerDecision.CREATE_CASE` → `TriggerDecision.TRIGGER` and `TriggerDecision.CREATE_CASE_AND_CONTINUE` → `TriggerDecision.TRIGGER_AND_CONTINUE` in `runtime/src/main/java/io/casehub/ras/runtime/DefaultRasTriggerPolicy.java`.

Key lines: 34 (`TRIGGER`), 42 (`TRIGGER_AND_CONTINUE`), 48 (`TRIGGER_AND_CONTINUE`).

- [ ] **Step 4: Update SituationEvaluator switch labels**

In `runtime/src/main/java/io/casehub/ras/runtime/SituationEvaluator.java`, replace switch case labels in `executeDecision()`:
- `case CREATE_CASE ->` → `case TRIGGER ->`
- `case CREATE_CASE_AND_CONTINUE ->` → `case TRIGGER_AND_CONTINUE ->`

- [ ] **Step 5: Update YamlSituationDefinitionProvider constructor call**

In `parseSituation()`, wrap the `CaseTriggerConfig` in `TriggerAction.CreateCase`:
```java
CaseTriggerConfig triggerConfig = parseTriggerConfig(triggerMap);

SituationDefinition def = new SituationDefinition(
        situationId, new LinkedHashSet<>(eventTypeList),
        correlationWindow, eventBufferDelay, chainMode,
        new TriggerAction.CreateCase(triggerConfig), triggerMode);
```

- [ ] **Step 6: Update all test files**

Mechanical find-and-replace across test files in api/, runtime/, testing/:
1. `TriggerDecision.CREATE_CASE` → `TriggerDecision.TRIGGER` (not `CREATE_CASE_AND_CONTINUE`)
2. `TriggerDecision.CREATE_CASE_AND_CONTINUE` → `TriggerDecision.TRIGGER_AND_CONTINUE`
3. Every `new SituationDefinition(... new CaseTriggerConfig(...), ...)` → `new SituationDefinition(... new TriggerAction.CreateCase(new CaseTriggerConfig(...)), ...)`

Files to update (from search results):
- `api/src/test/.../SituationDefinitionTest.java`
- `api/src/test/.../SituationRegistrationTest.java`
- `runtime/src/test/.../DefaultRasTriggerPolicyTest.java` (~30 enum references)
- `runtime/src/test/.../SituationEvaluatorTest.java`
- `runtime/src/test/.../SituationDefinitionRegistryTest.java`
- `runtime/src/test/.../SituationExpiryJobTest.java`
- `runtime/src/test/.../RasEngineTest.java`
- `runtime/src/test/.../EventReorderBufferTest.java`
- `runtime/src/test/.../YamlSituationDefinitionProviderTest.java`

- [ ] **Step 7: Run full build to verify**

Run: `mvn --batch-mode install`
Expected: BUILD SUCCESS

- [ ] **Step 8: Commit**

```
feat(casehub-ras#6): rename TriggerDecision values, migrate SituationDefinition to TriggerAction
```

---

### Task 3: Enriched SituationChangeEvent

**Files:**
- Modify: `api/src/main/java/io/casehub/ras/api/SituationChangeEvent.java`
- Modify: `runtime/src/main/java/io/casehub/ras/runtime/SituationEvaluator.java` (fireAsync calls)
- Modify: `runtime/src/test/.../SituationEvaluatorTest.java` (TestChangeEvent, assertions)
- Modify: `runtime/src/test/.../RasEngineTest.java` (NoOpChangeEvent)

**Interfaces:**
- Consumes: `SituationContext` (existing)
- Produces: `SituationChangeEvent` with `context` field — used by Task 5 (evaluator dispatch)

- [ ] **Step 1: Write test for enriched SituationChangeEvent**

Add to existing `api/src/test/.../SituationChangeEventTest.java` (or create if absent):

```java
@Test
void constructor_includes_context() {
    var context = new SituationContext("sit", "key", "tenant",
            Instant.now(), Instant.now(), List.of(), OptionalLong.empty(), null, 0);
    var event = new SituationChangeEvent("tenant", "sit", "key",
            SituationChangeEvent.ChangeType.TRIGGERED, context);
    assertThat(event.context()).isSameAs(context);
}

@Test
void constructor_rejects_null_context() {
    assertThatNullPointerException()
            .isThrownBy(() -> new SituationChangeEvent("t", "s", "k",
                    SituationChangeEvent.ChangeType.TRIGGERED, null))
            .withMessage("context");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn --batch-mode test -pl api -Dtest=SituationChangeEventTest`
Expected: FAIL — constructor doesn't accept 5 args

- [ ] **Step 3: Update SituationChangeEvent**

```java
package io.casehub.ras.api;

import java.util.Objects;

public record SituationChangeEvent(
        String tenancyId,
        String situationId,
        String correlationKey,
        ChangeType changeType,
        SituationContext context
) {
    public enum ChangeType { TRIGGERED, RESOLVED, DISCARDED }

    public SituationChangeEvent {
        Objects.requireNonNull(tenancyId, "tenancyId");
        Objects.requireNonNull(situationId, "situationId");
        Objects.requireNonNull(correlationKey, "correlationKey");
        Objects.requireNonNull(changeType, "changeType");
        Objects.requireNonNull(context, "context");
    }
}
```

- [ ] **Step 4: Update SituationEvaluator fireAsync calls**

In `executeDecision()`, every `new SituationChangeEvent(tenancyId, situationId, correlationKey, ChangeType.X)` gains the `context` argument. There are 4 call sites (lines ~186, ~211, ~235, ~243):

```java
changeEvent.fireAsync(new SituationChangeEvent(
        tenancyId, situationId, correlationKey,
        SituationChangeEvent.ChangeType.TRIGGERED, context));
```

For DISCARD and RESOLVE branches, the `context` variable is available from `processEvent()`.

- [ ] **Step 5: Update test mocks**

In `SituationEvaluatorTest.java`, the `TestChangeEvent` class and assertions need updating.
In `RasEngineTest.java`, the `NoOpChangeEvent` class needs updating.
Any test creating `SituationChangeEvent` directly needs the 5th `context` arg.

- [ ] **Step 6: Run full build**

Run: `mvn --batch-mode install`
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```
feat(casehub-ras#6): enrich SituationChangeEvent with SituationContext
```

---

### Task 4: SituationStore.removeAllForSituation

**Files:**
- Modify: `api/src/main/java/io/casehub/ras/api/SituationStore.java`
- Modify: `persistence-memory/src/main/java/.../InMemorySituationStore.java`
- Modify: `persistence-jpa/src/main/java/.../JpaSituationStore.java`
- Test: `persistence-memory/src/test/.../InMemorySituationStoreTest.java`
- Test: `persistence-jpa/src/test/.../JpaSituationStoreTest.java`

**Interfaces:**
- Consumes: `SituationStore` SPI (existing)
- Produces: `removeAllForSituation(String situationId)` method — used by consuming apps at deregister time

- [ ] **Step 1: Write tests for InMemorySituationStore.removeAllForSituation**

```java
@Test
void removeAllForSituation_removes_all_matching_entries() {
    var ctx1 = new SituationContext("sit-A", "key-1", "tenant",
            Instant.now(), Instant.now(), List.of(), OptionalLong.empty(), null, 0);
    var ctx2 = new SituationContext("sit-A", "key-2", "tenant",
            Instant.now(), Instant.now(), List.of(), OptionalLong.empty(), null, 0);
    var ctx3 = new SituationContext("sit-B", "key-1", "tenant",
            Instant.now(), Instant.now(), List.of(), OptionalLong.empty(), null, 0);

    store.save(ctx1).await().indefinitely();
    store.save(ctx2).await().indefinitely();
    store.save(ctx3).await().indefinitely();

    store.removeAllForSituation("sit-A").await().indefinitely();

    assertThat(store.find("sit-A", "key-1", "tenant").await().indefinitely()).isEmpty();
    assertThat(store.find("sit-A", "key-2", "tenant").await().indefinitely()).isEmpty();
    assertThat(store.find("sit-B", "key-1", "tenant").await().indefinitely()).isPresent();
}

@Test
void removeAllForSituation_noop_when_no_matches() {
    store.removeAllForSituation("nonexistent").await().indefinitely();
    // no exception
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn --batch-mode test -pl persistence-memory -Dtest=InMemorySituationStoreTest#removeAllForSituation*`
Expected: FAIL — method not found

- [ ] **Step 3: Add method to SituationStore SPI**

In `api/src/main/java/io/casehub/ras/api/SituationStore.java`:
```java
Uni<Void> removeAllForSituation(String situationId);
```

No default implementation — both stores must implement it.

- [ ] **Step 4: Implement in InMemorySituationStore**

```java
@Override
public Uni<Void> removeAllForSituation(String situationId) {
    store.keySet().removeIf(key -> key.situationId().equals(situationId));
    versions.keySet().removeIf(key -> key.situationId().equals(situationId));
    claims.keySet().removeIf(key -> key.situationId().equals(situationId));
    return Uni.createFrom().voidItem();
}
```

- [ ] **Step 5: Implement in JpaSituationStore**

```java
@Override
public Uni<Void> removeAllForSituation(String situationId) {
    return Uni.createFrom().item(() -> {
        em.createQuery("DELETE FROM SituationEntity e WHERE e.situationId = :situationId")
                .setParameter("situationId", situationId)
                .executeUpdate();
        return null;
    });
}
```

- [ ] **Step 6: Write JPA test (similar to InMemory test)**

- [ ] **Step 7: Run build**

Run: `mvn --batch-mode install`
Expected: BUILD SUCCESS

- [ ] **Step 8: Commit**

```
feat(casehub-ras#6): add SituationStore.removeAllForSituation for persistent situation cleanup
```

---

### Task 5: SituationEvaluator — TriggerAction dispatch + NotifyOnly semantics

**Files:**
- Modify: `runtime/src/main/java/io/casehub/ras/runtime/SituationEvaluator.java`
- Test: `runtime/src/test/.../SituationEvaluatorTest.java`

**Interfaces:**
- Consumes: `TriggerAction` (Task 1), enriched `SituationChangeEvent` (Task 3)
- Produces: `NotifyOnly` situations that await CDI event delivery and reset claim on failure

- [ ] **Step 1: Write tests for NotifyOnly trigger behavior**

Add to `SituationEvaluatorTest.java`:

```java
@Test
void notifyOnly_fires_enriched_event_without_case_creation() {
    var def = new SituationDefinition("sit", Set.of("evt"),
            Duration.ofMinutes(5), null,
            new ChainMode.Or(Set.of(GANGLION_ID)),
            new TriggerAction.NotifyOnly(), null);
    // ... register, send event that satisfies Or ...
    // Assert: changeEvent fired with TRIGGERED + context
    // Assert: caseTrigger.fire() was NOT called
}

@Test
void notifyOnly_resets_claim_on_event_delivery_failure() {
    // Use a TestChangeEvent that throws on fireAsync
    // Assert: claim is reset, situation can re-trigger
}

@Test
void createCase_fires_event_as_fire_and_forget() {
    // Existing behavior: caseTrigger.fire() + changeEvent.fireAsync()
    // Assert: event delivery failure does NOT reset claim
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl runtime -Dtest=SituationEvaluatorTest#notifyOnly*`
Expected: FAIL — current code always calls caseTrigger.fire()

- [ ] **Step 3: Update executeDecision() for TRIGGER branch**

Replace the `TRIGGER` (was `CREATE_CASE`) branch in `executeDecision()`:

```java
case TRIGGER -> {
    if (context.storeVersion().isPresent()) {
        boolean claimed = store.tryClaimTrigger(situationId, correlationKey,
                                                 tenancyId, triggerTime)
                .await().indefinitely();
        if (!claimed) {
            return true;
        }
        try {
            context = store.save(context).await().indefinitely();
        } catch (SituationConflictException e) {
            store.resetTriggerClaim(situationId, correlationKey, tenancyId)
                    .await().indefinitely();
            throw e;
        }
    } else {
        context = store.save(context).await().indefinitely();
        boolean claimed = store.tryClaimTrigger(situationId, correlationKey,
                                                 tenancyId, triggerTime)
                .await().indefinitely();
        if (!claimed) {
            return true;
        }
    }

    if (definition.triggerAction() instanceof TriggerAction.CreateCase createCase) {
        try {
            caseTrigger.fire(createCase.config(), context).await().indefinitely();
        } catch (RuntimeException ex) {
            LOG.severe("CaseTrigger.fire() failed for situation '"
                       + situationId + "': " + ex.getMessage());
            store.resetTriggerClaim(situationId, correlationKey, tenancyId)
                    .await().indefinitely();
            return false;
        }
        changeEvent.fireAsync(new SituationChangeEvent(
                tenancyId, situationId, correlationKey,
                SituationChangeEvent.ChangeType.TRIGGERED, context));
    } else {
        try {
            changeEvent.fireAsync(new SituationChangeEvent(
                    tenancyId, situationId, correlationKey,
                    SituationChangeEvent.ChangeType.TRIGGERED, context))
                    .toCompletableFuture().join();
        } catch (Exception ex) {
            LOG.severe("SituationChangeEvent delivery failed for situation '"
                       + situationId + "': " + ex.getMessage());
            store.resetTriggerClaim(situationId, correlationKey, tenancyId)
                    .await().indefinitely();
            return false;
        }
    }

    closeGanglia(definition, situationId, correlationKey, tenancyId);
    return true;
}
```

- [ ] **Step 4: Update TRIGGER_AND_CONTINUE branch similarly**

Same TriggerAction pattern matching. For `NotifyOnly`: await delivery. For `CreateCase`: fire-and-forget event.

- [ ] **Step 5: Run full evaluator tests**

Run: `mvn --batch-mode test -pl runtime -Dtest=SituationEvaluatorTest`
Expected: PASS

- [ ] **Step 6: Run full build**

Run: `mvn --batch-mode install`
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```
feat(casehub-ras#6): TriggerAction dispatch in SituationEvaluator — NotifyOnly awaits delivery
```

---

### Task 6: Dynamic situation registration

**Files:**
- Modify: `runtime/src/main/java/io/casehub/ras/runtime/SituationDefinitionRegistry.java`
- Test: `runtime/src/test/.../SituationDefinitionRegistryTest.java`

**Interfaces:**
- Consumes: `SituationRegistration`, `Ganglion` (existing)
- Produces: `register(SituationRegistration)`, `deregister(String situationId)` — public API for consuming apps

- [ ] **Step 1: Write tests for register/deregister**

```java
@Test
void register_adds_situation_found_by_event_type() {
    // Start with empty registry (no providers)
    // Register a situation for event type "io.test.event"
    // Assert: findByEventType("io.test.event") returns the registration
}

@Test
void register_rejects_duplicate_situationId() {
    // Register situation "sit-A"
    // Register another with same "sit-A" → IllegalStateException
}

@Test
void register_validates_ganglion_references() {
    // Register situation referencing unknown ganglionId → IllegalStateException
}

@Test
void deregister_removes_situation() {
    // Register, verify found, deregister, verify not found
}

@Test
void deregister_is_idempotent() {
    // Deregister nonexistent situationId → no exception
}

@Test
void deregister_updates_maxCorrelationWindow() {
    // Register two situations with different windows
    // Deregister the one with the longest window
    // Assert: maxCorrelationWindow() reflects the remaining shorter window
}

@Test
void findByEventType_is_thread_safe_during_registration() {
    // Concurrent reads and writes — no ConcurrentModificationException
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl runtime -Dtest=SituationDefinitionRegistryTest#register*,SituationDefinitionRegistryTest#deregister*`
Expected: FAIL — methods don't exist

- [ ] **Step 3: Implement RegistrySnapshot + register/deregister**

Refactor `SituationDefinitionRegistry` to use a `RegistrySnapshot` record:

```java
@ApplicationScoped
public class SituationDefinitionRegistry {

    private record RegistrySnapshot(
        Map<String, List<SituationRegistration>> byEventType,
        Set<String> situationIds,
        Duration maxCorrelationWindow
    ) {}

    private volatile RegistrySnapshot snapshot;
    private final Map<String, Ganglion> gangliaById;

    @Inject
    public SituationDefinitionRegistry(Instance<SituationDefinitionProvider> providers,
                                       Instance<Ganglion> ganglia) {
        this(toList(providers), toList(ganglia));
    }

    SituationDefinitionRegistry(List<SituationDefinitionProvider> providers,
                                List<Ganglion> ganglia) {
        this.gangliaById = ganglia.stream()
                .collect(Collectors.toMap(Ganglion::ganglionId, g -> g, (g1, g2) -> {
                    throw new IllegalStateException(
                            "Duplicate ganglionId '" + g1.ganglionId() + "'");
                }));

        List<SituationRegistration> allRegistrations = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        for (var provider : providers) {
            for (var reg : provider.registrations()) {
                String sitId = reg.definition().situationId();
                if (!seenIds.add(sitId)) {
                    throw new IllegalStateException("Duplicate situationId '" + sitId + "'");
                }
                validate(reg.definition());
                allRegistrations.add(reg);
            }
        }

        this.snapshot = buildSnapshot(allRegistrations);
    }

    public List<SituationRegistration> findByEventType(String eventType) {
        return snapshot.byEventType().getOrDefault(eventType, List.of());
    }

    public Ganglion ganglion(String ganglionId) {
        Ganglion g = gangliaById.get(ganglionId);
        if (g == null) {
            throw new IllegalArgumentException("Unknown ganglionId: " + ganglionId);
        }
        return g;
    }

    public Duration maxCorrelationWindow() {
        return snapshot.maxCorrelationWindow();
    }

    public synchronized void register(SituationRegistration registration) {
        String sitId = registration.definition().situationId();
        if (snapshot.situationIds().contains(sitId)) {
            throw new IllegalStateException("Duplicate situationId: " + sitId);
        }
        validate(registration.definition());

        List<SituationRegistration> all = new ArrayList<>();
        snapshot.byEventType().values().stream()
                .flatMap(List::stream)
                .distinct()
                .forEach(all::add);
        all.add(registration);
        this.snapshot = buildSnapshot(all);
    }

    public synchronized void deregister(String situationId) {
        if (!snapshot.situationIds().contains(situationId)) {
            return;
        }
        List<SituationRegistration> remaining = snapshot.byEventType().values().stream()
                .flatMap(List::stream)
                .distinct()
                .filter(reg -> !reg.definition().situationId().equals(situationId))
                .toList();
        this.snapshot = buildSnapshot(remaining);
    }

    private static RegistrySnapshot buildSnapshot(List<SituationRegistration> registrations) {
        Map<String, List<SituationRegistration>> index = new HashMap<>();
        Set<String> ids = new HashSet<>();
        for (var reg : registrations) {
            ids.add(reg.definition().situationId());
            for (String eventType : reg.definition().eventTypes()) {
                index.computeIfAbsent(eventType, k -> new ArrayList<>()).add(reg);
            }
        }
        Duration maxWindow = registrations.stream()
                .map(r -> r.definition().correlationWindow())
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);

        return new RegistrySnapshot(
                Map.copyOf(index.entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, e -> List.copyOf(e.getValue())))),
                Set.copyOf(ids),
                maxWindow);
    }

    // validate() unchanged
}
```

- [ ] **Step 4: Run registry tests**

Run: `mvn --batch-mode test -pl runtime -Dtest=SituationDefinitionRegistryTest`
Expected: PASS

- [ ] **Step 5: Run full build**

Run: `mvn --batch-mode install`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```
feat(casehub-ras#6): dynamic situation registration — register/deregister with RegistrySnapshot
```

---

### Task 7: YAML triggerAction format

**Files:**
- Modify: `runtime/src/main/java/io/casehub/ras/runtime/YamlSituationDefinitionProvider.java`
- Modify: `runtime/src/test/.../YamlSituationDefinitionProviderTest.java`

**Interfaces:**
- Consumes: `TriggerAction` (Task 1)
- Produces: YAML parsing that supports `triggerAction: { type: create-case, ... }` and `triggerAction: { type: notify-only }`

- [ ] **Step 1: Write tests for new YAML format**

```java
@Test
void parses_triggerAction_createCase() {
    String yaml = """
            situations:
              - situationId: test-sit
                eventTypes: [io.test.event]
                chainMode:
                  type: or
                  ganglia: [g1]
                triggerAction:
                  type: create-case
                  caseNamespace: ns
                  caseName: name
                  caseVersion: "1.0"
            """;
    var provider = new YamlSituationDefinitionProvider(
            new ByteArrayInputStream(yaml.getBytes()));
    var def = provider.registrations().getFirst().definition();
    assertThat(def.triggerAction()).isInstanceOf(TriggerAction.CreateCase.class);
    var createCase = (TriggerAction.CreateCase) def.triggerAction();
    assertThat(createCase.config().caseNamespace()).isEqualTo("ns");
}

@Test
void parses_triggerAction_notifyOnly() {
    String yaml = """
            situations:
              - situationId: test-sit
                eventTypes: [io.test.event]
                chainMode:
                  type: or
                  ganglia: [g1]
                triggerAction:
                  type: notify-only
            """;
    var provider = new YamlSituationDefinitionProvider(
            new ByteArrayInputStream(yaml.getBytes()));
    var def = provider.registrations().getFirst().definition();
    assertThat(def.triggerAction()).isInstanceOf(TriggerAction.NotifyOnly.class);
}

@Test
void rejects_missing_triggerAction() {
    String yaml = """
            situations:
              - situationId: test-sit
                eventTypes: [io.test.event]
                chainMode:
                  type: or
                  ganglia: [g1]
            """;
    assertThatIllegalArgumentException()
            .isThrownBy(() -> new YamlSituationDefinitionProvider(
                    new ByteArrayInputStream(yaml.getBytes())))
            .withMessageContaining("triggerAction");
}

@Test
void rejects_unknown_triggerAction_type() {
    String yaml = """
            situations:
              - situationId: test-sit
                eventTypes: [io.test.event]
                chainMode:
                  type: or
                  ganglia: [g1]
                triggerAction:
                  type: explode
            """;
    assertThatIllegalArgumentException()
            .isThrownBy(() -> new YamlSituationDefinitionProvider(
                    new ByteArrayInputStream(yaml.getBytes())));
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl runtime -Dtest=YamlSituationDefinitionProviderTest`
Expected: FAIL — parser still expects `triggerConfig`

- [ ] **Step 3: Update parseSituation()**

Replace the `triggerConfig` parsing block with `triggerAction`:

```java
@SuppressWarnings("unchecked")
private static SituationRegistration parseSituation(Map<String, Object> map) {
    String situationId = requireString(map, "situationId");
    // ... eventTypes, correlationWindow, eventBufferDelay, chainMode unchanged ...

    Map<String, Object> triggerActionMap = (Map<String, Object>) map.get("triggerAction");
    if (triggerActionMap == null) {
        throw new IllegalArgumentException(
                "triggerAction required for situation '" + situationId + "'");
    }

    ChainMode chainMode = parseChainMode(chainModeMap, situationId);
    TriggerAction triggerAction = parseTriggerAction(triggerActionMap, situationId);

    // ... triggerMode parsing unchanged ...

    SituationDefinition def = new SituationDefinition(
            situationId, new LinkedHashSet<>(eventTypeList),
            correlationWindow, eventBufferDelay, chainMode, triggerAction, triggerMode);
    return new SituationRegistration(def);
}

@SuppressWarnings("unchecked")
private static TriggerAction parseTriggerAction(Map<String, Object> map, String situationId) {
    String type = requireString(map, "type");
    return switch (type) {
        case "create-case" -> new TriggerAction.CreateCase(new CaseTriggerConfig(
                requireString(map, "caseNamespace"),
                requireString(map, "caseName"),
                requireString(map, "caseVersion"),
                (Map<String, Object>) map.getOrDefault("baseCaseData", Map.of())));
        case "notify-only" -> new TriggerAction.NotifyOnly();
        default -> throw new IllegalArgumentException(
                "Unknown triggerAction type '" + type + "' in situation '" + situationId
                + "'. Expected 'create-case' or 'notify-only'");
    };
}
```

- [ ] **Step 4: Update existing YAML tests that used old triggerConfig format**

- [ ] **Step 5: Run full build**

Run: `mvn --batch-mode install`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```
feat(casehub-ras#6): YAML triggerAction format with type discriminator
```

---

### Task 8: Cross-repo issue creation + CLAUDE.md update

**Files:**
- Modify: `CLAUDE.md` — update spec references, core types table, YAML section

**Interfaces:**
- Consumes: All prior tasks
- Produces: Cross-repo issues for desiredstate and ops callers, updated CLAUDE.md

- [ ] **Step 1: Create cross-repo issue for desiredstate/ras-adapter**

`DesiredStateSituationDefinitionProvider` uses old `CaseTriggerConfig` constructor and old `SituationDefinition` constructor. Create issue in casehubio/casehub-desiredstate.

- [ ] **Step 2: Create cross-repo issue for ops/deployment**

`AdaptiveTopologyManager.onSituationChange()` uses old 4-arg `SituationChangeEvent` constructor. Create issue in casehubio/casehub-ops.

- [ ] **Step 3: Update CLAUDE.md**

Update the following sections:
- Add `TriggerAction` to Core Types table
- Update `SituationDefinition` entry (triggerConfig → triggerAction)
- Update `SituationChangeEvent` entry (add context field)
- Update `TriggerDecision` entry (renamed values)
- Update `SituationStore` SPI section (add removeAllForSituation)
- Update YAML section (triggerConfig → triggerAction format)
- Add spec reference to Design specs list

- [ ] **Step 4: Commit**

```
docs(casehub-ras#6): update CLAUDE.md for TriggerAction, enriched events, dynamic registration
```
