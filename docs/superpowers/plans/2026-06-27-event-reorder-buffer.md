# Per-Situation Event Reordering Buffer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Buffer and reorder CloudEvents by timestamp before ganglion dispatch, eliminating the upstream ordering requirement for DroolsGanglion pseudo clock mode.

**Architecture:** `SituationDefinition` gains a nullable `eventBufferDelay` Duration. When set, `SituationEvaluator` submits events to a per-situation-key `EventReorderBuffer` that holds events sorted by timestamp and releases them when the watermark (max event time - bufferDelay) passes. An idle flush job handles situations that stop receiving events. The existing `evaluate()` is split into buffer orchestration + a `processEvent()` pipeline that returns a termination signal for mid-batch CREATE_CASE/DISCARD.

**Tech Stack:** Java 21, Quarkus (CDI, @Scheduled), CloudEvents, Mutiny, JUnit 5, AssertJ

## Global Constraints

- Changes span `api/` (SituationDefinition) and `runtime/` (all other files)
- `SituationDefinition` record gains `eventBufferDelay` between `correlationWindow` and `chainMode` — ALL existing constructor calls must add `null` as the new 4th parameter
- `DroolsSessionStore` SPI is unchanged
- `Ganglion` SPI is unchanged
- All existing tests must pass after each task
- Spec: `docs/superpowers/specs/2026-06-27-event-reorder-buffer-design.md`

---

### Task 1: SituationDefinition API change + EventReorderBuffer + caller migration

Add `eventBufferDelay` to the API, create the buffer data structure with unit tests, and migrate all existing constructor calls.

**Files:**
- Modify: `api/src/main/java/io/casehub/ras/api/SituationDefinition.java`
- Modify: `api/src/test/java/io/casehub/ras/api/SituationDefinitionTest.java`
- Create: `runtime/src/main/java/io/casehub/ras/runtime/EventReorderBuffer.java`
- Create: `runtime/src/test/java/io/casehub/ras/runtime/EventReorderBufferTest.java`
- Modify (caller migration): `runtime/src/main/java/io/casehub/ras/runtime/YamlSituationDefinitionProvider.java`
- Modify (caller migration): `runtime/src/test/java/io/casehub/ras/runtime/RasEngineTest.java`
- Modify (caller migration): `runtime/src/test/java/io/casehub/ras/runtime/SituationEvaluatorTest.java`
- Modify (caller migration): `runtime/src/test/java/io/casehub/ras/runtime/DefaultRasTriggerPolicyTest.java`
- Modify (caller migration): `runtime/src/test/java/io/casehub/ras/runtime/SituationDefinitionRegistryTest.java`
- Modify (caller migration): `runtime/src/test/java/io/casehub/ras/runtime/SituationRegistrationTest.java`
- Modify (caller migration): `runtime/src/test/java/io/casehub/ras/runtime/SituationExpiryJobTest.java`
- Modify (caller migration): `runtime/src/test/java/io/casehub/ras/runtime/YamlSituationDefinitionProviderTest.java`

**Interfaces:**
- Consumes: existing `SituationDefinition` record, `CloudEvent` from cloudevents-core
- Produces: `SituationDefinition.eventBufferDelay()` (nullable Duration), `EventReorderBuffer` class with `submit(CloudEvent, Instant) → List<CloudEvent>`, `drainAll() → List<CloudEvent>`, `isIdle(Instant) → boolean`, `isEmpty() → boolean`, `definition() → SituationDefinition`

- [ ] **Step 1: Add eventBufferDelay field to SituationDefinition**

In `api/src/main/java/io/casehub/ras/api/SituationDefinition.java`, add the new field between `correlationWindow` and `chainMode`, and add validation in the compact constructor:

```java
public record SituationDefinition(
        String situationId,
        Set<String> eventTypes,
        Duration correlationWindow,
        Duration eventBufferDelay,
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
        if (eventBufferDelay != null
                && (eventBufferDelay.isZero() || eventBufferDelay.isNegative())) {
            throw new IllegalArgumentException(
                    "eventBufferDelay must be positive when set, got: " + eventBufferDelay);
        }
    }
}
```

- [ ] **Step 2: Migrate ALL existing constructor calls — insert null as the 4th parameter**

The record constructor parameter order changed from `(situationId, eventTypes, correlationWindow, chainMode, triggerConfig)` to `(situationId, eventTypes, correlationWindow, eventBufferDelay, chainMode, triggerConfig)`. Every existing `new SituationDefinition(...)` call needs `null` inserted between the `correlationWindow` argument and the `chainMode` argument.

**Migration pattern — before:**
```java
new SituationDefinition("sit-1", Set.of("e"), Duration.ofMinutes(5), chainMode, trigger)
```
**After:**
```java
new SituationDefinition("sit-1", Set.of("e"), Duration.ofMinutes(5), null, chainMode, trigger)
```

Files to migrate (insert `null` before the `chainMode` / `ChainMode` argument in every `new SituationDefinition(...)` call):

1. `api/src/test/java/io/casehub/ras/api/SituationDefinitionTest.java` — 8 constructor calls (lines 17, 27, 35, 43, 51, 59, 67, 75)
2. `runtime/src/main/java/io/casehub/ras/runtime/YamlSituationDefinitionProvider.java` — 1 call (line 91): insert `null,` between `correlationWindow,` and `chainMode,`
3. `runtime/src/test/java/io/casehub/ras/runtime/SituationEvaluatorTest.java` — helper + 9 inline calls. All construct with `Duration.ofMinutes(N)` or `null` then `new ChainMode.X(...)` — insert `null,` between them.
4. `runtime/src/test/java/io/casehub/ras/runtime/RasEngineTest.java` — 3 calls (lines 41, 61, 81)
5. `runtime/src/test/java/io/casehub/ras/runtime/DefaultRasTriggerPolicyTest.java` — helper method `def()` (line 22)
6. `runtime/src/test/java/io/casehub/ras/runtime/SituationDefinitionRegistryTest.java` — helper method `definition()` (line 21)
7. `runtime/src/test/java/io/casehub/ras/runtime/SituationRegistrationTest.java` — constant (line 12)
8. `runtime/src/test/java/io/casehub/ras/runtime/SituationExpiryJobTest.java` — 2 calls (lines 28, 48)
9. `runtime/src/test/java/io/casehub/ras/runtime/YamlSituationDefinitionProviderTest.java` — check for any assertion that accesses field by position

- [ ] **Step 3: Add validation tests for eventBufferDelay**

Add to `SituationDefinitionTest.java`:

```java
    @Test
    void nullEventBufferDelayIsAllowed() {
        var def = new SituationDefinition("sit-1",
                Set.of("type"), null, null, CHAIN, TRIGGER);
        assertThat(def.eventBufferDelay()).isNull();
    }

    @Test
    void validEventBufferDelay() {
        var def = new SituationDefinition("sit-1",
                Set.of("type"), null, Duration.ofSeconds(5), CHAIN, TRIGGER);
        assertThat(def.eventBufferDelay()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void zeroEventBufferDelayRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SituationDefinition("sit-1",
                        Set.of("type"), null, Duration.ZERO, CHAIN, TRIGGER))
                .withMessageContaining("positive");
    }

    @Test
    void negativeEventBufferDelayRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SituationDefinition("sit-1",
                        Set.of("type"), null, Duration.ofSeconds(-1), CHAIN, TRIGGER))
                .withMessageContaining("positive");
    }
```

- [ ] **Step 4: Run all tests to verify caller migration**

Run: `/opt/homebrew/bin/mvn --batch-mode install`
Expected: All tests pass. The migration is purely mechanical — null eventBufferDelay produces identical behavior.

- [ ] **Step 5: Create EventReorderBuffer**

Create `runtime/src/main/java/io/casehub/ras/runtime/EventReorderBuffer.java`:

```java
package io.casehub.ras.runtime;

import io.casehub.ras.api.SituationDefinition;
import io.cloudevents.CloudEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

class EventReorderBuffer {

    private final Duration bufferDelay;
    private final SituationDefinition definition;
    private final TreeMap<Instant, List<CloudEvent>> pending = new TreeMap<>();
    private Instant maxEventTime;
    private Instant lastArrivalTime;

    EventReorderBuffer(Duration bufferDelay, SituationDefinition definition) {
        this.bufferDelay = bufferDelay;
        this.definition = definition;
    }

    List<CloudEvent> submit(CloudEvent event, Instant now) {
        lastArrivalTime = now;
        Instant eventTime = event.getTime().toInstant();
        pending.computeIfAbsent(eventTime, k -> new ArrayList<>()).add(event);
        maxEventTime = (maxEventTime == null) ? eventTime
                : eventTime.isAfter(maxEventTime) ? eventTime : maxEventTime;
        Instant watermark = maxEventTime.minus(bufferDelay);
        return drain(watermark);
    }

    List<CloudEvent> drainAll() {
        List<CloudEvent> result = new ArrayList<>();
        for (List<CloudEvent> events : pending.values()) {
            result.addAll(events);
        }
        pending.clear();
        return result;
    }

    boolean isIdle(Instant now) {
        return lastArrivalTime != null
                && now.isAfter(lastArrivalTime.plus(bufferDelay))
                && !pending.isEmpty();
    }

    boolean isEmpty() {
        return pending.isEmpty();
    }

    SituationDefinition definition() {
        return definition;
    }

    private List<CloudEvent> drain(Instant watermark) {
        List<CloudEvent> result = new ArrayList<>();
        var head = pending.headMap(watermark, true);
        for (List<CloudEvent> events : head.values()) {
            result.addAll(events);
        }
        head.clear();
        return result;
    }
}
```

- [ ] **Step 6: Write EventReorderBuffer unit tests**

Create `runtime/src/test/java/io/casehub/ras/runtime/EventReorderBufferTest.java`:

```java
package io.casehub.ras.runtime;

import io.casehub.ras.api.*;
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

class EventReorderBufferTest {

    private static final Duration BUFFER_DELAY = Duration.ofSeconds(5);
    private static final SituationDefinition DEF = new SituationDefinition(
            "sit-1", Set.of("test.event"), null, BUFFER_DELAY,
            new ChainMode.Or(Set.of("g1")),
            new CaseTriggerConfig("ns", "case", "1.0", Map.of()));

    private static final Instant NOW = Instant.parse("2026-06-27T10:00:00Z");

    private CloudEvent eventAt(Instant time) {
        return CloudEventBuilder.v1()
                .withId("evt-" + time.getEpochSecond())
                .withSource(URI.create("/test"))
                .withType("test.event")
                .withTime(OffsetDateTime.ofInstant(time, ZoneOffset.UTC))
                .build();
    }

    @Test
    void singleEventWithinDelayStaysBuffered() {
        var buffer = new EventReorderBuffer(BUFFER_DELAY, DEF);
        var event = eventAt(Instant.parse("2026-06-27T10:00:10Z"));
        List<CloudEvent> released = buffer.submit(event, NOW);
        assertThat(released).isEmpty();
        assertThat(buffer.isEmpty()).isFalse();
    }

    @Test
    void eventPastWatermarkIsReleased() {
        var buffer = new EventReorderBuffer(BUFFER_DELAY, DEF);
        var e1 = eventAt(Instant.parse("2026-06-27T10:00:00Z"));
        var e2 = eventAt(Instant.parse("2026-06-27T10:00:10Z"));
        buffer.submit(e1, NOW);
        List<CloudEvent> released = buffer.submit(e2, NOW.plusSeconds(1));
        // watermark = 10s - 5s = 5s. e1 at 0s <= 5s → released
        assertThat(released).containsExactly(e1);
    }

    @Test
    void outOfOrderEventsReorderedByEventTime() {
        var buffer = new EventReorderBuffer(BUFFER_DELAY, DEF);
        var t5 = Instant.parse("2026-06-27T10:00:05Z");
        var t10 = Instant.parse("2026-06-27T10:00:10Z");
        var t3 = Instant.parse("2026-06-27T10:00:03Z");

        // Submit T=10 first (out of order)
        buffer.submit(eventAt(t10), NOW);
        // Submit T=5 (also out of order but within buffer)
        List<CloudEvent> r1 = buffer.submit(eventAt(t5), NOW.plusSeconds(1));
        // watermark = 10s - 5s = 5s. t3 and t5 are <= 5s
        assertThat(r1).hasSize(1);
        assertThat(r1.get(0).getTime().toInstant()).isEqualTo(t5);

        // Submit T=3 (late — below watermark, immediately released)
        List<CloudEvent> r2 = buffer.submit(eventAt(t3), NOW.plusSeconds(2));
        assertThat(r2).hasSize(1);
        assertThat(r2.get(0).getTime().toInstant()).isEqualTo(t3);
    }

    @Test
    void drainAllReturnsEventsInOrder() {
        var buffer = new EventReorderBuffer(BUFFER_DELAY, DEF);
        var t1 = Instant.parse("2026-06-27T10:00:01Z");
        var t2 = Instant.parse("2026-06-27T10:00:02Z");
        buffer.submit(eventAt(t2), NOW);
        buffer.submit(eventAt(t1), NOW.plusSeconds(1));
        List<CloudEvent> all = buffer.drainAll();
        assertThat(all).hasSize(2);
        assertThat(all.get(0).getTime().toInstant()).isEqualTo(t1);
        assertThat(all.get(1).getTime().toInstant()).isEqualTo(t2);
    }

    @Test
    void isIdleReturnsTrueAfterInactivity() {
        var buffer = new EventReorderBuffer(BUFFER_DELAY, DEF);
        buffer.submit(eventAt(Instant.parse("2026-06-27T10:00:01Z")), NOW);
        assertThat(buffer.isIdle(NOW.plusSeconds(3))).isFalse();
        assertThat(buffer.isIdle(NOW.plusSeconds(6))).isTrue();
    }

    @Test
    void isIdleReturnsFalseWhenEmpty() {
        var buffer = new EventReorderBuffer(BUFFER_DELAY, DEF);
        assertThat(buffer.isIdle(NOW.plusSeconds(100))).isFalse();
    }

    @Test
    void watermarkAdvancesWithMaxEventTime() {
        var buffer = new EventReorderBuffer(BUFFER_DELAY, DEF);
        var t1 = Instant.parse("2026-06-27T10:00:01Z");
        var t10 = Instant.parse("2026-06-27T10:00:10Z");
        var t8 = Instant.parse("2026-06-27T10:00:08Z");

        buffer.submit(eventAt(t1), NOW);
        buffer.submit(eventAt(t10), NOW.plusSeconds(1));
        // watermark = 10 - 5 = 5. t1 at 1s <= 5s → released
        // t10 at 10s > 5s → stays

        // Submit t8 — doesn't advance maxEventTime (8 < 10). watermark stays at 5s.
        List<CloudEvent> r = buffer.submit(eventAt(t8), NOW.plusSeconds(2));
        // t8 at 8s > 5s → stays. Nothing new released.
        assertThat(r).isEmpty();
    }
}
```

- [ ] **Step 7: Run all tests**

Run: `/opt/homebrew/bin/mvn --batch-mode install`
Expected: All tests pass — existing (with null migration) and new EventReorderBuffer tests.

- [ ] **Step 8: Commit**

```bash
git add api/src/main/java/io/casehub/ras/api/SituationDefinition.java \
       api/src/test/java/io/casehub/ras/api/SituationDefinitionTest.java \
       runtime/src/main/java/io/casehub/ras/runtime/EventReorderBuffer.java \
       runtime/src/test/java/io/casehub/ras/runtime/EventReorderBufferTest.java \
       runtime/src/main/java/io/casehub/ras/runtime/YamlSituationDefinitionProvider.java \
       runtime/src/test/java/io/casehub/ras/runtime/RasEngineTest.java \
       runtime/src/test/java/io/casehub/ras/runtime/SituationEvaluatorTest.java \
       runtime/src/test/java/io/casehub/ras/runtime/DefaultRasTriggerPolicyTest.java \
       runtime/src/test/java/io/casehub/ras/runtime/SituationDefinitionRegistryTest.java \
       runtime/src/test/java/io/casehub/ras/runtime/SituationRegistrationTest.java \
       runtime/src/test/java/io/casehub/ras/runtime/SituationExpiryJobTest.java \
       runtime/src/test/java/io/casehub/ras/runtime/YamlSituationDefinitionProviderTest.java
git commit -m "feat(casehub-ras#16): SituationDefinition.eventBufferDelay + EventReorderBuffer

Add nullable eventBufferDelay Duration to SituationDefinition (api/).
EventReorderBuffer: watermark-based reordering by event time with
idle detection. All existing callers migrated to null (no buffering).

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: SituationEvaluator refactoring — processEvent extraction + buffer integration

Extract the single-event pipeline from `evaluate()` into `processEvent()` with a termination signal. Add buffer submission, mid-batch termination, and flushIdleBuffers().

**Files:**
- Modify: `runtime/src/main/java/io/casehub/ras/runtime/SituationEvaluator.java`
- Modify: `runtime/src/test/java/io/casehub/ras/runtime/SituationEvaluatorTest.java`

**Interfaces:**
- Consumes: `EventReorderBuffer` from Task 1 (submit, drainAll, isIdle, definition), `SituationDefinition.eventBufferDelay()`
- Produces: `SituationEvaluator.flushIdleBuffers(Instant now)` — package-private, called by EventBufferFlushJob (Task 3)

- [ ] **Step 1: Write test — no buffer (eventBufferDelay null) behaves identically**

Add to `SituationEvaluatorTest.java`:

```java
    @Test
    void nullBufferDelayProcessesImmediately() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")), TRIGGER_CONFIG);
        buildEvaluator(List.of(ganglion), def);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

        assertThat(caseTrigger.firedCases()).hasSize(1);
    }
```

- [ ] **Step 2: Run test — verify it passes (baseline, no code change yet)**

Run: `/opt/homebrew/bin/mvn --batch-mode -pl runtime -am test -Dtest="SituationEvaluatorTest#nullBufferDelayProcessesImmediately"`
Expected: PASS — null eventBufferDelay hits the else branch.

- [ ] **Step 3: Write test — buffer reorders out-of-order events**

```java
    @Test
    void bufferReordersOutOfOrderEvents() {
        var detections = new java.util.ArrayList<Instant>();
        var ganglion = new Ganglion() {
            @Override public String ganglionId() { return "g1"; }
            @Override public Set<String> handledEventTypes() { return Set.of("temp.reading"); }
            @Override public io.smallrye.mutiny.Uni<DetectionResult> detect(
                    io.cloudevents.CloudEvent event, SituationContext context) {
                detections.add(event.getTime().toInstant());
                return io.smallrye.mutiny.Uni.createFrom().item(
                        FixedDetectionResult.detected("g1", 0.4));
            }
        };
        // 5-second buffer, Count(g1, 3) so it accumulates
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), Duration.ofSeconds(5),
                new ChainMode.Count("g1", 3), TRIGGER_CONFIG);
        buildEvaluator(List.of(ganglion), def);

        var t10 = Instant.parse("2026-06-25T10:00:10Z");
        var t5 = Instant.parse("2026-06-25T10:00:05Z");
        var t3 = Instant.parse("2026-06-25T10:00:03Z");

        // T=10 arrives first — buffered (watermark = 10-5 = 5, nothing to drain)
        evaluator.evaluate(event("temp.reading", t10), def, "key-1", "tenant-a");
        assertThat(detections).isEmpty();

        // T=5 arrives — buffered. watermark = 10-5 = 5. T=5 <= 5 → released.
        evaluator.evaluate(event("temp.reading", t5), def, "key-1", "tenant-a");
        assertThat(detections).containsExactly(t5);

        // T=3 arrives — late (below watermark). Released immediately.
        evaluator.evaluate(event("temp.reading", t3), def, "key-1", "tenant-a");
        assertThat(detections).containsExactly(t5, t3);
    }
```

- [ ] **Step 4: Run test — verify it fails**

Run: `/opt/homebrew/bin/mvn --batch-mode -pl runtime -am test -Dtest="SituationEvaluatorTest#bufferReordersOutOfOrderEvents"`
Expected: FAIL — evaluate() doesn't buffer yet, processes immediately.

- [ ] **Step 5: Refactor evaluate() — extract processEvent(), add buffer logic**

Rewrite `SituationEvaluator.evaluate()`. Add `buffers` map and `processEvent()`:

```java
    private final ConcurrentHashMap<SituationInstanceKey, EventReorderBuffer> buffers = new ConcurrentHashMap<>();

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

        SituationContext context = store.find(situationId, correlationKey, tenancyId)
                .await().indefinitely()
                .orElseGet(() -> SituationContext.initial(situationId, correlationKey,
                                                         tenancyId, eventTime));

        if (isExpired(context, definition, eventTime)) {
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
```

Note: `locks.remove(key)` moves from the old CREATE_CASE/DISCARD branches (inside the pipeline) to `evaluate()` (after the pipeline). This fixes the pre-existing race where lock removal inside the synchronized block could allow concurrent access on the same key.

Add the required import:
```java
import java.util.List;
```

- [ ] **Step 6: Run test — verify buffer test passes**

Run: `/opt/homebrew/bin/mvn --batch-mode -pl runtime -am test -Dtest="SituationEvaluatorTest#bufferReordersOutOfOrderEvents"`
Expected: PASS

- [ ] **Step 7: Write test — null-time event bypasses buffer**

```java
    @Test
    void nullTimeEventBypassesBuffer() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), Duration.ofSeconds(5),
                new ChainMode.Or(Set.of("g1")), TRIGGER_CONFIG);
        buildEvaluator(List.of(ganglion), def);

        CloudEvent noTime = CloudEventBuilder.v1()
                .withId("evt-null").withSource(URI.create("/test"))
                .withType("temp.reading").build();

        evaluator.evaluate(noTime, def, "key-1", "tenant-a");

        assertThat(caseTrigger.firedCases()).hasSize(1);
    }
```

- [ ] **Step 8: Write test — mid-batch termination stops loop**

```java
    @Test
    void midBatchTerminationStopsProcessing() {
        var callCount = new AtomicInteger();
        var ganglion = new Ganglion() {
            @Override public String ganglionId() { return "g1"; }
            @Override public Set<String> handledEventTypes() { return Set.of("temp.reading"); }
            @Override public io.smallrye.mutiny.Uni<DetectionResult> detect(
                    io.cloudevents.CloudEvent event, SituationContext context) {
                callCount.incrementAndGet();
                return io.smallrye.mutiny.Uni.createFrom().item(
                        FixedDetectionResult.detected("g1", 0.9));
            }
        };
        // Or mode with 1 ganglion → first detection triggers CREATE_CASE
        // Buffer with large delay so all events stay buffered until a late event releases them
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), Duration.ofSeconds(2),
                new ChainMode.Or(Set.of("g1")), TRIGGER_CONFIG);
        buildEvaluator(List.of(ganglion), def);

        var t1 = Instant.parse("2026-06-25T10:00:01Z");
        var t2 = Instant.parse("2026-06-25T10:00:02Z");
        var t10 = Instant.parse("2026-06-25T10:00:10Z");

        // Buffer t1 and t2
        evaluator.evaluate(event("temp.reading", t1), def, "key-1", "tenant-a");
        evaluator.evaluate(event("temp.reading", t2), def, "key-1", "tenant-a");
        assertThat(callCount.get()).isZero();

        // t10 arrives — watermark = 10-2 = 8. All three events released.
        // t1 triggers CREATE_CASE → loop stops → t2 and t10 not processed.
        evaluator.evaluate(event("temp.reading", t10), def, "key-1", "tenant-a");
        assertThat(callCount.get()).isEqualTo(1);
        assertThat(caseTrigger.firedCases()).hasSize(1);
    }
```

- [ ] **Step 9: Write test — idle flush processes buffered events**

```java
    @Test
    void idleFlushProcessesBufferedEvents() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), Duration.ofSeconds(5),
                new ChainMode.Or(Set.of("g1")), TRIGGER_CONFIG);
        buildEvaluator(List.of(ganglion), def);

        // Event arrives, stays buffered (within delay window)
        var t1 = Instant.parse("2026-06-25T10:00:01Z");
        evaluator.evaluate(event("temp.reading", t1), def, "key-1", "tenant-a");
        assertThat(caseTrigger.firedCases()).isEmpty();

        // Simulate idle flush after bufferDelay
        Instant flushTime = Instant.now().plusSeconds(10);
        evaluator.flushIdleBuffers(flushTime);

        assertThat(caseTrigger.firedCases()).hasSize(1);
    }
```

- [ ] **Step 10: Run all tests**

Run: `/opt/homebrew/bin/mvn --batch-mode install`
Expected: All tests pass — existing (processEvent refactoring preserves behavior) and new buffer tests.

- [ ] **Step 11: Commit**

```bash
git add runtime/src/main/java/io/casehub/ras/runtime/SituationEvaluator.java \
       runtime/src/test/java/io/casehub/ras/runtime/SituationEvaluatorTest.java
git commit -m "feat(casehub-ras#16): SituationEvaluator buffer integration + processEvent extraction

evaluate() splits into buffer orchestration + processEvent() pipeline.
processEvent() returns termination signal for mid-batch CREATE_CASE/DISCARD.
Locks/buffers removed in evaluate() (not processEvent), fixing pre-existing
race. flushIdleBuffers() for idle situations.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: EventBufferFlushJob + YAML provider support

Add the scheduled flush job CDI bean and YAML parsing for eventBufferDelay.

**Files:**
- Create: `runtime/src/main/java/io/casehub/ras/runtime/EventBufferFlushJob.java`
- Modify: `runtime/src/main/java/io/casehub/ras/runtime/YamlSituationDefinitionProvider.java`
- Modify: `runtime/src/test/java/io/casehub/ras/runtime/YamlSituationDefinitionProviderTest.java`

**Interfaces:**
- Consumes: `SituationEvaluator.flushIdleBuffers(Instant)` from Task 2
- Produces: `EventBufferFlushJob` CDI bean (@Scheduled every 1s), YAML `eventBufferDelay` field parsing

- [ ] **Step 1: Create EventBufferFlushJob**

Create `runtime/src/main/java/io/casehub/ras/runtime/EventBufferFlushJob.java`:

```java
package io.casehub.ras.runtime;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;

@ApplicationScoped
public class EventBufferFlushJob {

    private final SituationEvaluator evaluator;

    @Inject
    public EventBufferFlushJob(SituationEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    @Scheduled(every = "PT1S")
    void flush() {
        evaluator.flushIdleBuffers(Instant.now());
    }
}
```

- [ ] **Step 2: Add YAML parsing for eventBufferDelay**

In `YamlSituationDefinitionProvider.java`, add parsing in `parseSituation()`. Insert between the `correlationWindow` and `chainModeMap` parsing:

```java
        Duration eventBufferDelay = null;
        if (map.containsKey("eventBufferDelay")) {
            eventBufferDelay = Duration.parse((String) map.get("eventBufferDelay"));
        }
```

And update the `SituationDefinition` construction:

```java
        SituationDefinition def = new SituationDefinition(
                situationId, new LinkedHashSet<>(eventTypeList),
                correlationWindow, eventBufferDelay, chainMode, triggerConfig);
```

- [ ] **Step 3: Write YAML parsing test**

Add to `YamlSituationDefinitionProviderTest.java` (or create if absent). Create a test YAML with eventBufferDelay:

```java
    @Test
    void parsesEventBufferDelay() {
        var yaml = """
                situations:
                  - situationId: buffered-sit
                    eventTypes: [test.event]
                    correlationWindow: PT5M
                    eventBufferDelay: PT3S
                    chainMode:
                      type: or
                      ganglia: [g1]
                    triggerConfig:
                      caseNamespace: ns
                      caseName: case
                      caseVersion: "1.0"
                """;
        var provider = new YamlSituationDefinitionProvider(
                new java.io.ByteArrayInputStream(yaml.getBytes()));
        var regs = provider.registrations();
        assertThat(regs).hasSize(1);
        assertThat(regs.get(0).definition().eventBufferDelay())
                .isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void absentEventBufferDelayIsNull() {
        var yaml = """
                situations:
                  - situationId: no-buffer
                    eventTypes: [test.event]
                    chainMode:
                      type: or
                      ganglia: [g1]
                    triggerConfig:
                      caseNamespace: ns
                      caseName: case
                      caseVersion: "1.0"
                """;
        var provider = new YamlSituationDefinitionProvider(
                new java.io.ByteArrayInputStream(yaml.getBytes()));
        var regs = provider.registrations();
        assertThat(regs).hasSize(1);
        assertThat(regs.get(0).definition().eventBufferDelay()).isNull();
    }
```

- [ ] **Step 4: Run all tests**

Run: `/opt/homebrew/bin/mvn --batch-mode install`
Expected: All tests pass.

- [ ] **Step 5: Commit**

```bash
git add runtime/src/main/java/io/casehub/ras/runtime/EventBufferFlushJob.java \
       runtime/src/main/java/io/casehub/ras/runtime/YamlSituationDefinitionProvider.java \
       runtime/src/test/java/io/casehub/ras/runtime/YamlSituationDefinitionProviderTest.java
git commit -m "feat(casehub-ras#16): EventBufferFlushJob + YAML eventBufferDelay parsing

@Scheduled flush job (1s interval) calls evaluator.flushIdleBuffers().
YamlSituationDefinitionProvider parses optional eventBufferDelay as
Duration. Absent → null (no buffering).

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```
