# Per-Situation Event Reordering Buffer — Design Spec

**Issue:** casehubio/casehub-ras#16
**Date:** 2026-06-27
**Depends on:** #2 (RAS Runtime — done), #4 (DroolsGanglion — done)
**Prior spec:** `2026-06-25-epic2-ras-runtime-design.md` §5.2, `2026-06-21-epic4-drools-ganglion-design.md` §8.1

## Problem

DroolsGanglion pseudo clock mode requires events dispatched in non-decreasing timestamp
order (Epic 4 §8.1). The runtime dispatches events in CDI arrival order — `@ObservesAsync`
provides no ordering guarantee. Out-of-order events cause `IllegalStateException` (clock
delta < 0). Currently, upstream ordering guarantees (Kafka partition ordering) are required.

## Design

### 1. SituationDefinition gains eventBufferDelay

New nullable `Duration` field on `SituationDefinition` (api/), alongside `correlationWindow`.
Both are nullable Durations governing temporal behavior. Null means no buffering — current
behavior, zero overhead.

```java
public record SituationDefinition(
        String situationId,
        Set<String> eventTypes,
        Duration correlationWindow,
        Duration eventBufferDelay,  // new — nullable
        ChainMode chainMode,
        CaseTriggerConfig triggerConfig
) { ... }
```

Validation: `eventBufferDelay` must be positive when set (same constraint as `correlationWindow`).

### 2. EventReorderBuffer

Per-situation-key buffer in `runtime/`. Holds events sorted by event time, tracks a
watermark, and releases events when they're past the watermark.

```java
class EventReorderBuffer {
    private final Duration bufferDelay;
    private final SituationDefinition definition;
    private final TreeMap<Instant, List<CloudEvent>> pending;  // sorted by event time
    private Instant maxEventTime;       // highest event time seen
    private Instant lastArrivalTime;    // wall-clock of last submit — for idle flush
}
```

**`submit(CloudEvent event, Instant now) → List<CloudEvent>`:**
1. Record `lastArrivalTime = now`
2. Insert event by `CloudEvent.getTime()` into the TreeMap
3. Update `maxEventTime`: if null (first event), set to `event.getTime()`;
   otherwise `max(maxEventTime, event.getTime())`
4. Compute watermark = `maxEventTime - bufferDelay`
5. Drain: poll all entries with key ≤ watermark, flatten into a list, return in order

**`drainAll() → List<CloudEvent>`:** Returns all pending events in event-time order.
Used by the idle flush job.

**`isIdle(Instant now) → boolean`:** Returns `now - lastArrivalTime > bufferDelay`.
The idle check uses wall-clock time only — it answers "have all events within the
disorder window plausibly arrived?" without mixing time domains.

**Late events (event time below watermark):** Inserted and immediately drained on the
next submit — they arrive at the ganglion out of order. DroolsGanglion's pseudo clock
throws `IllegalStateException`. This is correct — the buffer cannot help for events later
than `bufferDelay`. The `bufferDelay` must be configured greater than the maximum expected
out-of-order delay in the event source.

**`isEmpty() → boolean`:** For buffer cleanup on situation termination.

### 3. SituationEvaluator integration

`SituationEvaluator` gains a `ConcurrentHashMap<SituationInstanceKey, EventReorderBuffer>`
for active buffers. The existing `evaluate()` method splits into buffer submission and
event processing:

```java
public void evaluate(CloudEvent event, SituationDefinition definition,
                     String correlationKey, String tenancyId) {
    var key = new SituationInstanceKey(definition.situationId(), correlationKey, tenancyId);
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
```

**`processEvent()` returns a termination signal.** The extracted pipeline — load/create
SituationContext, check expiry, dispatch to ganglia, evaluate trigger policy, act on
decision — returns `true` for CREATE_CASE and DISCARD, `false` for CONTINUE_ACCUMULATING.
Lock and buffer removal move from inside processEvent() to evaluate(), after the loop.

This separation is necessary for correctness: if a batch of buffered events triggers
CREATE_CASE mid-loop, the remaining events must NOT be processed. Without the break:
- `locks.remove(key)` inside processEvent() breaks the serialization guarantee — a
  concurrent `evaluate()` for the same key creates a new lock and enters its own
  synchronized block (violates Epic 4 §7).
- Remaining events would restart the situation on a fresh SituationContext, semantically
  inconsistent with the terminated situation's event stream.

The `caseTrigger.fire()` failure case (current code: `return` at line 74) becomes a
non-termination: processEvent() returns `false`, remaining buffered events continue
processing against the saved context. This is correct — the situation is still alive.

**Note:** `locks.remove(key)` inside synchronized is pre-existing (lines 78, 89 of
current SituationEvaluator). Benign today (one event per evaluate call) but becomes a
real race with batched processing. Moving it to evaluate() after the loop resolves both
the pre-existing and new cases.

**Null-time events bypass the buffer.** `CloudEvent.getTime()` can be null. These events
cannot be ordered by event time. They are processed immediately — DroolsGanglion's
`advanceClock()` already no-ops on null time, so they don't affect the pseudo clock.

**Buffer cleanup on situation termination:** Handled by the `if (terminated)` block in
evaluate() — both buffers and locks are removed. Remaining buffered events (still in the
buffer's TreeMap, not yet drained) are garbage collected with the buffer. Events in the
`toProcess` list (drained but not yet processed) are dropped by the loop break.

**Latency cost on stateless ganglia:** If a situation has both a DroolsGanglion (pseudo
clock, needs ordering) and a JavaSwitchGanglion (stateless, doesn't), the
JavaSwitchGanglion's detection is delayed by up to `bufferDelay`. This is the correct
trade-off — all ganglia in a situation should see events in the same order for trigger
policy consistency. Operators control this via per-situation `eventBufferDelay`; situations
that don't need ordering leave it null.

### 4. Idle flush job

A `@Scheduled` CDI bean that flushes buffers for idle situations — situations that haven't
received a new event for longer than their `bufferDelay`. Without this, idle situations
hold buffered events indefinitely.

```java
@ApplicationScoped
public class EventBufferFlushJob {
    @Inject SituationEvaluator evaluator;

    @Scheduled(every = "PT1S")
    void flush() {
        evaluator.flushIdleBuffers(Instant.now());
    }
}
```

**`SituationEvaluator.flushIdleBuffers(Instant now)`:**

```java
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

Same mid-batch termination pattern as `evaluate()`. `ConcurrentHashMap` iteration is
weakly consistent — `buffers.remove(key)` during iteration is safe (no
`ConcurrentModificationException`).

**Flush job acquires the per-key lock.** The flush job processes events through the same
pipeline as `evaluate()`. Without the per-key lock, it would race with concurrent
`evaluate()` calls — double-processing events or interleaving with an in-progress
evaluation. The lock is acquired inside the loop, per-key, matching the existing
serialization contract (Epic 4 §7).

**1-second interval:** Low overhead — when no buffers are active, the loop iterates
nothing. The interval bounds worst-case idle-flush latency to `bufferDelay + 1s`.

### 5. YAML provider support

`YamlSituationDefinitionProvider` gains `eventBufferDelay` parsing:

```yaml
situations:
  - situationId: temperature-spike
    eventTypes: [temperature.reading]
    correlationWindow: PT5M
    eventBufferDelay: PT3S      # new — optional
    chainMode:
      type: or
      ganglia: [temperature-cep]
    triggerConfig:
      caseNamespace: iot
      caseName: temperature-spike
      caseVersion: "1.0"
```

Parsed as `Duration.parse()`, same as `correlationWindow`. Null when absent.

## Out of scope

- Dynamic buffer delay adjustment at runtime
- Per-ganglion buffering (buffer is per-situation, all ganglia see same event order)
- Buffer persistence across restarts (in-memory only, like InMemorySituationStore)
- SituationExpiryJob integration for buffer cleanup (pre-existing gap: expiry doesn't
  clean up the lock map either — both leak entries for expired situations)

## Test plan

1. No buffer (eventBufferDelay null): events processed immediately — existing behavior.
2. Buffer reorders out-of-order events: submit T=10 then T=5, both arrive at ganglion
   in T=5, T=10 order.
3. Watermark advancement: event at T=10 with 5s buffer → watermark at T=5 → event at
   T=3 is released, event at T=8 stays buffered.
4. Late event (below watermark): arrives at ganglion out of order — verify
   DroolsGanglion's pseudo clock throws.
5. Null-time event bypasses buffer: processed immediately, doesn't affect buffered events.
6. Idle flush: buffer with events, no new arrivals for > bufferDelay → events flushed.
7. Buffer cleanup on CREATE_CASE: situation terminates, buffer removed, buffered events
   discarded.
8. SituationDefinition validation: eventBufferDelay zero or negative throws.
9. YAML parsing: eventBufferDelay parsed correctly, absent → null.
10. Mid-batch termination: batch of buffered events where the second triggers CREATE_CASE
    → loop stops, remaining events are not processed, buffer and lock removed.
11. Full lifecycle: mixed time-bearing and null-time events, buffer delay, out-of-order
    arrival, all processed correctly.
