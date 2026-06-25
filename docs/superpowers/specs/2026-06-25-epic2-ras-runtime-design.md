# Epic 2: RAS Runtime — Design Spec

**Date:** 2026-06-25 (revised 2026-06-25)
**Status:** Approved
**Issue:** casehubio/casehub-ras#2
**Depends on:** Epic 1 (casehubio/casehub-ras#1 — core API, done)

---

## 1. Scope

Build the RAS coordination layer in the `runtime/` module:

- **RasEngine** — CDI observer entry point (`@ObservesAsync CloudEvent`)
- **SituationEvaluator** — core per-situation pipeline (dispatch, accumulate, evaluate, trigger)
- **SituationDefinitionRegistry** — startup-built routing index from CDI-discovered providers
- **DefaultRasTriggerPolicy** — chain mode evaluation (`@DefaultBean`)
- **DefaultCaseTrigger** — bridge to `casehub-engine-api` `startCase()`
- **SituationExpiryJob** — scheduled cleanup of expired situations

Plus API changes (correlationKey on SituationContext/SituationStore/Ganglion.close,
new TimestampedDetection record, new CaseTrigger SPI), cross-module updates
(persistence-memory/, ras-drools/, testing/), and test coverage.

**Not in scope:** YAML-based SituationDefinitionProvider (#13), JPA SituationStore (#14),
Ganglion `compact()` invocation (#12), ANTI signal subtraction (#15),
per-situation event reordering buffer (#16).

---

## 2. Architecture Overview

```
CloudEvent (CDI @ObservesAsync)
  → RasEngine (extract tenancyId, find matching definitions)
    → SituationEvaluator (per-definition, per-correlation-key)
      1. SituationStore.find() — load or create SituationContext
      2. Window expiry check
      3. Dispatch to matching ganglia → DetectionResult
      4. Wrap as TimestampedDetection, accumulate into SituationContext
      5. RasTriggerPolicy.evaluate() → TriggerDecision
      6. Act: CREATE_CASE → CaseTrigger.fire()
             CONTINUE_ACCUMULATING → SituationStore.save()
             DISCARD → close ganglia, remove situation
```

Three-layer decomposition: RasEngine (CDI routing) → SituationEvaluator (domain pipeline) →
CaseTrigger (engine bridge). Each class has one responsibility and is independently testable.

---

## 3. API Module Changes (casehub-ras-api)

### 3.1 Identity model change from Epic 1

Epic 1 §3 proposed constructing the instance `situationId` as a composite of definition identifier
+ correlation key (e.g. `"equipment-failure-risk:machine-42"`). This spec replaces that approach
with separate fields: `situationId` remains the definition-level ID, and `correlationKey` is a
distinct field on `SituationContext`.

**Why:** A composite string is opaque — consumers can't decompose it without knowing the separator
convention. Every SituationStore, DroolsSessionStore, log entry, and metric would need to parse a
convention they didn't author. Separate fields are semantically clear and queryable (e.g. "all
situation instances for definition X" is a direct query on `situationId`, not a prefix scan with
string parsing). The compositing approach from Epic 1 §3 is retired.

### 3.2 TimestampedDetection — new record

```java
public record TimestampedDetection(DetectionResult result, Instant eventTime) {
    public TimestampedDetection {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(eventTime, "eventTime");
    }
}
```

Pairs a ganglion's detection output with the source event's timestamp (`CloudEvent.getTime()`).
The ganglion produces `DetectionResult` (what was detected); the runtime wraps it with timing
context at the accumulation boundary.

**Why a separate record (not adding eventTime to DetectionResult):** `DetectionResult` is the
ganglion's output — `ganglionId`, `confidence`, `signal`, `evidence`. The event timestamp is
runtime context, not something the ganglion produces. A nullable `eventTime` on `DetectionResult`
(set by the ganglion to null, filled by the runtime later) is a partial-construction smell.
`TimestampedDetection` makes the boundary explicit: the ganglion produces `DetectionResult`,
the runtime adds `eventTime` when accumulating.

**Architectural benefits:**
- `ChainMode.Sequence` evaluation sorts by `eventTime` instead of arrival order — correct
  regardless of CDI managed executor scheduling (see §5.2)
- Forensics: each detection carries when it occurred
- Future compaction: expire detections by time
- Avoids a second breaking change to SituationContext if timestamps were added later

### 3.3 SituationContext — three-field identity, timestamped detections

```java
public record SituationContext(
    String situationId,      // definition-level ID (matches SituationDefinition.situationId)
    String correlationKey,   // scopes instance within definition + tenant
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

Identity triple: `(situationId, correlationKey, tenancyId)`. `correlationKey` is never null — the
runtime supplies `"_singleton"` when `CloudEvent.getSubject()` is null, meaning all events share
one situation instance per definition per tenant.

`detections` is now `List<TimestampedDetection>`. The `withDetection()` signature is unchanged —
it wraps the result internally.

### 3.4 SituationStore — correlationKey on find/remove

```java
public interface SituationStore {
    Uni<Optional<SituationContext>> find(String situationId, String correlationKey, String tenancyId);
    Uni<Void> save(SituationContext context);
    Uni<Void> remove(String situationId, String correlationKey, String tenancyId);
    Uni<Void> removeExpired(Instant cutoff);
}
```

`save()` and `removeExpired()` unchanged — they read identity from SituationContext fields.

### 3.5 ChainMode.referencedGanglia() — utility method

```java
public sealed interface ChainMode {

    default Set<String> referencedGanglia() {
        return switch (this) {
            case And a -> a.requiredGanglia();
            case Or o -> o.ganglia();
            case Threshold t -> t.ganglia();
            case Sequence s -> Set.copyOf(s.orderedGanglia());
            case Count c -> Set.of(c.ganglionId());
        };
    }

    // ... existing variant records unchanged
}
```

Ganglion ID extraction from ChainMode is needed by three consumers:
SituationDefinitionRegistry (startup validation), SituationEvaluator (ganglion dispatch and
close), and any future chain-aware component. Without this method, each consumer duplicates
the same exhaustive switch. The sealed interface is the natural owner.

### 3.6 Ganglion.close() — correlationKey

```java
default Uni<Void> close(String situationId, String correlationKey, String tenancyId) {
    return Uni.createFrom().voidItem();
}
```

Breaking change. DroolsGanglion overrides this — mechanical update.

### 3.7 CaseTrigger — new SPI

```java
public interface CaseTrigger {
    Uni<UUID> fire(CaseTriggerConfig triggerConfig, SituationContext context);
}
```

Returns the created case ID. Default implementation in `runtime/` bridges to `CaseHubRuntime.startCase()`.
Tests use MockCaseTrigger to verify the detection pipeline without engine infrastructure.

---

## 4. Runtime Module (casehub-ras)

Package: `io.casehub.ras.runtime`

### 4.1 CorrelationKeyExtractor

```java
@FunctionalInterface
public interface CorrelationKeyExtractor {
    String extract(CloudEvent event);
}
```

Pluggable per-definition. Lives in `runtime/` — extraction strategy is a runtime concern.

**DefaultCorrelationKeyExtractor:** `CloudEvent.getSubject()` if non-null, else `"_singleton"`.
Exposed as `DefaultCorrelationKeyExtractor.INSTANCE` for providers that want the default explicitly.

### 4.2 SituationRegistration

```java
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

Bundles a definition with its extraction strategy. Convenience constructor defaults to
`CloudEvent.getSubject()`.

### 4.3 SituationDefinitionProvider

```java
public interface SituationDefinitionProvider {
    List<SituationRegistration> registrations();
}
```

CDI-discovered. Multiple providers contribute definitions. Deployers implement this in their
app module — programmatic, config-driven, or database-backed.

### 4.4 SituationDefinitionRegistry

`@ApplicationScoped`. Built at `@PostConstruct`:

1. Collects all `SituationRegistration`s from injected `Instance<SituationDefinitionProvider>`
2. Builds ganglionId → Ganglion lookup from injected `Instance<Ganglion>`
3. Validates:
   - `situationId` is unique across all registrations — duplicate IDs from different providers
     would cause conflicting SituationContexts in the same store key space
   - Every ganglion referenced in every ChainMode exists
   - Each referenced ganglion's `handledEventTypes()` overlaps with the definition's `eventTypes`
4. Builds `eventType → List<SituationRegistration>` routing index
5. Throws on validation failure — fast fail at startup

Public methods:
- `List<SituationRegistration> findByEventType(String eventType)` — O(1) lookup
- `Ganglion ganglion(String ganglionId)` — O(1) lookup

### 4.5 RasEngine

`@ApplicationScoped`. ~40 lines.

```java
void onCloudEvent(@ObservesAsync CloudEvent event) {
    String tenancyId = extractTenancyId(event);  // event.getExtension("tenancyid")
    if (tenancyId == null) {
        LOG.warn("CloudEvent without tenancyid — skipping");
        return;
    }

    List<SituationRegistration> registrations = registry.findByEventType(event.getType());
    for (SituationRegistration reg : registrations) {
        try {
            String correlationKey = reg.correlationKeyExtractor().extract(event);
            evaluator.evaluate(event, reg.definition(), correlationKey, tenancyId);
        } catch (RuntimeException ex) {
            LOG.warnf(ex, "Evaluation failed for situation '%s'", reg.definition().situationId());
        }
    }
}
```

Per-registration try-catch — failure in one situation does not affect others.

### 4.6 SituationEvaluator

`@ApplicationScoped`. Core pipeline (~120 lines).

Injects: `SituationStore`, `RasTriggerPolicy`, `CaseTrigger`, `SituationDefinitionRegistry`.

```java
void evaluate(CloudEvent event, SituationDefinition definition,
              String correlationKey, String tenancyId)
```

Pipeline steps:

1. **Acquire striped lock** — `synchronized` on key `(situationId, correlationKey, tenancyId)`
2. **Find or create SituationContext** — `store.find()`, or `SituationContext.initial()` if absent
3. **Window check** — if `correlationWindow` is set and `context.lastSignal` is older than
   `now - correlationWindow`: close ganglia, remove situation, create fresh context
4. **Dispatch ganglia** — extract ganglion IDs from ChainMode, filter to those whose
   `handledEventTypes()` includes this event's type, invoke `detect()`, wrap result as
   `TimestampedDetection` with the event's timestamp, accumulate via `withDetection()`
5. **Evaluate trigger** — `triggerPolicy.evaluate(context, definition)`
6. **Act on decision:**
   - `CREATE_CASE` — `caseTrigger.fire()`, close ganglia, remove situation, clean lock entry
   - `CONTINUE_ACCUMULATING` — `store.save(context)`
   - `DISCARD` — close ganglia, remove situation, clean lock entry

**Event time extraction:** `CloudEvent.getTime()` converted from `OffsetDateTime` to `Instant`
via `.toInstant()`. If `getTime()` is null, fall back to `Instant.now()`. Per Epic 1 spec §4.3.

**Ganglion dispatch per event:** For `ChainMode.And(Set.of("temp-spike", "vibration-anomaly"))`,
a temperature event only invokes "temp-spike" (filtered by `handledEventTypes()`). The And chain
requires "both have fired at some point within the window," not "both fire on every event."

### 4.7 DefaultRasTriggerPolicy

`@DefaultBean @ApplicationScoped`. Pure domain logic — no CDI dependencies beyond the scope annotation.

Exhaustive pattern match on ChainMode. Signal threshold: `signal.isAtLeast(DetectionSignal.WEAK)`.
NOISE and ANTI detections do not contribute to chain satisfaction. The policy accesses the
`DetectionResult` via `TimestampedDetection.result()`.

| ChainMode | Satisfaction condition |
|-----------|----------------------|
| And | Every required ganglion has ≥1 detection with signal ≥ WEAK |
| Or | Any listed ganglion has ≥1 detection with signal ≥ WEAK |
| Threshold | Sum of confidence values from detections with signal ≥ WEAK ≥ minConfidence |
| Sequence | Detections with signal ≥ WEAK from listed ganglia appear in declared order **when sorted by `eventTime`** (not arrival order — see §5.2) |
| Count | Ganglion has ≥ requiredCount detections with signal ≥ WEAK |

Never returns DISCARD — returns CREATE_CASE when satisfied, CONTINUE_ACCUMULATING otherwise.
DISCARD is an escape hatch for custom policies (rate limiting, deduplication, domain-specific rejection).

### 4.8 DefaultCaseTrigger

`@ApplicationScoped`. Injects `Instance<CaseHub>`.

1. Iterates CDI-discovered `CaseHub` beans, matches by `(namespace, name, version)` from
   CaseTriggerConfig against `caseHub.getDefinition()`
2. Throws `IllegalStateException` if zero matches (deployment misconfiguration — no CaseHub
   registered for this case type) or multiple matches (ambiguous — `CaseDefinition.equals()`
   uses the `(namespace, name, version)` triple, so two CaseHub beans with identical definitions
   would both match; this is a configuration error, not a runtime choice)
3. Builds inputData: `CaseTriggerConfig.baseCaseData` merged with situation evidence
   (`situationId`, `correlationKey`, `tenancyId`, `detections` list)
4. Calls `caseHub.startCase(inputData)` — converts `CompletionStage<UUID>` to `Uni<UUID>`

**CaseHub warm-up:** At `@PostConstruct`, DefaultCaseTrigger iterates all `CaseHub` beans and
calls `getDefinition()` once on each. This pre-loads definitions (including `YamlCaseHub`'s
lazy double-checked locking path) so that the first `fire()` call does not bear YAML parsing
cost under the situation's striped lock.

### 4.9 SituationExpiryJob

`@ApplicationScoped`. Quarkus `@Scheduled(every = "PT5M")`.

Calls `SituationStore.removeExpired(Instant.now().minus(maxWindow))` where `maxWindow` is the
longest `correlationWindow` across all registered definitions that have a window set.

If all definitions have `correlationWindow = null` (all persistent), the job is a no-op — nothing
to expire.

Does not close ganglia — see §5.3 for the trade-off and accepted leak.

---

## 5. Concurrency, Ordering, and Expiry

### 5.1 Striped locking per situation instance

`SituationEvaluator` holds a `ConcurrentHashMap<SituationInstanceKey, Object>` as a lock map.
Processing acquires `synchronized(lock)` on `(situationId, correlationKey, tenancyId)` before
the find-detect-accumulate-save pipeline.

- Different situation instances: full parallelism, zero contention
- Same situation instance: serialised — prevents lost-update from concurrent events
- Lock entries cleaned on CREATE_CASE and DISCARD
- Lock map growth bounded by active situation count (bounded by expiry)

### 5.2 Event ordering

`@ObservesAsync` delivers events on the CDI managed executor thread pool with no ordering
guarantee. Two events for the same situation arriving close together may be serialized in
arbitrary order by the striped lock. This affects two components:

**ChainMode.Sequence evaluation:** Solved by `TimestampedDetection`. The `DefaultRasTriggerPolicy`
Sequence evaluator sorts accumulated detections by `TimestampedDetection.eventTime()` before
checking whether they appear in the declared ganglion order. This produces correct results
regardless of CDI executor scheduling order.

**DroolsGanglion pseudo clock mode:** NOT solved at this layer. The runtime dispatches to ganglia
in event arrival order (one event at a time from @ObservesAsync). If event B (T=10) arrives
before event A (T=5), the ganglion receives them in arrival order. Pseudo clock mode's
`IllegalStateException` (clock delta < 0) is the correct fail-fast response — it surfaces the
ordering violation immediately.

Pseudo clock mode requires upstream ordering guarantees: partitioned Kafka consumers (same key →
same partition → in-order), single-threaded per-key event processing, or ordered CDI event
dispatching. Realtime clock mode (Epic 4 §8.2) handles out-of-order delivery naturally — the
clock is monotonic regardless of event arrival order.

A runtime-level per-situation event reordering buffer (sorted by `CloudEvent.getTime()`, processed
after a configurable delay or watermark) would eliminate the upstream ordering requirement for
pseudo clock mode. This is tracked as #16.

### 5.3 Expiry and ganglia resource management

**Per-event window check** (SituationEvaluator §4.6 step 3): When an event arrives for a situation
whose `lastSignal` is older than `now - correlationWindow`, the evaluator closes all ganglia
for that situation (disposing KieSessions etc.), removes the situation, and starts fresh.
This handles the common case — a new event arrives for an expired situation.

**Scheduled cleanup** (SituationExpiryJob §4.9): Removes expired situations from the store.
Does NOT close ganglia. This handles the uncommon case — a situation expires with no further
events.

**Accepted leak:** For situations that go silent (no further events after window expiry), the
SituationExpiryJob cleans the store but does not close ganglia. DroolsSessionStore retains
orphaned KieSessions until JVM restart. The per-event window check covers the common case
(next event arrives); the scheduled job covers store bloat but not ganglia resources. Fixing
this requires the expiry job to know which ganglia participated in each situation — information
that SituationContext does not currently track. This is coupled to #12 (compact): a
ganglia-aware expiry mechanism is the proper solution for both compaction and orphan cleanup.

### 5.4 Threading

`@ObservesAsync` runs on CDI managed executor thread pool. `Uni` results are awaited synchronously
(`.await().indefinitely()`). This is safe — managed executor threads are worker threads, not event
loop threads. Blocking is acceptable.

---

## 6. Error Handling

**Per-situation isolation:** RasEngine wraps each registration evaluation in try-catch. A ganglion
failure or store failure for one situation does not affect others matched by the same CloudEvent.

**Ganglion failure:** If `detect()` throws, the evaluation for that situation aborts. SituationContext
is not saved — situation state is unchanged. The event is skipped for that situation. Logged at WARN.

**CaseTrigger failure:** If `fire()` fails, the situation is NOT removed — it stays accumulated.
The next event will re-evaluate the satisfied chain mode and retry triggering. Logged at ERROR.

**No retry loops.** The natural event stream provides implicit retry — the next event for the same
situation will trigger the pipeline again.

---

## 7. Cross-Module Changes

### 7.1 persistence-memory/ (InMemorySituationStore)

`SituationKey` becomes `(situationId, correlationKey, tenancyId)`. `find()` and `remove()` gain
`correlationKey`. Mechanical.

### 7.2 ras-drools/

**DroolsSessionStore** — all methods gain `correlationKey` parameter.
**InMemoryDroolsSessionStore** — `SessionKey` record gains `correlationKey`.
**DroolsGanglion** — `close()` override updated; `detect()` passes `context.correlationKey()`
to session store calls. All mechanical.

### 7.3 testing/

**MockCaseTrigger** — new. Records `FiredCase(UUID caseId, CaseTriggerConfig, SituationContext)`.
Thread-safe via `CopyOnWriteArrayList`. Provides `firedCases()`, `reset()`.

---

## 8. Module Dependencies

```
runtime/  (casehub-ras)
  ├── api/                    (casehub-ras-api)          — compile
  ├── casehub-platform-api                               — compile
  ├── casehub-platform                                   — compile
  ├── casehub-engine-api                                 — compile (DefaultCaseTrigger)
  ├── quarkus-arc                                        — compile
  ├── quarkus-vertx                                      — compile (existing — verify during impl; likely transitive, remove if not directly used)
  ├── quarkus-scheduler                                  — compile (SituationExpiryJob — to be added)
  ├── persistence-memory/     (casehub-ras-memory)       — test
  ├── testing/                (casehub-ras-testing)      — test
  ├── casehub-platform-testing                           — test
  └── quarkus-junit5                                     — test
```

`quarkus-vertx` is in the current runtime pom but the spec's architecture does not use the Vert.x
event bus directly (`@ObservesAsync` is quarkus-arc, `@Scheduled` is quarkus-scheduler). Verify
during implementation whether it is transitively required; remove if not directly used.
`quarkus-scheduler` is new — required for `SituationExpiryJob`.

Tier compliance: api/ stays Tier 1 (pure Java). runtime/ is Tier 3 (Quarkus runtime). No violations.
`casehub-engine-api` on runtime/ only — per engine-api-scope-rule protocol.

---

## 9. File Inventory

| Module | New files | Modified files |
|--------|-----------|----------------|
| api/ | `CaseTrigger.java`, `TimestampedDetection.java` | `SituationContext.java`, `SituationStore.java`, `Ganglion.java`, `ChainMode.java` |
| runtime/ | `CorrelationKeyExtractor.java`, `DefaultCorrelationKeyExtractor.java`, `SituationRegistration.java`, `SituationDefinitionProvider.java`, `SituationDefinitionRegistry.java`, `RasEngine.java`, `SituationEvaluator.java`, `DefaultRasTriggerPolicy.java`, `DefaultCaseTrigger.java`, `SituationExpiryJob.java` | `pom.xml` (add `quarkus-scheduler`, update `<description>`) |
| persistence-memory/ | — | `InMemorySituationStore.java` |
| ras-drools/ | — | `DroolsSessionStore.java`, `InMemoryDroolsSessionStore.java`, `DroolsGanglion.java` |
| testing/ | `MockCaseTrigger.java` | — |
| project root | — | `CLAUDE.md` (update runtime/ module description — replace stale names: CompositeEventCorrelator → SituationEvaluator, SituationAccumulator → removed (accumulation is inside SituationEvaluator), CaseTriggerService → DefaultCaseTrigger) |

Plus corresponding test files for each new/modified production class.

---

## 10. Deferred Items

| Item | Issue | Reason |
|------|-------|--------|
| Ganglion `compact()` invocation | #12 | Compaction matters for persistent situations (null window). Short-lived windowed situations don't accumulate enough to need it. |
| YAML-driven SituationDefinitionProvider | #13 | Programmatic providers cover initial use. Config loading is a convenience. |
| JPA SituationStore | #14 | InMemorySituationStore covers development/testing. Production persistence is a deployment concern. |
| ANTI signal subtraction | #15 | Default policy ignores ANTI. Custom policies handle it. Needs domain-specific requirements. |
| Per-situation event reordering buffer | #16 | DroolsGanglion pseudo clock mode requires events dispatched in non-decreasing timestamp order (Epic 4 §8.1). The runtime currently dispatches in arrival order. Upstream ordering (Kafka partition ordering) mitigates this. A runtime-level buffer sorted by `CloudEvent.getTime()` with configurable delay/watermark would eliminate the upstream requirement. |
| SituationExpiryJob ganglia cleanup | — | SituationExpiryJob does not close ganglia for expired situations. DroolsSessionStore entries for orphaned situations accumulate until JVM restart. Per-event window check handles the common case (another event arrives); this job handles the uncommon case (no further events) where KieSession leak is accepted. Coupled to #12 (compact) — a ganglia-aware expiry mechanism is the proper solution for both compaction and orphan cleanup. |
