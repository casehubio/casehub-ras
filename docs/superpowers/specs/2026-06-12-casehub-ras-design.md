# casehub-ras — Design Spec

**Date:** 2026-06-12
**Status:** Pre-implementation — spec for brainstorm and issue creation
**Repo:** casehubio/casehub-ras

---

## 1. The Problem

CaseHub cases are currently created imperatively: something calls `startCase()` with explicit intent. This is correct for workflow-driven scenarios but leaves a gap: **who monitors the environment and decides a case is warranted?**

In regulated domains, cases are not always explicitly requested:
- A pattern of IoT sensor readings indicates equipment failure
- A sequence of financial transactions matches a suspicious pattern
- A clinical patient's metrics cross a multi-factor risk threshold
- Agent communication patterns signal a governance escalation
- A deployment's behaviour over time deviates from expected norms

These situations require **reactive, declarative case creation** — the platform watches, detects, and acts. `casehub-ras` is that layer.

---

## 2. Naming and Conceptual Model

**RAS — Reticular Activating System** maps precisely to the function:
- The biological RAS monitors all incoming sensory streams
- It filters for what is significant enough to elevate to conscious awareness
- It coordinates multiple input channels into a unified awareness signal

**Ganglion** — the pluggable detection unit inside the RAS:
- A biological ganglion pre-processes sensory signals before they reach the brain
- Multiple ganglia detect different patterns using different strategies
- Their outputs are accumulated and correlated by the RAS

Architecture: multiple `Ganglion` implementations → one `RasEngine` → `case created`.

---

## 3. Two-Tier Architecture

**Platform tier (NOT in casehub-ras):** stream ingestion and data normalisation.

The raw data sources — Kafka topics, AMQP queues, REST webhooks, database change streams, IoT state events, Qhorus messages — are infrastructure concerns. They live in casehub-platform (new submodules) or casehub-connectors. Quarkus handles the endpoint wiring; Apache Camel handles data transformation and routing when mapping is needed.

These produce a standardised `SensoryEvent` CDI event that the RAS observes. The RAS has zero knowledge of Kafka, webhooks, or Camel.

**RAS tier (casehub-ras):** detection intelligence.

The RAS observes `SensoryEvent` CDI events, routes them to registered `Ganglion` implementations, accumulates `DetectionResult` outputs, correlates composite events, and triggers case creation when the situation threshold is crossed.

```
External sources
  → casehub-platform stream submodules (Quarkus + Camel)
    → SensoryEvent CDI fireAsync()
      → casehub-ras RasEngine
        → Ganglion implementations (parallel detection)
          → DetectionResult accumulation
            → CompositeEventCorrelator
              → SituationThreshold evaluation
                → startCase() via casehub-engine
```

---

## 4. Platform Stream Infrastructure (separate design — referenced here)

New submodules in `casehub-platform` (or `casehub-connectors`):

| Module | Transport | Notes |
|--------|-----------|-------|
| `platform-streams-kafka` | Apache Kafka | Quarkus Kafka consumer; configurable topics |
| `platform-streams-amqp` | AMQP 1.0 | Quarkus AMQP consumer |
| `platform-streams-webhook` | HTTP push | Quarkus REST endpoint; receives webhook payloads |
| `platform-streams-poll` | Any HTTP API | Quarkus `@Scheduled` + REST client; polling adapter |
| `platform-streams-camel` | Any Camel connector | Apache Camel route → `SensoryEvent`; 300+ connectors available |

Each module:
- Activates by classpath presence (Jandex library pattern)
- Applies any needed data mapping (Camel routes for transformation)
- Fires `Event<SensoryEvent>` via CDI `fireAsync()`
- Zero dependency on casehub-ras

The `SensoryEvent` type lives in `casehub-ras-api` (so platform modules can import it without pulling in the RAS runtime). Platform stream modules depend on `casehub-ras-api` only.

---

## 5. Core Types (casehub-ras-api)

```java
// The standardised input event — produced by platform stream modules
record SensoryEvent(
    String sourceId,        // identifies the data source (kafka topic, webhook path, etc.)
    String streamType,      // domain classification ("iot.temperature", "finance.transaction")
    Instant timestamp,
    String tenancyId,
    Map<String, Object> payload  // raw event data, opaque to the RAS engine
) {}

// What a Ganglion produces when it processes a SensoryEvent
record DetectionResult(
    String ganglionId,
    String situationId,     // groups related detections (composite events)
    double confidence,      // 0.0 – 1.0
    DetectionSignal signal, // DETECTED / WEAK / NOISE / ANTI
    Map<String, Object> evidence  // what the ganglion observed
) {}

// Accumulated state for an evolving situation
record SituationContext(
    String situationId,
    String tenancyId,
    Instant firstSignal,
    Instant lastSignal,
    List<DetectionResult> detections,
    Map<String, Object> accumulatedEvidence
) {}
```

---

## 6. Ganglion SPI (casehub-ras-api)

```java
interface Ganglion {
    // Unique identifier — used to route events and filter results
    String ganglionId();

    // Stream types this ganglion handles — RAS routes only matching events
    Set<String> handledStreamTypes();

    // Core detection — called by RasEngine for each matching SensoryEvent
    Uni<DetectionResult> detect(SensoryEvent event, SituationContext context);

    // Optional: ganglion can request correlation window size
    default Duration correlationWindow() { return Duration.ofMinutes(5); }
}
```

**Built-in implementations:**

| Implementation | Strategy | Notes |
|---|---|---|
| `JavaSwitchGanglion` | Java pattern switch / predicate chain | Zero deps, fast, deterministic |
| `DroolsCepGanglion` | Drools Complex Event Processing | Sliding windows, event correlation, temporal patterns |
| `BayesianGanglion` | Bayesian network | Weighted multi-signal accumulation |
| `LlmGanglion` | LLM via `casehub-platform-agent-api` | Narrative/ambiguous signals; slow path only |

---

## 7. Composite Event Chains

A single `SensoryEvent` rarely warrants a case alone. The `CompositeEventCorrelator` accumulates `DetectionResult` objects sharing the same `situationId` within a configurable time window.

**Chaining modes:**
- **AND** — all named ganglia must fire before case is created
- **OR** — any single ganglion firing crosses threshold
- **THRESHOLD** — minimum confidence sum across all detections
- **SEQUENCE** — detections must arrive in declared order within window
- **COUNT** — same ganglion must fire N times within window

Declared in a `SituationDefinition` (YAML or fluent Java DSL):

```yaml
situation:
  id: equipment-failure-risk
  streamTypes: [iot.temperature, iot.vibration, iot.pressure]
  correlationWindow: PT10M
  chain:
    mode: AND
    ganglia: [temp-spike-ganglion, vibration-anomaly-ganglion]
  threshold:
    minConfidence: 0.75
  onTrigger:
    caseType: equipment-maintenance
    caseData:
      priority: HIGH
```

---

## 8. RasTriggerPolicy SPI (casehub-ras-api)

Controls when a case is created from a `SituationContext`:

```java
interface RasTriggerPolicy {
    TriggerDecision evaluate(SituationContext context, SituationDefinition definition);
    // Returns: CREATE_CASE / CONTINUE_ACCUMULATING / DISCARD
}
```

`DefaultRasTriggerPolicy` implements confidence threshold + chain mode evaluation. Custom policies can implement rate limiting, deduplication, or domain-specific gate logic.

---

## 9. Module Structure

```
casehub-ras/
  pom.xml                      ← casehub-ras-parent
  api/                         ← casehub-ras-api (Pure Java, Mutiny provided)
    SensoryEvent, DetectionResult, SituationContext, SituationDefinition
    Ganglion SPI, RasTriggerPolicy SPI
  runtime/                     ← casehub-ras (Quarkus extension)
    RasEngine — observes SensoryEvent CDI events, orchestrates ganglia
    CompositeEventCorrelator — accumulates and correlates detection results
    SituationStore — tracks open situations (in-memory default; JPA optional)
    CaseTriggerService — calls casehub-engine startCase() when threshold crossed
  ras-drools/                  ← casehub-ras-drools (Optional module, Jandex library)
    DroolsGanglion — KieSession-based CEP detection
  ras-llm/                     ← casehub-ras-llm (Optional module)
    LlmGanglion — casehub-platform-agent-api based narrative detection
  testing/                     ← casehub-ras-testing (Test scope only)
    MockGanglion, FixedDetectionResult, InMemorySituationStore
```

---

## 10. Tier Placement

```
Foundation:
  casehub-platform stream submodules (Kafka, AMQP, webhook, Camel)
    → fires SensoryEvent CDI events

Integration:
  casehub-ras
    → consumes SensoryEvent
    → routes to Ganglion implementations
    → triggers cases via casehub-engine-api
```

casehub-ras sits at the Integration tier — alongside claudony, casehub-openclaw, casehub-workers.

---

## 11. Use Cases (Reference Scenarios)

**Equipment failure (IoT):** Temperature spike + vibration anomaly within 10 minutes → create maintenance case with CRITICAL priority.

**AML composite signal:** Three transactions > threshold from same entity + PEP flag detected → create AML investigation case.

**Clinical deterioration:** Patient vitals cross CTCAE grade threshold twice in 24h + attending note contains flagged terms (LlmGanglion) → create escalation case.

**Agent mesh anomaly:** Qhorus commitment failure rate exceeds baseline for 5 minutes (Bayesian) → create platform health case.

**Compliance drift:** Log retention policy last verified > 30 days AND encryption control changed → create compliance review case.

---

## 12. Open Design Questions

1. **SituationStore persistence** — in-memory for prototype; JPA for durable correlation across restarts. What is the retention policy for expired situations?
2. **SensoryEvent routing** — does the RasEngine broadcast to all ganglia or route by `handledStreamTypes()`? Routing is more efficient but requires registration.
3. **Back-pressure** — high-volume streams could overwhelm ganglia. What is the buffering/dropping policy?
4. **Situation deduplication** — if two events trigger the same case type within a short window, should the second be suppressed?
5. **Platform stream module placement** — submodules of casehub-platform, or a separate `casehub-streams` repo?
6. **Drools CEP integration** — stateful KieSession per situation or shared session? Stateful per-situation is safer but more expensive.
7. **LlmGanglion latency** — LLM inference is slow. Should LlmGanglion always run async on a slow path with a timeout, while fast ganglia make immediate decisions?
8. **Multi-tenancy** — each tenant has independent SituationContext. How is tenant isolation enforced in the SituationStore and RasEngine?
