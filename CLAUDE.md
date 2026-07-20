# CaseHub RAS

## Project Type

type: java

## Repository Role

Integration-tier situational awareness and reactive case creation. Observes `CloudEvent` CDI events
(`@ObservesAsync CloudEvent`) from platform stream modules (Kafka, AMQP, webhook, Camel), routes them
to pluggable `Ganglion` detection implementations, correlates composite events, and triggers case creation
via casehub-engine-api when a situation threshold is crossed.

**Tier:** Foundation (reclassification pending casehubio/parent#327). RAS is core situation awareness infrastructure consumed by ops-deployment, ops-compliance, and desiredstate.

**Key principle:** casehub-ras contains NO stream infrastructure. Quarkus endpoints, Kafka consumers,
AMQP consumers, webhook receivers, and Camel routes for data mapping all live in casehub-platform stream
submodules. casehub-ras observes the `CloudEvent` CDI events they produce. `CloudEvent` comes from
`io.cloudevents:cloudevents-core` via `casehub-platform-api` — no wrapper type.

**Naming:** RAS = Reticular Activating System — the biological system that monitors all sensory input,
filters for significance, and elevates to awareness. Ganglion = pluggable detection unit within the RAS.
Multiple ganglia, one RAS per deployment context.

**Design specs:**
- Original: `docs/superpowers/specs/2026-06-12-casehub-ras-design.md`
- Epic 1 API: `docs/superpowers/specs/2026-06-18-epic1-core-ras-api-design.md`
- Epic 2 Runtime: `docs/superpowers/specs/2026-06-25-epic2-ras-runtime-design.md`
- Epic 4 DroolsGanglion: `docs/superpowers/specs/2026-06-21-epic4-drools-ganglion-design.md`
- Result collection + test gaps: `docs/superpowers/specs/2026-06-22-drools-result-collection-and-test-gaps.md`
- Epic 3 JavaSwitchGanglion + NaiveBayesGanglion: `docs/superpowers/specs/2026-06-26-epic3-java-switch-naive-bayes-ganglion-design.md`
- DroolsGanglion hot reload: `docs/superpowers/specs/2026-06-26-drools-hot-reload-design.md`
- Event reorder buffer: `docs/superpowers/specs/2026-06-27-event-reorder-buffer-design.md`
- JPA SituationStore: `docs/superpowers/specs/2026-06-28-jpa-situation-store-design.md`
- Clustered retry logic: `docs/superpowers/specs/2026-06-29-clustered-retry-logic-design.md`
- Trigger lifecycle + situation query: `docs/superpowers/specs/2026-06-30-trigger-lifecycle-and-situation-query-design.md`
- Service lifecycle RAS integration: `docs/superpowers/specs/2026-07-07-service-lifecycle-ras-integration-design.md`
- DroolsSessionStore hardening: `docs/superpowers/specs/2026-07-09-drools-session-store-hardening-design.md`
- RAS runtime metrics: `docs/superpowers/specs/2026-07-12-ras-runtime-metrics-design.md`
- GanglionStateStore: `docs/superpowers/specs/2026-07-13-ganglion-state-store-design.md`
- DroolsSessionStore orphan cleanup: `docs/superpowers/specs/2026-07-17-drools-session-store-orphan-cleanup-design.md`
- ExpressionEvaluator integration: `docs/superpowers/specs/2026-07-17-expression-evaluator-integration-design.md`
- JQ Map context + NaiveBayes expressions: `docs/superpowers/specs/2026-07-20-jq-map-context-naivebayes-expressions-design.md`

## Build Commands

```bash
mvn --batch-mode install
mvn --batch-mode deploy -DskipTests   # CI only
```

## Module Structure

| Module | Artifact | Root package | Purpose |
|--------|----------|-------------|---------|
| `api/` | `casehub-ras-api` | `io.casehub.ras.api` | Core SPIs + domain types + JavaSwitchGanglion. GanglionStateStore SPI + GanglionStateKey, GanglionState, GanglionStateConflictException. OrphanedResourceCleaner SPI. EventFilter interface. GanglionDescriptor sealed interface (NaiveBayes variant) for declarative ganglion configuration. `SituationDefinitionProvider.ganglionDescriptors()` default method. Depends on `casehub-platform-api` (for `CloudEvent`, `ExpressionEvaluator`). Jackson annotations provided (polymorphic serde for sealed types). Mutiny provided. Publishes test-jar for AbstractGanglionContractTest + AbstractGanglionStateStoreContractTest. |
| `persistence-memory/` | `casehub-ras-persistence-memory` | `io.casehub.ras.persistence.memory` | InMemorySituationStore — `@Alternative @Priority(100)`, ConcurrentHashMap-backed. Dev/test only. |
| `persistence-jpa/` | `casehub-ras-persistence-jpa` | `io.casehub.ras.persistence.jpa` | JpaSituationStore — `@ApplicationScoped`, Hibernate ORM + JSONB detections. JpaGanglionStateStore — `@ApplicationScoped`, GanglionStateEntity with JSONB state + `@Version` optimistic locking. Implements `OrphanedResourceCleaner` for SQL join-based orphan cleanup. Consumers add `classpath:db/ras/migration` to `quarkus.flyway.locations`. |
| `runtime/` | `casehub-ras` | `io.casehub.ras.runtime` | RasEngine, SituationEvaluator, DefaultRasTriggerPolicy, DefaultCaseTrigger, SituationExpiryJob, EventBufferFlushJob, EventReorderBuffer, YamlSituationDefinitionProvider, NaiveBayesGanglion, DefaultSituationSource, RasEndpointRegistration, RasMetrics, InMemoryGanglionStateStore (`@DefaultBean`), CloudEventExpressionContext, SituationContextExpressionContext, ExpressionFeatureExtractor, JqResultUnwrapper. SituationDefinitionRegistry compiles expression descriptors at registration via three-phase constructor (descriptor ganglia → CDI ganglia → situation registrations). Constructs NaiveBayesGanglion from GanglionDescriptor.NaiveBayes with expression-based feature extraction. Micrometer metrics (optional, via `Instance<MeterRegistry>`). `casehub-platform-expression` at test scope only — deployers add it to classpath when expressions are needed. Quarkus extension. |
| `ras-drools/` | `casehub-ras-drools` | `io.casehub.ras.drools` | DroolsGanglion — Drools CEP (KieSession, sliding windows, temporal correlation). Optional. |
| `drools-reliability/` | `casehub-ras-drools-reliability` | `io.casehub.ras.drools.reliability` | ReliableDroolsSessionStore — persistent DroolsSessionStore backed by drools-reliability + H2MVStore. Implements `OrphanedResourceCleaner` for orphaned session cleanup via `SituationStore.find()`. DroolsReliabilityMetrics (centralised, optional via `Instance<MeterRegistry>`). ReliableDroolsSessionStoreHealthCheck (`@Readiness`). H2MVStore corruption auto-recovery at startup. Experimental. |
| `ras-llm/` | `casehub-ras-llm` | `io.casehub.ras.llm` | LlmGanglion — narrative detection via casehub-platform-agent-api. Optional, slow path. |
| `testing/` | `casehub-ras-testing` | `io.casehub.ras.testing` | MockGanglion, FixedDetectionResult, MockCaseTrigger. **Test scope only.** |

## Core SPIs (api/)

### Ganglion — detection strategy

```java
interface Ganglion {
    String ganglionId();
    Set<String> handledEventTypes();
    Uni<DetectionResult> detect(CloudEvent event, SituationContext context);
    default Uni<SituationContext> compact(SituationContext context) { ... }
    default Uni<Void> close(String situationId, String correlationKey, String tenancyId) { ... }
}
```

### RasTriggerPolicy — when to trigger

```java
interface RasTriggerPolicy {
    Uni<TriggerDecision> evaluate(SituationContext context, SituationDefinition definition);
    // TriggerDecision: TRIGGER / TRIGGER_AND_CONTINUE / CONTINUE_ACCUMULATING / DISCARD / RESOLVE
}
```

### SituationStore — situation persistence

```java
interface SituationStore {
    Uni<Optional<SituationContext>> find(String situationId, String correlationKey, String tenancyId);
    Uni<SituationContext> save(SituationContext context);
    Uni<Void> remove(String situationId, String correlationKey, String tenancyId);
    Uni<Integer> removeExpired(Instant cutoff);
    Uni<Void> removeAllForSituation(String situationId);
    default Uni<Boolean> tryClaimTrigger(String situationId, String correlationKey, String tenancyId, Instant triggerTime) { ... }
    default Uni<Void> resetTriggerClaim(String situationId, String correlationKey, String tenancyId) { ... }
    default Uni<Integer> removeTriggeredBefore(Instant triggerCutoff) { ... }
    default Uni<List<SituationContext>> findActive(String tenancyId) { ... }
}
```

### GanglionStateStore — ganglion computation state persistence (api/)

```java
interface GanglionStateStore {
    Uni<Optional<GanglionState>> load(GanglionStateKey key);
    Uni<Void> save(GanglionStateKey key, GanglionState state);
    Uni<Void> remove(GanglionStateKey key);
    Uni<Void> removeForSituation(String situationId);
}
```

Pluggable persistence for simple-state ganglia (numeric accumulation). `GanglionStateKey` is a 4-tuple `(ganglionId, situationId, correlationKey, tenancyId)`. `GanglionState` carries `double[] values` + `OptionalLong storeVersion` for optimistic locking. `InMemoryGanglionStateStore` (`@DefaultBean` in runtime/) is the zero-config default. `JpaGanglionStateStore` (`@ApplicationScoped` in persistence-jpa/) adds persistence — wins over `@DefaultBean` by CDI priority when on the classpath. Orphan cleanup via `OrphanedResourceCleaner` SPI (see below).

### OrphanedResourceCleaner — derived resource cleanup (api/)

```java
interface OrphanedResourceCleaner {
    String cleanerType();
    Uni<Integer> removeOrphaned();
}
```

Generic SPI for cleaning up derived resources whose parent situation no longer exists. Discovered via CDI `Instance<OrphanedResourceCleaner>` in `SituationExpiryJob`. Implementations: `JpaGanglionStateStore` (`cleanerType="ganglion_state"`, SQL join-based), `ReliableDroolsSessionStore` (`cleanerType="drools_session"`, `SituationStore.find()` per key with error isolation). Metric: `ras.expiry.orphans_cleaned` counter tagged by `cleaner_type`.

### EventFilter — pre-ganglion event filter (api/)

```java
@FunctionalInterface
interface EventFilter {
    boolean accepts(CloudEvent event);
}
```

Compiled from `SituationDefinition.eventFilter()` expression descriptors by `SituationDefinitionRegistry`. Evaluated by `RasEngine` before correlation key extraction — events that don't pass are skipped without invoking detection. Filter errors degrade to pass-through (non-fatal) with `ras.expression.error` metric.

### JavaSwitchGanglion — synchronous detection base class (api/)

Abstract class in `api/`. Developers subclass and override `evaluate(CloudEvent, SituationContext) → DetectionResult`.
`detect()` is final — wraps `evaluate()` in `Uni`. Stateless (no-op `compact()`/`close()`). Helper methods:
`detected()`, `weak()`, `noise()`, `anti()` — auto-embed ganglionId. Preferred path for simple stateless detection.

### NaiveBayesGanglion — Bayesian classification (runtime/)

Concrete class in `runtime/`, configured via `NaiveBayesConfig`. Incrementally accumulates posteriors across
`detect()` calls using Naive Bayes. Log-space arithmetic prevents underflow. Implements `compact()` to collapse
running posteriors into a single detection — necessary for correct Threshold ChainMode interaction. Config types:
`NaiveBayesConfig`, `FeatureLikelihood`, `NaiveBayesFeatureExtractor`, `NaiveBayesSignalMapping` (with optional ANTI threshold).

### CorrelationKeyExtractor — custom correlation key extraction (api/)

```java
@FunctionalInterface
interface CorrelationKeyExtractor {
    String extract(CloudEvent event);
}
```

Domain adapters implement `CorrelationKeyExtractor` to provide custom correlation logic. Used by `SituationRegistration` to bundle situation definition with its correlation strategy.

### DefaultCorrelationKeyExtractor — default correlation (api/)

Default `CorrelationKeyExtractor` implementation in `api/`. Returns `CloudEvent.getSubject()` when non-null, otherwise `"_singleton"`. Sufficient for most use cases — custom extractors needed only when correlation key is derived from event data.

### GanglionDescriptor — declarative ganglion configuration (api/)

Sealed interface for declaring ganglia via configuration rather than CDI beans. Providers return
descriptors via `SituationDefinitionProvider.ganglionDescriptors()`. The registry compiles
feature expressions and constructs ganglion instances during its three-phase startup.

Currently permits `NaiveBayes` — carries outcomes, priors, per-feature expression + likelihood
tables, and signal mapping thresholds. Extensible for future ganglion types (expression-rules).

### SituationDefinitionProvider — situation registration SPI (api/)

```java
interface SituationDefinitionProvider {
    List<SituationRegistration> registrations();
    default List<GanglionDescriptor> ganglionDescriptors() { return List.of(); }
}
```

SPI for registering situation definitions. Implementations return a list of `SituationRegistration` records. Multiple providers can coexist — the runtime aggregates all definitions at startup. `YamlSituationDefinitionProvider` in `runtime/` is the default classpath-based implementation.

### SituationRegistration — situation + correlation bundle (api/)

```java
record SituationRegistration(SituationDefinition definition, CorrelationKeyExtractor correlationKeyExtractor,
        EventFilter eventFilter, Map<String, CompiledExpression<Map, Object>> compiledDynamicData) {}
```

Bundles a `SituationDefinition` with compiled strategies. `correlationKeyExtractor` and `eventFilter` are compiled from expression descriptors on the definition by `SituationDefinitionRegistry`. `compiledDynamicData` holds compiled dynamic case data expressions. Convenience constructors default new fields to null. Returned by `SituationDefinitionProvider` implementations (with defaults); the registry replaces strategies with compiled versions.

## Core Types (api/)

| Type | Purpose |
|------|---------|
| `CloudEvent` | Input — from `io.cloudevents:cloudevents-core` via `casehub-platform-api`. Fields: `type` (event type for routing), `source`, `subject`, `data`, `tenancyid` extension |
| `DetectionResult` | Ganglion output — `ganglionId`, `confidence` (0.0–1.0, NaN rejected), `signal` (NOISE/ANTI/WEAK/DETECTED), `evidence` |
| `DetectionSignal` | Signal strength — NOISE, ANTI, WEAK, DETECTED (ascending). `isAtLeast(threshold)` for comparisons. |
| `TimestampedDetection` | Wraps `DetectionResult` + `Instant eventTime` — runtime adds event timestamp at accumulation boundary |
| `SituationContext` | Accumulated state — `situationId`, `correlationKey`, `tenancyId`, `firstSignal`, `lastSignal`, `List<TimestampedDetection>`, `OptionalLong storeVersion`, `Instant lastTriggered` (@Nullable), `int triggerCount` |
| `SituationConflictException` | Thrown by `SituationStore.save()` on concurrent modification — evaluator catches and retries |
| `TriggerAction` | Sealed interface — `CreateCase(CaseTriggerConfig)`, `NotifyOnly()`. Jackson `@JsonTypeInfo(property="type")` with names `create-case`, `notify-only`. Declares what happens when a situation triggers. `CreateCase` starts a new case via `CaseTrigger`. `NotifyOnly` fires enriched `SituationChangeEvent` only — for signaling existing cases via consumer bridge. |
| `SituationDefinition` | Declared situation — `situationId`, `eventTypes`, `correlationWindow` (@Nullable), `eventBufferDelay` (@Nullable), `ChainMode`, `TriggerAction`, `TriggerMode triggerMode`, `ExpressionEvaluator correlationKeyExpression` (@Nullable), `ExpressionEvaluator eventFilter` (@Nullable), `Map<String, ExpressionEvaluator> dynamicCaseData` (defaults empty). 7-arg convenience constructor for non-expression usage. |
| `ChainMode` | Sealed interface — And, Or, Threshold, Sequence, Count, Streak, Rate. Jackson `@JsonTypeInfo(property="type")` with names matching YAML convention (`and`, `or`, `threshold`, `sequence`, `count`, `streak`, `rate`). All variants carry explicit ganglion references. `referencedGanglia()` default method extracts IDs. |
| `CaseTriggerConfig` | Case creation parameters — `caseNamespace`, `caseName`, `caseVersion`, `baseCaseData`. String identifiers, no engine-api dependency. |
| `CaseTrigger` | SPI for case creation — `fire(CaseTriggerConfig, SituationContext) → Uni<UUID>`. Default impl in runtime/ bridges to CaseHub. |
| `TriggerDecision` | Trigger outcome — TRIGGER, TRIGGER_AND_CONTINUE, CONTINUE_ACCUMULATING, DISCARD, RESOLVE |
| `TriggerMode` | Sealed interface — FireOnce, Repeating(Duration cooldown). Jackson `@JsonTypeInfo(property="type")` with names `fire-once`, `repeating`. Declares post-trigger lifecycle on SituationDefinition. DefaultRasTriggerPolicy maps to TriggerDecision. |
| `ActiveSituation` | Read-only projection — `situationId`, `correlationKey`, `tenancyId`, `confidence`, `evidence`, `since`, `lastSignal`, `triggerCount`. For external consumers querying active situations. |
| `SituationSource` | Query SPI — `Uni<List<ActiveSituation>> activeSituations(String tenancyId)`. Implemented by DefaultSituationSource in runtime/. |
| `SituationChangeEvent` | CDI event — `tenancyId`, `situationId`, `correlationKey`, `ChangeType` (TRIGGERED/RESOLVED/DISCARDED), `SituationContext context`. Fired by evaluator after state transitions. Context carries detection results for consumer bridges. |
| `CorrelationKeyExtractor` | Function interface — `String extract(CloudEvent event)`. Domain adapters implement for custom correlation key derivation. |
| `SituationRegistration` | Record bundling `SituationDefinition` + `CorrelationKeyExtractor`. Returned by `SituationDefinitionProvider`. |
| `GanglionStateKey` | Key for ganglion state — `ganglionId`, `situationId`, `correlationKey`, `tenancyId`. Same 4-tuple as `DroolsSessionKey`. |
| `GanglionState` | Ganglion computation state — `double[] values`, `OptionalLong storeVersion`. Carries log-posteriors (or other numeric accumulation) with optional version for optimistic locking. |
| `GanglionStateConflictException` | Thrown by `GanglionStateStore.save()` on concurrent modification — ganglion catches and retries internally. Mirrors `SituationConflictException`. |
| `DroolsSessionStoreException` | Unchecked exception in `ras-drools/` — thrown by `DroolsSessionStore` implementations on storage read failure. Part of the SPI contract. |
| `GanglionDescriptor` | Sealed interface in api/ for declarative ganglion configuration. `NaiveBayes` variant carries outcomes, priors, per-feature expression + likelihood tables, signal mapping. Registry constructs ganglion instances from descriptors during three-phase startup. |

## Routing Model — Definition-Driven (Model B)

The engine owns situation routing. Ganglia evaluate — they do not choose which situation
an event belongs to. `SituationDefinition.eventTypes` is the routing key; `ChainMode` identifies
participating ganglia; `Ganglion.handledEventTypes()` is a capability declaration for startup
validation only. A situation instance is identified by the tuple `(situationId, correlationKey, tenancyId)`.
`correlationKey` defaults to `CloudEvent.getSubject()` or `"_singleton"` when null.

Chain modes: AND (all named ganglia must fire), OR (any single firing), THRESHOLD (min confidence sum,
no upper bound — ANTI detections subtract from the sum), SEQUENCE (ordered arrival), COUNT (same
ganglion fires N times), STREAK (same ganglion fires N times consecutively — ANTI resets), RATE
(ratio of qualifying signals in a sliding window of scoreable signals).

## YAML Situation Definitions (runtime/)

`YamlSituationDefinitionProvider` reads `SituationDefinition` entries from a classpath YAML resource
(default `META-INF/ras-situations.yaml`, configurable via `ras.situations.yaml`). Returns empty list
when the resource is absent — coexists with programmatic providers. Supports all seven ChainMode variants
via a `type` discriminator (`and`, `or`, `threshold`, `sequence`, `count`, `streak`, `rate`). `triggerAction`
field uses a `type` discriminator: `type: create-case` (with `caseNamespace`, `caseName`, `caseVersion`,
optional `baseCaseData`) or `type: notify-only`. Optional `eventBufferDelay` field (ISO-8601 Duration)
enables per-situation event reordering for pseudo clock mode. Optional `triggerMode` field: `type: fire-once`
(default when absent) or `type: repeating` with `cooldown` (ISO-8601 Duration). Optional `correlationKey`
field: `{expression, language}` for expression-based correlation key extraction (replaces
`DefaultCorrelationKeyExtractor`). Optional `eventFilter` field: `{expression, language}` for pre-ganglion
event filtering. Optional `dynamicCaseData` field: map of `{expression, language}` entries for expressions
evaluated against `SituationContext` at trigger time. Supported languages: `jq`, `mvel`. Expression
compilation is handled by `SituationDefinitionRegistry` at registration time via `ExpressionEngineRegistry`.

### YAML Ganglia

Optional `ganglia:` section in the same YAML resource, parsed before `situations:`. Declares ganglion
instances via a `type` discriminator. Currently supports `type: naive-bayes` — carries `ganglionId`,
`handledEventTypes`, `outcomes`, `priors`, per-feature `expression` + `language` + `values` + `likelihoods`,
and `signalMapping` (targetOutcome, detectedThreshold, weakThreshold, optional antiThreshold). Numeric
YAML values coerced via `Number.doubleValue()`. Expression compilation and ganglion construction handled
by `SituationDefinitionRegistry` during three-phase startup. YAML ganglia coexist with CDI-declared
ganglia — duplicate `ganglionId` is a startup error.

## Dynamic Situation Registration (runtime/)

`SituationDefinitionRegistry` supports runtime registration and deregistration of situation definitions
via `register(SituationRegistration)` and `deregister(String situationId)`. Thread-safe via copy-on-write
`RegistrySnapshot` — one `volatile` swap per mutation, lock-free reads on `findByEventType()`. Ganglia
remain static (CDI beans at startup); only situation definitions are dynamic. Used by consuming apps to
register monitoring situations at deploy time and deregister at decommission time. `deregister()` is
idempotent. For persistent situations (`correlationWindow=null`), consuming apps must call
`SituationStore.removeAllForSituation()` AND `GanglionStateStore.removeForSituation()` before
deregistering to avoid orphaned entries. `SituationExpiryJob` calls `GanglionStateStore.removeOrphaned()`
as a background safety net, but explicit cleanup at deregistration is the primary mechanism.

## Persistent Situation Compaction (runtime/)

For persistent situations (`correlationWindow = null`), `SituationEvaluator` calls `Ganglion.compact()`
on each referenced ganglion after every `CONTINUE_ACCUMULATING` decision. The ganglion decides what to
compact — the evaluator just triggers it. Windowed situations skip compaction.

## Clustered Conflict Handling (runtime/)

`SituationEvaluator.processEvent()` is two-phase: Phase 1 detects once (ganglia mutate internal state),
Phase 2 retries read-modify-write on `SituationConflictException`. `JpaSituationStore` uses two-layer
conflict detection: application-level `storeVersion` comparison (non-overlapping transactions) +
Hibernate `@Version` OLE/constraint violation (overlapping transactions). Max retries configurable
via `ras.evaluator.max-conflict-retries` (default 3). `InMemorySituationStore` is unaffected — per-key
`synchronized` locks prevent concurrent access within a single JVM.

## Ganglion Error Isolation (runtime/)

`SituationEvaluator.runDetection()` wraps each `ganglion.detect()` call in a per-ganglion try-catch.
One ganglion's failure (storage error, rule engine crash) is logged and skipped — remaining ganglia
still evaluate and produce partial results. `DroolsGanglion.detect()` catches
`DroolsSessionStoreException` from `computeIfAbsent`, attempts defensive session key cleanup
(suppressed if it also fails), and rethrows.

## Duplicate Trigger Prevention (runtime/)

TRIGGER path uses `SituationStore.tryClaimTrigger()` for exactly-once trigger execution across
clustered JVMs. `tryClaimTrigger` atomically stamps `lastTriggered` and increments `triggerCount`
alongside the `policyTriggered` CAS — trigger metadata is store-managed, not written by `save()`.
Bifurcated claim path for TRIGGER: new entities use save-before-claim, existing entities use
claim-before-save. TRIGGER_AND_CONTINUE uses save-first flow (no bifurcation) — detection is
always persisted before claiming. Entity removal is deferred after successful trigger — the
`policyTriggered=true` entity guards against duplicate triggers from retrying losers, cleaned up
by `SituationExpiryJob` after a configurable guard period (`ras.evaluator.trigger-guard-period`,
default PT1M). On trigger failure, `resetTriggerClaim()` clears the flag for retry.

## Key Rules

- `testing/` is never compile or runtime scope — test only
- Ganglion implementations activate by classpath presence
- `LlmGanglion` always runs async on slow path — never blocks fast detection path
- All `SituationContext` is tenancy-scoped — no cross-tenant situation accumulation
- Platform stream modules have NO dependency on `casehub-ras-api` — they fire `CloudEvent` from `casehub-platform-api`
- `casehub-ras-api` depends on `casehub-platform-api` (for `CloudEvent` type) — correct direction: integration → foundation
- casehub-ras never imports Kafka, AMQP, Camel, or any transport library
- `JavaSwitchGanglion` lives in api/ (abstract extension point, zero deps) — `NaiveBayesGanglion` lives in runtime/ (concrete implementation with internal state)

## Cross-Repo Conventions

Protocols live in `casehub/garden`. Do not write protocol files in this repo.

## Artifact Locations

| Skill | Writes to |
|-------|-----------|
| brainstorming (specs) | `docs/superpowers/specs/` |
| adr | `docs/adr/` |
| handover | workspace `HANDOFF.md` |
| write-blog | workspace `blog/` |

## Work Tracking

**Issue tracking:** enabled
**GitHub repo:** casehubio/casehub-ras

## Workspace

**Project repo:** `/Users/mdproctor/claude/casehub/ras`
**Workspace:** `/Users/mdproctor/claude/public/casehub-ras`
**Workspace type:** public
