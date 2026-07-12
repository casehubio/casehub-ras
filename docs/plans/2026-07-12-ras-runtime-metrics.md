# RAS Runtime Metrics Instrumentation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #32 — RAS runtime metrics instrumentation (SituationEvaluator, RasEngine)
**Issue group:** #32

**Goal:** Add Micrometer instrumentation to the runtime module via a centralised
`RasMetrics` bean — 22 metrics (18 counters, 2 timers, 2 gauges) across
`RasEngine`, `SituationEvaluator`, `SituationExpiryJob`.

**Architecture:** Centralised `RasMetrics` `@ApplicationScoped` bean owns all metric names,
tags, and null-guarding. Injected into `RasEngine`, `SituationEvaluator`, and
`SituationExpiryJob`. `Instance<MeterRegistry>` optional injection — fully functional
without Micrometer on classpath. `SituationStore` API change: `removeExpired()` and
`removeTriggeredBefore()` return `Uni<Integer>` for expiry-job counters.

**Tech Stack:** Java 21, Micrometer 1.x (`provided` scope), Quarkus CDI, JUnit 5,
AssertJ, `SimpleMeterRegistry` for tests.

## Global Constraints

- `micrometer-core` is `<scope>provided</scope>` — annotation-only library dep per
  protocol PP-20260604-88f660
- All tag values lowercase with underscores — `decision.name().toLowerCase()`
- Timer start methods return `Object` (opaque handle) — callers never reference
  `Timer.Sample` to prevent classloading when Micrometer absent
- `RasMetrics` methods are no-ops when `MeterRegistry` is null
- Use IntelliJ MCP (`ide_edit_member`, `ide_insert_member`, `ide_replace_member`) for
  all Java edits — never Edit/Write on existing `.java` files
- `project_path` for all IntelliJ calls: `/Users/mdproctor/claude/casehub/ras`

---

### Task 1: SituationStore API change — `Uni<Integer>` return types

**Files:**
- Modify: `api/src/main/java/io/casehub/ras/api/SituationStore.java`
- Modify: `persistence-memory/src/main/java/io/casehub/ras/persistence/memory/InMemorySituationStore.java`
- Modify: `persistence-jpa/src/main/java/io/casehub/ras/persistence/jpa/JpaSituationStore.java`
- Modify: `api/src/test/java/io/casehub/ras/api/AbstractSituationStoreContractTest.java`
- Modify: `runtime/src/test/java/io/casehub/ras/runtime/SituationEvaluatorTest.java` (inner classes)

**Interfaces:**
- Produces: `SituationStore.removeExpired(Instant) → Uni<Integer>` (abstract),
  `SituationStore.removeTriggeredBefore(Instant) → Uni<Integer>` (default, returns 0)
- Produces: `InMemorySituationStore.removeExpired(Instant) → Uni<Integer>` (returns count),
  `InMemorySituationStore.removeTriggeredBefore(Instant) → Uni<Integer>` (returns count)

- [ ] **Step 1: Write failing test — contract test for removeExpired returning count**

In `AbstractSituationStoreContractTest`, add a test that saves two contexts, calls
`removeExpired` with a cutoff that expires one, and asserts the returned count is 1.

Find the file:
```
ide_find_file query="AbstractSituationStoreContractTest"
```

Read the existing file to understand the test pattern, then add via `ide_insert_member`:

```java
@Test
void removeExpiredReturnsCountOfRemovedEntries() {
    Instant old = Instant.now().minus(Duration.ofHours(2));
    Instant recent = Instant.now().minus(Duration.ofMinutes(5));
    store().save(SituationContext.initial("sit-1", "k1", "t", old)).await().indefinitely();
    store().save(SituationContext.initial("sit-2", "k2", "t", recent)).await().indefinitely();

    int removed = store().removeExpired(Instant.now().minus(Duration.ofHours(1)))
            .await().indefinitely();

    assertThat(removed).isEqualTo(1);
}
```

- [ ] **Step 2: Write failing test — contract test for removeTriggeredBefore returning count**

```java
@Test
void removeTriggeredBeforeReturnsCountOfRemovedEntries() {
    Instant triggerTime = Instant.now().minus(Duration.ofHours(1));
    store().save(SituationContext.initial("sit-1", "k1", "t", triggerTime)).await().indefinitely();
    store().tryClaimTrigger("sit-1", "k1", "t", triggerTime).await().indefinitely();

    int removed = store().removeTriggeredBefore(Instant.now()).await().indefinitely();

    assertThat(removed).isEqualTo(1);
}
```

- [ ] **Step 3: Run tests to verify they fail**

```bash
/opt/homebrew/bin/mvn --batch-mode -pl api -Dtest="AbstractSituationStoreContractTest" test
```

Expected: compilation error — return type mismatch (`Uni<Void>` vs `Uni<Integer>`).

- [ ] **Step 4: Change SituationStore interface**

Use `ide_replace_member` on `SituationStore`:

Change `removeExpired`:
```java
Uni<Integer> removeExpired(Instant cutoff);
```

Change `removeTriggeredBefore` default:
```java
default Uni<Integer> removeTriggeredBefore(Instant triggerCutoff) {
    return Uni.createFrom().item(0);
}
```

- [ ] **Step 5: Update InMemorySituationStore.removeExpired**

Use `ide_replace_member` to change `removeExpired`:

```java
@Override
public Uni<Integer> removeExpired(Instant cutoff) {
    int[] count = {0};
    store.entrySet().removeIf(entry -> {
        SituationContext ctx = entry.getValue();
        if (ctx.lastSignal().isBefore(cutoff) && claims.get(entry.getKey()) == null) {
            versions.remove(entry.getKey());
            count[0]++;
            return true;
        }
        return false;
    });
    return Uni.createFrom().item(count[0]);
}
```

- [ ] **Step 6: Update InMemorySituationStore.removeTriggeredBefore**

Use `ide_replace_member`:

```java
@Override
public Uni<Integer> removeTriggeredBefore(Instant cutoff) {
    int[] count = {0};
    store.entrySet().removeIf(entry -> {
        SituationKey key = entry.getKey();
        if (claims.get(key) != null) {
            SituationContext ctx = entry.getValue();
            if (ctx.lastTriggered() != null && ctx.lastTriggered().isBefore(cutoff)) {
                claims.remove(key);
                versions.remove(key);
                count[0]++;
                return true;
            }
        }
        return false;
    });
    return Uni.createFrom().item(count[0]);
}
```

- [ ] **Step 7: Update JpaSituationStore.removeExpired**

Use `ide_replace_member`:

```java
@Override
public Uni<Integer> removeExpired(Instant cutoff) {
    int removed = em.createQuery("DELETE FROM SituationEntity e WHERE e.lastSignal < :cutoff AND e.policyTriggered = false")
            .setParameter("cutoff", cutoff)
            .executeUpdate();
    return Uni.createFrom().item(removed);
}
```

- [ ] **Step 8: Update JpaSituationStore.removeTriggeredBefore**

Use `ide_replace_member`:

```java
@Override
public Uni<Integer> removeTriggeredBefore(Instant triggerCutoff) {
    int removed = em.createQuery("DELETE FROM SituationEntity e WHERE e.policyTriggered = true AND e.lastTriggered < :cutoff")
            .setParameter("cutoff", triggerCutoff)
            .executeUpdate();
    return Uni.createFrom().item(removed);
}
```

- [ ] **Step 9: Update ConflictSimulatingStore and ClaimTrackingStore in SituationEvaluatorTest**

Both inner classes in `SituationEvaluatorTest` override `removeExpired` with
`Uni<Void>` return. Change both to `Uni<Integer>`:

```java
@Override
public Uni<Integer> removeExpired(Instant cutoff) {
    return delegate.removeExpired(cutoff);
}
```

- [ ] **Step 10: Run all tests**

```bash
/opt/homebrew/bin/mvn --batch-mode install
```

Expected: all tests pass — `Uni<Integer>` return types are compatible everywhere.

- [ ] **Step 11: Run diagnostics to verify no compilation errors**

```
ide_diagnostics file="api/src/main/java/io/casehub/ras/api/SituationStore.java" severity="errors"
```

- [ ] **Step 12: Commit**

```bash
git add -A && git commit -m "feat(casehub-ras#32): SituationStore API — removeExpired/removeTriggeredBefore return Uni<Integer>"
```

---

### Task 2: RasMetrics bean, dependency, and RasMetricsTest

**Files:**
- Modify: `runtime/pom.xml`
- Create: `runtime/src/main/java/io/casehub/ras/runtime/RasMetrics.java`
- Create: `runtime/src/test/java/io/casehub/ras/runtime/RasMetricsTest.java`
- Modify: `runtime/src/main/java/io/casehub/ras/runtime/SituationDefinitionRegistry.java`

**Interfaces:**
- Consumes: `SituationDefinitionRegistry` (existing), `TriggerDecision` (existing enum)
- Produces: `RasMetrics` — full public API per spec §2 (23 methods)
- Produces: `SituationDefinitionRegistry.definitionCount() → int`

- [ ] **Step 1: Add micrometer dependencies to runtime/pom.xml**

Add to `<dependencies>` in `runtime/pom.xml`:

```xml
<dependency><groupId>io.micrometer</groupId><artifactId>micrometer-core</artifactId><scope>provided</scope></dependency>
<dependency><groupId>io.micrometer</groupId><artifactId>micrometer-test</artifactId><scope>test</scope></dependency>
```

- [ ] **Step 2: Add SituationDefinitionRegistry.definitionCount()**

Use `ide_insert_member` on `SituationDefinitionRegistry` after the `deregister` method:

```java
public int definitionCount() {
    return snapshot.situationIds().size();
}
```

- [ ] **Step 3: Write RasMetricsTest — counter methods, null safety, gauges**

Create `runtime/src/test/java/io/casehub/ras/runtime/RasMetricsTest.java`:

```java
package io.casehub.ras.runtime;

import io.casehub.ras.api.*;
import io.casehub.ras.testing.FixedDetectionResult;
import io.casehub.ras.testing.MockGanglion;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class RasMetricsTest {

    private SimpleMeterRegistry meterRegistry;
    private SituationDefinitionRegistry registry;
    private RasMetrics metrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        var ganglion = new MockGanglion("g1", Set.of("e"),
                FixedDetectionResult.noise("g1"));
        var def = new SituationDefinition("sit-1", Set.of("e"),
                Duration.ofMinutes(5), null,
                new ChainMode.Or(Set.of("g1")),
                new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "c", "1", Map.of())),
                null);
        registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(new SituationRegistration(def))),
                List.of(ganglion));
        metrics = new RasMetrics(registry);
        metrics.setMeterRegistry(meterRegistry);
        metrics.init();
    }

    @Test
    void eventReceivedIncrementsCounterWithEventTypeTag() {
        metrics.eventReceived("temp.reading");
        metrics.eventReceived("temp.reading");
        metrics.eventReceived("pressure.alert");

        assertThat(meterRegistry.counter("ras.engine.events.received",
                "event_type", "temp.reading").count()).isEqualTo(2.0);
        assertThat(meterRegistry.counter("ras.engine.events.received",
                "event_type", "pressure.alert").count()).isEqualTo(1.0);
    }

    @Test
    void eventSkippedIncrementsCounterWithReasonTag() {
        metrics.eventSkipped("no_tenancy_id");
        assertThat(meterRegistry.counter("ras.engine.events.skipped",
                "reason", "no_tenancy_id").count()).isEqualTo(1.0);
    }

    @Test
    void eventRoutedIncrementsWithSituationAndTenancy() {
        metrics.eventRouted("sit-1", "tenant-a");
        assertThat(meterRegistry.counter("ras.engine.events.routed",
                "situation_id", "sit-1", "tenancy_id", "tenant-a").count())
                .isEqualTo(1.0);
    }

    @Test
    void evaluationFailedIncrementsWithSituationAndTenancy() {
        metrics.evaluationFailed("sit-1", "tenant-a");
        assertThat(meterRegistry.counter("ras.engine.evaluation.failed",
                "situation_id", "sit-1", "tenancy_id", "tenant-a").count())
                .isEqualTo(1.0);
    }

    @Test
    void decisionCounterUsesLowercaseDecisionTag() {
        metrics.decision("sit-1", "tenant-a", TriggerDecision.TRIGGER);
        metrics.decision("sit-1", "tenant-a", TriggerDecision.CONTINUE_ACCUMULATING);

        assertThat(meterRegistry.counter("ras.evaluator.decision",
                "situation_id", "sit-1", "tenancy_id", "tenant-a",
                "decision", "trigger").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("ras.evaluator.decision",
                "situation_id", "sit-1", "tenancy_id", "tenant-a",
                "decision", "continue_accumulating").count()).isEqualTo(1.0);
    }

    @Test
    void conflictRetryAndRetriesExhausted() {
        metrics.conflictRetry("sit-1", "tenant-a");
        metrics.conflictRetry("sit-1", "tenant-a");
        metrics.retriesExhausted("sit-1", "tenant-a");

        assertThat(meterRegistry.counter("ras.evaluator.conflict_retries",
                "situation_id", "sit-1", "tenancy_id", "tenant-a").count())
                .isEqualTo(2.0);
        assertThat(meterRegistry.counter("ras.evaluator.retries_exhausted",
                "situation_id", "sit-1", "tenancy_id", "tenant-a").count())
                .isEqualTo(1.0);
    }

    @Test
    void contextExpired() {
        metrics.contextExpired("sit-1", "tenant-a");
        assertThat(meterRegistry.counter("ras.evaluator.context_expired",
                "situation_id", "sit-1", "tenancy_id", "tenant-a").count())
                .isEqualTo(1.0);
    }

    @Test
    void ganglionFailureCounters() {
        metrics.ganglionDetectFailed("g1", "sit-1");
        metrics.ganglionCompactFailed("g1", "sit-1");
        metrics.ganglionCloseFailed("g1", "sit-1");

        assertThat(meterRegistry.counter("ras.evaluator.ganglion.detect_failed",
                "ganglion_id", "g1", "situation_id", "sit-1").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("ras.evaluator.ganglion.compact_failed",
                "ganglion_id", "g1", "situation_id", "sit-1").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("ras.evaluator.ganglion.close_failed",
                "ganglion_id", "g1", "situation_id", "sit-1").count()).isEqualTo(1.0);
    }

    @Test
    void triggerClaimedAndRaceLost() {
        metrics.triggerClaimed("sit-1", "tenant-a");
        metrics.triggerRaceLost("sit-1", "tenant-a");

        assertThat(meterRegistry.counter("ras.evaluator.trigger.claimed",
                "situation_id", "sit-1", "tenancy_id", "tenant-a").count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.counter("ras.evaluator.trigger.race_lost",
                "situation_id", "sit-1", "tenancy_id", "tenant-a").count())
                .isEqualTo(1.0);
    }

    @Test
    void triggerFireTimerRecordsDuration() {
        Object sample = metrics.startTriggerFireTimer();
        assertThat(sample).isNotNull();
        metrics.stopTriggerFireTimer(sample, "sit-1", "tenant-a", "create_case");

        assertThat(meterRegistry.timer("ras.evaluator.trigger.fire_time",
                "situation_id", "sit-1", "tenancy_id", "tenant-a",
                "trigger_action", "create_case").count()).isEqualTo(1);
    }

    @Test
    void triggerFiredAndFailedWithTriggerActionTag() {
        metrics.triggerFired("sit-1", "tenant-a", "create_case");
        metrics.triggerFailed("sit-1", "tenant-a", "notify_only");

        assertThat(meterRegistry.counter("ras.evaluator.trigger.fired",
                "situation_id", "sit-1", "tenancy_id", "tenant-a",
                "trigger_action", "create_case").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("ras.evaluator.trigger.failed",
                "situation_id", "sit-1", "tenancy_id", "tenant-a",
                "trigger_action", "notify_only").count()).isEqualTo(1.0);
    }

    @Test
    void processTimerRecordsDuration() {
        Object sample = metrics.startProcessTimer();
        assertThat(sample).isNotNull();
        metrics.stopProcessTimer(sample, "sit-1", "tenant-a");

        assertThat(meterRegistry.timer("ras.evaluator.process_time",
                "situation_id", "sit-1", "tenancy_id", "tenant-a").count())
                .isEqualTo(1);
    }

    @Test
    void eventBuffered() {
        metrics.eventBuffered("sit-1", "tenant-a");
        assertThat(meterRegistry.counter("ras.evaluator.buffer.events_buffered",
                "situation_id", "sit-1", "tenancy_id", "tenant-a").count())
                .isEqualTo(1.0);
    }

    @Test
    void triggeredCleanedIncrementsByCount() {
        metrics.triggeredCleaned(5);
        assertThat(meterRegistry.counter("ras.expiry.triggered_cleaned").count())
                .isEqualTo(5.0);
    }

    @Test
    void expiredCleanedIncrementsByCount() {
        metrics.expiredCleaned(3);
        assertThat(meterRegistry.counter("ras.expiry.expired_cleaned").count())
                .isEqualTo(3.0);
    }

    @Test
    void definitionsActiveGaugeReflectsRegistryCount() {
        assertThat(meterRegistry.get("ras.registry.definitions.active")
                .gauge().value()).isEqualTo(1.0);
    }

    @Test
    void activeBuffersGaugeRegisteredViaCallback() {
        AtomicInteger bufferCount = new AtomicInteger(3);
        metrics.registerActiveBuffersGauge(bufferCount::get);

        assertThat(meterRegistry.get("ras.evaluator.buffers.active")
                .gauge().value()).isEqualTo(3.0);

        bufferCount.set(7);
        assertThat(meterRegistry.get("ras.evaluator.buffers.active")
                .gauge().value()).isEqualTo(7.0);
    }

    @Test
    void allMethodsAreNoOpsWhenMeterRegistryIsNull() {
        var nullMetrics = new RasMetrics(registry);
        // no init() called — metrics field stays null

        // None of these should throw
        nullMetrics.eventReceived("type");
        nullMetrics.eventSkipped("reason");
        nullMetrics.eventRouted("sit", "tenant");
        nullMetrics.evaluationFailed("sit", "tenant");
        nullMetrics.decision("sit", "tenant", TriggerDecision.TRIGGER);
        nullMetrics.conflictRetry("sit", "tenant");
        nullMetrics.retriesExhausted("sit", "tenant");
        nullMetrics.contextExpired("sit", "tenant");
        nullMetrics.ganglionDetectFailed("g", "sit");
        nullMetrics.ganglionCompactFailed("g", "sit");
        nullMetrics.ganglionCloseFailed("g", "sit");
        nullMetrics.triggerClaimed("sit", "tenant");
        nullMetrics.triggerRaceLost("sit", "tenant");
        nullMetrics.triggerFired("sit", "tenant", "create_case");
        nullMetrics.triggerFailed("sit", "tenant", "notify_only");
        nullMetrics.eventBuffered("sit", "tenant");
        nullMetrics.triggeredCleaned(5);
        nullMetrics.expiredCleaned(3);
        nullMetrics.registerActiveBuffersGauge(() -> 0);

        Object sample = nullMetrics.startProcessTimer();
        assertThat(sample).isNull();
        nullMetrics.stopProcessTimer(null, "sit", "tenant");

        Object triggerSample = nullMetrics.startTriggerFireTimer();
        assertThat(triggerSample).isNull();
        nullMetrics.stopTriggerFireTimer(null, "sit", "tenant", "create_case");
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

```bash
/opt/homebrew/bin/mvn --batch-mode -pl runtime -Dtest="RasMetricsTest" test
```

Expected: compilation error — `RasMetrics` class not found.

- [ ] **Step 5: Implement RasMetrics**

Create `runtime/src/main/java/io/casehub/ras/runtime/RasMetrics.java`:

```java
package io.casehub.ras.runtime;

import io.casehub.ras.api.TriggerDecision;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.List;
import java.util.function.Supplier;

@ApplicationScoped
public class RasMetrics {

    private final SituationDefinitionRegistry registry;

    @Inject
    Instance<MeterRegistry> meterRegistryInstance;

    private MeterRegistry metrics;

    @Inject
    public RasMetrics(SituationDefinitionRegistry registry) {
        this.registry = registry;
    }

    void setMeterRegistry(MeterRegistry registry) {
        this.metrics = registry;
    }

    @PostConstruct
    void init() {
        if (metrics == null && meterRegistryInstance != null && meterRegistryInstance.isResolvable()) {
            metrics = meterRegistryInstance.get();
        }
        if (metrics != null) {
            metrics.gauge("ras.registry.definitions.active", List.of(),
                    registry, r -> r.definitionCount());
        }
    }

    // --- RasEngine counters ---

    public void eventReceived(String eventType) {
        counter("ras.engine.events.received", "event_type", eventType);
    }

    public void eventSkipped(String reason) {
        counter("ras.engine.events.skipped", "reason", reason);
    }

    public void eventRouted(String situationId, String tenancyId) {
        counter("ras.engine.events.routed",
                "situation_id", situationId, "tenancy_id", tenancyId);
    }

    public void evaluationFailed(String situationId, String tenancyId) {
        counter("ras.engine.evaluation.failed",
                "situation_id", situationId, "tenancy_id", tenancyId);
    }

    // --- SituationEvaluator counters and timers ---

    public Object startProcessTimer() {
        return startTimer();
    }

    public void stopProcessTimer(Object sample, String situationId, String tenancyId) {
        stopTimer(sample, "ras.evaluator.process_time",
                "situation_id", situationId, "tenancy_id", tenancyId);
    }

    public void decision(String situationId, String tenancyId, TriggerDecision decision) {
        counter("ras.evaluator.decision",
                "situation_id", situationId, "tenancy_id", tenancyId,
                "decision", decision.name().toLowerCase());
    }

    public void conflictRetry(String situationId, String tenancyId) {
        counter("ras.evaluator.conflict_retries",
                "situation_id", situationId, "tenancy_id", tenancyId);
    }

    public void retriesExhausted(String situationId, String tenancyId) {
        counter("ras.evaluator.retries_exhausted",
                "situation_id", situationId, "tenancy_id", tenancyId);
    }

    public void contextExpired(String situationId, String tenancyId) {
        counter("ras.evaluator.context_expired",
                "situation_id", situationId, "tenancy_id", tenancyId);
    }

    public void ganglionDetectFailed(String ganglionId, String situationId) {
        counter("ras.evaluator.ganglion.detect_failed",
                "ganglion_id", ganglionId, "situation_id", situationId);
    }

    public void ganglionCompactFailed(String ganglionId, String situationId) {
        counter("ras.evaluator.ganglion.compact_failed",
                "ganglion_id", ganglionId, "situation_id", situationId);
    }

    public void ganglionCloseFailed(String ganglionId, String situationId) {
        counter("ras.evaluator.ganglion.close_failed",
                "ganglion_id", ganglionId, "situation_id", situationId);
    }

    public void triggerClaimed(String situationId, String tenancyId) {
        counter("ras.evaluator.trigger.claimed",
                "situation_id", situationId, "tenancy_id", tenancyId);
    }

    public void triggerRaceLost(String situationId, String tenancyId) {
        counter("ras.evaluator.trigger.race_lost",
                "situation_id", situationId, "tenancy_id", tenancyId);
    }

    public Object startTriggerFireTimer() {
        return startTimer();
    }

    public void stopTriggerFireTimer(Object sample, String situationId,
                                      String tenancyId, String triggerAction) {
        stopTimer(sample, "ras.evaluator.trigger.fire_time",
                "situation_id", situationId, "tenancy_id", tenancyId,
                "trigger_action", triggerAction);
    }

    public void triggerFired(String situationId, String tenancyId, String triggerAction) {
        counter("ras.evaluator.trigger.fired",
                "situation_id", situationId, "tenancy_id", tenancyId,
                "trigger_action", triggerAction);
    }

    public void triggerFailed(String situationId, String tenancyId, String triggerAction) {
        counter("ras.evaluator.trigger.failed",
                "situation_id", situationId, "tenancy_id", tenancyId,
                "trigger_action", triggerAction);
    }

    public void eventBuffered(String situationId, String tenancyId) {
        counter("ras.evaluator.buffer.events_buffered",
                "situation_id", situationId, "tenancy_id", tenancyId);
    }

    // --- SituationExpiryJob counters ---

    public void triggeredCleaned(int count) {
        counterBy("ras.expiry.triggered_cleaned", count);
    }

    public void expiredCleaned(int count) {
        counterBy("ras.expiry.expired_cleaned", count);
    }

    // --- Gauge self-registration ---

    public void registerActiveBuffersGauge(Supplier<Number> supplier) {
        if (metrics != null) {
            metrics.gauge("ras.evaluator.buffers.active", List.of(), supplier,
                    Supplier::get);
        }
    }

    // --- Private helpers ---

    private void counter(String name, String... tags) {
        if (metrics != null) {
            metrics.counter(name, tags).increment();
        }
    }

    private void counterBy(String name, double amount, String... tags) {
        if (metrics != null) {
            metrics.counter(name, tags).increment(amount);
        }
    }

    private Object startTimer() {
        return metrics != null ? Timer.start(metrics) : null;
    }

    private void stopTimer(Object sample, String name, String... tags) {
        if (sample != null) {
            ((Timer.Sample) sample).stop(metrics.timer(name, tags));
        }
    }
}
```

- [ ] **Step 6: Run tests**

```bash
/opt/homebrew/bin/mvn --batch-mode -pl runtime -Dtest="RasMetricsTest" test
```

Expected: all pass.

- [ ] **Step 7: Run diagnostics**

```
ide_diagnostics file="runtime/src/main/java/io/casehub/ras/runtime/RasMetrics.java" severity="errors"
```

- [ ] **Step 8: Commit**

```bash
git add -A && git commit -m "feat(casehub-ras#32): RasMetrics bean — centralised runtime metrics with 22 metrics"
```

---

### Task 3: SituationEvaluator instrumentation (14 metrics)

**Files:**
- Modify: `runtime/src/main/java/io/casehub/ras/runtime/SituationEvaluator.java`
- Modify: `runtime/src/test/java/io/casehub/ras/runtime/SituationEvaluatorTest.java`

**Interfaces:**
- Consumes: `RasMetrics` (from Task 2)
- Produces: `SituationEvaluator(store, policy, caseTrigger, registry, maxRetries, changeEvent, metrics)`,
  `SituationEvaluator.activeBufferCount() → int`

- [ ] **Step 1: Write failing tests — metric assertions in SituationEvaluatorTest**

Add to `SituationEvaluatorTest`:
- A `SimpleMeterRegistry` field and a `RasMetrics` field
- Update `setUp()` to create `RasMetrics` with the registry
- Update `buildEvaluator()` to pass `metrics` as final constructor parameter

Add new test methods:

```java
@Test
void processTimeTimerRecordsLatency() {
    // set up single ganglion OR mode, fire one event
    // assert meterRegistry.timer("ras.evaluator.process_time", ...).count() == 1
}

@Test
void decisionCounterTracksAllDecisionTypes() {
    // fire event that triggers → assert TRIGGER decision counted
    // fire NOISE event → assert CONTINUE_ACCUMULATING counted
}

@Test
void conflictRetryCounterIncrementsOnRetry() {
    // use ConflictSimulatingStore with 1 conflict
    // assert conflict_retries count == 1
}

@Test
void retriesExhaustedCounterOnAllRetriesFailed() {
    // use ConflictSimulatingStore with maxRetries+1 conflicts
    // assert retries_exhausted count == 1
}

@Test
void contextExpiredCounterOnWindowExpiry() {
    // fire event past window → assert context_expired == 1
}

@Test
void ganglionDetectFailedCounterOnDetectException() {
    // use FailingGanglion for detect → assert ganglion.detect_failed == 1
}

@Test
void triggerClaimedCounterOnSuccessfulClaim() {
    // fire triggering event → assert trigger.claimed == 1
}

@Test
void triggerRaceLostCounterOnLostClaim() {
    // pre-claim, then fire → assert trigger.race_lost == 1
}

@Test
void triggerFiredCounterWithCreateCaseAction() {
    // fire triggering event with CreateCase → assert trigger.fired with create_case tag
}

@Test
void triggerFailedCounterOnCaseTriggerFailure() {
    // failing MockCaseTrigger → assert trigger.failed with create_case tag
}

@Test
void triggerFireTimerWithNotifyOnlyAction() {
    // fire triggering event with NotifyOnly → assert fire_time with notify_only tag
}

@Test
void eventBufferedCounterOnBufferedEvent() {
    // buffered definition → assert events_buffered == 1
}
```

Each test follows the existing test structure: create ganglia, definition, registry,
evaluator, fire events, assert metrics via `meterRegistry.counter(name, tags).count()`.

- [ ] **Step 2: Run tests to verify they fail**

```bash
/opt/homebrew/bin/mvn --batch-mode -pl runtime -Dtest="SituationEvaluatorTest" test
```

Expected: compilation error — constructor parameter count mismatch.

- [ ] **Step 3: Add RasMetrics to SituationEvaluator constructor**

Use `ide_replace_member` on the constructor to add `RasMetrics metrics` as the
final parameter. Add a `private final RasMetrics metrics;` field.

Use `ide_insert_member` to add:
```java
int activeBufferCount() {
    return buffers.size();
}
```

Use `ide_insert_member` to add `@PostConstruct`:
```java
@PostConstruct
void initGauges() {
    metrics.registerActiveBuffersGauge(this::activeBufferCount);
}
```

- [ ] **Step 4: Instrument processEvent()**

Use `ide_replace_member` on `processEvent`. Wrap the method body with
`startProcessTimer`/`stopProcessTimer`. Add `metrics.decision()` after
policy evaluation. Add `metrics.conflictRetry()` in the retry path.
Add `metrics.retriesExhausted()` when all retries fail.

- [ ] **Step 5: Instrument loadContext()**

Add `metrics.contextExpired()` in the `isExpired` branch.

- [ ] **Step 6: Instrument runDetection()**

Replace the `LOG.warning` in the catch block with:
```java
LOG.warning("Ganglion '" + ganglionId + "' detect() failed, skipping: " + ex.getMessage());
metrics.ganglionDetectFailed(ganglionId, definition.situationId());
```

- [ ] **Step 7: Instrument executeDecision()**

For TRIGGER and TRIGGER_AND_CONTINUE branches:
- Add `metrics.triggerClaimed()` after successful `tryClaimTrigger`
- Add `metrics.triggerRaceLost()` when claim returns false
- Wrap `caseTrigger.fire()` with `startTriggerFireTimer`/`stopTriggerFireTimer` + `triggerFired`/`triggerFailed` with `"create_case"` tag
- Wrap `changeEvent.fireAsync(...).join()` (NotifyOnly path) with same timer + `triggerFired`/`triggerFailed` with `"notify_only"` tag

- [ ] **Step 8: Instrument evaluate()**

Add `metrics.eventBuffered()` in the buffer submission path.

- [ ] **Step 9: Instrument compactGanglia() and closeGanglia()**

Add `metrics.ganglionCompactFailed()` and `metrics.ganglionCloseFailed()` in
the respective catch blocks.

- [ ] **Step 10: Run all tests**

```bash
/opt/homebrew/bin/mvn --batch-mode -pl runtime -Dtest="SituationEvaluatorTest" test
```

Expected: all pass.

- [ ] **Step 11: Run diagnostics**

```
ide_diagnostics file="runtime/src/main/java/io/casehub/ras/runtime/SituationEvaluator.java" severity="errors"
```

- [ ] **Step 12: Commit**

```bash
git add -A && git commit -m "feat(casehub-ras#32): SituationEvaluator instrumentation — 14 metrics"
```

---

### Task 4: RasEngine instrumentation (4 metrics)

**Files:**
- Modify: `runtime/src/main/java/io/casehub/ras/runtime/RasEngine.java`
- Modify: `runtime/src/test/java/io/casehub/ras/runtime/RasEngineTest.java`

**Interfaces:**
- Consumes: `RasMetrics` (from Task 2)
- Produces: `RasEngine(registry, evaluator, metrics)` — new constructor

- [ ] **Step 1: Write failing tests — metric assertions in RasEngineTest**

Add `SimpleMeterRegistry` and `RasMetrics` fields. Update all test methods to
pass `metrics` to `RasEngine` constructor: `new RasEngine(registry, evaluator, metrics)`.

Add new test methods:

```java
@Test
void receivedCounterIncrementedForEveryEvent() {
    // fire valid event → assert ras.engine.events.received with event_type tag == 1
}

@Test
void skippedCounterForMissingTenancy() {
    // fire event without tenancyid → assert ras.engine.events.skipped reason=no_tenancy_id
}

@Test
void skippedCounterForNoMatchingSituation() {
    // fire event with unmatched type → assert ras.engine.events.skipped reason=no_matching_situation
}

@Test
void routedCounterPerSituationDispatch() {
    // fire matched event → assert ras.engine.events.routed per situation
}

@Test
void evaluationFailedCounterOnEvaluatorException() {
    // use failing evaluator → assert ras.engine.evaluation.failed
}

@Test
void counterAdditiveInvariant() {
    // fire mix of valid, no-tenancy, unmatched events
    // assert received = skipped(no_tenancy_id) + skipped(no_matching_situation) + routed
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
/opt/homebrew/bin/mvn --batch-mode -pl runtime -Dtest="RasEngineTest" test
```

Expected: compilation error — constructor parameter count mismatch.

- [ ] **Step 3: Add RasMetrics to RasEngine constructor**

Use `ide_replace_member` on the constructor. Add `private final RasMetrics metrics;` field.

- [ ] **Step 4: Restructure onCloudEvent() for counter ordering**

Use `ide_replace_member` on `onCloudEvent`. New structure:

```java
void onCloudEvent(@ObservesAsync CloudEvent event) {
    metrics.eventReceived(event.getType());

    String tenancyId = extractTenancyId(event);
    if (tenancyId == null) {
        LOG.warning("CloudEvent without tenancyid extension — skipping: " + event.getType());
        metrics.eventSkipped("no_tenancy_id");
        return;
    }

    List<SituationRegistration> registrations = registry.findByEventType(event.getType());
    if (registrations.isEmpty()) {
        metrics.eventSkipped("no_matching_situation");
        return;
    }

    for (SituationRegistration reg : registrations) {
        try {
            String correlationKey = reg.correlationKeyExtractor().extract(event);
            metrics.eventRouted(reg.definition().situationId(), tenancyId);
            evaluator.evaluate(event, reg.definition(), correlationKey, tenancyId);
        } catch (RuntimeException ex) {
            LOG.warning("Evaluation failed for situation '" + reg.definition().situationId()
                        + "': " + ex.getMessage());
            metrics.evaluationFailed(reg.definition().situationId(), tenancyId);
        }
    }
}
```

- [ ] **Step 5: Run tests**

```bash
/opt/homebrew/bin/mvn --batch-mode -pl runtime -Dtest="RasEngineTest" test
```

Expected: all pass.

- [ ] **Step 6: Run diagnostics**

```
ide_diagnostics file="runtime/src/main/java/io/casehub/ras/runtime/RasEngine.java" severity="errors"
```

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -m "feat(casehub-ras#32): RasEngine instrumentation — 4 metrics with additive invariant"
```

---

### Task 5: SituationExpiryJob instrumentation (2 metrics)

**Files:**
- Modify: `runtime/src/main/java/io/casehub/ras/runtime/SituationExpiryJob.java`
- Modify: `runtime/src/test/java/io/casehub/ras/runtime/SituationExpiryJobTest.java`

**Interfaces:**
- Consumes: `RasMetrics` (from Task 2),
  `SituationStore.removeTriggeredBefore() → Uni<Integer>` (from Task 1),
  `SituationStore.removeExpired() → Uni<Integer>` (from Task 1)
- Produces: `SituationExpiryJob(store, registry, triggerGuardPeriod, metrics)` — new constructor

- [ ] **Step 1: Write failing tests — metric assertions in SituationExpiryJobTest**

Add `SimpleMeterRegistry` and `RasMetrics` fields. Update all test methods to pass
`metrics` to the constructor: `new SituationExpiryJob(store, registry, guardPeriod, metrics)`.

Add new test methods:

```java
@Test
void triggeredCleanedCounterReflectsRemovedCount() {
    // save + claim a situation, run cleanup → assert triggered_cleaned count
}

@Test
void expiredCleanedCounterReflectsRemovedCount() {
    // save expired situation, run cleanup → assert expired_cleaned count
}

@Test
void expiredCleanedNotIncrementedWhenNoWindowedDefinitions() {
    // all persistent definitions → assert expired_cleaned metric absent
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
/opt/homebrew/bin/mvn --batch-mode -pl runtime -Dtest="SituationExpiryJobTest" test
```

Expected: compilation error — constructor parameter count mismatch.

- [ ] **Step 3: Add RasMetrics to SituationExpiryJob constructor**

Use `ide_replace_member` on the constructor. Add `private final RasMetrics metrics;` field.

- [ ] **Step 4: Instrument cleanup()**

Use `ide_replace_member` on `cleanup`. Use the `Uni<Integer>` return values:

```java
@Scheduled(every = "PT5M")
void cleanup() {
    Instant guardCutoff = Instant.now().minus(triggerGuardPeriod);
    int triggeredRemoved = store.removeTriggeredBefore(guardCutoff).await().indefinitely();
    metrics.triggeredCleaned(triggeredRemoved);

    Duration maxWindow = registry.maxCorrelationWindow();
    if (maxWindow != null) {
        Instant cutoff = Instant.now().minus(maxWindow);
        int expiredRemoved = store.removeExpired(cutoff).await().indefinitely();
        metrics.expiredCleaned(expiredRemoved);
    }
}
```

- [ ] **Step 5: Run all tests**

```bash
/opt/homebrew/bin/mvn --batch-mode -pl runtime -Dtest="SituationExpiryJobTest" test
```

Expected: all pass.

- [ ] **Step 6: Full build**

```bash
/opt/homebrew/bin/mvn --batch-mode install
```

Expected: all modules compile and all tests pass.

- [ ] **Step 7: Run diagnostics**

```
ide_diagnostics file="runtime/src/main/java/io/casehub/ras/runtime/SituationExpiryJob.java" severity="errors"
```

- [ ] **Step 8: Commit**

```bash
git add -A && git commit -m "feat(casehub-ras#32): SituationExpiryJob instrumentation — triggered_cleaned + expired_cleaned"
```

---

### Task 6: Final verification and CLAUDE.md update

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Full build with all tests**

```bash
/opt/homebrew/bin/mvn --batch-mode install
```

Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 2: Verify metric count matches spec**

Review the implemented `RasMetrics` class and count: 18 counters + 2 timers + 2 gauges = 22.
Cross-reference against spec §3.4 total.

- [ ] **Step 3: Update CLAUDE.md**

Add the new design spec to the Design specs list:

```
- RAS runtime metrics: `docs/superpowers/specs/2026-07-12-ras-runtime-metrics-design.md`
```

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md && git commit -m "docs(casehub-ras#32): add runtime metrics design spec to CLAUDE.md"
```
