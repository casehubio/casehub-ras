# CaseHub RAS

## Project Type

type: java

## Repository Role

Integration-tier situational awareness and reactive case creation. Observes `CloudEvent` CDI events
(`@ObservesAsync CloudEvent`) from platform stream modules (Kafka, AMQP, webhook, Camel), routes them
to pluggable `Ganglion` detection implementations, correlates composite events, and triggers case creation
via casehub-engine-api when a situation threshold is crossed.

**Tier:** Integration (alongside claudony, casehub-openclaw, casehub-workers)

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

## Build Commands

```bash
mvn --batch-mode install
mvn --batch-mode deploy -DskipTests   # CI only
```

## Module Structure

| Module | Artifact | Root package | Purpose |
|--------|----------|-------------|---------|
| `api/` | `casehub-ras-api` | `io.casehub.ras.api` | Core SPIs + domain types + JavaSwitchGanglion. Depends on `casehub-platform-api` (for `CloudEvent`). Mutiny provided. Publishes test-jar for AbstractGanglionContractTest. |
| `persistence-memory/` | `casehub-ras-memory` | `io.casehub.ras.persistence.memory` | InMemorySituationStore — `@Alternative @Priority(100)`, ConcurrentHashMap-backed. Dev/test only. |
| `persistence-jpa/` | `casehub-ras-jpa` | `io.casehub.ras.persistence.jpa` | JpaSituationStore — `@ApplicationScoped`, Hibernate ORM + JSONB detections. Consumers add `classpath:db/ras/migration` to `quarkus.flyway.locations`. |
| `runtime/` | `casehub-ras` | `io.casehub.ras.runtime` | RasEngine, SituationEvaluator, DefaultRasTriggerPolicy, DefaultCaseTrigger, SituationExpiryJob, EventBufferFlushJob, EventReorderBuffer, YamlSituationDefinitionProvider, NaiveBayesGanglion. Quarkus extension. |
| `ras-drools/` | `casehub-ras-drools` | `io.casehub.ras.drools` | DroolsGanglion — Drools CEP (KieSession, sliding windows, temporal correlation). Optional. |
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

### RasTriggerPolicy — when to create a case

```java
interface RasTriggerPolicy {
    Uni<TriggerDecision> evaluate(SituationContext context, SituationDefinition definition);
    // TriggerDecision: CREATE_CASE / CONTINUE_ACCUMULATING / DISCARD
}
```

### SituationStore — situation persistence

```java
interface SituationStore {
    Uni<Optional<SituationContext>> find(String situationId, String correlationKey, String tenancyId);
    Uni<Void> save(SituationContext context);
    Uni<Void> remove(String situationId, String correlationKey, String tenancyId);
    Uni<Void> removeExpired(Instant cutoff);
}
```

### JavaSwitchGanglion — synchronous detection base class (api/)

Abstract class in `api/`. Developers subclass and override `evaluate(CloudEvent, SituationContext) → DetectionResult`.
`detect()` is final — wraps `evaluate()` in `Uni`. Stateless (no-op `compact()`/`close()`). Helper methods:
`detected()`, `weak()`, `noise()`, `anti()` — auto-embed ganglionId. Preferred path for simple stateless detection.

### NaiveBayesGanglion — Bayesian classification (runtime/)

Concrete class in `runtime/`, configured via `NaiveBayesConfig`. Incrementally accumulates posteriors across
`detect()` calls using Naive Bayes. Log-space arithmetic prevents underflow. Implements `compact()` to collapse
running posteriors into a single detection — necessary for correct Threshold ChainMode interaction. Config types:
`NaiveBayesConfig`, `FeatureLikelihood`, `NaiveBayesFeatureExtractor`, `NaiveBayesSignalMapping` (with optional ANTI threshold).

## Core Types (api/)

| Type | Purpose |
|------|---------|
| `CloudEvent` | Input — from `io.cloudevents:cloudevents-core` via `casehub-platform-api`. Fields: `type` (event type for routing), `source`, `subject`, `data`, `tenancyid` extension |
| `DetectionResult` | Ganglion output — `ganglionId`, `confidence` (0.0–1.0, NaN rejected), `signal` (NOISE/ANTI/WEAK/DETECTED), `evidence` |
| `DetectionSignal` | Signal strength — NOISE, ANTI, WEAK, DETECTED (ascending). `isAtLeast(threshold)` for comparisons. |
| `TimestampedDetection` | Wraps `DetectionResult` + `Instant eventTime` — runtime adds event timestamp at accumulation boundary |
| `SituationContext` | Accumulated state — `situationId`, `correlationKey`, `tenancyId`, `firstSignal`, `lastSignal`, `List<TimestampedDetection>` |
| `SituationDefinition` | Declared situation — `situationId`, `eventTypes`, `correlationWindow` (@Nullable), `eventBufferDelay` (@Nullable), `ChainMode`, `CaseTriggerConfig` |
| `ChainMode` | Sealed interface — And, Or, Threshold, Sequence, Count. All variants carry explicit ganglion references. `referencedGanglia()` default method extracts IDs. |
| `CaseTriggerConfig` | Case creation parameters — `caseNamespace`, `caseName`, `caseVersion`, `baseCaseData`. String identifiers, no engine-api dependency. |
| `CaseTrigger` | SPI for case creation — `fire(CaseTriggerConfig, SituationContext) → Uni<UUID>`. Default impl in runtime/ bridges to CaseHub. |
| `TriggerDecision` | Trigger outcome — CREATE_CASE, CONTINUE_ACCUMULATING, DISCARD |

## Routing Model — Definition-Driven (Model B)

The engine owns situation routing. Ganglia evaluate — they do not choose which situation
an event belongs to. `SituationDefinition.eventTypes` is the routing key; `ChainMode` identifies
participating ganglia; `Ganglion.handledEventTypes()` is a capability declaration for startup
validation only. A situation instance is identified by the tuple `(situationId, correlationKey, tenancyId)`.
`correlationKey` defaults to `CloudEvent.getSubject()` or `"_singleton"` when null.

Chain modes: AND (all named ganglia must fire), OR (any single firing), THRESHOLD (min confidence sum,
no upper bound — ANTI detections subtract from the sum), SEQUENCE (ordered arrival), COUNT (same
ganglion fires N times).

## YAML Situation Definitions (runtime/)

`YamlSituationDefinitionProvider` reads `SituationDefinition` entries from a classpath YAML resource
(default `META-INF/ras-situations.yaml`, configurable via `ras.situations.yaml`). Returns empty list
when the resource is absent — coexists with programmatic providers. Supports all five ChainMode variants
via a `type` discriminator (`and`, `or`, `threshold`, `sequence`, `count`). Optional `eventBufferDelay`
field (ISO-8601 Duration) enables per-situation event reordering for pseudo clock mode.

## Persistent Situation Compaction (runtime/)

For persistent situations (`correlationWindow = null`), `SituationEvaluator` calls `Ganglion.compact()`
on each referenced ganglion after every `CONTINUE_ACCUMULATING` decision. The ganglion decides what to
compact — the evaluator just triggers it. Windowed situations skip compaction.

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
