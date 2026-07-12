# RAS Runtime Metrics Instrumentation Design

**Issue:** casehubio/casehub-ras#32
**Date:** 2026-07-12
**Status:** Draft

## Context

`ReliableDroolsSessionStore` (#28) established the Micrometer instrumentation pattern for
casehub-ras: `Instance<MeterRegistry>` optional injection, null-safe helpers, `provided`-scope
`micrometer-core` dependency, counters/gauge/timer. That work instruments only the
`drools-reliability` module. The runtime module — `RasEngine`, `SituationEvaluator`,
`DefaultCaseTrigger`, `SituationExpiryJob` — has no observability beyond JUL log lines.

This spec adds Micrometer instrumentation to the runtime module via a centralised
`RasMetrics` bean.

### Garden Context

Two garden entries inform this design:

- **GE-20260604-942686:** Prometheus counter fires on both paths of a find-or-create method —
  be precise about where counters increment to avoid double-counting.
- **GE-20260630-6c2515:** CDI `Instance<T>` makes injection optional but not classloading —
  `provided` scope prevents transitive classloading failures while `Instance.isResolvable()`
  guards runtime resolution.

### Pattern Precedent

`ReliableDroolsSessionStore` (#28) uses inline instrumentation — direct `MeterRegistry`
field injection with private helpers (`incrementCounter`, `startTimer`, `stopTimer`).
This spec introduces a centralised `RasMetrics` bean as the go-forward pattern for the
runtime module: single source of truth for metric names, typed public API, null-guarding
in one place. The inline pattern remains appropriate for `drools-reliability` as a
self-contained optional module with its own deployment scope. See #35 for potential
future migration of `drools-reliability` to the centralised pattern.

## Scope

**In scope:**
1. Dependency — `micrometer-core` `provided`-scope in `runtime/pom.xml`
2. `RasMetrics` — centralised `@ApplicationScoped` metrics bean
3. Instrumentation of `RasEngine`, `SituationEvaluator`, `SituationExpiryJob`
4. Gauge accessors — `SituationDefinitionRegistry.definitionCount()`,
   `SituationEvaluator.activeBufferCount()`
5. `SituationStore` API change — `removeTriggeredBefore()` and `removeExpired()` return
   `Uni<Integer>` (count of removed items)
6. Tests — `RasMetricsTest` + metric assertions in existing test classes

**Out of scope:**
- `DefaultCaseTrigger` — trigger fire timing is captured at the call site in
  `SituationEvaluator.executeDecision()`, covering all `CaseTrigger` implementations
  without modifying the SPI
- `DefaultSituationSource` — lightweight projection, no business logic worth timing
- `RasEndpointRegistration` — one-time startup registration
- `EventReorderBuffer` — internal data structure, not a CDI bean; its behavior is captured
  via evaluator-level buffer metrics
- `EventBufferFlushJob` — delegates to `SituationEvaluator.flushIdleBuffers()` which
  calls `processEvent()` with full metric coverage; no independent instrumentation needed
- `drools-reliability` module — already instrumented inline (#28), separate optional module

## Design

### 1. Dependency Strategy

Add `io.micrometer:micrometer-core` to `runtime/pom.xml` with `<scope>provided</scope>`.
Same pattern as `drools-reliability/`. Consuming app provides
`quarkus-micrometer-registry-prometheus` which brings `micrometer-core` transitively.

No other dependency changes.

### 2. RasMetrics Bean

`@ApplicationScoped` bean in `io.casehub.ras.runtime`. Single point of ownership for all
runtime metric names, tags, and null-guarding.

**Wiring:**
- `@Inject Instance<MeterRegistry>` — resolved in `@PostConstruct` to a nullable `metrics` field
- Constructor takes `SituationDefinitionRegistry` for definition-count gauge registration
  (one-way dependency, no cycle)
- All public methods are no-ops when `metrics` is null

**Private helpers:**
- `counter(String name, String... tags)` — null-checked `metrics.counter(...).increment()`
- `counterBy(String name, double amount, String... tags)` — null-checked increment by amount
- `startTimer() → Object` — returns null when metrics absent
- `stopTimer(Object, String name, String... tags)` — null-checked stop

**Gauge registration** (in `@PostConstruct`):
- `ras.registry.definitions.active` — backed by `SituationDefinitionRegistry.definitionCount()`

**Gauge self-registration** (called by `SituationEvaluator` in its `@PostConstruct`):
- `registerActiveBuffersGauge(Supplier<Number> supplier)` — registers
  `ras.evaluator.buffers.active` backed by the supplied function

**Public API:**

| Method | Parameters | Metric |
|--------|-----------|--------|
| `eventReceived` | `String eventType` | `ras.engine.events.received` |
| `eventSkipped` | `String reason` | `ras.engine.events.skipped` |
| `eventRouted` | `String situationId, String tenancyId` | `ras.engine.events.routed` |
| `evaluationFailed` | `String situationId, String tenancyId` | `ras.engine.evaluation.failed` |
| `startProcessTimer` | — | returns opaque handle |
| `stopProcessTimer` | `Object sample, String situationId, String tenancyId` | `ras.evaluator.process_time` |
| `decision` | `String situationId, String tenancyId, TriggerDecision decision` | `ras.evaluator.decision` |
| `conflictRetry` | `String situationId, String tenancyId` | `ras.evaluator.conflict_retries` |
| `retriesExhausted` | `String situationId, String tenancyId` | `ras.evaluator.retries_exhausted` |
| `contextExpired` | `String situationId, String tenancyId` | `ras.evaluator.context_expired` |
| `ganglionDetectFailed` | `String ganglionId, String situationId` | `ras.evaluator.ganglion.detect_failed` |
| `ganglionCompactFailed` | `String ganglionId, String situationId` | `ras.evaluator.ganglion.compact_failed` |
| `ganglionCloseFailed` | `String ganglionId, String situationId` | `ras.evaluator.ganglion.close_failed` |
| `triggerClaimed` | `String situationId, String tenancyId` | `ras.evaluator.trigger.claimed` |
| `triggerRaceLost` | `String situationId, String tenancyId` | `ras.evaluator.trigger.race_lost` |
| `startTriggerFireTimer` | — | returns opaque handle |
| `stopTriggerFireTimer` | `Object sample, String situationId, String tenancyId, String triggerAction` | `ras.evaluator.trigger.fire_time` |
| `triggerFired` | `String situationId, String tenancyId, String triggerAction` | `ras.evaluator.trigger.fired` |
| `triggerFailed` | `String situationId, String tenancyId, String triggerAction` | `ras.evaluator.trigger.failed` |
| `eventBuffered` | `String situationId, String tenancyId` | `ras.evaluator.buffer.events_buffered` |
| `triggeredCleaned` | `int count` | `ras.expiry.triggered_cleaned` |
| `expiredCleaned` | `int count` | `ras.expiry.expired_cleaned` |
| `registerActiveBuffersGauge` | `Supplier<Number> supplier` | `ras.evaluator.buffers.active` |

`TriggerDecision` parameter is the enum value; `RasMetrics` formats it to lowercase
for the tag value (see §4).

Timer start methods return an opaque handle (`Object`); timer stop methods accept it.
Internally this is `Timer.Sample` but callers do not reference the Micrometer type,
preventing classloading issues when Micrometer is absent from the runtime classpath.

### 3. Metric Catalogue

#### 3.1 RasEngine (4 metrics)

| Metric | Type | Tags | When |
|--------|------|------|------|
| `ras.engine.events.received` | Counter | `event_type` | Every `onCloudEvent()` invocation — total ingress |
| `ras.engine.events.skipped` | Counter | `reason` | Event dropped — `no_tenancy_id` or `no_matching_situation` |
| `ras.engine.events.routed` | Counter | `situation_id`, `tenancy_id` | Each situation an event is dispatched to |
| `ras.engine.evaluation.failed` | Counter | `situation_id`, `tenancy_id` | RuntimeException in per-situation try-catch |

**Counter ordering in `onCloudEvent()`:** `received` fires first (total ingress), then
`skipped(no_tenancy_id)` on early return, then `skipped(no_matching_situation)` if no
situations match the event type, then `routed` per situation dispatch. This gives the
additive invariant: `received = skipped(no_tenancy_id) + skipped(no_matching_situation)
+ events_entering_routing`.

The `no_matching_situation` skip path requires an explicit empty-check on
`registry.findByEventType()` with an early return — new control flow that makes the
existing no-op-for-empty-registrations behavior explicit.

#### 3.2 SituationEvaluator (14 metrics)

| Metric | Type | Tags | When |
|--------|------|------|------|
| `ras.evaluator.process_time` | Timer | `situation_id`, `tenancy_id` | Per-event `processEvent()` latency |
| `ras.evaluator.decision` | Counter | `situation_id`, `tenancy_id`, `decision` | After policy evaluates — `trigger`, `trigger_and_continue`, `continue_accumulating`, `discard`, `resolve` |
| `ras.evaluator.conflict_retries` | Counter | `situation_id`, `tenancy_id` | Each conflict retry (not the initial attempt) |
| `ras.evaluator.retries_exhausted` | Counter | `situation_id`, `tenancy_id` | All retries exhausted, event lost |
| `ras.evaluator.context_expired` | Counter | `situation_id`, `tenancy_id` | Windowed context expired and reset |
| `ras.evaluator.ganglion.detect_failed` | Counter | `ganglion_id`, `situation_id` | Ganglion `detect()` threw |
| `ras.evaluator.ganglion.compact_failed` | Counter | `ganglion_id`, `situation_id` | Ganglion `compact()` threw |
| `ras.evaluator.ganglion.close_failed` | Counter | `ganglion_id`, `situation_id` | Ganglion `close()` threw |
| `ras.evaluator.trigger.claimed` | Counter | `situation_id`, `tenancy_id` | `tryClaimTrigger` returned true |
| `ras.evaluator.trigger.race_lost` | Counter | `situation_id`, `tenancy_id` | `tryClaimTrigger` returned false — another node won the claim race |
| `ras.evaluator.trigger.fire_time` | Timer | `situation_id`, `tenancy_id`, `trigger_action` | Trigger execution latency — `create_case` or `notify_only` |
| `ras.evaluator.trigger.fired` | Counter | `situation_id`, `tenancy_id`, `trigger_action` | Successful trigger execution |
| `ras.evaluator.trigger.failed` | Counter | `situation_id`, `tenancy_id`, `trigger_action` | Trigger execution failed — RuntimeException (`create_case`) or delivery Exception (`notify_only`) |
| `ras.evaluator.buffer.events_buffered` | Counter | `situation_id`, `tenancy_id` | Event submitted to reorder buffer |

The `process_time` timer wraps individual `processEvent()` calls, not the outer
`evaluate()` method. This ensures each timing sample represents a consistent unit of
work (one event through detect → apply → persist) regardless of buffering state. It also
covers events processed through the `flushIdleBuffers()` path, which calls `processEvent()`
directly without going through `evaluate()`.

Trigger metrics (`fire_time`, `fired`, `failed`) are recorded at the trigger execution
sites in `executeDecision()`, not inside `DefaultCaseTrigger`. The `trigger_action` tag
distinguishes `create_case` (wrapping `caseTrigger.fire(...).await().indefinitely()`) from
`notify_only` (wrapping `changeEvent.fireAsync(...).toCompletableFuture().join()`). Both
paths have timing, success/failure semantics, and error handling — a single set of metrics
with a discriminator tag covers both without parallel metric names.

#### 3.3 SituationExpiryJob (2 metrics)

| Metric | Type | Tags | When |
|--------|------|------|------|
| `ras.expiry.triggered_cleaned` | Counter | — | Count of triggered contexts removed by guard cleanup |
| `ras.expiry.expired_cleaned` | Counter | — | Count of expired contexts removed by windowed cleanup |

These require `SituationStore.removeTriggeredBefore()` and `removeExpired()` to return
`Uni<Integer>` (count of removed items). See §5 for the API change.

When windowed cleanup is skipped (no windowed definitions configured), `expired_cleaned`
is not incremented — its absence from the time series distinguishes "no windowed situations
configured" from "windowed cleanup found nothing" (which produces a 0-value increment).

#### 3.4 Gauges (2 metrics)

| Metric | Type | Source |
|--------|------|--------|
| `ras.registry.definitions.active` | Gauge | `SituationDefinitionRegistry.definitionCount()` |
| `ras.evaluator.buffers.active` | Gauge | `SituationEvaluator.activeBufferCount()` |

**Total: 22 metrics** (18 counters, 2 timers, 2 gauges).

### 4. Tagging Strategy

All metrics that operate on a situation instance carry `situation_id` and `tenancy_id` tags.
Both are bounded in practice — situations are defined in YAML/code, tenancies are bounded by
deployment context. Operators can drop tags via Prometheus relabel rules if cardinality
becomes a concern.

Ganglion-level failure counters use `ganglion_id` + `situation_id` (no `tenancy_id` — the
failure is ganglion-scoped, not tenant-scoped).

Engine-level ingress (`events.received`) uses `event_type` — bounded by CloudEvent type
vocabulary.

**Tag value formatting:** all tag values use lowercase with underscores. `TriggerDecision`
enum values are formatted via `decision.name().toLowerCase()` (e.g. `trigger`,
`trigger_and_continue`, `continue_accumulating`, `discard`, `resolve`). Skip reasons
(`no_tenancy_id`, `no_matching_situation`) already follow this convention.

### 5. Integration Points

**Constructor changes:**
- `SituationEvaluator` — add `RasMetrics` parameter
- `RasEngine` — add `RasMetrics` parameter
- `SituationExpiryJob` — add `RasMetrics` parameter

**New accessor methods:**
- `SituationDefinitionRegistry.definitionCount()` — returns `snapshot.situationIds().size()`
- `SituationEvaluator.activeBufferCount()` — returns `buffers.size()`

**SituationEvaluator `@PostConstruct`:** calls
`metrics.registerActiveBuffersGauge(() -> buffers.size())` to self-register the buffer
count gauge, avoiding a circular dependency with `RasMetrics`.

**Trigger metric placement:** trigger fire timing, success, and failure metrics are recorded
at both trigger execution sites in `SituationEvaluator.executeDecision()`:
`caseTrigger.fire(...).await().indefinitely()` for `CreateCase` and
`changeEvent.fireAsync(...).toCompletableFuture().join()` for `NotifyOnly`. The
`trigger_action` tag (`create_case` / `notify_only`) distinguishes the paths.
`DefaultCaseTrigger` is not modified — no `RasMetrics` injection, no constructor change,
no SPI modification.

**SituationStore API change:**
- `removeTriggeredBefore(Instant)` — return type changes from `Uni<Void>` to `Uni<Integer>`.
  Remains a default method; default implementation returns `Uni.createFrom().item(0)`.
- `removeExpired(Instant)` — return type changes from `Uni<Void>` to `Uni<Integer>`.
  Remains abstract — every `SituationStore` implementation must handle expiry cleanup.
- Affects: `SituationStore` interface (api module), `InMemorySituationStore`
  (persistence-memory), `JpaSituationStore` (persistence-jpa)

**`RasEngine.onCloudEvent()` control flow:** an explicit `registrations.isEmpty()` check
with early return is added to support the `no_matching_situation` skip counter.

### 6. Testing Strategy

**`RasMetricsTest`** — dedicated unit test:
- Verify each public method registers the correct metric name and tags with a real
  `SimpleMeterRegistry`
- Verify all methods are no-ops when `MeterRegistry` is null (no NPE)
- Verify gauge registrations read live values from registry/evaluator

**Existing test classes** — add `RasMetrics` with `SimpleMeterRegistry`:
- `RasEngineTest` — `new RasEngine(registry, evaluator, metrics)`: assert `events.received`,
  `events.skipped`, `events.routed`, `evaluation.failed` after key operations
- `SituationEvaluatorTest` — `new SituationEvaluator(store, policy, caseTrigger, registry, 3, changeEvent, metrics)`:
  assert `process_time`, `decision` counts, `conflict_retries`, `retries_exhausted`,
  `context_expired`, ganglion failure counters, `trigger.claimed`/`race_lost`,
  `trigger.fire_time`/`fired`/`failed`, buffer metrics
- `SituationExpiryJobTest` — `new SituationExpiryJob(store, registry, guardPeriod, metrics)`:
  assert `expiry.triggered_cleaned`, `expiry.expired_cleaned`

`DefaultCaseTriggerTest` is unchanged — `DefaultCaseTrigger` does not receive `RasMetrics`.

No mock-based testing — real `SimpleMeterRegistry`, real assertions on counter/timer values.
