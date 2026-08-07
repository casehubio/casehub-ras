# casehub-ras — Consumer Guide

> Situational awareness and reactive case creation — observe CloudEvents, detect patterns via pluggable ganglia, trigger cases when thresholds are crossed.

**GitHub:** [casehubio/casehub-ras](https://github.com/casehubio/casehub-ras)
**Tier:** Foundation

---

## Purpose

Reticular Activating System — observes CloudEvent CDI async events produced by platform stream modules (Kafka, AMQP, webhook, Camel), routes them to pluggable detection strategies (Ganglia), correlates composite events across time windows, and triggers case creation via casehub-engine when a situation threshold is crossed.

The biological metaphor is deliberate: a ganglion is a neural cluster that detects a specific signal pattern. Multiple ganglia compose via `ChainMode` (AND, OR, threshold, sequence, count, streak, rate) to detect complex situations from simple parts.

---

## Module Structure

| Module | artifactId | When to use |
|--------|-----------|-------------|
| `api/` | `casehub-ras-api` | Always — core SPIs, domain types, `JavaSwitchGanglion` base class. No CDI. |
| `runtime/` | `casehub-ras` | Always — CDI runtime, `RasEngine`, `NaiveBayesGanglion`, `ExpressionRulesGanglion`, `EvidenceExtractingGanglion`, YAML definitions. Quarkus extension. |
| `persistence-memory/` | `casehub-ras-persistence-memory` | Dev/test — `@Alternative @Priority(100)`, ConcurrentHashMap-backed. Zero config. |
| `persistence-jpa/` | `casehub-ras-persistence-jpa` | Production — JPA-backed with dual-layer OCC. Flyway V1-V7 (V7: ras_outcome_record). Add `classpath:db/ras/migration` to `quarkus.flyway.locations`. |
| `ras-drools/` | `casehub-ras-drools` | Optional — Drools CEP stream-mode ganglion with long-lived/ephemeral sessions. |
| `drools-reliability/` | `casehub-ras-drools-reliability` | Optional — persistent `DroolsSessionStore` backed by H2MVStore. Experimental. |
| `ras-llm/` | `casehub-ras-llm` | Optional — LLM-based ganglion (POM placeholder, no source yet). |
| `testing/` | `casehub-ras-testing` | Test scope only — `MockGanglion`, `MockCaseTrigger`, `FixedDetectionResult`. |

---

## Key Consumer APIs and SPIs

### Ganglion — detection strategy

The central SPI. Each ganglion handles specific CloudEvent types and produces a `DetectionResult` per event.

```java
interface Ganglion {
    String ganglionId();
    Set<String> handledEventTypes();
    DetectionResult detect(CloudEvent event, SituationContext context);
    default SituationContext compact(SituationContext context) { ... }
    default void close(String situationId, String correlationKey, String tenancyId) { }
}
```

Four built-in implementations:
- **`JavaSwitchGanglion`** (api/) — abstract base for pure-Java detection. Subclass and override `evaluate()`. Provides convenience methods: `detected()`, `weak()`, `noise()`, `anti()`. Preferred path for simple stateless detection.
- **`NaiveBayesGanglion`** (runtime/) — incremental Naive Bayes classifier with log-posterior persistence via `GanglionStateStore`. Configured via `NaiveBayesConfig` or YAML `GanglionDescriptor.NaiveBayes`. Supports per-outcome evidence templates. OCC retry on `GanglionStateConflictException` (up to 3 retries).
- **`ExpressionRulesGanglion`** (runtime/) — ordered boolean condition-to-signal rules using JQ or MVEL expressions. First matching rule wins; null `when` acts as an `otherwise` catch-all. Supports per-rule evidence templates and dynamic confidence expressions.
- **`DroolsGanglion`** (ras-drools/) — Drools CEP stream-mode engine with long-lived/ephemeral sessions, pseudo/realtime clock, hot rule reload.

**Design invariant:** `DetectionResult` must be portable — it may be applied to a different `SituationContext` than the one passed to `detect()` (e.g. after an OCC retry). Implementations must not base decisions on accumulated `context.detections()`.

### CaseInputContributor — domain-specific case seeding

```java
interface CaseInputContributor {
    Map<String, Object> contribute(CaseTriggerConfig config, SituationContext context);
}
```

Discovered via CDI. Output merged into case input data at trigger time (after `baseCaseData` and correlation metadata). Enables domain-specific case seeding without modifying RAS internals.

### SituationDefinitionProvider — situation registration

```java
interface SituationDefinitionProvider {
    List<SituationRegistration> registrations();
    default List<GanglionDescriptor> ganglionDescriptors() { return List.of(); }
}
```

Register situation definitions programmatically. `YamlSituationDefinitionProvider` in runtime/ is the default classpath-based implementation. `ganglionDescriptors()` returns declarative ganglion definitions (NaiveBayes or ExpressionRules) that the registry constructs into live `Ganglion` instances at startup.

### CorrelationKeyExtractor — custom correlation

```java
@FunctionalInterface
interface CorrelationKeyExtractor {
    String extract(CloudEvent event);
}
```

Default (`DefaultCorrelationKeyExtractor`) returns `CloudEvent.getSubject()` or `"_singleton"` when null. Implement for custom correlation logic. Can also be overridden by YAML `correlationKey` expression (expression wins over programmatic extractor).

### EventFilter — pre-detection event filtering

```java
@FunctionalInterface
interface EventFilter {
    boolean accepts(CloudEvent event);
}
```

Per-situation event filter. If `accepts()` returns false, the event is skipped for that situation. Errors are logged and treated as pass-through (event is not filtered out). Can be set programmatically in `SituationRegistration` or via YAML `eventFilter` expression.

### SituationSource — query active situations

```java
interface SituationSource {
    List<ActiveSituation> activeSituations(String tenancyId);
}
```

Read-only projections of active situations for external consumers. `ActiveSituation` record: `situationId`, `correlationKey`, `tenancyId`, `confidence`, `evidence`, `since`, `lastSignal`, `triggerCount`.

### SituationQueryService — historical queries

```java
interface SituationQueryService {
    List<SituationEvent> history(String tenancyId, Instant from, Instant to);
    List<SituationEvent> history(String tenancyId, String situationId, Instant from, Instant to);
    List<SituationEvent> history(String tenancyId, String situationId,
                                 String correlationKey, Instant from, Instant to);
    long triggerCount(String tenancyId, String situationId, Instant from, Instant to);
    TrendResult trend(String tenancyId, String situationId,
                      Duration window, Duration baseline, Instant asOf);
    TenantHealth health(String tenancyId, Duration window, Instant asOf);
}
```

Query SPI for historical situation data — trends, trigger counts, tenant health. `TrendResult.TrendDirection`: `RISING`, `FALLING`, `STABLE`, `INSUFFICIENT_DATA` (computed from rate ratio with 0.8/1.2 thresholds). Implementations: `InMemorySituationQueryService` (dev/test, CDI event capture), `JpaSituationQueryService` (production, persistent event log).

### RasTriggerPolicy — trigger evaluation

```java
interface RasTriggerPolicy {
    PolicyDecision evaluate(SituationContext context, SituationDefinition definition);
}
```

Returns a `PolicyDecision` wrapping a `TriggerDecision` plus optional metadata. The `DefaultRasTriggerPolicy` (`@DefaultBean`) evaluates the `ChainMode` and maps to a `TriggerDecision` based on `TriggerMode`. Consumers can override via CDI to implement custom trigger logic (suppression, adaptive thresholds).

---

## Core Domain Types

| Type | Purpose |
|------|---------|
| `DetectionResult` | Ganglion output — `ganglionId`, `confidence` (0.0-1.0), `signal`, `evidence` map |
| `DetectionSignal` | Signal strength enum — `NOISE`, `ANTI`, `WEAK`, `DETECTED` (ascending ordinal). `isAtLeast(threshold)` method. |
| `SituationContext` | Accumulated detections for a `(situationId, correlationKey, tenancyId)` tuple — immutable record with `firstSignal`, `lastSignal`, `storeVersion`, `lastTriggered`, `triggerCount` |
| `SituationDefinition` | Declared situation — `eventTypes`, `correlationWindow`, `eventBufferDelay`, `chainMode`, `triggerAction`, `triggerMode`, `correlationKeyExpression`, `eventFilter`, `dynamicCaseData` |
| `SituationRegistration` | Binds a `SituationDefinition` to its `CorrelationKeyExtractor`, `EventFilter`, and compiled dynamic case data expressions |
| `ChainMode` | Sealed — 7 composition strategies: `And(requiredGanglia)`, `Or(ganglia)`, `Threshold(ganglia, minConfidence)`, `Sequence(orderedGanglia)`, `Count(ganglionId, requiredCount)`, `Streak(ganglionId, requiredCount)`, `Rate(ganglia, minRate, windowSize)` |
| `TriggerAction` | Sealed — `CreateCase(CaseTriggerConfig)` or `NotifyOnly()` |
| `TriggerMode` | Sealed — `FireOnce()` or `Repeating(Duration cooldown)` |
| `PolicyDecision` | Wraps `TriggerDecision` + `Map<String, Object> metadata` — metadata flows through to `SituationChangeEvent` and `CaseTriggerConfig.baseCaseData` |
| `TriggerDecision` | Enum — 6 outcomes: `TRIGGER`, `TRIGGER_AND_CONTINUE`, `CONTINUE_ACCUMULATING`, `DISCARD`, `RESOLVE`, `SUPPRESS` |
| `SituationChangeEvent` | CDI event fired after state transitions — `tenancyId`, `situationId`, `correlationKey`, `ChangeType`, `SituationContext`, `metadata`. ChangeType: `TRIGGERED`, `RESOLVED`, `DISCARDED`, `SUPPRESSED`, `DISMISSED` |
| `CaseTriggerConfig` | Target case coordinates — `caseNamespace`, `caseName`, `caseVersion`, `baseCaseData` |
| `TimestampedDetection` | Wraps `DetectionResult` + `Instant eventTime` |
| `GanglionDescriptor` | Sealed — `NaiveBayes` or `ExpressionRules` for declarative ganglion configuration via YAML |
| `GanglionState` | Ganglion computation state — `double[] values`, `OptionalLong storeVersion` for OCC |
| `GanglionStateKey` | 4-tuple: `ganglionId`, `situationId`, `correlationKey`, `tenancyId` |
| `SituationEvent` | Event log record for historical queries — situation coordinates, change type, event time, confidence, detection count, trigger count, evidence, metadata |
| `TrendResult` | Trend query result — `currentCount`, `baselineCount`, `TrendDirection` |
| `TenantHealth` | Per-tenant aggregate — `tenancyId`, `windowStart`, `windowEnd`, `totalEvents`, `List<SituationSummary>` |
| `SituationSummary` | Per-situation aggregate within a health window — `situationId`, `eventCount`, `triggerCount`, `lastEvent` |
| `ActiveSituation` | Read-only projection — `situationId`, `correlationKey`, `tenancyId`, `confidence`, `evidence`, `since`, `lastSignal`, `triggerCount` |
| `SuppressionMetadataKeys` | Constants for suppression-related metadata keys: `TIER`, `DISMISSAL_RATE`, `MATCH_COUNT`, `AVERAGE_SIMILARITY` |

---

## YAML Situation Definitions

`YamlSituationDefinitionProvider` reads from classpath YAML (default `META-INF/ras-situations.yaml`, configurable via `ras.situations.yaml`). Supports:

- All 7 `ChainMode` variants via `type` discriminator
- `triggerAction`: `type: create-case` (with `caseNamespace`, `caseName`, `caseVersion`, optional `baseCaseData`) or `type: notify-only`
- `triggerMode`: `type: fire-once` (default) or `type: repeating` with `cooldown` (ISO 8601 duration)
- Optional `correlationKey`, `eventFilter`, `dynamicCaseData` expressions (`jq`, `mvel`)
- YAML ganglia: `type: naive-bayes` or `type: expression-rules`
- Evidence templates: ganglion-level, per-rule (expression-rules), and per-outcome (naive-bayes)
- Dynamic confidence expressions in expression-rules (`confidenceExpression` per rule)
- Situation templates: `fromTemplate:` + `parameters:` with `${param}` substitution. Deep merge of consumer overrides.
- Built-in templates: `streak-breach`, `threshold-crossing`, `count-accumulation`, `rate-breach`
- Feedback loop: optional `feedback:` section on situations, `outcomeGroundTruth:` on NaiveBayes ganglia

### YAML Ganglia

Ganglia can be defined entirely in YAML without CDI beans:

**NaiveBayes** — `type: naive-bayes` with `ganglionId`, `handledEventTypes`, `outcomes` (min 2), `priors` (must sum to 1.0), `features` (each with `expression`, `values`, `likelihoods`), `signalMapping` (`targetOutcome`, `detectedThreshold`, `weakThreshold`, optional `antiThreshold`), optional `evidenceTemplates`, optional `outcomeEvidenceTemplates`, optional `outcomeGroundTruth` (maps case outcome labels to NaiveBayes outcome names — enables feedback-driven prior recalibration).

**ExpressionRules** — `type: expression-rules` with `ganglionId`, `handledEventTypes`, `rules` (ordered list). Each rule has `when` (expression) or `otherwise` (catch-all, must be last), `signal`, `confidence` (0.0-1.0), optional `confidenceExpression` (overrides static confidence), optional per-rule `evidenceTemplates`. Ganglion-level `evidenceTemplates` wrapped via `EvidenceExtractingGanglion` decorator.

### Feedback Configuration

Optional `feedback:` section on situation definitions enables the sense-decide-act-learn loop. Case outcomes feed back into detection via suppression, threshold drift, and prior recalibration.

```yaml
situations:
  - situationId: fraud-detection
    eventTypes: [payment.flagged]
    chainMode:
      type: threshold
      ganglia: [fraud-nb]
      minConfidence: 0.7
    triggerAction:
      type: create-case
      caseNamespace: compliance
      caseName: fraud
      caseVersion: "1.0"
    feedback:
      noiseLabels: [dismissed, false-positive]
      confirmedLabels: [escalated]
      suppressionCooldown: PT6H
      learningRate: 0.1
      retentionPeriod: P90D
      tuningEnabled: true
```

| Field | Required | Default | Description |
|-------|----------|---------|-------------|
| `noiseLabels` | yes | — | Case outcome labels classified as noise (suppression candidates) |
| `confirmedLabels` | yes | — | Case outcome labels classified as confirmed signals |
| `suppressionCooldown` | yes | — | ISO 8601 duration — suppress events for this correlation key after a noise dismissal |
| `learningRate` | yes | — | Tuning step size, (0.0, 1.0] |
| `retentionPeriod` | yes | — | ISO 8601 duration — how long outcome records are kept (must be >= cooldown) |
| `tuningEnabled` | no | `false` | Advisory mode (default): suppression + metrics only. Set `true` for automatic threshold/prior adjustment. |

**Two modes:**
- **Advisory** (`tuningEnabled: false`) — suppression and metrics always active. The system learns what's noise and stops re-raising dismissed situations during the cooldown period. No detection parameters are changed.
- **Tuning** (`tuningEnabled: true`) — additionally adjusts `ChainMode.Threshold` minConfidence based on noise rate, and recalibrates NaiveBayes priors using outcome-to-outcome mapping via `outcomeGroundTruth` on the ganglion descriptor.

**NaiveBayes outcomeGroundTruth** — maps case outcome labels (e.g. `escalated`, `dismissed`) to NaiveBayes outcome names (e.g. `fraud`, `legitimate`). Required for prior recalibration; without it, only threshold adjustment applies.

---

## CloudEvent Consumption Pattern

RAS is a pure CloudEvent consumer. Platform stream modules produce CloudEvents as CDI async events. RAS observes via `@ObservesAsync CloudEvent`.

**CloudEvent fields used:**
- `type` — routes to matching `SituationDefinition`s and `Ganglion`s
- `time` — temporal ordering (reorder buffer, Drools pseudo clock)
- `subject` — default correlation key (falls back to `"_singleton"`)
- extension `tenancyid` — required; events without it are skipped

**RAS does not produce CloudEvents.** Its outputs are:
1. Case creation via `CaseTrigger` (for `TriggerAction.CreateCase`)
2. `SituationChangeEvent` CDI events (for all trigger decisions and for `TriggerAction.NotifyOnly`)

---

## Configuration Properties

| Property | Default | Purpose |
|----------|---------|---------|
| `ras.situations.yaml` | `META-INF/ras-situations.yaml` | Classpath location of YAML situation definitions |
| `ras.evaluator.max-conflict-retries` | `3` | Max OCC retry attempts per event in `SituationEvaluator` |
| `ras.evaluator.trigger-guard-period` | `PT1M` | How long triggered entities are kept before cleanup |
| `ras.event-history.retention` | `P30D` | Retention period for situation event history (JPA module) |
| `ras.feedback.update-interval` | `PT5M` | How often the feedback update job runs (threshold/prior adjustment, cleanup) |

---

## Dependencies

| Repo | Module | How |
|------|--------|-----|
| `casehub-platform` | `platform-api` | CloudEvent types, CDI event infrastructure, `ExpressionEvaluator` SPI |
| `casehub-platform` | `platform-expression` | JQ/MVEL expression engines — test scope in runtime, deployers add to classpath when expressions are needed |
| `casehub-engine` | `engine-api` | `CaseHub` SPI for case creation (via `DefaultCaseTrigger`) |
| Drools 10.1.0 | `drools-model-codegen` | ras-drools module only |

---

## What It Does NOT Do

- **Stream infrastructure** — Kafka, AMQP, webhook, Camel routes live in casehub-platform stream submodules; RAS observes CDI events
- **Case lifecycle management** — only triggers creation; case state machines, milestones, tasks are casehub-engine
- **REST/gRPC endpoints** — entirely event-driven via CDI async events (registers one service endpoint via `EndpointRegistry` for discovery only)
- **ML model training** — `NaiveBayesGanglion` uses pre-configured priors and likelihoods; no online learning
- **LLM integration (yet)** — `ras-llm` is a POM placeholder with no source
- **Event sourcing or audit trail** — situations are mutable state, removed when case is created or discarded (event history is best-effort via `SituationEventRecorder`)
- **Distributed coordination** — uses in-process synchronized locks; clustered deployments rely on OCC
- **Rule authoring UI** — DRL provided as classpath resources or programmatic strings
