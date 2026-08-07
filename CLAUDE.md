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
- Evidence templates + expression-rule ganglion: `docs/superpowers/specs/2026-07-21-evidence-templates-expression-rules-design.md`
- Per-decision-path evidence templates: `docs/superpowers/specs/2026-07-22-per-decision-path-evidence-templates-design.md`
- Retire reactive (Mutiny): `docs/superpowers/specs/2026-07-23-retire-reactive-design.md`
- Dynamic confidence expressions: `docs/superpowers/specs/2026-07-23-dynamic-confidence-expressions-design.md`
- Passive observation query service: `docs/superpowers/specs/2026-07-29-passive-observation-query-service-design.md`
- RAS feedback loop: `docs/superpowers/specs/2026-08-06-ras-feedback-loop-design.md`

## Platform Docs
- [Platform Index](https://raw.githubusercontent.com/casehubio/parent/main/docs/INDEX.md) — discovery index (start here)
- [Building Platform](https://raw.githubusercontent.com/casehubio/parent/main/docs/guides/building-platform.md) — platform contributor guide

## Repo Guide

This repo owns its own documentation, synced to parent via CI:
- `docs/guides/consumer-guide.md` — for app builders: modules, APIs, quick start
- `docs/guides/contributor-guide.md` — for platform builders: architecture, SPIs, internals

Update the relevant guide in the same session when implementation changes modules, SPIs, or public APIs. Do not defer — drift compounds.

Read `docs/guides/consumer-guide.md` for app-level work. Only read `docs/guides/contributor-guide.md` when modifying this repo's internals or extension points.

## Build Commands

```bash
mvn --batch-mode install
mvn --batch-mode deploy -DskipTests   # CI only
```

## Module Structure

| Module | Artifact | Root package | Purpose |
|--------|----------|-------------|---------|
| `api/` | `casehub-ras-api` | `io.casehub.ras.api` | Core SPIs + domain types + JavaSwitchGanglion. GanglionStateStore SPI + GanglionStateKey, GanglionState, GanglionStateConflictException. OrphanedResourceCleaner SPI. EventFilter interface. GanglionDescriptor sealed interface (NaiveBayes, ExpressionRules variants) for declarative ganglion configuration. Cross-cutting `evidenceTemplates()` default method on GanglionDescriptor. `SituationDefinitionProvider.ganglionDescriptors()` default method. SituationQueryService SPI + SituationEvent, TrendResult, TenantHealth, SituationSummary. SituationEventRetention SPI. Feedback loop SPIs: OutcomeLedger, SuppressionStrategy, FeedbackTuningStrategy, FeedbackConfig, OutcomeRecord, OutcomeClassification, OutcomeStatistics. AbstractOutcomeLedgerContractTest in test-jar. Depends on `casehub-platform-api` (for `CloudEvent`, `ExpressionEvaluator`). Jackson annotations provided (polymorphic serde for sealed types). Publishes test-jar for AbstractGanglionContractTest + AbstractGanglionStateStoreContractTest + AbstractSituationQueryServiceContractTest + AbstractOutcomeLedgerContractTest. |
| `persistence-memory/` | `casehub-ras-persistence-memory` | `io.casehub.ras.persistence.memory` | InMemorySituationStore — `@Alternative @Priority(100)`, ConcurrentHashMap-backed. InMemorySituationQueryService — `@Alternative @Priority(100)`, implements SituationQueryService + SituationEventRetention, CDI `@ObservesAsync` event capture. Dev/test only. |
| `persistence-jpa/` | `casehub-ras-persistence-jpa` | `io.casehub.ras.persistence.jpa` | JpaSituationStore — `@ApplicationScoped`, Hibernate ORM + JSONB detections. JpaGanglionStateStore — `@ApplicationScoped`, GanglionStateEntity with JSONB state + `@Version` optimistic locking. JpaSituationQueryService — `@ApplicationScoped`, implements SituationQueryService + SituationEventRetention. SituationEventRecorder — CDI `@ObservesAsync` observer, `@Transactional`, best-effort event log capture. JpaOutcomeLedger — `@ApplicationScoped`, OutcomeLedger impl with UNIQUE(case_id) dedup, native INSERT ON CONFLICT. Implements `OrphanedResourceCleaner` for SQL join-based orphan cleanup. Consumers add `classpath:db/ras/migration` to `quarkus.flyway.locations`. Flyway V1-V7. |
| `runtime/` | `casehub-ras` | `io.casehub.ras.runtime` | RasEngine, SituationEvaluator, DefaultRasTriggerPolicy, DefaultCaseTrigger, SituationExpiryJob, EventBufferFlushJob, EventReorderBuffer, YamlSituationDefinitionProvider, NaiveBayesGanglion, ExpressionRulesGanglion, EvidenceExtractingGanglion, DefaultSituationSource, RasEndpointRegistration, RasMetrics, InMemoryGanglionStateStore (`@DefaultBean`), InMemoryOutcomeLedger (`@DefaultBean`), CloudEventExpressionContext, SituationContextExpressionContext, ExpressionFeatureExtractor, JqResultUnwrapper. Feedback loop: OutcomeRecorder (CaseOutcomeObserver impl), FeedbackAnalyzer, FeedbackUpdateJob (`@Scheduled` every 5m), FeedbackState, FeedbackMetrics, DefaultSuppressionStrategy (`@DefaultBean`), DefaultTuningStrategy (`@DefaultBean`). SituationDefinitionRegistry compiles expression descriptors at registration via three-phase constructor (descriptor ganglia → CDI ganglia → situation registrations). Constructs NaiveBayesGanglion from GanglionDescriptor.NaiveBayes, ExpressionRulesGanglion from GanglionDescriptor.ExpressionRules. Wraps with EvidenceExtractingGanglion when evidenceTemplates are present. NaiveBayesGanglion accepts FeedbackState for adjusted initial priors. SituationEvaluator checks SuppressionStrategy before detection and applies FeedbackState threshold overrides for ChainMode.Threshold. Micrometer metrics (optional, via `Instance<MeterRegistry>`). `casehub-platform-expression` at test scope only — deployers add it to classpath when expressions are needed. Quarkus extension. |
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
    DetectionResult detect(CloudEvent event, SituationContext context);
    default SituationContext compact(SituationContext context) { ... }
    default void close(String situationId, String correlationKey, String tenancyId) { }
}
```

### RasTriggerPolicy — when to trigger

```java
interface RasTriggerPolicy {
    PolicyDecision evaluate(SituationContext context, SituationDefinition definition);
    // TriggerDecision: TRIGGER / TRIGGER_AND_CONTINUE / CONTINUE_ACCUMULATING / DISCARD / RESOLVE
}
```

### SituationStore — situation persistence

```java
interface SituationStore {
    Optional<SituationContext> find(String situationId, String correlationKey, String tenancyId);
    SituationContext save(SituationContext context);
    void remove(String situationId, String correlationKey, String tenancyId);
    int removeExpired(Instant cutoff);
    void removeAllForSituation(String situationId);
    default boolean tryClaimTrigger(String situationId, String correlationKey, String tenancyId, Instant triggerTime) { ... }
    default void resetTriggerClaim(String situationId, String correlationKey, String tenancyId) { }
    default int removeTriggeredBefore(Instant triggerCutoff) { ... }
    default List<SituationContext> findActive(String tenancyId) { ... }
}
```

### GanglionStateStore — ganglion computation state persistence (api/)

```java
interface GanglionStateStore {
    Optional<GanglionState> load(GanglionStateKey key);
    void save(GanglionStateKey key, GanglionState state);
    void remove(GanglionStateKey key);
    void removeForSituation(String situationId);
}
```

Pluggable persistence for simple-state ganglia (numeric accumulation). `GanglionStateKey` is a 4-tuple `(ganglionId, situationId, correlationKey, tenancyId)`. `GanglionState` carries `double[] values` + `OptionalLong storeVersion` for optimistic locking. `InMemoryGanglionStateStore` (`@DefaultBean` in runtime/) is the zero-config default. `JpaGanglionStateStore` (`@ApplicationScoped` in persistence-jpa/) adds persistence — wins over `@DefaultBean` by CDI priority when on the classpath. Orphan cleanup via `OrphanedResourceCleaner` SPI (see below).

### OrphanedResourceCleaner — derived resource cleanup (api/)

```java
interface OrphanedResourceCleaner {
    String cleanerType();
    int removeOrphaned();
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
`detect()` is final — delegates to `evaluate()`. Stateless (no-op `compact()`/`close()`). Helper methods:
`detected()`, `weak()`, `noise()`, `anti()` — auto-embed ganglionId. Preferred path for simple stateless detection.

### NaiveBayesGanglion — Bayesian classification (runtime/)

Concrete class in `runtime/`, configured via `NaiveBayesConfig`. Incrementally accumulates posteriors across
`detect()` calls using Naive Bayes. Log-space arithmetic prevents underflow. Implements `compact()` to collapse
running posteriors into a single detection — necessary for correct Threshold ChainMode interaction. Config types:
`NaiveBayesConfig`, `FeatureLikelihood`, `NaiveBayesFeatureExtractor`, `NaiveBayesSignalMapping` (with optional ANTI threshold).
Automatic evidence: `posterior` (target outcome posterior), `features` (extracted feature values),
`winningOutcome` (outcome with highest posterior). Per-outcome evidence: evaluates
`outcomeEvidenceTemplates` from `NaiveBayesConfig` for the winning outcome, merged after auto evidence.
Constructor: 3-arg `(NaiveBayesConfig, GanglionStateStore, MeterRegistry)` — MeterRegistry nullable.

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

Permits `NaiveBayes` (Bayesian classification with per-feature expression extraction) and
`ExpressionRules` (ordered boolean condition→signal rules, first match wins). Cross-cutting
`evidenceTemplates()` default method — any variant can carry expression-based evidence
extraction templates. Registry wraps descriptor-constructed ganglia in
`EvidenceExtractingGanglion` when templates are present. Per-decision-path evidence:
`ExpressionRules.Rule` carries per-rule `evidenceTemplates` (evaluated by
`ExpressionRulesGanglion` for the matched rule) and optional `confidenceExpression`
(nullable `ExpressionEvaluator` — dynamic confidence override with static fallback,
clamped to [0.0, 1.0], Infinity/NaN fall back to static). `NaiveBayes` carries
`outcomeEvidenceTemplates` (evaluated by `NaiveBayesGanglion` for the winning outcome).
Merge order: automatic evidence → per-decision-path → ganglion-level (wrapper).

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
| `SituationDefinition` | Declared situation — `situationId`, `eventTypes`, `correlationWindow` (@Nullable), `eventBufferDelay` (@Nullable), `ChainMode`, `TriggerAction`, `TriggerMode triggerMode`, `ExpressionEvaluator correlationKeyExpression` (@Nullable), `ExpressionEvaluator eventFilter` (@Nullable), `Map<String, ExpressionEvaluator> dynamicCaseData` (defaults empty), `FeedbackConfig feedbackConfig` (@Nullable). `withChainMode(ChainMode)` for threshold override construction. 7-arg convenience constructor for non-expression usage. |
| `ChainMode` | Sealed interface — And, Or, Threshold, Sequence, Count, Streak, Rate. Jackson `@JsonTypeInfo(property="type")` with names matching YAML convention (`and`, `or`, `threshold`, `sequence`, `count`, `streak`, `rate`). All variants carry explicit ganglion references. `referencedGanglia()` default method extracts IDs. |
| `CaseTriggerConfig` | Case creation parameters — `caseNamespace`, `caseName`, `caseVersion`, `baseCaseData`. String identifiers, no engine-api dependency. |
| `CaseTrigger` | SPI for case creation — `UUID fire(CaseTriggerConfig, SituationContext)`. Default impl in runtime/ bridges to CaseHub via `CompletionStage.join()`. |
| `TriggerDecision` | Trigger outcome — TRIGGER, TRIGGER_AND_CONTINUE, CONTINUE_ACCUMULATING, DISCARD, RESOLVE |
| `TriggerMode` | Sealed interface — FireOnce, Repeating(Duration cooldown). Jackson `@JsonTypeInfo(property="type")` with names `fire-once`, `repeating`. Declares post-trigger lifecycle on SituationDefinition. DefaultRasTriggerPolicy maps to TriggerDecision. |
| `ActiveSituation` | Read-only projection — `situationId`, `correlationKey`, `tenancyId`, `confidence`, `evidence`, `since`, `lastSignal`, `triggerCount`. For external consumers querying active situations. |
| `SituationSource` | Query SPI — `List<ActiveSituation> activeSituations(String tenancyId)`. Implemented by DefaultSituationSource in runtime/. |
| `SituationChangeEvent` | CDI event — `tenancyId`, `situationId`, `correlationKey`, `ChangeType` (TRIGGERED/RESOLVED/DISCARDED), `SituationContext context`. Fired by evaluator after state transitions. Context carries detection results for consumer bridges. |
| `CorrelationKeyExtractor` | Function interface — `String extract(CloudEvent event)`. Domain adapters implement for custom correlation key derivation. |
| `SituationRegistration` | Record bundling `SituationDefinition` + `CorrelationKeyExtractor`. Returned by `SituationDefinitionProvider`. |
| `GanglionStateKey` | Key for ganglion state — `ganglionId`, `situationId`, `correlationKey`, `tenancyId`. Same 4-tuple as `DroolsSessionKey`. |
| `GanglionState` | Ganglion computation state — `double[] values`, `OptionalLong storeVersion`. Carries log-posteriors (or other numeric accumulation) with optional version for optimistic locking. |
| `GanglionStateConflictException` | Thrown by `GanglionStateStore.save()` on concurrent modification — ganglion catches and retries internally. Mirrors `SituationConflictException`. |
| `DroolsSessionStoreException` | Unchecked exception in `ras-drools/` — thrown by `DroolsSessionStore` implementations on storage read failure. Part of the SPI contract. |
| `GanglionDescriptor` | Sealed interface in api/ for declarative ganglion configuration. `NaiveBayes` variant carries outcomes, priors, per-feature expression + likelihood tables, signal mapping, `outcomeEvidenceTemplates`, `outcomeGroundTruth` (@Nullable, maps case outcome labels to NaiveBayes outcome names for feedback-driven prior recalibration). `ExpressionRules` variant carries ordered boolean condition→signal rules with per-rule `evidenceTemplates` and optional per-rule `confidenceExpression` (dynamic confidence override). Cross-cutting `evidenceTemplates()` for expression-based evidence extraction. Registry constructs ganglion instances from descriptors during three-phase startup, wraps with `EvidenceExtractingGanglion` when ganglion-level evidence templates are present. Merge order: auto evidence → per-decision-path → ganglion-level. |
| `FeedbackConfig` | Feedback loop configuration on `SituationDefinition` — `noiseLabels`, `confirmedLabels` (disjoint sets), `suppressionCooldown` (positive Duration), `learningRate` (0.0–1.0], `retentionPeriod` (>= cooldown), `tuningEnabled` (default false). `classify(String outcomeLabel)` returns `OutcomeClassification`. |
| `OutcomeClassification` | Enum — NOISE, CONFIRMED, NEUTRAL. Derived from case outcome label via `FeedbackConfig.classify()`. |
| `OutcomeRecord` | Immutable record — `situationId`, `correlationKey`, `tenancyId`, `outcomeLabel`, `classification`, `closedAt`, `caseId`. UNIQUE(case_id) for at-least-once dedup. |
| `OutcomeStatistics` | Aggregate — `situationId`, `tenancyId`, `totalOutcomes`, `noiseCount`, `confirmedCount`, `neutralCount`, `windowStart`. `precision()` and `noiseRate()` computed properties (NaN when insufficient data). |
| `OutcomeLedger` | SPI — `record()`, `statistics()`, `lastNoiseDismissalTime()`, `countByLabel()`, `distinctTenancies()`, `removeRecordsBefore()`. `InMemoryOutcomeLedger` (`@DefaultBean` in runtime/), `JpaOutcomeLedger` (`@ApplicationScoped` in persistence-jpa/). |
| `SuppressionStrategy` | SPI — `boolean shouldSuppress(situationId, correlationKey, tenancyId, FeedbackConfig, Optional<Instant> lastNoiseDismissalTime)`. `DefaultSuppressionStrategy` (`@DefaultBean`) suppresses within cooldown. |
| `FeedbackTuningStrategy` | SPI — `adjustThreshold(OutcomeStatistics, currentThreshold, FeedbackConfig)`, `adjustPriors(currentPriors, outcomeCounts, FeedbackConfig)`. `DefaultTuningStrategy` (`@DefaultBean`) uses noise-rate-driven threshold shift and Laplace-smoothed prior blending. |
| `SituationEvent` | Event log record — `situationId`, `correlationKey`, `tenancyId`, `changeType`, `eventTime`, `firstSeen`, `confidence`, `detectionCount`, `triggerCount`, `evidence`, `metadata`. Immutable with defensive copying. Static `from(SituationChangeEvent, Instant)` factory for projection from CDI events. |
| `SituationQueryService` | Query SPI for historical situation data — `history()` (3 overloads: tenant, situation, correlation key), `triggerCount()`, `trend()`, `health()`. Backed by append-only event log table. `SituationSource` stays for live state queries. |
| `SituationEventRetention` | Cleanup SPI — `removeEventsBefore(Instant cutoff)`. Implemented by JPA/InMemory query services. Called by `SituationExpiryJob` via `Instance<SituationEventRetention>`. |
| `TrendResult` | Trend query result — `currentCount`, `baselineCount`, `TrendDirection` (RISING/FALLING/STABLE/INSUFFICIENT_DATA). Static `compute()` factory with rate normalization (ratio thresholds: >1.2 RISING, <0.8 FALLING). |
| `TenantHealth` | Per-tenant aggregate — `tenancyId`, `windowStart`, `windowEnd`, `totalEvents`, `List<SituationSummary>`. |
| `SituationSummary` | Per-situation aggregate within a health query — `situationId`, `eventCount`, `triggerCount`, `lastEvent`. |

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
instances via a `type` discriminator. Supports `type: naive-bayes` (Bayesian classification) and
`type: expression-rules` (ordered boolean condition→signal rules, first match wins). All ganglion
types support optional `evidenceTemplates` — map of `{expression, language}` entries evaluated against
`CloudEvent` at detection time, merged into `DetectionResult.evidence()`. Expression-rules ganglia
automatically include `matchedRuleIndex` in evidence and support per-rule `evidenceTemplates`
on individual rules. Optional per-rule `confidenceExpression` (`{expression, language}`) computes
confidence dynamically from event data — static `confidence` is the fallback on null/error/non-numeric;
finite out-of-range values clamp to [0.0, 1.0]; Infinity/NaN fall back to static.
NaiveBayes ganglia support `outcomeEvidenceTemplates` — per-outcome evidence
keyed by outcome name, evaluated for the winning outcome. `signal` and `confidence` required per rule,
validated at parse time (0.0–1.0 range, valid enum). `otherwise` must be last rule if present.
All `{expression, language}` parsing consolidated via shared `parseExpressionEntry()`. Numeric
YAML values coerced via `Number.doubleValue()`. Expression compilation and ganglion construction handled
by `SituationDefinitionRegistry` during three-phase startup. YAML ganglia coexist with CDI-declared
ganglia — duplicate `ganglionId` is a startup error.

### YAML Situation Templates (runtime/)

`YamlSituationDefinitionProvider` supports reusable situation templates with typed parameters.
Templates defined in `templates:` section (parsed before `ganglia:` and `situations:`). Built-in
templates loaded from `META-INF/ras-situation-templates.yaml` before consumer YAML. Consumers
instantiate via `fromTemplate:` + `parameters:`. Resolution happens at parse time — registry sees
fully resolved `SituationRegistration` objects. `${param}` placeholders support whole-value typed
substitution (int/double/list preserved) and substring interpolation. Consumer overrides deep-merge
into resolved template. Identity fields (`situationId`, `eventTypes`) implicitly available as
parameter values. Templates can optionally bundle `ganglia:` section for parameterised ganglion
descriptors. Built-in templates: `streak-breach`, `threshold-crossing`, `count-accumulation`,
`rate-breach`. Consumer templates with same `id` replace built-in entirely.

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
