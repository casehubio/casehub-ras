# CaseHub RAS

## Project Type

type: java

## Repository Role

Integration-tier situational awareness and reactive case creation. Monitors standardised `SensoryEvent`
CDI events from platform stream modules (Kafka, AMQP, webhook, Camel), routes them to pluggable `Ganglion`
detection implementations, correlates composite events, and triggers case creation via casehub-engine-api
when a situation threshold is crossed.

**Tier:** Integration (alongside claudony, casehub-openclaw, casehub-workers)

**Key principle:** casehub-ras contains NO stream infrastructure. Quarkus endpoints, Kafka consumers,
AMQP consumers, webhook receivers, and Camel routes for data mapping all live in casehub-platform stream
submodules. casehub-ras observes the `SensoryEvent` CDI events they produce.

**Naming:** RAS = Reticular Activating System — the biological system that monitors all sensory input,
filters for significance, and elevates to awareness. Ganglion = pluggable detection unit within the RAS.
Multiple ganglia, one RAS per deployment context.

**Design spec:** `docs/superpowers/specs/2026-06-12-casehub-ras-design.md`

## Build Commands

```bash
mvn --batch-mode install
mvn --batch-mode deploy -DskipTests   # CI only
```

## Module Structure

| Module | Artifact | Root package | Purpose |
|--------|----------|-------------|---------|
| `api/` | `casehub-ras-api` | `io.casehub.ras.api` | Core SPIs + domain types. Pure Java, Mutiny provided. Also imported by platform stream modules to fire `SensoryEvent`. |
| `runtime/` | `casehub-ras` | `io.casehub.ras.runtime` | RasEngine, CompositeEventCorrelator, SituationAccumulator, CaseTriggerService. Quarkus extension. |
| `ras-drools/` | `casehub-ras-drools` | `io.casehub.ras.drools` | DroolsGanglion — Drools CEP (KieSession, sliding windows, temporal correlation). Optional. |
| `ras-llm/` | `casehub-ras-llm` | `io.casehub.ras.llm` | LlmGanglion — narrative detection via casehub-platform-agent-api. Optional, slow path. |
| `testing/` | `casehub-ras-testing` | `io.casehub.ras.testing` | MockGanglion, FixedDetectionResult, InMemorySituationStore. **Test scope only.** |

## Core SPIs (api/)

### Ganglion — detection strategy

```java
interface Ganglion {
    String ganglionId();
    Set<String> handledStreamTypes();
    Uni<DetectionResult> detect(SensoryEvent event, SituationContext context);
    default Duration correlationWindow() { return Duration.ofMinutes(5); }
}
```

### RasTriggerPolicy — when to create a case

```java
interface RasTriggerPolicy {
    TriggerDecision evaluate(SituationContext context, SituationDefinition definition);
    // TriggerDecision: CREATE_CASE / CONTINUE_ACCUMULATING / DISCARD
}
```

## Core Types (api/)

| Type | Purpose |
|------|---------|
| `SensoryEvent` | Standardised input — `sourceId`, `streamType`, `timestamp`, `tenancyId`, `payload` |
| `DetectionResult` | Ganglion output — `ganglionId`, `situationId`, `confidence` (0.0–1.0), `signal` (DETECTED/WEAK/NOISE/ANTI), `evidence` |
| `SituationContext` | Accumulated state — `situationId`, `firstSignal`, `lastSignal`, `List<DetectionResult>`, `accumulatedEvidence` |
| `SituationDefinition` | Declared situation — `streamTypes`, `correlationWindow`, chain mode (AND/OR/THRESHOLD/SEQUENCE/COUNT), trigger config |

## Composite Event Chains

Situations are declared via `SituationDefinition` (YAML or fluent DSL). The `CompositeEventCorrelator`
accumulates `DetectionResult` objects sharing the same `situationId` within the correlation window.

Chain modes: AND (all named ganglia must fire), OR (any single firing), THRESHOLD (min confidence sum),
SEQUENCE (ordered arrival), COUNT (same ganglion fires N times).

## Key Rules

- `testing/` is never compile or runtime scope — test only
- Ganglion implementations activate by classpath presence
- `LlmGanglion` always runs async on slow path — never blocks fast detection path
- All `SituationContext` is tenancy-scoped — no cross-tenant situation accumulation
- Platform stream modules depend on `casehub-ras-api` only — never on `casehub-ras` runtime
- casehub-ras never imports Kafka, AMQP, Camel, or any transport library

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
