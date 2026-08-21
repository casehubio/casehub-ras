# casehub-ras — Contributor Guide

> Platform internals for RAS contributors — architecture, full module details, runtime mechanics, and extension points.

**GitHub:** [casehubio/casehub-ras](https://github.com/casehubio/casehub-ras)

---

## Internal Architecture

### Routing Model — Definition-Driven (Model B)

The engine owns situation routing. Ganglia evaluate — they do not choose which situation an event belongs to. `SituationDefinition.eventTypes` is the routing key; `ChainMode` identifies participating ganglia; `Ganglion.handledEventTypes()` is a capability declaration for startup validation only. A situation instance is identified by the tuple `(situationId, correlationKey, tenancyId)`. `correlationKey` defaults to `CloudEvent.getSubject()` or `"_singleton"` when null.

### RasEngine (Entry Point)

`@ApplicationScoped` CDI bean. Observes `CloudEvent` CDI async events via `@ObservesAsync`. For each event: extracts `tenancyid` extension (skips events without it), looks up matching `SituationRegistration`s by event type from `SituationDefinitionRegistry`, applies `EventFilter` (errors treated as pass-through), extracts correlation key (expression errors fall back to default extractor), delegates to `SituationEvaluator`.

### SituationEvaluator — Two-Phase Processing

Per event:
1. **Phase 1 — Detect** (never retried): calls `ganglion.detect()` for all relevant ganglia. Per-ganglion error isolation — one ganglion's failure is logged and skipped, remaining ganglia still evaluate.
2. **Phase 2 — Apply + persist** (retried on `SituationConflictException` up to `ras.evaluator.max-conflict-retries=3`): applies detections to context, evaluates trigger policy (returns `PolicyDecision`), executes decision.

Decisions — 6 outcomes:
- `TRIGGER` — fires `CaseTrigger` (or `SituationChangeEvent` for `NotifyOnly`), then removes the situation. Uses bifurcated claim path: new entities save-before-claim, existing entities claim-before-save.
- `TRIGGER_AND_CONTINUE` — fires `CaseTrigger` then keeps the situation. Uses save-first flow (no bifurcation). Resets trigger claim after fire. Compacts ganglia for persistent situations.
- `CONTINUE_ACCUMULATING` — optionally compacts (persistent situations only), then saves.
- `SUPPRESS` — removes the situation, fires `SituationChangeEvent` with `SUPPRESSED` change type and policy metadata. Metric: `ras.engine.situations.suppressed`.
- `DISCARD` — removes situation, fires `SituationChangeEvent` with `DISCARDED`.
- `RESOLVE` — removes situation without creating a case, fires `SituationChangeEvent` with `RESOLVED`.

Policy metadata flows through the entire trigger pipeline: `PolicyDecision.metadata()` is passed to `SituationChangeEvent` and merged into `CaseTriggerConfig.baseCaseData` for case creation.

Handles event reorder buffers (TreeMap-based, drains when watermark advances past `eventBufferDelay`) and correlation window expiry.

### Clustered Conflict Handling

`SituationEvaluator.processEvent()` is two-phase: Phase 1 detects once (ganglia mutate internal state), Phase 2 retries read-modify-write on `SituationConflictException`. `JpaSituationStore` uses two-layer conflict detection: application-level `storeVersion` comparison (non-overlapping transactions) + Hibernate `@Version` OLE/constraint violation (overlapping transactions). Max retries configurable via `ras.evaluator.max-conflict-retries` (default 3). `InMemorySituationStore` is unaffected — per-key `synchronized` locks prevent concurrent access within a single JVM.

### Duplicate Trigger Prevention

TRIGGER path uses `SituationStore.tryClaimTrigger()` for exactly-once trigger execution across clustered JVMs. `tryClaimTrigger` atomically stamps `lastTriggered` and increments `triggerCount` alongside the `policyTriggered` CAS — trigger metadata is store-managed, not written by `save()`. Bifurcated claim path for TRIGGER: new entities use save-before-claim, existing entities use claim-before-save. TRIGGER_AND_CONTINUE uses save-first flow (no bifurcation). Entity removal is deferred after successful trigger — the `policyTriggered=true` entity guards against duplicate triggers from retrying losers, cleaned up by `SituationExpiryJob` after a configurable guard period (`ras.evaluator.trigger-guard-period`, default PT1M). On trigger failure, `resetTriggerClaim()` clears the flag for retry.

### Ganglion Error Isolation

`SituationEvaluator.runDetection()` wraps each `ganglion.detect()` call in a per-ganglion try-catch. One ganglion's failure (storage error, rule engine crash) is logged and skipped — remaining ganglia still evaluate and produce partial results. Metrics: `ras.evaluator.ganglion.detect_failed`, `ras.evaluator.ganglion.compact_failed`, `ras.evaluator.ganglion.close_failed`.

### Persistent Situation Compaction

For persistent situations (`correlationWindow = null`), `SituationEvaluator` calls `Ganglion.compact()` on each referenced ganglion after every `CONTINUE_ACCUMULATING` and `TRIGGER_AND_CONTINUE` decision. Windowed situations skip compaction. Compaction errors are logged and skipped per ganglion.

### Dynamic Situation Registration

`SituationDefinitionRegistry` supports runtime registration and deregistration via `register(SituationRegistration)` and `deregister(String situationId)`. Thread-safe via copy-on-write `RegistrySnapshot` — one `volatile` swap per mutation, lock-free reads on `findByEventType()`. Ganglia remain static (CDI beans at startup); only situation definitions are dynamic.

For persistent situations (`correlationWindow=null`), consuming apps must call `SituationStore.removeAllForSituation()` AND `GanglionStateStore.removeForSituation()` before deregistering to avoid orphaned entries.

### SituationDefinitionRegistry — Three-Phase Construction

`@ApplicationScoped` CDI bean. Constructor phases:
1. **Descriptor ganglia** — iterates all `SituationDefinitionProvider.ganglionDescriptors()`, constructs `NaiveBayesGanglion` or `ExpressionRulesGanglion` instances. If a descriptor has `evidenceTemplates`, wraps the ganglion in `EvidenceExtractingGanglion` decorator. Duplicate ganglionId is a fatal error.
2. **CDI ganglia** — adds CDI-discovered `Ganglion` beans. Duplicate with a descriptor ganglion is a fatal error.
3. **Situation registrations** — iterates all `SituationDefinitionProvider.registrations()`, validates each definition against registered ganglia (event type overlap check), compiles expressions. Duplicate situationId across providers is a fatal error.

Expression compilation: `StringExpressionEvaluator` instances (`JQExpressionEvaluator`, `MvelExpressionEvaluator`) are compiled via `ExpressionEngineRegistry`. If the engine is missing, fails fast with "add casehub-platform-expression to the classpath". JQ results are unwrapped via `JqResultUnwrapper` for non-Boolean/non-List return types.

### Event Buffer Flush

`EventBufferFlushJob` runs every 1 second (`@Scheduled(every = "PT1S")`), calls `SituationEvaluator.flushIdleBuffers()` which drains all idle reorder buffers. Buffer is idle when wall-clock time has advanced past the buffer's watermark + delay.

### Situation Expiry

`SituationExpiryJob` runs every 5 minutes (`@Scheduled(every = "PT5M")`). Four cleanup phases:
1. **Triggered guard cleanup** — `store.removeTriggeredBefore(guardCutoff)` removes `policyTriggered=true` entities older than `ras.evaluator.trigger-guard-period` (default PT1M).
2. **Expired situation cleanup** — `store.removeExpired(cutoff)` removes windowed situations past the max correlation window.
3. **Orphaned resource cleanup** — iterates all `OrphanedResourceCleaner` CDI beans, calls `removeOrphaned()`. Errors logged and skipped per cleaner.
4. **Event history cleanup** — if `SituationEventRetention` is available, calls `removeEventsBefore(cutoff)` with `ras.event-history.retention` (default P30D).

### Feedback Loop — Sense-Decide-Act-Learn

Three-layer architecture: ingestion, analysis, application. All feedback state is tenant-scoped.

**Ingestion:** `OutcomeRecorder` implements `CaseOutcomeObserver` (from `casehub-engine-api`). Extracts `situationId`, `correlationKey` from `CaseOutcomeEvent.caseFileSnapshot()` (populated by `DefaultCaseTrigger`). Classifies outcome via `FeedbackConfig.classify()` and records to `OutcomeLedger`. Errors swallowed (best-effort).

**Analysis:** `FeedbackAnalyzer` queries `OutcomeLedger.statistics()` and `OutcomeLedger.ganglionStatistics()` within the retention window. Returns `OutcomeStatistics` / `Map<String, GanglionOutcomeStatistics>`. Both implement `QualityMetrics` (shared `precision()`, `noiseRate()` computation).

**Application:** `FeedbackUpdateJob` (`@Scheduled(every = "${ras.feedback.update-interval:PT5M}")`). Per situation with feedback config, per tenant:
1. Records situation-level metrics via `FeedbackMetrics` (precision, noise rate, outcome totals)
1b. Records per-ganglion metrics via `FeedbackMetrics.recordGanglionStatistics()` — `ras.feedback.ganglion.precision` and `ras.feedback.ganglion.noise_rate` gauges per `(ganglion_id, situation_id, tenancy_id)`. Only counts positive-signal (DETECTED/WEAK) contributions.
2. If `tuningEnabled`:
   - **Threshold adjustment** — for `ChainMode.Threshold` situations, calls `FeedbackTuningStrategy.adjustThreshold()` and applies via `FeedbackState.applyThresholdOverride()`. `SituationEvaluator` uses `FeedbackState.effectiveThreshold()` to construct an adjusted `ChainMode.Threshold` before policy evaluation.
   - **Prior recalibration** — for NaiveBayes ganglia with `outcomeGroundTruth`, maps case outcome labels to NaiveBayes outcome indices, calls `FeedbackTuningStrategy.adjustPriors()` (Laplace-smoothed blend), applies via `FeedbackState.applyPriorOverride()` (converts raw→log at the boundary). `NaiveBayesGanglion` uses `FeedbackState.adjustedLogPriors()` as initial priors for new situation instances.
3. Runs retention cleanup via `OutcomeLedger.removeRecordsBefore()`

**Suppression** (always active, even without tuning): `SituationEvaluator` checks `SuppressionStrategy.shouldSuppress()` before detection. `DefaultSuppressionStrategy` suppresses when the correlation key's last noise dismissal is within `suppressionCooldown`. Metric: `ras.feedback.suppressions_total`.

**FeedbackState** — `@ApplicationScoped`, two `ConcurrentHashMap` maps keyed by `(id, tenancyId)`. Holds threshold overrides and log-space prior overrides. `DefaultRasTriggerPolicy` is NOT modified — purity preserved.

**Strategy SPIs** — `SuppressionStrategy` and `FeedbackTuningStrategy` are pluggable. Default implementations are `@DefaultBean`.

---

## Full Module Details

### api/ — `casehub-ras-api`

Core SPIs and domain types.

**SPIs:** `Ganglion`, `SituationStore`, `GanglionStateStore`, `CaseTrigger`, `RasTriggerPolicy`, `CaseInputContributor`, `OrphanedResourceCleaner`, `EventFilter`, `CorrelationKeyExtractor`, `SituationDefinitionProvider`, `SituationQueryService`, `SituationSource`, `SituationEventRetention`, `OutcomeLedger`, `SuppressionStrategy`, `FeedbackTuningStrategy`.

**Records:** `SituationDefinition`, `SituationContext`, `SituationRegistration`, `DetectionResult`, `CaseTriggerConfig`, `GanglionState`, `GanglionStateKey`, `ActiveSituation`, `SituationChangeEvent`, `SituationEvent`, `PolicyDecision`, `TrendResult`, `TenantHealth`, `SituationSummary`, `TimestampedDetection`, `FeedbackConfig`, `OutcomeRecord`, `OutcomeStatistics`.

**Sealed types:** `ChainMode` (7 variants: And, Or, Threshold, Sequence, Count, Streak, Rate), `TriggerMode` (FireOnce, Repeating), `TriggerAction` (CreateCase, NotifyOnly), `GanglionDescriptor` (NaiveBayes, ExpressionRules).

**Enums:** `TriggerDecision` (6 values: TRIGGER, TRIGGER_AND_CONTINUE, CONTINUE_ACCUMULATING, DISCARD, RESOLVE, SUPPRESS), `DetectionSignal` (NOISE, ANTI, WEAK, DETECTED), `OutcomeClassification` (NOISE, CONFIRMED, NEUTRAL).

**Other:** `JavaSwitchGanglion` (abstract base class), `DefaultCorrelationKeyExtractor`, `SituationConflictException`, `GanglionStateConflictException`, `SuppressionMetadataKeys` (constants: TIER, DISMISSAL_RATE, MATCH_COUNT, AVERAGE_SIMILARITY).

Depends on CloudEvents SDK, `casehub-platform-api` (for `CloudEvent`, `ExpressionEvaluator`). No CDI. Publishes test-jar with `AbstractGanglionContractTest`, `AbstractGanglionStateStoreContractTest`, `AbstractSituationStoreContractTest`, `AbstractSituationQueryServiceContractTest`, `AbstractOutcomeLedgerContractTest`.

### runtime/ — `casehub-ras`

CDI runtime. All beans are `@ApplicationScoped`.

**Core beans:**
- `RasEngine` — CloudEvent observer (`@ObservesAsync`). Entry point for all event processing.
- `SituationEvaluator` — two-phase detection + OCC retry. Per-situation-instance `synchronized` locks via `ConcurrentHashMap<SituationInstanceKey, Object>`. Event reorder buffers via `ConcurrentHashMap<SituationInstanceKey, EventReorderBuffer>`.
- `SituationDefinitionRegistry` — three-phase constructor (descriptor ganglia, CDI ganglia, situation registrations). Copy-on-write `RegistrySnapshot` for thread-safe reads. Compiles `StringExpressionEvaluator` instances into `CompiledExpression` via `ExpressionEngineRegistry`.
- `DefaultRasTriggerPolicy` (`@DefaultBean`) — evaluates all 7 `ChainMode` variants. Maps `TriggerMode.FireOnce` to `TRIGGER`, `TriggerMode.Repeating` to `TRIGGER_AND_CONTINUE` (with cooldown check). Qualifying detections are those with signal >= `WEAK`.
- `DefaultCaseTrigger` — resolves `CaseHub` bean by `(namespace, name, version)` match, calls `startCase()` with built input data. Supports dynamic case data expressions evaluated against `SituationContextExpressionContext`.
- `DefaultSituationSource` — delegates to `SituationStore.findActive()`.
- `RasEndpointRegistration` — registers `ras/situations` service endpoint on startup via `EndpointRegistry`.
- `RasMetrics` — centralised Micrometer metrics. Optional: degrades to no-ops when no `MeterRegistry` is available (via `Instance<MeterRegistry>`).

**Ganglion implementations:**
- `NaiveBayesGanglion` — incremental Naive Bayes with log-posterior arithmetic. Persists state via `GanglionStateStore`. OCC retry (up to 3 attempts) on `GanglionStateConflictException`. Supports per-outcome evidence templates. Implements `compact()` (retains only latest detection per ganglionId) and `close()` (removes state).
- `ExpressionRulesGanglion` — package-private. Ordered rules evaluated top-down, first match wins. Null `when` = catch-all. Dynamic confidence via `confidenceExpression` with fallback to static `confidence`. Per-rule evidence templates. Auto-evidence: `matchedRuleIndex` (integer, -1 for no match).
- `EvidenceExtractingGanglion` — package-private decorator. Wraps any `Ganglion` with expression-based evidence templates. Applied by `SituationDefinitionRegistry` when a descriptor has `evidenceTemplates`.

**Expression context builders:**
- `CloudEventExpressionContext` — builds `Map<String, Object>` from CloudEvent for expression evaluation (used by event filters, correlation key expressions, evidence templates).
- `SituationContextExpressionContext` — builds `Map<String, Object>` from `SituationContext` for dynamic case data evaluation.

**Other:**
- `YamlSituationDefinitionProvider` — YAML parser for situation definitions + ganglion descriptors. Supports templates with `${param}` substitution, deep merge of overrides, built-in templates from `META-INF/ras-situation-templates.yaml`.
- `ExpressionFeatureExtractor` — `NaiveBayesFeatureExtractor` implementation that evaluates compiled expressions against CloudEvent context.
- `NaiveBayesConfig`, `NaiveBayesSignalMapping`, `FeatureLikelihood` — configuration records for NaiveBayes ganglion.
- `EventReorderBuffer` — TreeMap-based per-situation reorder buffer.
- `EventBufferFlushJob` — `@Scheduled(every = "PT1S")`, flushes idle reorder buffers.
- `SituationExpiryJob` — `@Scheduled(every = "PT5M")`, four-phase cleanup (triggered guard, expired, orphans, event history).
- `InMemoryGanglionStateStore` (`@DefaultBean`) — ConcurrentHashMap-backed. Zero config.
- `InMemoryOutcomeLedger` (`@DefaultBean`) — ConcurrentHashMap-backed. UNIQUE(case_id) dedup via `Set<UUID>`.
- `JqResultUnwrapper` — unwraps JQ expression results (which return `List`) into expected types.

**Feedback loop beans:**
- `OutcomeRecorder` — implements `CaseOutcomeObserver`. Extracts `situationId`/`correlationKey` from `caseFileSnapshot()`, classifies via `FeedbackConfig`, records to `OutcomeLedger`.
- `FeedbackAnalyzer` — queries `OutcomeLedger.statistics()` within retention window.
- `FeedbackUpdateJob` — `@Scheduled(every = "${ras.feedback.update-interval:PT5M}")`. Iterates situations with feedback config, applies tuning strategies (threshold + priors), runs retention cleanup.
- `FeedbackState` — `@ApplicationScoped`. Two `ConcurrentHashMap` maps for threshold and log-space prior overrides, keyed by `(id, tenancyId)`.
- `FeedbackMetrics` — Micrometer gauges for precision, noise rate, outcomes total; counters for threshold/prior adjustments and retention cleanup.
- `DefaultSuppressionStrategy` (`@DefaultBean`) — suppresses within cooldown period.
- `DefaultTuningStrategy` (`@DefaultBean`) — noise-rate-driven threshold shift (min 10 outcomes), Laplace-smoothed prior blend (min 5 outcomes).

`casehub-platform-expression` at test scope only — deployers add it to classpath when expressions are needed. Quarkus extension.

### persistence-memory/ — `casehub-ras-persistence-memory`

`InMemorySituationStore` (`@Alternative @Priority(100)`, ConcurrentHashMap-backed). `InMemorySituationQueryService` (`@Alternative @Priority(100)`, CDI `@ObservesAsync` `SituationChangeEvent` capture — records events in-memory for query). Dev/test only. Zero config.

### persistence-jpa/ — `casehub-ras-persistence-jpa`

Production persistence. All beans activate by classpath presence (CDI priority wins over in-memory alternatives).

- `JpaSituationStore` — JPA-backed with dual-layer OCC (application-level `storeVersion` comparison + JPA `@Version`). `SituationEntity` (table `ras_situation`, JSONB detections via `io.hypersistence:hypersistence-utils`).
- `JpaGanglionStateStore` — `GanglionStateEntity` (table `ras_ganglion_state`) with JSONB state + `@Version` optimistic locking. Implements `OrphanedResourceCleaner` for SQL join-based orphan cleanup (LEFT JOIN against `ras_situation`).
- `JpaSituationQueryService` — implements both `SituationQueryService` and `SituationEventRetention`. Queries against `SituationEventEntity` table.
- `SituationEventRecorder` — CDI observer (`@ObservesAsync SituationChangeEvent`), `@Transactional`, best-effort event log capture to `SituationEventEntity`.
- `SituationEntity` — JPA entity, table `ras_situation`. Composite key: `(situationId, correlationKey, tenancyId)`. Columns: JSONB `detections`, `storeVersion`, `policyTriggered`, `lastTriggered`, `triggerCount`, `firstSignal`, `lastSignal`, `version` (JPA `@Version`).
- `SituationEventEntity` — JPA entity, table `ras_situation_event`. Captures situation lifecycle events for historical queries.
- `GanglionStateEntity` — JPA entity, table `ras_ganglion_state`. Composite key: `(ganglionId, situationId, correlationKey, tenancyId)`. JSONB `stateValues`, `@Version`.
- `SituationMapper` — maps between JPA entities and API records.

Flyway migrations V1-V7:
- V1: `ras_situation` table
- V2: `version` column (JPA `@Version`)
- V3: `policy_triggered` column
- V4: trigger lifecycle columns (`last_triggered`, `trigger_count`)
- V5: `ras_ganglion_state` table
- V6: `ras_situation_event` table
- V7: `ras_outcome_record` table (feedback loop)

**Feedback persistence:**
- `JpaOutcomeLedger` — `@ApplicationScoped`, `OutcomeLedger` impl. Native `INSERT ON CONFLICT (case_id) DO NOTHING` for idempotent recording. JPQL for typed queries (`lastNoiseDismissalTime`), native SQL for aggregates and cleanup.
- `OutcomeRecordEntity` — JPA entity, table `ras_outcome_record`. `UNIQUE(case_id)`, `classification` stored as `VARCHAR` via `@Enumerated(EnumType.STRING)`. Indexed for tenant+situation and suppression lookups.

Consumers add `classpath:db/ras/migration` to `quarkus.flyway.locations`.

### ras-drools/ — `casehub-ras-drools`

`DroolsGanglion` — Drools CEP stream-mode engine. Builds `KieBase` with `EventProcessingOption.STREAM` from DRL rules via `KieFileSystem` + `ExecutableModelProject`.

**Session management:** Two session modes: `LONG_LIVED` (stateful, keyed by `DroolsSessionKey(ganglionId, situationId, correlationKey, tenancyId)`, persisted in `DroolsSessionStore`, lazily invalidated on rule reload via `reloadGeneration` counter) and `EPHEMERAL` (new session per event, disposed after detection).

**Clock modes:** `PSEUDO` (event-time driven via `SessionPseudoClock.advanceTime()`, throws `IllegalStateException` on out-of-order events) and `REALTIME` (wall-clock).

**Result collection:** Results produced via `ResultCollectorChannel` (Drools channel named `"results"`). `ResultCollectionStrategy` enum: `HIGHEST_CONFIDENCE` (default), `FIRST_MATCH`, `LAST_WINS`, `ACCUMULATE` (merges evidence, takes max confidence and strongest signal).

**Hot rule reload:** `reload(classpathRules, programmaticRules)` rebuilds `KieBase` and increments `reloadGeneration`. Old `ReleaseId` is removed from `KieRepository`. Existing long-lived sessions are lazily invalidated — `computeIfAbsent` checks generation and creates a new session if stale.

**Extension:** `DroolsObjectExtractor` SPI for domain-specific fact insertion. Each extractor declares `handledEventTypes()` and `extract(CloudEvent)` returns `List<Object>` to insert into the session.

**Error handling:** On session error during detection, EPHEMERAL sessions are disposed; LONG_LIVED sessions have their key removed from the store (forces a fresh session on next event). `DroolsSessionStoreException` triggers remove-and-rethrow.

**Types:** `DroolsGanglionConfig` (record: `ganglionId`, `handledEventTypes`, `sessionMode`, `clockMode`, `classpathRules`, `programmaticRules`, `resultCollectionStrategy`), `DroolsSessionKey` (record: `ganglionId`, `situationId`, `correlationKey`, `tenancyId`), `InMemoryDroolsSessionStore` (default `ConcurrentHashMap`-backed), `DroolsSessionStoreException`.

Drools dependency: version 10.1.0 via `${version.drools}` from casehub-ras POM.

### drools-reliability/ — `casehub-ras-drools-reliability`

`ReliableDroolsSessionStore` — `@ApplicationScoped` `DroolsSessionStore` backed by `drools-reliability-h2mvstore`. ConcurrentHashMap hot cache with generation-based eviction. Corrupt store auto-recovery (catches `MVStoreException`, deletes store file, reopens).

**Persistence:** `STORES_ONLY` persistence strategy with `AFTER_FIRE` safepoints. Creates a uniquely named H2MVStore per `DroolsSessionKey`.

**Orphan cleanup:** Implements `OrphanedResourceCleaner` (cleanerType: `"drools-session"`). Checks each stored key against `SituationStore.find()` — if the parent situation no longer exists, removes the session store.

**Observability:** `DroolsReliabilityMetrics` (Micrometer) — counters for cache hits/misses, store operations, corruption recoveries. `ReliableDroolsSessionStoreHealthCheck` (`@Readiness` MicroProfile health check) — reports store status.

Experimental.

### ras-llm/ — `casehub-ras-llm`

POM-only placeholder. Intended: LLM-based ganglion via `casehub-platform-agent-api` for narrative and ambiguous signal detection. No source directory exists.

### testing/ — `casehub-ras-testing`

Test scope only.

- `MockGanglion` — fixed-result ganglion with call counter. Constructor: `(ganglionId, handledEventTypes, fixedResult)`. Methods: `callCount()`, `reset()`.
- `MockCaseTrigger` — records fired cases in `CopyOnWriteArrayList`. Methods: `firedCases()` (returns `List<FiredCase>`), `reset()`. `FiredCase` record: `(caseId, triggerConfig, context)`.
- `FixedDetectionResult` — static factory methods: `detected(ganglionId, confidence[, evidence])`, `weak(ganglionId, confidence)`, `noise(ganglionId)`, `anti(ganglionId, confidence)`.

---

## Internal SPIs

### SituationStore — situation persistence

```java
interface SituationStore {
    Optional<SituationContext> find(String situationId, String correlationKey, String tenancyId);
    SituationContext save(SituationContext context);
    void remove(String situationId, String correlationKey, String tenancyId);
    int removeExpired(Instant cutoff);
    void removeAllForSituation(String situationId);
    default boolean tryClaimTrigger(String situationId, String correlationKey,
                                    String tenancyId, Instant triggerTime) { return true; }
    default void resetTriggerClaim(String situationId, String correlationKey,
                                   String tenancyId) { }
    default int removeTriggeredBefore(Instant triggerCutoff) { return 0; }
    default List<SituationContext> findActive(String tenancyId) { return List.of(); }
}
```

Implementations: `InMemorySituationStore` (dev/test, `@Alternative @Priority(100)`, ConcurrentHashMap-backed), `JpaSituationStore` (production, dual-layer OCC). Throws `SituationConflictException` on concurrent modification.

### GanglionStateStore — ganglion computation state

```java
interface GanglionStateStore {
    Optional<GanglionState> load(GanglionStateKey key);
    void save(GanglionStateKey key, GanglionState state);
    void remove(GanglionStateKey key);
    void removeForSituation(String situationId);
}
```

Pluggable persistence for numeric accumulation (NaiveBayes log-posteriors). `GanglionStateKey` is a 4-tuple `(ganglionId, situationId, correlationKey, tenancyId)`. `GanglionState` carries `double[] values` + `OptionalLong storeVersion` for OCC. Throws `GanglionStateConflictException` on concurrent modification.

Implementations: `InMemoryGanglionStateStore` (`@DefaultBean` in runtime/, ConcurrentHashMap-backed), `JpaGanglionStateStore` (in persistence-jpa/, JPA entity with `@Version`, also implements `OrphanedResourceCleaner`).

### OrphanedResourceCleaner — derived resource cleanup

```java
interface OrphanedResourceCleaner {
    String cleanerType();
    int removeOrphaned();
}
```

Generic SPI for cleaning up derived resources whose parent situation no longer exists. Discovered via CDI `Instance<OrphanedResourceCleaner>` in `SituationExpiryJob`. Implementations: `JpaGanglionStateStore` (SQL LEFT JOIN-based, cleanerType: `"ganglion-state"`), `ReliableDroolsSessionStore` (`SituationStore.find()` per key, cleanerType: `"drools-session"`). Metric: `ras.expiry.orphans_cleaned` counter tagged by `cleaner_type`.

### CaseTrigger — case creation bridge

```java
interface CaseTrigger {
    UUID fire(CaseTriggerConfig config, SituationContext context);
}
```

`DefaultCaseTrigger` resolves the `CaseHub` CDI bean by `(namespace, name, version)` match against `CaseDefinition`, then calls `startCase()` with input data built from: `baseCaseData` + dynamic case data expressions + correlation metadata (`situationId`, `correlationKey`, `tenancyId`, `detections`) + `CaseInputContributor` outputs.

### RasTriggerPolicy — trigger evaluation

```java
interface RasTriggerPolicy {
    PolicyDecision evaluate(SituationContext context, SituationDefinition definition);
}
```

Returns `PolicyDecision(TriggerDecision decision, Map<String, Object> metadata)`. `DefaultRasTriggerPolicy` (`@DefaultBean`) maps `ChainMode` evaluation + `TriggerMode` to `TriggerDecision`. Qualifying detections are those with signal `isAtLeast(WEAK)`. Threshold mode supports ANTI subtraction (negates confidence for ANTI signals).

### SituationEventRetention — event log cleanup

```java
interface SituationEventRetention {
    int removeEventsBefore(Instant cutoff);
}
```

Implemented by `JpaSituationQueryService`. Called by `SituationExpiryJob` with `ras.event-history.retention` (default P30D).

---

## Metrics Reference

All metrics use Micrometer and degrade to no-ops when `MeterRegistry` is unavailable.

**RasEngine:**
- `ras.engine.events.received` (counter, tag: `event_type`)
- `ras.engine.events.skipped` (counter, tag: `reason`: `no_tenancy_id`, `no_matching_situation`)
- `ras.engine.events.routed` (counter, tags: `situation_id`, `tenancy_id`)
- `ras.events.filtered` (counter, tags: `situation_id`, `tenancy_id`)
- `ras.engine.evaluation.failed` (counter, tags: `situation_id`, `tenancy_id`)
- `ras.engine.situations.suppressed` (counter, tags: `situation_id`, `tenancy_id`)
- `ras.expression.error` (counter, tags: `situation_id`, `expression_point`)

**SituationEvaluator:**
- `ras.evaluator.process_time` (timer, tags: `situation_id`, `tenancy_id`)
- `ras.evaluator.decision` (counter, tags: `situation_id`, `tenancy_id`, `decision`)
- `ras.evaluator.conflict_retries` (counter, tags: `situation_id`, `tenancy_id`)
- `ras.evaluator.retries_exhausted` (counter, tags: `situation_id`, `tenancy_id`)
- `ras.evaluator.context_expired` (counter, tags: `situation_id`, `tenancy_id`)
- `ras.evaluator.ganglion.detect_failed` (counter, tags: `ganglion_id`, `situation_id`)
- `ras.evaluator.ganglion.compact_failed` (counter, tags: `ganglion_id`, `situation_id`)
- `ras.evaluator.ganglion.close_failed` (counter, tags: `ganglion_id`, `situation_id`)
- `ras.evaluator.trigger.claimed` (counter, tags: `situation_id`, `tenancy_id`)
- `ras.evaluator.trigger.race_lost` (counter, tags: `situation_id`, `tenancy_id`)
- `ras.evaluator.trigger.fire_time` (timer, tags: `situation_id`, `tenancy_id`, `trigger_action`)
- `ras.evaluator.trigger.fired` (counter, tags: `situation_id`, `tenancy_id`, `trigger_action`)
- `ras.evaluator.trigger.failed` (counter, tags: `situation_id`, `tenancy_id`, `trigger_action`)
- `ras.evaluator.buffer.events_buffered` (counter, tags: `situation_id`, `tenancy_id`)
- `ras.evaluator.buffers.active` (gauge)

**Registry:**
- `ras.registry.definitions.active` (gauge)

**Expiry:**
- `ras.expiry.triggered_cleaned` (counter)
- `ras.expiry.expired_cleaned` (counter)
- `ras.expiry.orphans_cleaned` (counter, tag: `cleaner_type`)
- `ras.expiry.event_log_cleaned` (counter)

**Expression errors (per-ganglion):**
- `ras.expression.error` (counter, tags vary: `ganglion_id`, `rule_index`, `expression_point`, `evidence_key`, `outcome`)

---

## Core Types Reference

| Type | Purpose |
|------|---------|
| `CloudEvent` | Input — from `io.cloudevents:cloudevents-core` via `casehub-platform-api` |
| `DetectionResult` | Ganglion output — `ganglionId`, `confidence` (0.0-1.0), `signal`, `evidence` map |
| `DetectionSignal` | Signal strength enum — `NOISE`, `ANTI`, `WEAK`, `DETECTED` (ascending ordinal) |
| `TimestampedDetection` | Wraps `DetectionResult` + `Instant eventTime` |
| `SituationContext` | Accumulated state — `situationId`, `correlationKey`, `tenancyId`, `firstSignal`, `lastSignal`, `detections`, `storeVersion`, `lastTriggered`, `triggerCount` |
| `SituationDefinition` | Full situation spec — `situationId`, `eventTypes`, `correlationWindow`, `eventBufferDelay`, `chainMode`, `triggerAction`, `triggerMode`, `correlationKeyExpression`, `eventFilter`, `dynamicCaseData` |
| `SituationRegistration` | Binds definition + `CorrelationKeyExtractor` + `EventFilter` + compiled dynamic data |
| `PolicyDecision` | `TriggerDecision` + `Map<String, Object> metadata` |
| `SituationConflictException` | Thrown by `SituationStore.save()` on concurrent modification — evaluator catches and retries |
| `GanglionStateConflictException` | Thrown by `GanglionStateStore.save()` on concurrent modification |
| `SituationChangeEvent` | CDI event — `tenancyId`, `situationId`, `correlationKey`, `ChangeType`, `SituationContext`, `metadata`. ChangeType: `TRIGGERED`, `RESOLVED`, `DISCARDED`, `SUPPRESSED`, `DISMISSED` |
| `SituationEvent` | Event log record — situation coordinates, `changeType`, `eventTime`, `firstSeen`, `confidence`, `detectionCount`, `triggerCount`, `evidence`, `metadata` |
| `TrendResult` | Trend query result — `currentCount`, `baselineCount`, `TrendDirection` (RISING/FALLING/STABLE/INSUFFICIENT_DATA) |
| `TenantHealth` | Per-tenant aggregate — `tenancyId`, `windowStart`, `windowEnd`, `totalEvents`, `List<SituationSummary>` |
| `SituationSummary` | Per-situation within health — `situationId`, `eventCount`, `triggerCount`, `lastEvent` |
| `ActiveSituation` | Read-only projection — `situationId`, `correlationKey`, `tenancyId`, `confidence`, `evidence`, `since`, `lastSignal`, `triggerCount` |
| `SuppressionMetadataKeys` | Constants: `TIER`, `DISMISSAL_RATE`, `MATCH_COUNT`, `AVERAGE_SIMILARITY` |

---

## Depended On By

| Repo | What it uses |
|------|-------------|
| Application-tier repos that need situational awareness | `casehub-ras-api` for Ganglion SPI; runtime + persistence module for CDI activation |
| `casehub-iot` | RAS integration for IoT event detection — uses `RasTriggerPolicy`, `SituationDefinitionRegistry.findBySituationId()` |

---

## Current State

- All modules on main: API, runtime, ras-drools, drools-reliability, persistence-memory, persistence-jpa, testing. `ras-llm` scaffolded (POM only, no source directory).
- 4 ganglion implementations: `JavaSwitchGanglion` (api/), `NaiveBayesGanglion` (runtime/), `ExpressionRulesGanglion` (runtime/), `DroolsGanglion` (ras-drools/). Plus `EvidenceExtractingGanglion` decorator (runtime/).
- `NaiveBayesGanglion` with incremental Bayesian classification, `GanglionStateStore` persistence, OCC retry, per-outcome evidence templates.
- `ExpressionRulesGanglion` with ordered boolean condition-to-signal rules, dynamic confidence expressions, per-rule evidence templates.
- `DroolsGanglion` supports CEP stream mode with long-lived sessions, hot rule reload, and classic kie-api (Drools 10.1.0).
- `ReliableDroolsSessionStore` backed by H2MVStore with Micrometer metrics, MicroProfile health check, and corruption auto-recovery.
- `GanglionStateStore` SPI with InMemory and JPA implementations (full OCC).
- JPA persistence with dual-layer OCC (application + JPA `@Version`) for both situations and ganglion state. Flyway V1-V6.
- 7 ChainMode variants: And, Or, Threshold, Sequence, Count, Streak, Rate.
- 6 TriggerDecision outcomes: TRIGGER, TRIGGER_AND_CONTINUE, CONTINUE_ACCUMULATING, DISCARD, RESOLVE, SUPPRESS.
- 5 SituationChangeEvent.ChangeType values: TRIGGERED, RESOLVED, DISCARDED, SUPPRESSED, DISMISSED.
- `PolicyDecision` record wrapping `TriggerDecision` + metadata, flowing through to CDI events and case input data.
- Trigger lifecycle: `TriggerMode` (FireOnce/Repeating with cooldown), `TriggerAction` (CreateCase/NotifyOnly).
- `CaseInputContributor` SPI for domain-specific case seeding at trigger time.
- YAML-based situation definitions with expression support (JQ, MVEL), ganglion descriptors, evidence templates, dynamic confidence, and situation templates.
- `SituationQueryService` SPI for historical situation data with trend and health queries. JPA implementation with event log and retention.
- Dynamic situation registration/deregistration at runtime via copy-on-write snapshot.
- API module publishes test-jars with contract tests for Ganglion, GanglionStateStore, SituationStore, and SituationQueryService.
- Comprehensive Micrometer metrics (optional, degrade to no-ops) covering engine, evaluator, triggers, expiry, expressions, and buffers.

---

## Design Documents

- Original: `docs/superpowers/specs/2026-06-12-casehub-ras-design.md`
- Epic 1 API: `docs/superpowers/specs/2026-06-18-epic1-core-ras-api-design.md`
- Epic 2 Runtime: `docs/superpowers/specs/2026-06-25-epic2-ras-runtime-design.md`
- Epic 3 JavaSwitchGanglion + NaiveBayesGanglion: `docs/superpowers/specs/2026-06-26-epic3-java-switch-naive-bayes-ganglion-design.md`
- Epic 4 DroolsGanglion: `docs/superpowers/specs/2026-06-21-epic4-drools-ganglion-design.md`
- Result collection + test gaps: `docs/superpowers/specs/2026-06-22-drools-result-collection-and-test-gaps.md`
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

---

## Key Rules

- `testing/` is never compile or runtime scope — test only
- Ganglion implementations activate by classpath presence
- `LlmGanglion` always runs async on slow path — never blocks fast detection path
- All `SituationContext` is tenancy-scoped — no cross-tenant situation accumulation
- Platform stream modules have NO dependency on `casehub-ras-api` — they fire `CloudEvent` from `casehub-platform-api`
- `casehub-ras-api` depends on `casehub-platform-api` (for `CloudEvent` type and `ExpressionEvaluator`) — correct direction: integration to foundation
- casehub-ras never imports Kafka, AMQP, Camel, or any transport library
- `JavaSwitchGanglion` lives in api/ (abstract extension point, zero deps) — `NaiveBayesGanglion` and `ExpressionRulesGanglion` live in runtime/ (concrete implementations with internal state)
- Expression engines (`casehub-platform-expression`) are test-scope in runtime — deployers add to classpath when YAML expressions are needed
- `PolicyDecision.metadata()` flows through to both `SituationChangeEvent.metadata()` and `CaseTriggerConfig.baseCaseData()` — this is the mechanism for suppression metadata, IoT metadata, and any custom policy data to reach downstream consumers
