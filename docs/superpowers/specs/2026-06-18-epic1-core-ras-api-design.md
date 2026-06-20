# Epic 1: Core RAS API — Design Spec

**Date:** 2026-06-18 (revised 2026-06-20)
**Status:** Approved
**Issue:** casehubio/casehub-ras#1
**Supersedes:** Relevant sections of `2026-06-12-casehub-ras-design.md` (original spec predates CloudEvent decision)

---

## 1. Scope

Define the core API module types and SPIs for `casehub-ras-api`, the `InMemorySituationStore`
in a new `persistence-memory/` module, and test fixtures in `testing/`.

**Not in scope:** `runtime/` (Epic 2), `ras-drools/` (Epic 4), `ras-llm/`, stream modules.

---

## 2. Package and Dependencies

**Package:** `io.casehub.ras.api`

**Compile dependencies:**
- `casehub-platform-api` — provides `io.cloudevents.CloudEvent` transitively via `cloudevents-core`

**Provided dependencies:**
- `io.smallrye.reactive:mutiny` — `Uni<T>` in SPI signatures
- `jakarta.inject-api`, `jakarta.enterprise.cdi-api` — CDI annotations

`casehub-engine-api` is NOT a dependency of the API module. `CaseTriggerConfig` uses string
identifiers (`caseNamespace`, `caseName`, `caseVersion`) specifically to avoid coupling ganglion
implementors to the engine's type graph. The engine dependency lives on `runtime/` only — the
runtime resolves `CaseTriggerConfig` strings into a `CaseDefinition` at trigger time.

**Build:** `jandex-maven-plugin` generates `META-INF/jandex.idx` (required per library-jars-require-jandex protocol).

Pure Java, Tier 1 per module-tier-structure protocol. No Quarkus runtime, no JPA.

---

## 3. Routing Model — Definition-Driven (Model B)

The engine owns situation routing. Ganglia evaluate — they do not choose which situation
an event belongs to.

**Flow:**
1. CloudEvent arrives with type T
2. Engine finds all `SituationDefinition`s where T ∈ `eventTypes`
3. For each matching definition, engine resolves a correlation key (default: `CloudEvent.getSubject()`)
   and finds or creates the `SituationContext` for that situation instance
4. Engine calls each ganglion listed in the definition's `ChainMode`
5. Ganglia return `DetectionResult` — signal, confidence, evidence. No situation routing.
6. Engine evaluates the `ChainMode` against accumulated results in the `SituationContext`
7. `RasTriggerPolicy` decides CREATE_CASE / CONTINUE_ACCUMULATING / DISCARD

**Situation instance identity:** A situation instance is identified by the tuple
`(situationId, tenancyId)`. The runtime constructs `situationId` as a composite of
definition identifier + correlation key — unique within a tenant. `tenancyId` provides
the separate tenant scope. For per-entity situations (100 machines, each independently
monitored), the correlation key comes from `CloudEvent.getSubject()` by default. The
correlation key derivation strategy is a runtime concern — not part of the API.

**Consequence for multi-situation events:** A single CloudEvent (e.g. temperature spike) can
contribute to multiple situations (e.g. "equipment-failure-risk" AND "environmental-hazard")
when multiple `SituationDefinition`s match its event type. The engine calls the ganglion
once per matching situation, each with its own `SituationContext`. `Uni<DetectionResult>`
(single result) is correct — the ganglion evaluates one event against one situation.

---

## 4. Core Types

### 4.1 DetectionSignal

```java
public enum DetectionSignal {
    DETECTED,
    WEAK,
    NOISE,
    ANTI
}
```

- `DETECTED` — clear positive signal
- `WEAK` — ambiguous, worth accumulating
- `NOISE` — nothing meaningful
- `ANTI` — counter-evidence, reduces situation confidence

### 4.2 DetectionResult

```java
public record DetectionResult(
    String ganglionId,
    double confidence,
    DetectionSignal signal,
    Map<String, Object> evidence
) {
    public DetectionResult {
        Objects.requireNonNull(ganglionId, "ganglionId");
        Objects.requireNonNull(signal, "signal");
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be 0.0–1.0, got: " + confidence);
        }
        evidence = evidence != null ? Map.copyOf(evidence) : Map.of();
    }
}
```

No `situationId` — under definition-driven routing (§3), the engine already knows which
situation this detection is for. The ganglion reports what it found; the engine handles routing.

- `ganglionId` — which ganglion produced this result
- `confidence` — 0.0–1.0, validated in compact constructor
- `signal` — classification of what was detected
- `evidence` — opaque to the engine; carried through to case creation. Defensively copied.

### 4.3 SituationContext

```java
public record SituationContext(
    String situationId,
    String tenancyId,
    Instant firstSignal,
    Instant lastSignal,
    List<DetectionResult> detections
) {
    public SituationContext {
        Objects.requireNonNull(situationId, "situationId");
        Objects.requireNonNull(tenancyId, "tenancyId");
        Objects.requireNonNull(firstSignal, "firstSignal");
        Objects.requireNonNull(lastSignal, "lastSignal");
        detections = detections != null ? List.copyOf(detections) : List.of();
    }

    public static SituationContext initial(String situationId, String tenancyId, Instant eventTime) {
        return new SituationContext(situationId, tenancyId, eventTime, eventTime, List.of());
    }

    public SituationContext withDetection(DetectionResult result, Instant eventTime) {
        var newDetections = new ArrayList<>(detections);
        newDetections.add(result);
        Instant newFirst = eventTime.isBefore(firstSignal) ? eventTime : firstSignal;
        Instant newLast = eventTime.isAfter(lastSignal) ? eventTime : lastSignal;
        return new SituationContext(situationId, tenancyId, newFirst, newLast, newDetections);
    }
}
```

Immutable. `initial()` creates the first context for a new situation instance.
`withDetection(result, eventTime)` returns a new instance with the detection appended
and timestamps adjusted for out-of-order events (common in distributed systems).
The runtime saves the new instance via `SituationStore`.

No `accumulatedEvidence` field — each `DetectionResult` in `detections` carries its own
`evidence` map alongside its `ganglionId`. The full forensic trail is preserved without
risk of silent key collision from `putAll`. When the runtime needs a merged view for case
creation, it constructs one with whatever collision policy it chooses.

**Timestamp policy:** `eventTime` comes from `CloudEvent.getTime()` (converted from
`OffsetDateTime` to `Instant` via `toInstant()` — lossless). If `CloudEvent.getTime()`
is null (optional per CloudEvents spec), the runtime falls back to processing-time
(`Instant.now()`). This conversion and fallback is a runtime concern — the API types
use `Instant` throughout.

---

## 5. ChainMode and SituationDefinition

### 5.1 ChainMode

```java
public sealed interface ChainMode {
    record And(Set<String> requiredGanglia) implements ChainMode {
        public And {
            if (requiredGanglia == null || requiredGanglia.isEmpty()) {
                throw new IllegalArgumentException("requiredGanglia must not be empty");
            }
            requiredGanglia = Set.copyOf(requiredGanglia);
        }
    }
    record Or(Set<String> ganglia) implements ChainMode {
        public Or {
            if (ganglia == null || ganglia.isEmpty()) {
                throw new IllegalArgumentException("ganglia must not be empty");
            }
            ganglia = Set.copyOf(ganglia);
        }
    }
    record Threshold(Set<String> ganglia, double minConfidence) implements ChainMode {
        public Threshold {
            if (ganglia == null || ganglia.isEmpty()) {
                throw new IllegalArgumentException("ganglia must not be empty");
            }
            ganglia = Set.copyOf(ganglia);
            if (minConfidence <= 0.0) {
                throw new IllegalArgumentException(
                    "minConfidence must be > 0.0, got: " + minConfidence);
            }
        }
    }
    record Sequence(List<String> orderedGanglia) implements ChainMode {
        public Sequence {
            if (orderedGanglia == null || orderedGanglia.isEmpty()) {
                throw new IllegalArgumentException("orderedGanglia must not be empty");
            }
            orderedGanglia = List.copyOf(orderedGanglia);
        }
    }
    record Count(String ganglionId, int requiredCount) implements ChainMode {
        public Count {
            Objects.requireNonNull(ganglionId, "ganglionId");
            if (requiredCount < 1) {
                throw new IllegalArgumentException(
                    "requiredCount must be >= 1, got: " + requiredCount);
            }
        }
    }
}
```

Every variant carries explicit ganglion references — no implicit discovery. Pattern matching
in the runtime is exhaustive with no default branch.

`Threshold.minConfidence` has no upper bound. A threshold of 2.0 means "I need the
equivalent of two strong signals" when confidence values sum across detections.

### 5.2 Event Type Routing vs Ganglion Capability

Two independent declarations exist for CloudEvent types:

- **`SituationDefinition.eventTypes`** — the routing key. The engine uses this to determine
  which situations a CloudEvent activates.
- **`Ganglion.handledEventTypes()`** — a capability declaration. Used at startup to validate
  that ganglia referenced in `ChainMode` actually claim to handle the event types the
  definition declares. Never used for runtime routing.

**Validation contract:** At startup, for each `SituationDefinition`, the engine verifies that
every ganglion referenced in its `ChainMode` has `handledEventTypes()` that overlap with the
definition's `eventTypes`. Mismatch is a configuration error, reported at startup.

### 5.3 CaseTriggerConfig

```java
public record CaseTriggerConfig(
    String caseNamespace,
    String caseName,
    String caseVersion,
    Map<String, Object> baseCaseData
) {
    public CaseTriggerConfig {
        Objects.requireNonNull(caseNamespace, "caseNamespace");
        Objects.requireNonNull(caseName, "caseName");
        Objects.requireNonNull(caseVersion, "caseVersion");
        baseCaseData = baseCaseData != null ? Map.copyOf(baseCaseData) : Map.of();
    }
}
```

String identifiers — no dependency on `casehub-engine-api`. The runtime resolves these
into a `CaseDefinition` at trigger time. `baseCaseData` is merged with situation evidence
to form the case's input data.

### 5.4 SituationDefinition

```java
public record SituationDefinition(
    String situationId,
    Set<String> eventTypes,
    Duration correlationWindow,
    ChainMode chainMode,
    CaseTriggerConfig triggerConfig
) {
    public SituationDefinition {
        Objects.requireNonNull(situationId, "situationId");
        Objects.requireNonNull(chainMode, "chainMode");
        Objects.requireNonNull(triggerConfig, "triggerConfig");
        if (eventTypes == null || eventTypes.isEmpty()) {
            throw new IllegalArgumentException("eventTypes must not be empty");
        }
        eventTypes = Set.copyOf(eventTypes);
        if (correlationWindow != null
                && (correlationWindow.isZero() || correlationWindow.isNegative())) {
            throw new IllegalArgumentException(
                "correlationWindow must be positive when set, got: " + correlationWindow);
        }
    }
}
```

- `situationId` — definition identifier (e.g. "equipment-failure-risk"). The runtime
  constructs the instance `situationId` from this + correlation key. The instance is
  identified by the tuple `(situationId, tenancyId)`.
- `eventTypes` — CloudEvent types that activate this situation (routing key)
- `correlationWindow` — `@Nullable`. Null = persistent situation, never expires. Must be
  positive when set (validated in compact constructor).
- `chainMode` — how detections are correlated, with explicit ganglion references
- `triggerConfig` — what case to create when the situation fires

---

## 6. Ganglion SPI

```java
public interface Ganglion {

    String ganglionId();

    Set<String> handledEventTypes();

    Uni<DetectionResult> detect(CloudEvent event, SituationContext context);

    default Uni<SituationContext> compact(SituationContext context) {
        return Uni.createFrom().item(context);
    }
}
```

**Contract:**

- `ganglionId()` — unique, stable across restarts. Used in `ChainMode` ganglion references
  and `DetectionResult.ganglionId()`.
- `handledEventTypes()` — CloudEvent `type` values this ganglion can handle. Capability
  declaration for startup validation only — the engine routes by `SituationDefinition.eventTypes`,
  not by this method (see §5.2).
- `detect(CloudEvent, SituationContext)` — called by the engine for each matching CloudEvent
  in the context of a specific situation instance. Receives `SituationContext.initial()` on
  first detection. Returns `Uni<DetectionResult>` per spi-reactive-blocking-io protocol —
  implementations may do blocking I/O (Drools session insert, LLM call, database lookup).
  In-memory implementations return `Uni.createFrom().item(result)`.
- `compact(SituationContext)` — default method. Returns `Uni<SituationContext>` per
  spi-reactive-blocking-io protocol — compaction may involve I/O (external state store queries).
  Called periodically by the runtime for persistent situations. The ganglion returns whatever
  representation it needs to keep working indefinitely: some trim old detections, some summarize
  evidence, some return unchanged because their internal state (KieSession, probability matrix)
  is the real memory. Default is identity — no compaction.

No `correlationWindow()` method — the correlation window is a situation-level concern,
declared in `SituationDefinition.correlationWindow`. A ganglion detects patterns; how long
to hold a situation open is the deployer's decision in the definition configuration.

**Default method contract test** required per spi-default-method-contract-test protocol:
anonymous implementation verifying `compact()` compiles without override.

---

## 7. RasTriggerPolicy SPI

```java
public interface RasTriggerPolicy {
    Uni<TriggerDecision> evaluate(SituationContext context, SituationDefinition definition);
}
```

```java
public enum TriggerDecision {
    CREATE_CASE,
    CONTINUE_ACCUMULATING,
    DISCARD
}
```

Returns `Uni<TriggerDecision>` per spi-reactive-blocking-io protocol — custom policies may
need I/O (fetch dynamic thresholds from DB, call external policy service).

The runtime module (Epic 2) will provide a `DefaultRasTriggerPolicy` (`@DefaultBean`) implementing
confidence threshold + chain mode evaluation. Custom policies can implement rate limiting,
deduplication windows, or domain-specific gate logic.

---

## 8. SituationStore SPI

```java
public interface SituationStore {

    Uni<Optional<SituationContext>> find(String situationId, String tenancyId);

    Uni<Void> save(SituationContext context);

    Uni<Void> remove(String situationId, String tenancyId);

    Uni<Void> removeExpired(Instant cutoff);
}
```

**Contract:**

- `find()` / `save()` / `remove()` — basic CRUD, always tenancy-scoped per no-conditional-tenancy-filtering
  protocol. `save()` is upsert — creates or replaces.
- `removeExpired(cutoff)` — evicts situations whose `lastSignal` is before `cutoff`. Cross-tenant
  housekeeping — called by a scheduled cleanup in the runtime, not per-request.
- Returns `Uni<T>` per spi-reactive-blocking-io protocol — JPA implementations will do blocking I/O.

---

## 9. Module Structure

### 9.1 api/

All types from §4–§8. Package: `io.casehub.ras.api`.

Dependencies: `casehub-platform-api` (compile), `mutiny` (provided), CDI annotations (provided).
No `casehub-engine-api` — that dependency lives on `runtime/` only.

### 9.2 persistence-memory/ (new module)

`InMemorySituationStore` — `@ApplicationScoped @Alternative @Priority(1)`, `ConcurrentHashMap`-backed.

- Package: `io.casehub.ras.memory`
- `@ApplicationScoped @Alternative @Priority(1)` — singleton lifecycle, activates by classpath
  presence, overrides any `@DefaultBean`
- Thread-safe: `ConcurrentHashMap<SituationKey, SituationContext>` where `SituationKey` is
  a composite of `situationId` + `tenancyId`
- `removeExpired()` scans all entries and removes those with `lastSignal` before cutoff
- Jandex plugin included
- Dependencies: `casehub-ras-api` (compile)

Per module-tier-structure protocol, this is the mandatory zero-config persistence module.

### 9.3 testing/

Test fixtures only — never compile or runtime scope.

- `MockGanglion` — configurable ganglion for tests. Fixed responses, records calls.
- `FixedDetectionResult` — factory methods for common detection patterns in tests.

Package: `io.casehub.ras.testing`

### 9.4 Parent pom changes

Add `persistence-memory` to `<modules>` list. Add dependency management entry:
```xml
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-ras-memory</artifactId>
    <version>${project.version}</version>
</dependency>
```

Folder name `persistence-memory/` per maven-submodule-folder-naming protocol.

Update parent pom `<description>` — replace "SensoryEvent" references with "CloudEvent".

Remove `casehub-engine-api` from `api/pom.xml`.

---

## 10. Deferred Items

| Item | Deferred to | Reason |
|------|-------------|--------|
| `DefaultRasTriggerPolicy` | Epic 2 | Runtime concern — needs chain mode evaluation logic |
| `RasEngine` (CDI observer) | Epic 2 | Runtime orchestration |
| `CompositeEventCorrelator` | Epic 2 | Runtime correlation logic |
| `CaseTriggerService` | Epic 2 | Runtime bridge to `casehub-engine-api` |
| Correlation key derivation strategy | Epic 2 | Runtime concern — default: `CloudEvent.getSubject()` |
| YAML deserialization of `SituationDefinition` | Epic 2 | Runtime config loading |
| Startup validation (ganglion capabilities vs definition event types) | Epic 2 | Runtime validation |
| JPA `SituationStore` impl | Future | Production persistence — not needed until deployment |
| `DroolsGanglion` | Epic 4 | Optional module |
| `LlmGanglion` | Future | Optional module |
