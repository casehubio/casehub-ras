# Situation Replay — Validate Definitions Against Historical Event Streams

**Issue:** casehubio/casehub-ras#44
**Date:** 2026-08-19

## Problem

There is no way to answer "would this situation definition have caught last week's incident?" without deploying and waiting. Situation definition authoring is trial-and-error in production. Consumers need confidence that new or modified definitions behave correctly before going live — especially for Threshold and Sequence chain modes where subtle configuration errors produce false positives or missed detections.

## Solution

A programmatic `SituationReplayRunner` in the `runtime/` module that accepts historical CloudEvent records, runs them through situation definitions using the real detection pipeline (ganglia, chain modes, trigger policy), and reports what would have triggered — deterministic, side-effect free, time-aware.

### Properties

- **Deterministic** — same input stream + same definitions = same output, always
- **Side-effect free** — uses in-memory stores by default, mock CaseTrigger by default
- **Time-aware** — respects event timestamps for windowed situations, correlation windows, and event reorder buffering
- **Full-fidelity** — runs the exact same code path as production (`SituationEvaluator.evaluate()`)

## Architecture

### Module Placement

`SituationReplayRunner` lives in `runtime/` (`casehub-ras`), not `testing/`. The runner orchestrates the production detection pipeline — it needs package-private access to `RasMetrics`, `flushIdleBuffers()`, and event buffer internals for end-of-stream drain. It accepts `SituationStore` and `CaseTrigger` as API interface parameters, so consumers provide their own implementations (`InMemorySituationStore`, `MockCaseTrigger`) from their test classpath without `runtime/` acquiring new dependencies.

### Pipeline Wiring

The runner constructs the full pipeline internally:

```
SituationReplayRunner
  ├── SituationDefinitionRegistry  (from YAML or programmatic registrations)
  ├── SituationEvaluator
  │     ├── SituationStore          (consumer-provided or InMemorySituationStore default)
  │     ├── DefaultRasTriggerPolicy
  │     ├── CaseTrigger             (consumer-provided or no-op default)
  │     ├── CollectingChangeEvent   (records SituationChangeEvents)
  │     ├── RasMetrics              (SimpleMeterRegistry)
  │     ├── SuppressionStrategy     (opt-in, null by default)
  │     ├── OutcomeLedger           (opt-in, null by default)
  │     └── FeedbackState           (opt-in, null by default)
  └── ReplayResult                  (collected from decorators after replay)
```

### Event Flow

1. Runner receives `List<CloudEvent>` (consumer-provided, any order)
2. For each event:
   a. Extract `tenancyid` extension (error handling per D11)
   b. Find matching registrations via `registry.findByEventType()`
   c. Apply `EventFilter` from `SituationRegistration` (skip on reject)
   d. Extract correlation key via `CorrelationKeyExtractor` (fallback to default on error)
   e. Call `evaluator.evaluate(event, definition, correlationKey, tenancyId)`
3. After all events: call `evaluator.drainAllBuffers()` to flush remaining buffered events
4. Collect results from decorators and return `ReplayResult`

This mirrors `RasEngine.onCloudEvent()` routing logic — the runner reimplements the ~15 lines of routing with error handling governed by the configured `ReplayErrorHandling` strategy.

## API Design

### Builder

```java
ReplayResult result = SituationReplayRunner.builder()
    // Definition input (one required)
    .withYaml("META-INF/ras-situations.yaml")        // classpath YAML
    // OR
    .withRegistrations(List.of(reg1, reg2))           // programmatic
    // OR
    .withProvider(myProvider)                          // SituationDefinitionProvider
    
    // Events (required)
    .withEvents(cloudEvents)
    
    // Optional — pluggable collaborators
    .withStore(mySituationStore)                      // default: InMemorySituationStore
    .withCaseTrigger(myTrigger)                       // default: no-op
    .withGanglia(List.of(customGanglion))             // additional CDI-style ganglia
    
    // Optional — feedback loop (all null by default)
    .withSuppressionStrategy(myStrategy)
    .withOutcomeLedger(myLedger)
    .withFeedbackState(myFeedbackState)
    
    // Optional — error handling
    .withErrorHandling(ReplayErrorHandling.LENIENT)   // default: STRICT
    
    .build()
    .run();
```

### ReplayResult

```java
public record ReplayResult(
    List<SituationChangeEvent> timeline,
    List<TriggerRecord> triggers,
    Map<SituationInstanceKey, SituationContext> finalState,
    List<SkippedEvent> skippedEvents,
    ReplaySummary summary
) {
    // Convenience queries
    public List<SituationChangeEvent> triggersFor(String situationId) { ... }
    public Optional<SituationContext> stateFor(String situationId, String correlationKey, String tenancyId) { ... }
    public boolean didTrigger(String situationId) { ... }
}
```

**`SituationInstanceKey`** — `(situationId, correlationKey, tenancyId)` tuple identifying a situation instance. Matches the existing key pattern in `SituationEvaluator` and `InMemorySituationStore`.

**`TriggerRecord`** — `(UUID caseId, CaseTriggerConfig config, SituationContext context, Instant triggerTime)`. Captured by a collecting `CaseTrigger` decorator that wraps the consumer-provided or default trigger.

**`SkippedEvent`** — `(CloudEvent event, String reason)`. Only populated in LENIENT mode. Records events skipped due to missing tenancy, filter errors, or correlation key extraction failures.

**`ReplaySummary`** — Computed aggregate: total events processed, total events skipped, total triggers, triggers per situation ID, triggers per tenancy ID.

### ReplayErrorHandling

```java
public enum ReplayErrorHandling {
    STRICT,   // Throw on routing errors (missing tenancyid, filter exception, etc.)
    LENIENT   // Skip problematic events, record in skippedEvents
}
```

**STRICT** (default) — for curated test fixtures where bad data should fail immediately.
**LENIENT** — for raw production event logs where historical exports may contain noise.

Events with no matching situation definition are NOT errors in either mode — they are silently skipped, same as production.

## Collecting Decorators

Three internal decorator classes capture results transparently:

### CollectingChangeEvent

Implements `Event<SituationChangeEvent>`. Records every `fireAsync()` call in a thread-safe list. Same pattern as `TestChangeEvent` in existing tests.

### CollectingCaseTrigger

Wraps the consumer-provided `CaseTrigger` (or a no-op default). Delegates `fire()` to the wrapped trigger and records each `TriggerRecord`. Returns the delegate's UUID.

### CollectingSituationStore

Wraps the consumer-provided `SituationStore` (or `InMemorySituationStore` default). Delegates all calls to the wrapped store. Tracks the latest `SituationContext` per instance key on every `save()` call to build the `finalState` map.

## SituationEvaluator Changes

### New: `drainAllBuffers()`

```java
public void drainAllBuffers() {
    for (var entry : buffers.entrySet()) {
        List<CloudEvent> drained = entry.getValue().drainAll();
        // process each drained event through the evaluator
    }
    buffers.clear();
}
```

This is a legitimate lifecycle method — useful for both replay (end-of-stream) and production (graceful shutdown). It drains all event reorder buffers unconditionally, processing any remaining buffered events through the detection pipeline.

## Determinism Guarantees

| Concern | How handled |
|---------|-------------|
| Event time | `CloudEvent.getTime()` used for all time-critical decisions (expiry, ordering) |
| Buffer watermark | Event-time-based (`maxEventTime - bufferDelay`), not wall-clock |
| `Instant.now()` in buffer submit | Only sets `lastArrivalTime` for idle detection — irrelevant during replay |
| `Instant.now()` in suppression | Suppression excluded by default; when opted in, consumers accept wall-clock dependency |
| Conflict retries | `InMemorySituationStore` uses per-key synchronized — no contention in single-threaded replay |
| CDI events | `CollectingChangeEvent` replaces CDI `Event<>` — no container needed |
| Metrics | `SimpleMeterRegistry` — deterministic, no external side effects |

## Test Strategy

### Unit tests for SituationReplayRunner

1. **Single-ganglion Or mode triggers** — one event, one ganglion, verify `didTrigger()` and `TriggerRecord`
2. **And mode accumulation** — two event types, verify accumulation then trigger
3. **Threshold chain mode** — multiple ganglia contributing confidence, verify trigger at threshold
4. **Sequence chain mode** — ordered arrival requirement, verify trigger on correct sequence
5. **Windowed situation expiry** — events outside correlation window, verify no trigger
6. **Event reorder buffer** — out-of-order events with `eventBufferDelay`, verify correct ordering after `drainAllBuffers()`
7. **YAML definition loading** — verify `withYaml()` produces same results as equivalent programmatic definitions
8. **LENIENT error handling** — events missing tenancyid, verify skip and `skippedEvents` populated
9. **STRICT error handling** — events missing tenancyid, verify exception thrown
10. **Feedback opt-in** — with `FeedbackState` threshold override, verify changed trigger behavior
11. **Multiple tenancies** — same definition, different tenants, verify isolation
12. **No-op on unmatched events** — events with no matching definition, verify silent skip
13. **Empty event stream** — zero events, verify empty result (not error)
14. **Final state inspection** — non-triggering events, verify accumulated `SituationContext` in `finalState`

### Unit tests for drainAllBuffers()

1. **Drains all pending events** — multiple buffers with pending events, verify all processed
2. **Empty buffers** — no pending events, verify no-op
3. **Cleared after drain** — verify buffers map is empty after drain

### Contract test for CollectingSituationStore

Extends `AbstractSituationStoreContractTest` — the decorator must satisfy the store contract.

## References

- `RasEngine.onCloudEvent()` — production routing logic (lines 29–77)
- `SituationEvaluator` — production detection pipeline (lines 42–481)
- `SituationEvaluatorTest` — existing test wiring pattern (1569 lines)
- `EventReorderBuffer` — buffer watermark mechanics
- `InMemorySituationStore` — default store for replay
- `MockCaseTrigger` — existing test trigger (model for `CollectingCaseTrigger`)
- `TestChangeEvent` — existing test change event (model for `CollectingChangeEvent`)
- `SituationDefinitionRegistry.forTesting()` — test factory for registry construction
- casehubio/casehub-ras#44 — issue
- casehubio/casehub-ras#40 — feedback loop (suppression/threshold opt-in context)
